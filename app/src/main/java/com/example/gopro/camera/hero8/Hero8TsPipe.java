package com.example.gopro.camera.hero8;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-memory bridge between the HERO8 UDP receiver and Media3.
 *
 * <p>This intentionally avoids a localhost UDP hop. Clean MPEG-TS chunks are copied into a bounded
 * queue and consumed by a custom Media3 DataSource. If the decoder falls behind, the oldest chunk
 * is dropped so a live preview does not accumulate seconds of latency.</p>
 */
@UnstableApi
public final class Hero8TsPipe {

    private static final int MAX_QUEUED_CHUNKS = 512;
    private static final long READ_POLL_MS = 250L;
    private static final Uri PIPE_URI = Uri.parse("hero8ts://preview");
    private static final Hero8TsPipe SHARED = new Hero8TsPipe();

    private final ArrayBlockingQueue<byte[]> queue =
            new ArrayBlockingQueue<>(MAX_QUEUED_CHUNKS);

    private volatile boolean streamEnded;

    private Hero8TsPipe() {}

    @NonNull
    public static Hero8TsPipe shared() {
        return SHARED;
    }

    /** Starts a fresh live session while preserving packets that arrive before Media3 opens. */
    public void reset() {
        queue.clear();
        streamEnded = false;
    }

    /** Wakes readers and marks the current session as ended. */
    public void endStream() {
        streamEnded = true;
    }

    /** Adds clean MPEG-TS bytes. Oldest data is discarded if the decoder cannot keep up. */
    public void offer(byte[] data, int offset, int length) {
        if (streamEnded || length <= 0) {
            return;
        }

        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);

        if (!queue.offer(copy)) {
            queue.poll();
            queue.offer(copy);
        }
    }

    @NonNull
    public DataSource.Factory dataSourceFactory() {
        return () -> new PipeDataSource(this);
    }

    @UnstableApi
    private static final class PipeDataSource extends BaseDataSource {

        private final Hero8TsPipe pipe;

        @Nullable private Uri uri;
        @Nullable private byte[] currentChunk;
        private int currentOffset;
        private boolean opened;

        PipeDataSource(@NonNull Hero8TsPipe pipe) {
            super(false);
            this.pipe = pipe;
        }

        @Override
        public long open(@NonNull DataSpec dataSpec) {
            transferInitializing(dataSpec);
            uri = dataSpec.uri;
            opened = true;
            transferStarted(dataSpec);
            return C.LENGTH_UNSET;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }

            while (opened) {
                if (currentChunk != null && currentOffset < currentChunk.length) {
                    int bytesToCopy = Math.min(length, currentChunk.length - currentOffset);
                    System.arraycopy(currentChunk, currentOffset, buffer, offset, bytesToCopy);
                    currentOffset += bytesToCopy;
                    if (currentOffset >= currentChunk.length) {
                        currentChunk = null;
                        currentOffset = 0;
                    }
                    bytesTransferred(bytesToCopy);
                    return bytesToCopy;
                }

                if (pipe.streamEnded && pipe.queue.isEmpty()) {
                    return C.RESULT_END_OF_INPUT;
                }

                try {
                    currentChunk = pipe.queue.poll(READ_POLL_MS, TimeUnit.MILLISECONDS);
                    currentOffset = 0;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for HERO8 MPEG-TS", exception);
                }
            }

            return C.RESULT_END_OF_INPUT;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return opened ? (uri == null ? PIPE_URI : uri) : null;
        }

        @Override
        public void close() {
            if (opened) {
                opened = false;
                currentChunk = null;
                currentOffset = 0;
                uri = null;
                transferEnded();
            }
        }
    }
}
