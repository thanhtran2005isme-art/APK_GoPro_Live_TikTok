package com.example.gopro;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.FrameLayout;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.gopro.camera.hero8.GoProHttpClient;
import com.example.gopro.camera.hero8.Hero8BleManager;
import com.example.gopro.camera.hero8.Hero8PreviewPlayer;
import com.example.gopro.camera.hero8.Hero8UdpPreviewProbe;
import com.example.gopro.camera.hero8.MpegTsStreamInspector;
import com.example.gopro.databinding.ActivityMainBinding;
import com.example.gopro.network.GoProNetworkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** HERO8 preview + fullscreen TikTok broadcast screen. */
public class MainActivity extends AppCompatActivity implements GoProNetworkManager.Listener {

    private ActivityMainBinding binding;
    private GoProNetworkManager networkManager;
    private GoProHttpClient httpClient;
    private Hero8BleManager bleManager;
    private Hero8UdpPreviewProbe previewProbe;
    private Hero8PreviewPlayer previewPlayer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String pendingSsid;
    private String pendingPassword;
    private boolean pendingCameraPreviewStart;
    private Network pendingPreviewNetwork;

    private boolean broadcastMode;
    private boolean broadcastFill = true;
    private int sourceVideoWidth = 16;
    private int sourceVideoHeight = 9;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (allGranted(result)) {
                            startBleBootstrap();
                        } else {
                            binding.connectButton.setEnabled(true);
                            setStatus(
                                    "Thiếu quyền Bluetooth/Wi-Fi. App cần BLE để kích hoạt HERO8 trước khi kết nối Wi-Fi.");
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Only the setup UI consumes system-bar insets. The black broadcast overlay deliberately
        // stays edge-to-edge so TikTok screen capture sees a true fullscreen frame.
        ViewCompat.setOnApplyWindowInsetsListener(
                binding.setupScroll,
                (view, insets) -> {
                    Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                    return insets;
                });

        networkManager = new GoProNetworkManager(this, this);
        httpClient = new GoProHttpClient();

        previewPlayer =
                new Hero8PreviewPlayer(
                        binding.previewSurfaceView,
                        new Hero8PreviewPlayer.Listener() {
                            @Override
                            public void onDecoderStatus(@NonNull String message) {
                                if (!broadcastMode) {
                                    binding.previewErrorText.setVisibility(View.VISIBLE);
                                    binding.previewErrorText.setText(message);
                                }
                            }

                            @Override
                            public void onDecoderError(@NonNull String message) {
                                if (broadcastMode) {
                                    exitBroadcastMode();
                                }
                                binding.previewErrorText.setVisibility(View.VISIBLE);
                                binding.previewErrorText.setText(message);
                                binding.broadcastButton.setEnabled(false);
                                setStatus(message);
                            }

                            @Override
                            public void onFirstFrame() {
                                binding.previewErrorText.setVisibility(View.GONE);
                                binding.broadcastButton.setEnabled(true);
                                setStatus(
                                        "Preview ổn định. PTS pacing + jitter buffer đang chạy; có thể mở Broadcast Fullscreen.");
                            }
                        });

        configureBroadcastSurface();
        configureBroadcastGestures();
        configureBackNavigation();

        bleManager =
                new Hero8BleManager(
                        this,
                        new Hero8BleManager.Listener() {
                            @Override
                            public void onScanning() {
                                binding.connectButton.setEnabled(false);
                                setStatus(
                                        "Đang tìm HERO8 qua BLE… Nếu camera chưa hiện, mở Connections > Connect Device > GoPro Quik App.");
                            }

                            @Override
                            public void onPairing(@NonNull String deviceName) {
                                setStatus(
                                        "Đã tìm thấy " + deviceName
                                                + ". Đang ghép đôi Bluetooth; xác nhận popup nếu Android yêu cầu.");
                            }

                            @Override
                            public void onBleConnecting(@NonNull String deviceName) {
                                setStatus(
                                        "Đang kết nối BLE tới " + deviceName
                                                + " và kích hoạt Wi-Fi control service…");
                            }

                            @Override
                            public void onWifiApEnabled(@NonNull String deviceName) {
                                setStatus(
                                        "BLE " + deviceName
                                                + " OK. HERO8 đã nhận lệnh bật Wi-Fi AP; đang kết nối SSID…");
                                mainHandler.postDelayed(
                                        MainActivity.this::connectToPendingGoPro, 1_500L);
                            }

                            @Override
                            public void onError(@NonNull String message) {
                                binding.connectButton.setEnabled(true);
                                binding.verifyButton.setEnabled(false);
                                binding.startPreviewButton.setEnabled(false);
                                binding.broadcastButton.setEnabled(false);
                                setStatus("BLE HERO8: " + message);
                            }
                        });

        previewProbe =
                new Hero8UdpPreviewProbe(
                        new Hero8UdpPreviewProbe.Listener() {
                            @Override
                            public void onStarted() {
                                binding.startPreviewButton.setEnabled(false);
                                binding.stopPreviewButton.setEnabled(true);
                                setStatus("UDP/8554 đã bind. Đang yêu cầu HERO8 bắt đầu gpStream…");

                                if (pendingCameraPreviewStart && pendingPreviewNetwork != null) {
                                    Network network = pendingPreviewNetwork;
                                    pendingCameraPreviewStart = false;
                                    sendCameraPreviewStart(network);
                                }
                            }

                            @Override
                            public void onStats(
                                    long totalPackets,
                                    long totalBytes,
                                    long bytesPerSecond,
                                    @NonNull MpegTsStreamInspector.Snapshot streamInfo) {
                                double kibPerSecond = bytesPerSecond / 1024.0;
                                double mbitPerSecond = bytesPerSecond * 8.0 / 1_000_000.0;
                                String container =
                                        streamInfo.transportStreamDetected ? "MPEG-TS" : "detecting";
                                String videoPid =
                                        streamInfo.videoPid >= 0
                                                ? String.format(Locale.US, "0x%04X", streamInfo.videoPid)
                                                : "detecting";

                                binding.udpStatsText.setText(
                                        String.format(
                                                Locale.US,
                                                "packets=%d\n"
                                                        + "bytes=%d\n"
                                                        + "rate=%.1f KiB/s (%.2f Mbit/s)\n"
                                                        + "container=%s\n"
                                                        + "codec=%s\n"
                                                        + "videoPid=%s\n"
                                                        + "resolution=%s\n"
                                                        + "fps≈%s\n"
                                                        + "render=PTS paced, jitter=~2 frames",
                                                totalPackets,
                                                totalBytes,
                                                kibPerSecond,
                                                mbitPerSecond,
                                                container,
                                                streamInfo.codec,
                                                videoPid,
                                                streamInfo.resolutionLabel(),
                                                streamInfo.fpsLabel()));

                                if (streamInfo.width > 0 && streamInfo.height > 0) {
                                    sourceVideoWidth = streamInfo.width;
                                    sourceVideoHeight = streamInfo.height;
                                    if (broadcastMode) {
                                        applyBroadcastVideoLayout();
                                    }
                                    if (!broadcastMode) {
                                        setStatus(
                                                String.format(
                                                        Locale.US,
                                                        "Đang nhận preview: %dx%d, %s, ~%s fps, %.2f Mbit/s. PTS pacing đang bật.",
                                                        streamInfo.width,
                                                        streamInfo.height,
                                                        streamInfo.codec,
                                                        streamInfo.fpsLabel(),
                                                        mbitPerSecond));
                                    }
                                } else if (streamInfo.transportStreamDetected) {
                                    if (!broadcastMode) {
                                        setStatus("Đã nhận MPEG-TS; đang tìm SPS/PPS/IDR cho MediaCodec…");
                                    }
                                } else if (totalPackets > 0 && !broadcastMode) {
                                    setStatus("Đã nhận UDP; đang xác định MPEG-TS…");
                                }
                            }

                            @Override
                            public void onStopped() {
                                if (broadcastMode) {
                                    exitBroadcastMode();
                                }
                                previewPlayer.stop();
                                binding.previewErrorText.setVisibility(View.GONE);
                                binding.broadcastButton.setEnabled(false);
                                binding.stopPreviewButton.setEnabled(false);
                                binding.startPreviewButton.setEnabled(
                                        networkManager.getActiveNetwork() != null);
                            }

                            @Override
                            public void onError(@NonNull String message) {
                                if (broadcastMode) {
                                    exitBroadcastMode();
                                }
                                previewPlayer.stop();
                                binding.broadcastButton.setEnabled(false);
                                binding.stopPreviewButton.setEnabled(false);
                                binding.startPreviewButton.setEnabled(
                                        networkManager.getActiveNetwork() != null);
                                setStatus(message);
                            }
                        });

        binding.connectButton.setOnClickListener(view -> prepareConnection());
        binding.disconnectButton.setOnClickListener(
                view -> {
                    stopPreviewProbe(false);
                    bleManager.close();
                    networkManager.disconnect();
                });
        binding.verifyButton.setOnClickListener(view -> verifyHero8Http());
        binding.startPreviewButton.setOnClickListener(view -> startPreviewProbe());
        binding.stopPreviewButton.setOnClickListener(view -> stopPreviewProbe(true));
        binding.broadcastButton.setOnClickListener(view -> enterBroadcastMode());
    }

