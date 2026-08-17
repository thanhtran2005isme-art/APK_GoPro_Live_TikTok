package com.example.gopro.camera.hero8;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Low-latency HERO8 preview renderer.
 *
 * <p>HERO8 supplies a live MPEG-TS stream over UDP. This class demuxes the H.264 PES payload,
 * preserves the real 90 kHz PES PTS, holds only 2-3 frames as a tiny jitter buffer and schedules
 * decoded output to the Surface by presentation timestamp. The Surface therefore presents a steady
 * 30 fps cadence even when UDP packets arrive in small bursts.</p>
 */
@UnstableApi
public final class Hero8PreviewPlayer implements AutoCloseable {

    public interface Listener {
        void onDecoderStatus(@NonNull String message);

        void onDecoderError(@NonNull String message);

        void onFirstFrame();
    }

    private static final int TS_PACKET_SIZE = 188;
    private static final int UNKNOWN_PID = -1;
    private static final long PIPE_POLL_MS = 50L;
    private static final long INPUT_TIMEOUT_US = 8_000L;
    private static final long DEFAULT_FRAME_DURATION_US = 33_333L;

    // Three access units in the queue means roughly two frames (~66 ms at 30 fps) are held back.
    private static final int JITTER_BUFFER_TARGET_FRAMES = 3;
    private static final long PACER_START_DELAY_NS = 8_000_000L;
    private static final long MAX_LATE_FRAME_NS = 70_000_000L;
    private static final long MIN_SCHEDULE_AHEAD_NS = 1_000_000L;

    private final SurfaceView surfaceView;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Hero8TsPipe tsPipe = Hero8TsPipe.shared();

    @Nullable private volatile Surface surface;
    @Nullable private ExecutorService decoderExecutor;
    private volatile boolean running;

