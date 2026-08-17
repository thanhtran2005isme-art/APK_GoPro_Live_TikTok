package com.example.gopro.camera.hero8;

import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Receives the HERO8 legacy UDP preview stream, inspects it and relays clean MPEG-TS bytes to a
 * loopback UDP port consumed by Media3.
 *
 * <p>HERO8 preview datagrams observed on UDP/8554 contain a small transport header before the
 * 188-byte MPEG-TS packets (for example 1328 bytes = 12-byte header + 7 * 188-byte TS packets).
 * The inspector can resynchronise by scanning for TS sync bytes, but Media3 expects a clean TS
 * byte stream. Therefore the relay strips everything before the first aligned 0x47 sync byte and
 * forwards only complete 188-byte TS packets.</p>
 */
public final class Hero8UdpPreviewProbe implements AutoCloseable {

    public interface Listener {
        void onStarted();

        void onStats(
                long totalPackets,
                long totalBytes,
                long bytesPerSecond,
                @NonNull MpegTsStreamInspector.Snapshot streamInfo);

        void onStopped();

        void onError(@NonNull String message);
    }

    private static final int UDP_PORT = 8554;
    private static final int TS_PACKET_SIZE = 188;
    private static final int MAX_HEADER_SCAN_BYTES = 64;
    private static final int RECEIVE_TIMEOUT_MS = 1_000;
    private static final long KEEP_ALIVE_PERIOD_MS = 2_500L;

    // HERO8/HERO9 use controller id 1 in the legacy _GPHD_ keep-alive packet.
    private static final byte[] KEEP_ALIVE_PAYLOAD =
            "_GPHD_:1:0:2:0.000000\n".getBytes(StandardCharsets.UTF_8);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final MpegTsStreamInspector streamInspector = new MpegTsStreamInspector();

    private volatile boolean running;
    private volatile DatagramSocket receiveSocket;

    private ExecutorService receiveExecutor;
    private ScheduledExecutorService keepAliveExecutor;

    public Hero8UdpPreviewProbe(@NonNull Listener listener) {
        this.listener = listener;
    }

    public synchronized void start(@NonNull Network network) {
        stopInternal(false);
        streamInspector.reset();
        running = true;

        receiveExecutor = Executors.newSingleThreadExecutor();
        keepAliveExecutor = Executors.newSingleThreadScheduledExecutor();

        receiveExecutor.execute(() -> receiveLoop(network));
        keepAliveExecutor.scheduleAtFixedRate(
                () -> sendKeepAlive(network),
                0,
                KEEP_ALIVE_PERIOD_MS,
                TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        stopInternal(true);
    }

    private void receiveLoop(@NonNull Network network) {
        DatagramSocket socket = null;
        DatagramSocket relaySocket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            network.bindSocket(socket);
            socket.bind(new InetSocketAddress(UDP_PORT));
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            receiveSocket = socket;

            relaySocket = new DatagramSocket();
            InetAddress loopback = InetAddress.getLoopbackAddress();

            mainHandler.post(listener::onStarted);

            byte[] buffer = new byte[65_535];
            long totalPackets = 0;
            long totalBytes = 0;
            long intervalBytes = 0;
            long intervalStartedAt = System.currentTimeMillis();

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    long receivedAt = System.currentTimeMillis();
                    int packetLength = packet.getLength();
                    totalPackets++;
                    totalBytes += packetLength;
                    intervalBytes += packetLength;

                    streamInspector.consume(packet.getData(), packetLength, receivedAt);
                    relayCleanTransportStream(relaySocket, loopback, packet);
                } catch (SocketTimeoutException ignored) {
                    // Timeout lets the loop publish stats and react to stop().
                }

                long now = System.currentTimeMillis();
                long elapsed = now - intervalStartedAt;
                if (elapsed >= 1_000L) {
                    long bytesPerSecond = elapsed == 0 ? 0 : intervalBytes * 1_000L / elapsed;
                    long packetsSnapshot = totalPackets;
                    long bytesSnapshot = totalBytes;
                    MpegTsStreamInspector.Snapshot streamSnapshot = streamInspector.snapshot();
                    mainHandler.post(
                            () -> listener.onStats(
                                    packetsSnapshot,
                                    bytesSnapshot,
                                    bytesPerSecond,
                                    streamSnapshot));
                    intervalBytes = 0;
                    intervalStartedAt = now;
                }
            }
        } catch (IOException exception) {
            if (running) {
                mainHandler.post(
                        () -> listener.onError(
                                "Không nhận/relay được preview UDP: " + exception.getMessage()));
            }
        } finally {
            if (socket != null) {
                socket.close();
            }
            if (relaySocket != null) {
                relaySocket.close();
            }
            receiveSocket = null;
        }
    }

    private static void relayCleanTransportStream(
            @NonNull DatagramSocket relaySocket,
            @NonNull InetAddress loopback,
            @NonNull DatagramPacket sourcePacket)
            throws IOException {
        byte[] data = sourcePacket.getData();
        int packetOffset = sourcePacket.getOffset();
        int packetLength = sourcePacket.getLength();
        int packetEnd = packetOffset + packetLength;

        int tsOffset = findAlignedTsOffset(data, packetOffset, packetEnd);
        if (tsOffset < 0) {
            return;
        }

        int available = packetEnd - tsOffset;
        int tsLength = available - (available % TS_PACKET_SIZE);
        if (tsLength < TS_PACKET_SIZE) {
            return;
        }

        DatagramPacket relayPacket =
                new DatagramPacket(
                        data,
                        tsOffset,
                        tsLength,
                        loopback,
                        Hero8PreviewPlayer.LOCAL_RELAY_PORT);
        relaySocket.send(relayPacket);
    }

    /** Finds the first MPEG-TS sync byte whose next packet is also aligned on 188 bytes. */
    private static int findAlignedTsOffset(byte[] data, int start, int end) {
        int scanEnd = Math.min(end, start + MAX_HEADER_SCAN_BYTES);
        for (int offset = start; offset < scanEnd; offset++) {
            if ((data[offset] & 0xFF) != 0x47) {
                continue;
            }

            int next = offset + TS_PACKET_SIZE;
            if (next >= end || (data[next] & 0xFF) == 0x47) {
                return offset;
            }
        }
        return -1;
    }

    private void sendKeepAlive(@NonNull Network network) {
        if (!running) {
            return;
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            network.bindSocket(socket);
            InetAddress cameraAddress = network.getByName("10.5.5.9");
            DatagramPacket packet =
                    new DatagramPacket(
                            KEEP_ALIVE_PAYLOAD,
                            KEEP_ALIVE_PAYLOAD.length,
                            cameraAddress,
                            UDP_PORT);
            socket.send(packet);
        } catch (IOException exception) {
            if (running) {
                mainHandler.post(
                        () -> listener.onError(
                                "Không gửi được GoPro keep-alive: " + exception.getMessage()));
            }
        }
    }

    private synchronized void stopInternal(boolean notifyListener) {
        boolean wasRunning = running;
        running = false;

        DatagramSocket socket = receiveSocket;
        if (socket != null) {
            socket.close();
        }

        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
            keepAliveExecutor = null;
        }

        if (receiveExecutor != null) {
            receiveExecutor.shutdownNow();
            receiveExecutor = null;
        }

        if (notifyListener && wasRunning) {
            mainHandler.post(listener::onStopped);
        }
    }

    @Override
    public void close() {
        stopInternal(false);
    }
}