    private void configureBroadcastSurface() {
        binding.broadcastSurfaceView.getHolder().addCallback(
                new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(@NonNull SurfaceHolder holder) {
                        if (broadcastMode) {
                            previewPlayer.requestOutputSurface(holder.getSurface());
                        }
                    }

                    @Override
                    public void surfaceChanged(
                            @NonNull SurfaceHolder holder,
                            int format,
                            int width,
                            int height) {
                        if (broadcastMode) {
                            previewPlayer.requestOutputSurface(holder.getSurface());
                        }
                    }

                    @Override
                    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                        // exitBroadcastMode() switches back to the normal Surface before hiding it.
                    }
                });
    }

    private void configureBroadcastGestures() {
        GestureDetector detector =
                new GestureDetector(
                        this,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(@NonNull MotionEvent event) {
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(@NonNull MotionEvent event) {
                                if (broadcastMode) {
                                    broadcastFill = !broadcastFill;
                                    applyBroadcastVideoLayout();
                                }
                                return true;
                            }

                            @Override
                            public void onLongPress(@NonNull MotionEvent event) {
                                if (broadcastMode) {
                                    exitBroadcastMode();
                                }
                            }
                        });

        binding.broadcastSurfaceView.setOnTouchListener(
                (view, event) -> detector.onTouchEvent(event));
    }

    private void configureBackNavigation() {
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                if (broadcastMode) {
                                    exitBroadcastMode();
                                    return;
                                }
                                setEnabled(false);
                                getOnBackPressedDispatcher().onBackPressed();
                            }
                        });
    }

    private void enterBroadcastMode() {
        if (!binding.broadcastButton.isEnabled() || broadcastMode) {
            return;
        }

        broadcastMode = true;
        broadcastFill = true;
        binding.broadcastOverlay.setVisibility(View.VISIBLE);
        hideSystemBarsForBroadcast();

        binding.broadcastOverlay.post(
                () -> {
                    applyBroadcastVideoLayout();
                    Surface surface = binding.broadcastSurfaceView.getHolder().getSurface();
                    if (surface != null && surface.isValid()) {
                        previewPlayer.requestOutputSurface(surface);
                    }
                });
    }

    private void exitBroadcastMode() {
        if (!broadcastMode) {
            return;
        }

        broadcastMode = false;
        Surface normalSurface = binding.previewSurfaceView.getHolder().getSurface();
        if (normalSurface != null && normalSurface.isValid()) {
            previewPlayer.requestOutputSurface(normalSurface);
        }

        // Give the decoder thread one cycle to execute setOutputSurface before the fullscreen
        // SurfaceView is hidden/destroyed.
        mainHandler.postDelayed(
                () -> {
                    if (!broadcastMode) {
                        binding.broadcastOverlay.setVisibility(View.GONE);
                        restoreSystemBars();
                        resetBroadcastSurfaceLayout();
                        setStatus("Đã thoát Broadcast Mode. Giữ lâu trên màn hình fullscreen để thoát lần sau.");
                    }
                },
                120L);
    }

    private void applyBroadcastVideoLayout() {
        int hostWidth = binding.broadcastOverlay.getWidth();
        int hostHeight = binding.broadcastOverlay.getHeight();
        if (hostWidth <= 0 || hostHeight <= 0 || sourceVideoWidth <= 0 || sourceVideoHeight <= 0) {
            return;
        }

        float sourceAspect = (float) sourceVideoWidth / (float) sourceVideoHeight;
        float hostAspect = (float) hostWidth / (float) hostHeight;
        int targetWidth;
        int targetHeight;

        if (broadcastFill) {
            // Center-crop so TikTok receives picture all the way to every edge.
            if (hostAspect > sourceAspect) {
                targetWidth = hostWidth;
                targetHeight = Math.round(hostWidth / sourceAspect);
            } else {
                targetHeight = hostHeight;
                targetWidth = Math.round(hostHeight * sourceAspect);
            }
        } else {
            // FIT is available by double-tap if the user wants the entire GoPro frame visible.
            if (hostAspect > sourceAspect) {
                targetHeight = hostHeight;
                targetWidth = Math.round(hostHeight * sourceAspect);
            } else {
                targetWidth = hostWidth;
                targetHeight = Math.round(hostWidth / sourceAspect);
            }
        }

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER);
        binding.broadcastSurfaceView.setLayoutParams(params);
    }

    private void resetBroadcastSurfaceLayout() {
        binding.broadcastSurfaceView.setLayoutParams(
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER));
    }

    private void hideSystemBarsForBroadcast() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), binding.main);
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void restoreSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), binding.main);
        controller.show(WindowInsetsCompat.Type.systemBars());
    }

    private void prepareConnection() {
        String ssid = textOf(binding.ssidInput);
        String password = textOf(binding.passwordInput);

        binding.ssidLayout.setError(null);
        binding.passwordLayout.setError(null);

        if (ssid.isEmpty()) {
            binding.ssidLayout.setError("Nhập SSID của GoPro.");
            return;
        }

        if (!password.isEmpty() && password.length() < 8) {
            binding.passwordLayout.setError("Mật khẩu WPA2 phải có ít nhất 8 ký tự.");
            return;
        }

        pendingSsid = ssid;
        pendingPassword = password;
        binding.httpResponseText.setText(R.string.http_response_empty);
        binding.udpStatsText.setText(R.string.udp_stats_empty);
        binding.previewErrorText.setVisibility(View.GONE);
        binding.broadcastButton.setEnabled(false);

        String[] missing = missingRuntimePermissions();
        if (missing.length > 0) {
            permissionLauncher.launch(missing);
            return;
        }

        startBleBootstrap();
    }

    private void startBleBootstrap() {
        binding.connectButton.setEnabled(false);
        binding.verifyButton.setEnabled(false);
        binding.startPreviewButton.setEnabled(false);
        binding.broadcastButton.setEnabled(false);
        bleManager.enableWifiAp();
    }

    private void connectToPendingGoPro() {
        if (pendingSsid == null || pendingSsid.isEmpty()) {
            binding.connectButton.setEnabled(true);
            return;
        }
        networkManager.connect(pendingSsid, pendingPassword);
    }

    private void verifyHero8Http() {
        Network network = networkManager.getActiveNetwork();
        if (network == null) {
            setStatus("Chưa có network GoPro để kiểm tra HTTP.");
            return;
        }

        binding.verifyButton.setEnabled(false);
        setStatus("BLE/Wi-Fi OK. Đang gọi http://10.5.5.9/gp/gpControl/status …");

        httpClient.verifyConnection(
                network,
                new GoProHttpClient.Callback() {
                    @Override
                    public void onSuccess(@NonNull String body) {
                        binding.connectButton.setEnabled(true);
                        binding.verifyButton.setEnabled(true);
                        binding.startPreviewButton.setEnabled(true);
                        setStatus("HTTP HERO8 OK. Bấm Start preview để xem hình camera.");
                        binding.httpResponseText.setText(trimForScreen(body));
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        binding.connectButton.setEnabled(true);
                        binding.verifyButton.setEnabled(true);
                        binding.startPreviewButton.setEnabled(false);
                        binding.broadcastButton.setEnabled(false);
                        setStatus(
                                message
                                        + "\nBLE đã được bootstrap nhưng HTTP chưa sẵn sàng; thử Kiểm tra HTTP lại sau vài giây.");
                        binding.httpResponseText.setText(R.string.http_response_empty);
                    }
                });
    }

    private void startPreviewProbe() {
        Network network = networkManager.getActiveNetwork();
        if (network == null) {
            setStatus("Chưa kết nối network GoPro.");
            return;
        }

        binding.startPreviewButton.setEnabled(false);
        binding.stopPreviewButton.setEnabled(true);
        binding.broadcastButton.setEnabled(false);
        binding.udpStatsText.setText("Đang bind UDP + khởi tạo MediaCodec…");
        binding.previewErrorText.setVisibility(View.VISIBLE);
        binding.previewErrorText.setText("Đang chuẩn bị MediaCodec + PTS pacing…");

        pendingPreviewNetwork = network;
        pendingCameraPreviewStart = true;

        previewPlayer.start();
        previewProbe.start(network);
    }

    private void sendCameraPreviewStart(@NonNull Network network) {
        httpClient.startPreview(
                network,
                new GoProHttpClient.Callback() {
                    @Override
                    public void onSuccess(@NonNull String body) {
                        binding.httpResponseText.setText(trimForScreen(body));
                        setStatus("HERO8 gpStream đã start; đang chờ SPS/PPS/IDR…");
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        pendingCameraPreviewStart = false;
                        pendingPreviewNetwork = null;
                        previewProbe.stop();
                        previewPlayer.stop();
                        binding.startPreviewButton.setEnabled(true);
                        binding.stopPreviewButton.setEnabled(false);
                        binding.broadcastButton.setEnabled(false);
                        binding.previewErrorText.setVisibility(View.VISIBLE);
                        binding.previewErrorText.setText("Không start được gpStream");
                        setStatus("Không start được preview: " + message);
                    }
                });
    }

    private void stopPreviewProbe(boolean sendStopCommand) {
        if (broadcastMode) {
            exitBroadcastMode();
        }
        pendingCameraPreviewStart = false;
        pendingPreviewNetwork = null;
        previewProbe.stop();
        previewPlayer.stop();
        binding.previewErrorText.setVisibility(View.GONE);
        binding.broadcastButton.setEnabled(false);
        binding.udpStatsText.setText(R.string.udp_stats_empty);

        Network network = networkManager.getActiveNetwork();
        if (!sendStopCommand || network == null) {
            return;
        }

        setStatus("Đang dừng preview HERO8…");
        httpClient.stopPreview(
                network,
                new GoProHttpClient.Callback() {
                    @Override
                    public void onSuccess(@NonNull String body) {
                        binding.httpResponseText.setText(trimForScreen(body));
                        setStatus("Đã dừng preview HERO8.");
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        setStatus("Preview local đã dừng, nhưng lệnh stop camera lỗi: " + message);
                    }
                });
    }

    @NonNull
    private String[] missingRuntimePermissions() {
        List<String> required = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_SCAN);
            required.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        List<String> missing = new ArrayList<>();
        for (String permission : required) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing.toArray(new String[0]);
    }

    private static boolean allGranted(@NonNull Map<String, Boolean> result) {
        if (result.isEmpty()) {
            return false;
        }
        for (Boolean granted : result.values()) {
            if (!Boolean.TRUE.equals(granted)) {
                return false;
            }
        }
        return true;
    }

    private static String textOf(android.widget.EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static String trimForScreen(String value) {
        final int maxChars = 12_000;
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n… response truncated …";
    }

    private void setStatus(String status) {
        binding.statusText.setText(status);
    }

    @Override
    public void onConnecting() {
        binding.connectButton.setEnabled(false);
        binding.verifyButton.setEnabled(false);
        binding.startPreviewButton.setEnabled(false);
        binding.stopPreviewButton.setEnabled(false);
        binding.broadcastButton.setEnabled(false);
        binding.disconnectButton.setEnabled(true);
        setStatus("BLE đã kích hoạt camera. Đang chờ Android kết nối Wi-Fi GoPro…");
    }

    @Override
    public void onConnected(@NonNull Network network) {
        binding.connectButton.setEnabled(false);
        binding.verifyButton.setEnabled(true);
        binding.startPreviewButton.setEnabled(false);
        binding.stopPreviewButton.setEnabled(false);
        binding.broadcastButton.setEnabled(false);
        binding.disconnectButton.setEnabled(true);
        setStatus("Đã có network GoPro: " + network + ". Đợi HTTP service khởi động…");
        mainHandler.postDelayed(this::verifyHero8Http, 1_500L);
    }

    @Override
    public void onDisconnected() {
        if (broadcastMode) {
            exitBroadcastMode();
        }
        pendingCameraPreviewStart = false;
        pendingPreviewNetwork = null;
        previewProbe.close();
        previewPlayer.stop();
        binding.previewErrorText.setVisibility(View.GONE);
        binding.connectButton.setEnabled(true);
        binding.verifyButton.setEnabled(false);
        binding.startPreviewButton.setEnabled(false);
        binding.stopPreviewButton.setEnabled(false);
        binding.broadcastButton.setEnabled(false);
        binding.disconnectButton.setEnabled(false);
        binding.udpStatsText.setText(R.string.udp_stats_empty);
        setStatus("Đã ngắt kết nối GoPro.");
    }

    @Override
    public void onError(@NonNull String message) {
        if (broadcastMode) {
            exitBroadcastMode();
        }
        pendingCameraPreviewStart = false;
        pendingPreviewNetwork = null;
        previewProbe.close();
        previewPlayer.stop();
        binding.previewErrorText.setVisibility(View.GONE);
        binding.connectButton.setEnabled(true);
        binding.verifyButton.setEnabled(false);
        binding.startPreviewButton.setEnabled(false);
        binding.stopPreviewButton.setEnabled(false);
        binding.broadcastButton.setEnabled(false);
        binding.disconnectButton.setEnabled(false);
        setStatus(message);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (previewProbe != null) {
            previewProbe.close();
        }
        if (previewPlayer != null) {
            previewPlayer.close();
        }
        if (bleManager != null) {
            bleManager.close();
        }
        if (networkManager != null) {
            networkManager.close();
        }
        if (httpClient != null) {
            httpClient.close();
        }
        super.onDestroy();
    }
}
