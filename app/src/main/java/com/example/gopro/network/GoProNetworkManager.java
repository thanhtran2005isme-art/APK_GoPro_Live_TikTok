package com.example.gopro.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Owns the local-only Wi-Fi connection to a GoPro camera.
 *
 * <p>The GoPro network is intentionally not bound process-wide. Callers should use the returned
 * {@link Network} for HTTP connections and bind UDP sockets to the same network. This keeps normal
 * internet traffic free to use cellular or another validated network while the camera traffic stays
 * on the GoPro Wi-Fi link.</p>
 */
public final class GoProNetworkManager implements AutoCloseable {

    public interface Listener {
        void onConnecting();

        void onConnected(@NonNull Network network);

        void onDisconnected();

        void onError(@NonNull String message);
    }

    private static final int REQUEST_TIMEOUT_MS = 30_000;

    private final ConnectivityManager connectivityManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    @Nullable
    private volatile ConnectivityManager.NetworkCallback networkCallback;

    @Nullable
    private volatile Network activeNetwork;

    public GoProNetworkManager(@NonNull Context context, @NonNull Listener listener) {
        connectivityManager =
                (ConnectivityManager) context.getApplicationContext()
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    public void connect(@NonNull String ssid, @Nullable String password) {
        disconnectInternal(false);

        WifiNetworkSpecifier.Builder specifierBuilder =
                new WifiNetworkSpecifier.Builder().setSsid(ssid);

        if (password != null && !password.isBlank()) {
            specifierBuilder.setWpa2Passphrase(password);
        }

        WifiNetworkSpecifier specifier = specifierBuilder.build();
        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(specifier)
                        .build();

        ConnectivityManager.NetworkCallback callback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        activeNetwork = network;
                        mainHandler.post(() -> listener.onConnected(network));
                    }

                    @Override
                    public void onLost(@NonNull Network network) {
                        if (network.equals(activeNetwork)) {
                            activeNetwork = null;
                            mainHandler.post(listener::onDisconnected);
                        }
                    }

                    @Override
                    public void onUnavailable() {
                        activeNetwork = null;
                        mainHandler.post(
                                () -> listener.onError(
                                        "Không thể kết nối Wi-Fi GoPro. Kiểm tra SSID/mật khẩu và xác nhận hộp thoại hệ thống."));
                    }
                };

        networkCallback = callback;
        mainHandler.post(listener::onConnecting);

        try {
            connectivityManager.requestNetwork(request, callback, REQUEST_TIMEOUT_MS);
        } catch (SecurityException exception) {
            networkCallback = null;
            mainHandler.post(
                    () -> listener.onError(
                            "Thiếu quyền Wi-Fi cần thiết: " + exception.getMessage()));
        } catch (RuntimeException exception) {
            networkCallback = null;
            mainHandler.post(
                    () -> listener.onError(
                            "Không thể tạo yêu cầu kết nối Wi-Fi: " + exception.getMessage()));
        }
    }

    public void disconnect() {
        disconnectInternal(true);
    }

    private void disconnectInternal(boolean notifyListener) {
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        activeNetwork = null;

        if (callback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException ignored) {
                // Callback was already unregistered by the system.
            }
        }

        if (notifyListener) {
            mainHandler.post(listener::onDisconnected);
        }
    }

    @Nullable
    public Network getActiveNetwork() {
        return activeNetwork;
    }

    @Override
    public void close() {
        disconnectInternal(false);
    }
}
