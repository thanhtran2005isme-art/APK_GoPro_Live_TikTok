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
 * Debug probe for the legacy GoPro UDP preview stream.
 *
 * <p>This class deliberately does not decode MPEG-TS yet. It proves that the phone can receive
 * packets on UDP/8554 while periodic keep-alive packets are routed to the GoPro network.</p>
 */
public final class Hero8UdpPreviewProbe implements AutoCloseable {

    public interface Listener {
        void onStarted();

        void onStats(long totalPackets, long totalBytes, long bytesPerSecond);

        void onStopped();

        void onError(@NonNull String message);
    }

    private static final int UDP_PORT = 8554;
    private static final int RECEIVE_TIMEOUT_MS = 1_000;
    private static final long KEEP_ALIVE_PERIOD_MS = 2_500L;
    private static final byte[] KEEP_ALIVE_PAYLOAD =
            "_GPHD_:0:0:2:0.000000\n".getBytes(StandardCharsets.UTF_8);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private volatile boolean running;
    private volatile DatagramSocket receiveSocket;

    private ExecutorService receiveExecutor;
    private ScheduledExecutorService keepAliveExecutor;

    public Hero8UdpPreviewProbe(@NonNull Listener listener) {
        this.listener = listener;
    }

    public synchronized void start(@NonNull Network network) {
        stopInternal(false);
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
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            network.bindSocket(socket);
            socket.bind(new InetSocketAddress(UDP_PORT));
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            receiveSocket = socket;

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
                    totalPackets++;
                    totalBytes += packet.getLength();
                    intervalBytes += packet.getLength();
                } catch (SocketTimeoutException ignored) {
                    // Timeout is expected so the loop can check the running flag and publish stats.
                }

                long now = System.currentTimeMillis();
                long elapsed = now - intervalStartedAt;
                if (elapsed >= 1_000L) {
                    long bytesPerSecond = elapsed == 0 ? 0 : intervalBytes * 1_000L / elapsed;
                    long packetsSnapshot = totalPackets;
                    long bytesSnapshot = totalBytes;
                    mainHandler.post(
                            () -> listener.onStats(
                                    packetsSnapshot,
                                    bytesSnapshot,
                                    bytesPerSecond));
                    intervalBytes = 0;
                    intervalStartedAt = now;
                }
            }
        } catch (IOException exception) {
            if (running) {
                mainHandler.post(
                        () -> listener.onError(
                                "Không mở/nhận được UDP 8554: " + exception.getMessage()));
            }
        } finally {
            if (socket != null) {
                socket.close();
            }
            receiveSocket = null;
        }
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
