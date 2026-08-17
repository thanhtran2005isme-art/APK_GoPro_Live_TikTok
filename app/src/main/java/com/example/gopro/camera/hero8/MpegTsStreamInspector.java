package com.example.gopro.camera.hero8;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Locale;

/**
 * Lightweight, dependency-free MPEG-TS/H.264 inspector used before the real decoder is introduced.
 *
 * <p>It discovers the PMT/video PID, identifies the video codec and parses H.264 SPS NAL units to
 * report the encoded width/height. It also estimates frame rate from first-slice NAL units. The
 * inspector never decodes video frames.</p>
 */
public final class MpegTsStreamInspector {

    public static final class Snapshot {
        public final boolean transportStreamDetected;
        @NonNull public final String codec;
        public final int videoPid;
        public final int width;
        public final int height;
        public final double estimatedFps;

        private Snapshot(
                boolean transportStreamDetected,
                @NonNull String codec,
                int videoPid,
                int width,
                int height,
                double estimatedFps) {
            this.transportStreamDetected = transportStreamDetected;
            this.codec = codec;
            this.videoPid = videoPid;
            this.width = width;
            this.height = height;
            this.estimatedFps = estimatedFps;
        }

        @NonNull
        public String resolutionLabel() {
            return width > 0 && height > 0 ? width + "x" + height : "detecting";
        }

        @NonNull
        public String fpsLabel() {
            return estimatedFps > 0.0
                    ? String.format(Locale.US, "%.1f", estimatedFps)
                    : "detecting";
        }
    }

    private static final int TS_PACKET_SIZE = 188;
    private static final int UNKNOWN_PID = -1;
    private static final int MAX_PENDING_BYTES = TS_PACKET_SIZE * 16;
    private static final int MAX_ES_BUFFER = 256 * 1024;

    private byte[] pendingTs = new byte[0];
    private byte[] elementaryBuffer = new byte[0];

    private boolean transportStreamDetected;
    private int pmtPid = UNKNOWN_PID;
    private int videoPid = UNKNOWN_PID;
    @NonNull private String codec = "unknown";
    private int width;
    private int height;

    private long fpsWindowStartedAtMs;
    private int framesInWindow;
    private double estimatedFps;

    public void reset() {
        pendingTs = new byte[0];
        elementaryBuffer = new byte[0];
        transportStreamDetected = false;
        pmtPid = UNKNOWN_PID;
        videoPid = UNKNOWN_PID;
        codec = "unknown";
        width = 0;
        height = 0;
        fpsWindowStartedAtMs = 0L;
        framesInWindow = 0;
        estimatedFps = 0.0;
    }

    public void consume(byte[] data, int length, long nowMs) {
        if (length <= 0) {
            return;
        }

        appendTransportBytes(data, length);
        parseTransportPackets(nowMs);
    }

    @NonNull
    public Snapshot snapshot() {
        return new Snapshot(
                transportStreamDetected,
                codec,
                videoPid,
                width,
                height,
                estimatedFps);
    }

    private void appendTransportBytes(byte[] data, int length) {
        int safeLength = Math.min(length, data.length);
        int keepExisting = Math.min(pendingTs.length, MAX_PENDING_BYTES);
        byte[] combined = new byte[keepExisting + safeLength];
        if (keepExisting > 0) {
            System.arraycopy(
                    pendingTs,
                    pendingTs.length - keepExisting,
                    combined,
                    0,
                    keepExisting);
        }
        System.arraycopy(data, 0, combined, keepExisting, safeLength);
        pendingTs = combined;
    }

