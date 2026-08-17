package com.example.gopro.camera.hero8;

import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal HTTP client for legacy HERO8 gpControl endpoints. */
public final class GoProHttpClient implements AutoCloseable {

    public interface Callback {
        void onSuccess(@NonNull String body);

        void onError(@NonNull String message);
    }

    private static final String BASE_URL = "http://10.5.5.9";
    private static final String STATUS_PATH = "/gp/gpControl/status";
    private static final String START_PREVIEW_PATH =
            "/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=restart";
    private static final String STOP_PREVIEW_PATH =
            "/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=stop";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Verifies that the selected local-only network reaches the legacy GoPro control endpoint. */
    public void verifyConnection(@NonNull Network network, @NonNull Callback callback) {
        get(network, STATUS_PATH, callback);
    }

    /** Requests the legacy gpStream preview session used by pre-Open-GoPro cameras. */
    public void startPreview(@NonNull Network network, @NonNull Callback callback) {
        get(network, START_PREVIEW_PATH, callback);
    }

    public void stopPreview(@NonNull Network network, @NonNull Callback callback) {
        get(network, STOP_PREVIEW_PATH, callback);
    }

    private void get(
            @NonNull Network network,
            @NonNull String path,
            @NonNull Callback callback) {
        executor.execute(
                () -> {
                    HttpURLConnection connection = null;
                    try {
                        URL url = new URL(BASE_URL + path);
                        connection = (HttpURLConnection) network.openConnection(url);
                        connection.setRequestMethod("GET");
                        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                        connection.setReadTimeout(READ_TIMEOUT_MS);
                        connection.setUseCaches(false);

                        int responseCode = connection.getResponseCode();
                        InputStream stream =
                                responseCode >= 200 && responseCode < 300
                                        ? connection.getInputStream()
                                        : connection.getErrorStream();
                        String body = readBody(stream);

                        if (responseCode >= 200 && responseCode < 300) {
                            mainHandler.post(() -> callback.onSuccess(body));
                        } else {
                            mainHandler.post(
                                    () -> callback.onError(
                                            "GoPro trả HTTP " + responseCode + ": " + body));
                        }
                    } catch (IOException exception) {
                        mainHandler.post(
                                () -> callback.onError(
                                        "Không gọi được HERO8 tại 10.5.5.9: "
                                                + exception.getMessage()));
                    } finally {
                        if (connection != null) {
                            connection.disconnect();
                        }
                    }
                });
    }

    @NonNull
    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
        }
        return result.toString().trim();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