    public Hero8PreviewPlayer(
            @NonNull SurfaceView surfaceView,
            @NonNull Listener listener) {
        this.surfaceView = surfaceView;
        this.listener = listener;

        SurfaceHolder holder = surfaceView.getHolder();
        surface = holder.getSurface();
        holder.addCallback(
                new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(@NonNull SurfaceHolder holder) {
                        surface = holder.getSurface();
                        requestThirtyFpsSurface(holder.getSurface());
                    }

                    @Override
                    public void surfaceChanged(
                            @NonNull SurfaceHolder holder,
                            int format,
                            int width,
                            int height) {
                        surface = holder.getSurface();
                        requestThirtyFpsSurface(holder.getSurface());
                    }

                    @Override
                    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                        surface = null;
                    }
                });
    }

    /** Starts a fresh decoder session. Must be called before UDP begins feeding transport stream. */
    public synchronized void start() {
        stopInternal();
        tsPipe.reset();
        running = true;
        decoderExecutor = Executors.newSingleThreadExecutor();
        decoderExecutor.execute(this::decodeLoop);
        postStatus("Đang chờ SPS/PPS/IDR; PTS pacing + jitter buffer 3 frame đã bật…");
    }

    public synchronized void stop() {
        stopInternal();
    }

    private synchronized void stopInternal() {
        running = false;
        tsPipe.endStream();
        if (decoderExecutor != null) {
            decoderExecutor.shutdownNow();
            decoderExecutor = null;
        }
    }

    private void decodeLoop() {
        MediaCodec codec = null;
        try {
            Surface outputSurface = waitForSurface();
            if (outputSurface == null) {
                if (running) {
                    postError("Surface preview chưa sẵn sàng.");
                }
                return;
            }
            requestThirtyFpsSurface(outputSurface);

            TsH264Demuxer demuxer = new TsH264Demuxer();
            MpegTsStreamInspector inspector = new MpegTsStreamInspector();
            ArrayDeque<AccessUnit> jitterBuffer = new ArrayDeque<>();
            FramePacer framePacer = new FramePacer();

            byte[] sps = null;
            byte[] pps = null;
            boolean waitingForIdr = true;
            boolean firstFrame = true;
            long syntheticPtsUs = 0L;

            while (running) {
                byte[] chunk;
                try {
                    chunk = tsPipe.pollChunk(PIPE_POLL_MS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (chunk == null) {
                    if (tsPipe.isStreamEnded()) {
                        return;
                    }
                    if (codec != null) {
                        boolean rendered = drainOutput(codec, framePacer);
                        if (rendered && firstFrame) {
                            firstFrame = false;
                            mainHandler.post(listener::onFirstFrame);
                        }
                    }
                    continue;
                }

                inspector.consume(chunk, chunk.length, System.currentTimeMillis());
                MpegTsStreamInspector.Snapshot snapshot = inspector.snapshot();
                List<AccessUnit> accessUnits = demuxer.consume(chunk);

                for (AccessUnit accessUnit : accessUnits) {
                    byte[] foundSps = findNal(accessUnit.data, 7);
                    byte[] foundPps = findNal(accessUnit.data, 8);
                    if (foundSps != null) {
                        sps = foundSps;
                    }
                    if (foundPps != null) {
                        pps = foundPps;
                    }

                    if (codec == null
                            && sps != null
                            && pps != null
                            && snapshot.width > 0
                            && snapshot.height > 0) {
                        codec = createDecoder(
                                outputSurface,
                                snapshot.width,
                                snapshot.height,
                                sps,
                                pps);
                        postStatus(
                                "MediaCodec "
                                        + snapshot.width
                                        + "x"
                                        + snapshot.height
                                        + " sẵn sàng; đang chờ IDR frame…");
                    }

                    if (codec == null) {
                        continue;
                    }

                    boolean containsIdr = containsNal(accessUnit.data, 5);
                    if (waitingForIdr) {
                        if (!containsIdr) {
                            continue;
                        }
                        waitingForIdr = false;
                        jitterBuffer.clear();
                        framePacer.reset();
                        postStatus("Đã nhận IDR; đang phát theo PTS với jitter buffer ~2 frame…");
                    }

                    long ptsUs = accessUnit.ptsUs;
                    if (ptsUs < 0L) {
                        ptsUs = syntheticPtsUs;
                    }
                    syntheticPtsUs = Math.max(
                            syntheticPtsUs + DEFAULT_FRAME_DURATION_US,
                            ptsUs + DEFAULT_FRAME_DURATION_US);

                    jitterBuffer.addLast(new AccessUnit(accessUnit.data, ptsUs));

                    // Keep only a tiny 2-frame look-ahead. This smooths packet burst timing without
                    // allowing latency to grow over time.
                    while (jitterBuffer.size() >= JITTER_BUFFER_TARGET_FRAMES) {
                        AccessUnit ready = jitterBuffer.removeFirst();
                        queueAccessUnit(codec, ready.data, ready.ptsUs);
                        boolean rendered = drainOutput(codec, framePacer);
                        if (rendered && firstFrame) {
                            firstFrame = false;
                            mainHandler.post(listener::onFirstFrame);
                        }
                    }
                }
            }
        } catch (Exception exception) {
            if (running) {
                postError(
                        "MediaCodec preview lỗi: "
                                + exception.getClass().getSimpleName()
                                + ": "
                                + safeMessage(exception));
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                    // Decoder may already be stopped by a codec error.
                }
                codec.release();
            }
        }
    }

    @Nullable
    private Surface waitForSurface() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (running && System.currentTimeMillis() < deadline) {
            Surface current = surface;
            if (current != null && current.isValid()) {
                return current;
            }
            Thread.sleep(25L);
        }
        return null;
    }

    private static void requestThirtyFpsSurface(@NonNull Surface outputSurface) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && outputSurface.isValid()) {
            try {
                outputSurface.setFrameRate(30.0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            } catch (IllegalStateException ignored) {
                // Some vendor Surface implementations reject the hint while being recreated.
            }
        }
    }

    @NonNull
    private static MediaCodec createDecoder(
            @NonNull Surface surface,
            int width,
            int height,
            @NonNull byte[] sps,
            @NonNull byte[] pps)
            throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024);

        MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(format, surface, null, 0);
        codec.start();
        return codec;
    }

    private static void queueAccessUnit(
            @NonNull MediaCodec codec,
            @NonNull byte[] data,
            long ptsUs) {
        int position = 0;
        while (position < data.length) {
            int inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US);
            if (inputIndex < 0) {
                // Prefer dropping one compressed frame over accumulating live-preview latency.
                return;
            }

            ByteBuffer input = codec.getInputBuffer(inputIndex);
            if (input == null) {
                return;
            }
            input.clear();

            int bytesToWrite = Math.min(input.remaining(), data.length - position);
            input.put(data, position, bytesToWrite);
            position += bytesToWrite;

            int flags = position < data.length ? MediaCodec.BUFFER_FLAG_PARTIAL_FRAME : 0;
            codec.queueInputBuffer(inputIndex, 0, bytesToWrite, ptsUs, flags);
        }
    }

    /** Releases decoded frames to Surface at their PTS-derived render time instead of immediately. */
    private static boolean drainOutput(
            @NonNull MediaCodec codec,
            @NonNull FramePacer framePacer) {
        boolean renderedAny = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, 0L);
            if (outputIndex >= 0) {
                boolean codecConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (codecConfig) {
                    codec.releaseOutputBuffer(outputIndex, false);
                    continue;
                }

                long renderTimeNs = framePacer.renderTimeNs(info.presentationTimeUs);
                if (renderTimeNs == FramePacer.DROP_FRAME) {
                    codec.releaseOutputBuffer(outputIndex, false);
                } else {
                    codec.releaseOutputBuffer(outputIndex, renderTimeNs);
                    renderedAny = true;
                }
                continue;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    || outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            }
            return renderedAny;
        }
    }

    /** Maps PES PTS to CLOCK_MONOTONIC and drops only frames that are already badly late. */
    private static final class FramePacer {
        static final long DROP_FRAME = Long.MIN_VALUE;

        private long firstPtsUs = Long.MIN_VALUE;
        private long firstRenderNs;

        void reset() {
            firstPtsUs = Long.MIN_VALUE;
            firstRenderNs = 0L;
        }

        long renderTimeNs(long ptsUs) {
            long nowNs = System.nanoTime();
            if (firstPtsUs == Long.MIN_VALUE) {
                firstPtsUs = ptsUs;
                firstRenderNs = nowNs + PACER_START_DELAY_NS;
            }

            long deltaUs = ptsUs - firstPtsUs;
            if (deltaUs < -1_000_000L || deltaUs > 60_000_000L) {
                // Timestamp discontinuity: re-anchor instead of producing a huge pause/burst.
                firstPtsUs = ptsUs;
                firstRenderNs = nowNs + PACER_START_DELAY_NS;
                deltaUs = 0L;
            }

            long targetNs = firstRenderNs + deltaUs * 1_000L;
            if (targetNs < nowNs - MAX_LATE_FRAME_NS) {
                return DROP_FRAME;
            }
            return Math.max(targetNs, nowNs + MIN_SCHEDULE_AHEAD_NS);
        }
    }

    @Nullable
    private static byte[] findNal(@NonNull byte[] data, int targetType) {
        int cursor = 0;
        while (true) {
            NalRange range = nextNal(data, cursor);
            if (range == null) {
                return null;
            }
            int type = data[range.payloadStart] & 0x1F;
            if (type == targetType) {
                byte[] normalized = new byte[4 + (range.end - range.payloadStart)];
                normalized[0] = 0;
                normalized[1] = 0;
                normalized[2] = 0;
                normalized[3] = 1;
                System.arraycopy(
                        data,
                        range.payloadStart,
                        normalized,
                        4,
                        range.end - range.payloadStart);
                return normalized;
            }
            cursor = range.end;
        }
    }

    private static boolean containsNal(@NonNull byte[] data, int targetType) {
        int cursor = 0;
        while (true) {
            NalRange range = nextNal(data, cursor);
            if (range == null) {
                return false;
            }
            if ((data[range.payloadStart] & 0x1F) == targetType) {
                return true;
            }
            cursor = range.end;
        }
    }

    @Nullable
    private static NalRange nextNal(@NonNull byte[] data, int from) {
        int start = findStartCode(data, from);
        if (start < 0) {
            return null;
        }
        int prefix = data[start + 2] == 1 ? 3 : 4;
        int payloadStart = start + prefix;
        if (payloadStart >= data.length) {
            return null;
        }
        int next = findStartCode(data, payloadStart);
        int end = next >= 0 ? next : data.length;
        return new NalRange(payloadStart, end);
    }

    private static int findStartCode(@NonNull byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void postStatus(@NonNull String message) {
        mainHandler.post(() -> listener.onDecoderStatus(message));
    }

    private void postError(@NonNull String message) {
        mainHandler.post(() -> listener.onDecoderError(message));
    }

    @NonNull
    private static String safeMessage(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "không có message" : message;
    }

    @Override
    public synchronized void close() {
        stopInternal();
    }

    private static final class NalRange {
        final int payloadStart;
        final int end;

        NalRange(int payloadStart, int end) {
            this.payloadStart = payloadStart;
            this.end = end;
        }
    }

    private static final class AccessUnit {
        @NonNull final byte[] data;
        final long ptsUs;

        AccessUnit(@NonNull byte[] data, long ptsUs) {
            this.data = data;
            this.ptsUs = ptsUs;
        }
    }

    /** Minimal PAT/PMT/PES demuxer for the single H.264 program emitted by HERO8 gpStream. */
    private static final class TsH264Demuxer {

        private int pmtPid = UNKNOWN_PID;
        private int videoPid = UNKNOWN_PID;
        private final ByteArrayOutputStream currentPes = new ByteArrayOutputStream(64 * 1024);
        private long currentPtsUs = -1L;

        @NonNull
        List<AccessUnit> consume(@NonNull byte[] data) {
            List<AccessUnit> output = new ArrayList<>();
            for (int offset = 0; offset + TS_PACKET_SIZE <= data.length; offset += TS_PACKET_SIZE) {
                if ((data[offset] & 0xFF) != 0x47) {
                    continue;
                }
                parsePacket(data, offset, output);
            }
            return output;
        }

        private void parsePacket(
                @NonNull byte[] packet,
                int packetOffset,
                @NonNull List<AccessUnit> output) {
            int second = packet[packetOffset + 1] & 0xFF;
            boolean payloadUnitStart = (second & 0x40) != 0;
            int pid = ((second & 0x1F) << 8) | (packet[packetOffset + 2] & 0xFF);
            int fourth = packet[packetOffset + 3] & 0xFF;
            int adaptationFieldControl = (fourth >> 4) & 0x03;

            if (adaptationFieldControl == 0 || adaptationFieldControl == 2) {
                return;
            }

            int payloadOffset = packetOffset + 4;
            if (adaptationFieldControl == 3) {
                int adaptationLength = packet[payloadOffset] & 0xFF;
                payloadOffset += 1 + adaptationLength;
            }

            int packetEnd = packetOffset + TS_PACKET_SIZE;
            if (payloadOffset >= packetEnd) {
                return;
            }

            if (pid == 0) {
                parsePat(packet, payloadOffset, packetEnd, payloadUnitStart);
            } else if (pid == pmtPid) {
                parsePmt(packet, payloadOffset, packetEnd, payloadUnitStart);
            } else if (pid == videoPid) {
                parseVideo(packet, payloadOffset, packetEnd, payloadUnitStart, output);
            }
        }

        private void parsePat(byte[] data, int offset, int end, boolean payloadUnitStart) {
            int section = sectionOffset(data, offset, end, payloadUnitStart);
            if (section < 0 || section + 8 > end || (data[section] & 0xFF) != 0x00) {
                return;
            }
            int sectionLength = ((data[section + 1] & 0x0F) << 8) | (data[section + 2] & 0xFF);
            int sectionEnd = Math.min(end, section + 3 + sectionLength);
            for (int cursor = section + 8; cursor + 4 <= sectionEnd - 4; cursor += 4) {
                int programNumber = ((data[cursor] & 0xFF) << 8) | (data[cursor + 1] & 0xFF);
                if (programNumber != 0) {
                    pmtPid = ((data[cursor + 2] & 0x1F) << 8) | (data[cursor + 3] & 0xFF);
                    return;
                }
            }
        }

        private void parsePmt(byte[] data, int offset, int end, boolean payloadUnitStart) {
            int section = sectionOffset(data, offset, end, payloadUnitStart);
            if (section < 0 || section + 12 > end || (data[section] & 0xFF) != 0x02) {
                return;
            }
            int sectionLength = ((data[section + 1] & 0x0F) << 8) | (data[section + 2] & 0xFF);
            int sectionEnd = Math.min(end, section + 3 + sectionLength);
            int programInfoLength = ((data[section + 10] & 0x0F) << 8) | (data[section + 11] & 0xFF);
            int cursor = section + 12 + programInfoLength;
            int entriesEnd = sectionEnd - 4;

            while (cursor + 5 <= entriesEnd) {
                int streamType = data[cursor] & 0xFF;
                int elementaryPid = ((data[cursor + 1] & 0x1F) << 8) | (data[cursor + 2] & 0xFF);
                int esInfoLength = ((data[cursor + 3] & 0x0F) << 8) | (data[cursor + 4] & 0xFF);
                if (streamType == 0x1B) {
                    videoPid = elementaryPid;
                    return;
                }
                cursor += 5 + esInfoLength;
            }
        }

        private void parseVideo(
                byte[] data,
                int offset,
                int end,
                boolean payloadUnitStart,
                @NonNull List<AccessUnit> output) {
            if (payloadUnitStart) {
                if (currentPes.size() > 0) {
                    output.add(new AccessUnit(currentPes.toByteArray(), currentPtsUs));
                    currentPes.reset();
                }

                if (offset + 9 > end
                        || data[offset] != 0
                        || data[offset + 1] != 0
                        || data[offset + 2] != 1) {
                    currentPtsUs = -1L;
                    return;
                }

                int flags = data[offset + 7] & 0xC0;
                int headerDataLength = data[offset + 8] & 0xFF;
                if ((flags == 0x80 || flags == 0xC0) && offset + 14 <= end) {
                    currentPtsUs = parsePtsUs(data, offset + 9);
                } else {
                    currentPtsUs = -1L;
                }

                int elementaryOffset = offset + 9 + headerDataLength;
                if (elementaryOffset < end) {
                    currentPes.write(data, elementaryOffset, end - elementaryOffset);
                }
            } else if (currentPes.size() > 0) {
                currentPes.write(data, offset, end - offset);
            }
        }

        private static int sectionOffset(byte[] data, int offset, int end, boolean payloadUnitStart) {
            if (!payloadUnitStart) {
                return offset;
            }
            if (offset >= end) {
                return -1;
            }
            int pointer = data[offset] & 0xFF;
            int section = offset + 1 + pointer;
            return section < end ? section : -1;
        }

        private static long parsePtsUs(byte[] data, int offset) {
            long pts = ((long) (data[offset] & 0x0E) << 29)
                    | ((long) (data[offset + 1] & 0xFF) << 22)
                    | ((long) (data[offset + 2] & 0xFE) << 14)
                    | ((long) (data[offset + 3] & 0xFF) << 7)
                    | ((long) (data[offset + 4] & 0xFE) >> 1);
            return pts * 1_000_000L / 90_000L;
        }
    }
}