    private void parseTransportPackets(long nowMs) {
        int offset = findSyncOffset(pendingTs);
        if (offset < 0) {
            if (pendingTs.length > MAX_PENDING_BYTES) {
                pendingTs = Arrays.copyOfRange(
                        pendingTs,
                        pendingTs.length - MAX_PENDING_BYTES,
                        pendingTs.length);
            }
            return;
        }

        while (offset + TS_PACKET_SIZE <= pendingTs.length) {
            if ((pendingTs[offset] & 0xFF) != 0x47) {
                offset++;
                continue;
            }

            if (offset + TS_PACKET_SIZE * 2 <= pendingTs.length
                    && (pendingTs[offset + TS_PACKET_SIZE] & 0xFF) != 0x47) {
                offset++;
                continue;
            }

            transportStreamDetected = true;
            parseTsPacket(pendingTs, offset, nowMs);
            offset += TS_PACKET_SIZE;
        }

        pendingTs = Arrays.copyOfRange(pendingTs, offset, pendingTs.length);
    }

    private static int findSyncOffset(byte[] data) {
        if (data.length < TS_PACKET_SIZE) {
            return -1;
        }

        for (int i = 0; i < data.length; i++) {
            if ((data[i] & 0xFF) != 0x47) {
                continue;
            }
            if (i + TS_PACKET_SIZE >= data.length
                    || (data[i + TS_PACKET_SIZE] & 0xFF) == 0x47) {
                return i;
            }
        }
        return -1;
    }

