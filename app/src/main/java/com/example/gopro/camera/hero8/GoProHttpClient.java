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
    private static final String[] START_PREVIEW_PATHS = {
        "/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=start",
        "/gp/gpControl/execute?p1=gpStream&c1=start",
        "/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=restart",
        "/gp/gpControl/execute?p1=gpStream&c1=restart"
    };
    private static final String[] STOP_PREVIEW_PATHS = {
        "/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=stop",
        "/gp/gpControl/execute?p1=gpStream&c1=stop"
    };

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void verifyConnection(@NonNull Network network, @NonNull Callback callback) {
        get(network, STATUS_PATH, callback);
    }

    public void startPreview(@NonNull Network network, @NonNull Callback callback) {
        tryPaths(network, START_PREVIEW_PATHS, "start preview", callback);
    }

    public void stopPreview(@NonNull Network network, @NonNull Callback callback) {
        tryPaths(network, STOP_PREVIEW_PATHS, "stop preview", callback);
    }

    private void get(@NonNull Network network, @NonNull String path, @NonNull Callback callback) {
        executor.execute(
                () -> {
                    try {
                        HttpResult result = request(network, path);
                        if (result.isSuccessful()) {
                            mainHandler.post(() -> callback.onSuccess(result.body));
                        } else {
                            mainHandler.post(
                                    () -> callback.onError(
                                            "GoPro trả HTTP " + result.responseCode + ": " + result.body));
                        }
                    } catch (IOException exception) {
                        postIoError(callback, exception);
                    }
                });
    }

    private void tryPaths(
            @NonNull Network network,
            @NonNull String[] paths,
            @NonNull String operation,
            @NonNull Callback callback) {
        executor.execute(
                () -> {
                    StringBuilder failures = new StringBuilder();
                    for (String path : paths) {
                        try {
                            HttpResult result = request(network, path);
                            if (result.isSuccessful()) {
                                String successBody = ("command=" + path + "\n" + result.body).trim();
                                mainHandler.post(() -> callback.onSuccess(successBody));
                                return;
                            }
                            appendFailure(
                                    failures,
                                    path,
                                    "HTTP " + result.responseCode + " " + compact(result.body));
                        } catch (IOException exception) {
                            appendFailure(failures, path, "I/O " + exception.getMessage());
                            break;
                        }
                    }
                    String message =
                            "HERO8 không " + operation + " được bằng các gpStream command đã biết:\n" + failures;
                    mainHandler.post(() -> callback.onError(message.trim()));
                });
    }

    @NonNull
    private static HttpResult request(@NonNull Network network, @NonNull String path)
            throws IOException {
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
            return new HttpResult(responseCode, readBody(stream));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void postIoError(@NonNull Callback callback, @NonNull IOException exception) {
        mainHandler.post(
                () -> callback.onError(
                        "Không gọi được HERO8 tại 10.5.5.9: " + exception.getMessage()));
    }

    private static void appendFailure(
            @NonNull StringBuilder output, @NonNull String path, @NonNull String reason) {
        if (output.length() > 0) {
            output.append('\n');
        }
        output.append("- ").append(path).append(" -> ").append(reason);
    }

    @NonNull
    private static String compact(@NonNull String value) {
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        final int maxChars = 260;
        return compact.length() <= maxChars
                ? compact
                : compact.substring(0, maxChars) + "…";
    }

    @NonNull
    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
        }
        return result.toString().trim();
    }

    private static final class HttpResult {
        final int responseCode;
        @NonNull final String body;

        HttpResult(int responseCode, @NonNull String body) {
            this.responseCode = responseCode;
            this.body = body;
        }

        boolean isSuccessful() {
            return responseCode >= 200 && responseCode < 300;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