    private void parseTsPacket(byte[] packet, int packetOffset, long nowMs) {
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
            parseVideoPayload(packet, payloadOffset, packetEnd, payloadUnitStart, nowMs);
        }
    }

    private void parsePat(byte[] data, int offset, int end, boolean payloadUnitStart) {
        int sectionOffset = sectionOffset(data, offset, end, payloadUnitStart);
        if (sectionOffset < 0 || sectionOffset + 8 > end || (data[sectionOffset] & 0xFF) != 0x00) {
            return;
        }

        int sectionLength = ((data[sectionOffset + 1] & 0x0F) << 8)
                | (data[sectionOffset + 2] & 0xFF);
        int sectionEnd = Math.min(end, sectionOffset + 3 + sectionLength);
        int cursor = sectionOffset + 8;
        int programsEnd = sectionEnd - 4;

        while (cursor + 4 <= programsEnd) {
            int programNumber = ((data[cursor] & 0xFF) << 8) | (data[cursor + 1] & 0xFF);
            int pid = ((data[cursor + 2] & 0x1F) << 8) | (data[cursor + 3] & 0xFF);
            if (programNumber != 0) {
                pmtPid = pid;
                return;
            }
            cursor += 4;
        }
    }

    private void parsePmt(byte[] data, int offset, int end, boolean payloadUnitStart) {
        int sectionOffset = sectionOffset(data, offset, end, payloadUnitStart);
        if (sectionOffset < 0 || sectionOffset + 12 > end || (data[sectionOffset] & 0xFF) != 0x02) {
            return;
        }

        int sectionLength = ((data[sectionOffset + 1] & 0x0F) << 8)
                | (data[sectionOffset + 2] & 0xFF);
        int sectionEnd = Math.min(end, sectionOffset + 3 + sectionLength);
        int programInfoLength = ((data[sectionOffset + 10] & 0x0F) << 8)
                | (data[sectionOffset + 11] & 0xFF);
        int cursor = sectionOffset + 12 + programInfoLength;
        int streamsEnd = sectionEnd - 4;

        while (cursor + 5 <= streamsEnd) {
            int streamType = data[cursor] & 0xFF;
            int elementaryPid = ((data[cursor + 1] & 0x1F) << 8)
                    | (data[cursor + 2] & 0xFF);
            int esInfoLength = ((data[cursor + 3] & 0x0F) << 8)
                    | (data[cursor + 4] & 0xFF);

            if (streamType == 0x1B) {
                videoPid = elementaryPid;
                codec = "H.264/AVC";
            } else if (streamType == 0x24 && videoPid == UNKNOWN_PID) {
                videoPid = elementaryPid;
                codec = "H.265/HEVC";
            }

            cursor += 5 + esInfoLength;
        }
    }

    private static int sectionOffset(byte[] data, int offset, int end, boolean payloadUnitStart) {
        if (!payloadUnitStart || offset >= end) {
            return -1;
        }
        int pointerField = data[offset] & 0xFF;
        int sectionOffset = offset + 1 + pointerField;
        return sectionOffset < end ? sectionOffset : -1;
    }

    private void parseVideoPayload(
            byte[] data,
            int offset,
            int end,
            boolean payloadUnitStart,
            long nowMs) {
        int elementaryOffset = offset;

        if (payloadUnitStart && end - offset >= 9
                && data[offset] == 0x00
                && data[offset + 1] == 0x00
                && data[offset + 2] == 0x01) {
            int headerDataLength = data[offset + 8] & 0xFF;
            elementaryOffset = offset + 9 + headerDataLength;
        }

        if (elementaryOffset >= end || !"H.264/AVC".equals(codec)) {
            return;
        }

        appendElementaryBytes(data, elementaryOffset, end - elementaryOffset);
        parseAnnexBNals(nowMs);
    }

    private void appendElementaryBytes(byte[] data, int offset, int length) {
        if (length <= 0) {
            return;
        }

        int keep = Math.min(elementaryBuffer.length, MAX_ES_BUFFER / 2);
        int incoming = Math.min(length, MAX_ES_BUFFER - keep);
        byte[] next = new byte[keep + incoming];
        if (keep > 0) {
            System.arraycopy(
                    elementaryBuffer,
                    elementaryBuffer.length - keep,
                    next,
                    0,
                    keep);
        }
        System.arraycopy(data, offset, next, keep, incoming);
        elementaryBuffer = next;
    }

    private void parseAnnexBNals(long nowMs) {
        int first = findStartCode(elementaryBuffer, 0);
        if (first < 0) {
            trimElementaryBuffer();
            return;
        }

        int cursor = first;
        while (true) {
            int startCodeLength = startCodeLength(elementaryBuffer, cursor);
            if (startCodeLength == 0) {
                cursor++;
                if (cursor >= elementaryBuffer.length) {
                    break;
                }
                continue;
            }

            int nalStart = cursor + startCodeLength;
            int next = findStartCode(elementaryBuffer, nalStart);
            if (next < 0) {
                elementaryBuffer = Arrays.copyOfRange(elementaryBuffer, cursor, elementaryBuffer.length);
                trimElementaryBuffer();
                return;
            }

            if (nalStart < next) {
                inspectNal(elementaryBuffer, nalStart, next, nowMs);
            }
            cursor = next;
        }

        trimElementaryBuffer();
    }

    private void inspectNal(byte[] data, int start, int end, long nowMs) {
        int nalType = data[start] & 0x1F;
        if (nalType == 7) {
            byte[] rbsp = unescapeRbsp(data, start + 1, end);
            VideoDimensions dimensions = parseH264Sps(rbsp);
            if (dimensions != null) {
                width = dimensions.width;
                height = dimensions.height;
            }
        } else if (nalType == 1 || nalType == 5) {
            byte[] rbsp = unescapeRbsp(data, start + 1, end);
            BitReader reader = new BitReader(rbsp);
            try {
                long firstMbInSlice = reader.readUnsignedExpGolomb();
                if (firstMbInSlice == 0) {
                    recordFrame(nowMs);
                }
            } catch (IllegalStateException ignored) {
                // A truncated NAL will be ignored; the next complete NAL can still be inspected.
            }
        }
    }

    private void recordFrame(long nowMs) {
        if (fpsWindowStartedAtMs == 0L) {
            fpsWindowStartedAtMs = nowMs;
            framesInWindow = 1;
            return;
        }

        framesInWindow++;
        long elapsed = nowMs - fpsWindowStartedAtMs;
        if (elapsed >= 2_000L) {
            estimatedFps = framesInWindow * 1_000.0 / elapsed;
            fpsWindowStartedAtMs = nowMs;
            framesInWindow = 0;
        }
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 < data.length; i++) {
            if (data[i] == 0x00 && data[i + 1] == 0x00) {
                if (data[i + 2] == 0x01) {
                    return i;
                }
                if (i + 3 < data.length && data[i + 2] == 0x00 && data[i + 3] == 0x01) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int offset) {
        if (offset + 2 < data.length
                && data[offset] == 0x00
                && data[offset + 1] == 0x00
                && data[offset + 2] == 0x01) {
            return 3;
        }
        if (offset + 3 < data.length
                && data[offset] == 0x00
                && data[offset + 1] == 0x00
                && data[offset + 2] == 0x00
                && data[offset + 3] == 0x01) {
            return 4;
        }
        return 0;
    }

    private void trimElementaryBuffer() {
        if (elementaryBuffer.length > MAX_ES_BUFFER) {
            elementaryBuffer = Arrays.copyOfRange(
                    elementaryBuffer,
                    elementaryBuffer.length - MAX_ES_BUFFER / 2,
                    elementaryBuffer.length);
        }
    }

    private static byte[] unescapeRbsp(byte[] data, int start, int end) {
        byte[] output = new byte[Math.max(0, end - start)];
        int outputLength = 0;
        int zeroCount = 0;
        for (int i = start; i < end; i++) {
            int value = data[i] & 0xFF;
            if (zeroCount >= 2 && value == 0x03) {
                zeroCount = 0;
                continue;
            }
            output[outputLength++] = data[i];
            if (value == 0) {
                zeroCount++;
            } else {
                zeroCount = 0;
            }
        }
        return Arrays.copyOf(output, outputLength);
    }

    @Nullable
    private static VideoDimensions parseH264Sps(byte[] rbsp) {
        try {
            BitReader bits = new BitReader(rbsp);
            int profileIdc = bits.readBits(8);
            bits.skipBits(8); // constraint flags + reserved_zero_2bits
            bits.skipBits(8); // level_idc
            bits.readUnsignedExpGolomb(); // seq_parameter_set_id

            int chromaFormatIdc = 1;
            boolean separateColourPlaneFlag = false;
            if (isHighProfile(profileIdc)) {
                chromaFormatIdc = (int) bits.readUnsignedExpGolomb();
                if (chromaFormatIdc == 3) {
                    separateColourPlaneFlag = bits.readBit();
                }
                bits.readUnsignedExpGolomb(); // bit_depth_luma_minus8
                bits.readUnsignedExpGolomb(); // bit_depth_chroma_minus8
                bits.skipBits(1); // qpprime_y_zero_transform_bypass_flag
                boolean scalingMatrixPresent = bits.readBit();
                if (scalingMatrixPresent) {
                    int scalingListCount = chromaFormatIdc != 3 ? 8 : 12;
                    for (int i = 0; i < scalingListCount; i++) {
                        boolean scalingListPresent = bits.readBit();
                        if (scalingListPresent) {
                            skipScalingList(bits, i < 6 ? 16 : 64);
                        }
                    }
                }
            }

            bits.readUnsignedExpGolomb(); // log2_max_frame_num_minus4
            long picOrderCntType = bits.readUnsignedExpGolomb();
            if (picOrderCntType == 0) {
                bits.readUnsignedExpGolomb(); // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                bits.skipBits(1); // delta_pic_order_always_zero_flag
                bits.readSignedExpGolomb();
                bits.readSignedExpGolomb();
                long cycle = bits.readUnsignedExpGolomb();
                for (long i = 0; i < cycle; i++) {
                    bits.readSignedExpGolomb();
                }
            }

            bits.readUnsignedExpGolomb(); // max_num_ref_frames
            bits.skipBits(1); // gaps_in_frame_num_value_allowed_flag
            long picWidthInMbsMinus1 = bits.readUnsignedExpGolomb();
            long picHeightInMapUnitsMinus1 = bits.readUnsignedExpGolomb();
            boolean frameMbsOnlyFlag = bits.readBit();
            if (!frameMbsOnlyFlag) {
                bits.skipBits(1); // mb_adaptive_frame_field_flag
            }
            bits.skipBits(1); // direct_8x8_inference_flag

            long cropLeft = 0;
            long cropRight = 0;
            long cropTop = 0;
            long cropBottom = 0;
            boolean frameCroppingFlag = bits.readBit();
            if (frameCroppingFlag) {
                cropLeft = bits.readUnsignedExpGolomb();
                cropRight = bits.readUnsignedExpGolomb();
                cropTop = bits.readUnsignedExpGolomb();
                cropBottom = bits.readUnsignedExpGolomb();
            }

            int chromaArrayType = separateColourPlaneFlag ? 0 : chromaFormatIdc;
            int subWidthC;
            int subHeightC;
            if (chromaArrayType == 1) {
                subWidthC = 2;
                subHeightC = 2;
            } else if (chromaArrayType == 2) {
                subWidthC = 2;
                subHeightC = 1;
            } else {
                subWidthC = 1;
                subHeightC = 1;
            }

            int cropUnitX = chromaArrayType == 0 ? 1 : subWidthC;
            int cropUnitY = chromaArrayType == 0
                    ? (frameMbsOnlyFlag ? 1 : 2)
                    : subHeightC * (frameMbsOnlyFlag ? 1 : 2);

            long rawWidth = (picWidthInMbsMinus1 + 1) * 16;
            long rawHeight = (2 - (frameMbsOnlyFlag ? 1 : 0))
                    * (picHeightInMapUnitsMinus1 + 1) * 16;
            long croppedWidth = rawWidth - (cropLeft + cropRight) * cropUnitX;
            long croppedHeight = rawHeight - (cropTop + cropBottom) * cropUnitY;

            if (croppedWidth <= 0 || croppedHeight <= 0
                    || croppedWidth > 16384 || croppedHeight > 16384) {
                return null;
            }
            return new VideoDimensions((int) croppedWidth, (int) croppedHeight);
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    private static boolean isHighProfile(int profileIdc) {
        return profileIdc == 100
                || profileIdc == 110
                || profileIdc == 122
                || profileIdc == 244
                || profileIdc == 44
                || profileIdc == 83
                || profileIdc == 86
                || profileIdc == 118
                || profileIdc == 128
                || profileIdc == 138
                || profileIdc == 139
                || profileIdc == 134
                || profileIdc == 135;
    }

    private static void skipScalingList(BitReader bits, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int deltaScale = (int) bits.readSignedExpGolomb();
                nextScale = (lastScale + deltaScale + 256) % 256;
            }
            lastScale = nextScale == 0 ? lastScale : nextScale;
        }
    }

    private static final class VideoDimensions {
        final int width;
        final int height;

        VideoDimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class BitReader {
        private final byte[] data;
        private int bitOffset;

        BitReader(byte[] data) {
            this.data = data;
        }

        boolean readBit() {
            return readBits(1) == 1;
        }

        int readBits(int count) {
            if (count < 0 || count > 31) {
                throw new IllegalStateException("Invalid bit count");
            }
            int value = 0;
            for (int i = 0; i < count; i++) {
                if (bitOffset >= data.length * 8) {
                    throw new IllegalStateException("End of RBSP");
                }
                int current = data[bitOffset / 8] & 0xFF;
                int shift = 7 - (bitOffset % 8);
                value = (value << 1) | ((current >> shift) & 1);
                bitOffset++;
            }
            return value;
        }

        void skipBits(int count) {
            if (count < 0 || bitOffset + count > data.length * 8) {
                throw new IllegalStateException("End of RBSP");
            }
            bitOffset += count;
        }

        long readUnsignedExpGolomb() {
            int leadingZeroBits = 0;
            while (!readBit()) {
                leadingZeroBits++;
                if (leadingZeroBits > 31) {
                    throw new IllegalStateException("Exp-Golomb overflow");
                }
            }
            if (leadingZeroBits == 0) {
                return 0;
            }
            long suffix = readBits(leadingZeroBits);
            return ((1L << leadingZeroBits) - 1L) + suffix;
        }

        long readSignedExpGolomb() {
            long codeNum = readUnsignedExpGolomb();
            long value = (codeNum + 1) / 2;
            return (codeNum & 1L) == 0L ? -value : value;
        }
    }
}
