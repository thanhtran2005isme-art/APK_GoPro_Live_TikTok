package com.example.gopro;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.gopro.camera.hero8.GoProHttpClient;
import com.example.gopro.camera.hero8.Hero8UdpPreviewProbe;
import com.example.gopro.databinding.ActivityMainBinding;
import com.example.gopro.network.GoProNetworkManager;

import java.util.Locale;

/** Debug-first entry screen for validating HERO8 Wi-Fi, gpControl and UDP preview traffic. */
public class MainActivity extends AppCompatActivity implements GoProNetworkManager.Listener {

    private ActivityMainBinding binding;
    private GoProNetworkManager networkManager;
    private GoProHttpClient httpClient;
    private Hero8UdpPreviewProbe previewProbe;

    private String pendingSsid;
    private String pendingPassword;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            connectToPendingGoPro();
                        } else {
                            setStatus("Không có quyền Wi-Fi cần thiết để kết nối GoPro.");
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(
                binding.main,
                (view, insets) -> {
                    Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                    return insets;
                });

        networkManager = new GoProNetworkManager(this, this);
        httpClient = new GoProHttpClient();
        previewProbe =
                new Hero8UdpPreviewProbe(
                        new Hero8UdpPreviewProbe.Listener() {
                            @Override
                            public void onStarted() {
                                binding.startPreviewButton.setEnabled(false);
                                binding.stopPreviewButton.setEnabled(true);
                                setStatus("UDP/8554 đã mở. Đang chờ MPEG-TS từ HERO8…");
                            }

                            @Override
                            public void onStats(
                                    long totalPackets,
                                    long totalBytes,
                                    long bytesPerSecond) {
                                double kibPerSecond = bytesPerSecond / 1024.0;
                                binding.udpStatsText.setText(
                                        String.format(
                                                Locale.US,
                                                "packets=%d\nbytes=%d\nrate=%.1f KiB/s",
                                                totalPackets,
                                                totalBytes,
                                                kibPerSecond));
                                if (totalPackets > 0) {
                                    setStatus(
                                            "Đang nhận dữ liệu preview HERO8 qua UDP/8554. Bước tiếp theo: decode MPEG-TS/H.264.");
                                }
                            }

                            @Override
                            public void onStopped() {
                                binding.stopPreviewButton.setEnabled(false);
                                binding.startPreviewButton.setEnabled(
                                        networkManager.getActiveNetwork() != null);
                            }

                            @Override
                            public void onError(@NonNull String message) {
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
                    networkManager.disconnect();
                });
        binding.verifyButton.setOnClickListener(view -> verifyHero8Http());
        binding.startPreviewButton.setOnClickListener(view -> startPreviewProbe());
        binding.stopPreviewButton.setOnClickListener(view -> stopPreviewProbe(true));
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

        String permission = requiredWifiPermission();
        if (permission != null
                && ContextCompat.checkSelfPermission(this, permission)
                        != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permission);
            return;
        }

        connectToPendingGoPro();
    }

    private void connectToPendingGoPro() {
        if (pendingSsid == null || pendingSsid.isEmpty()) {
            return;
        }
        binding.httpResponseText.setText(R.string.http_response_empty);
        binding.udpStatsText.setText(R.string.udp_stats_empty);
        networkManager.connect(pendingSsid, pendingPassword);
    }

    private void verifyHero8Http() {
        Network network = networkManager.getActiveNetwork();
        if (network == null) {
            setStatus("Chưa có network GoPro để kiểm tra HTTP.");
            return;
        }

        binding.verifyButton.setEnabled(false);
        setStatus("Đang gọi http://10.5.5.9/gp/gpControl/status …");

        httpClient.verifyConnection(
                network,
                new GoProHttpClient.Callback() {
                    @Override
                    public void onSuccess(@NonNull String body) {
                        binding.verifyButton.setEnabled(true);
                        binding.startPreviewButton.setEnabled(true);
                        setStatus("HTTP HERO8 OK. Có thể chạy preview probe.");
                        binding.httpResponseText.setText(trimForScreen(body));
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        binding.verifyButton.setEnabled(true);
                        binding.startPreviewButton.setEnabled(false);
                        setStatus(message);
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
        binding.udpStatsText.setText("Đang khởi tạo preview…");
        setStatus("Đang gửi lệnh gpStream restart tới HERO8…");

        httpClient.startPreview(
                network,
                new GoProHttpClient.Callback() {
                    @Override
                    public void onSuccess(@NonNull String body) {
                        binding.httpResponseText.setText(trimForScreen(body));
                        previewProbe.start(network);
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        binding.startPreviewButton.setEnabled(true);
                        binding.stopPreviewButton.setEnabled(false);
                        setStatus("Không start được preview: " + message);
                    }
                });
    }

    private void stopPreviewProbe(boolean sendStopCommand) {
        previewProbe.stop();
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
                        setStatus("UDP probe đã dừng, nhưng lệnh stop preview lỗi: " + message);
                    }
                });
    }

    private String requiredWifiPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Manifest.permission.NEARBY_WIFI_DEVICES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return Manifest.permission.ACCESS_FINE_LOCATION;
        }
        return null;
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
        binding.disconnectButton.setEnabled(true);
        setStatus("Đang chờ Android kết nối Wi-Fi GoPro… Hãy xác nhận hộp thoại hệ thống.");
    }

    @Override
    public void onConnected(@NonNull Network network) {
        binding.connectButton.setEnabled(true);
        binding.verifyButton.setEnabled(true);
        binding.startPreviewButton.setEnabled(false);
        binding.stopPreviewButton.setEnabled(false);
        binding.disconnectButton.setEnabled(true);
        setStatus("Đã kết nối network GoPro: " + network + ". Bấm Kiểm tra HTTP.");
    }

    @Override
    public void onDisconnected() {
        previewProbe.close();
        binding.connectButton.setEnabled(true);
        binding.verifyButton.setEnabled(false);
        binding.startPreviewButton.setEnabled(false);
        binding.stopPreviewButton.setEnabled(false);
        binding.disconnectButton.setEnabled(false);
        binding.udpStatsText.setText(R.string.udp_stats_empty);
        setStatus("Đã ngắt kết nối GoPro.");
    }

    @Override
    public void onError(@NonNull String message) {
        previewProbe.close();
        binding.connectButton.setEnabled(true);
        binding.verifyButton.setEnabled(false);
        binding.startPreviewButton.setEnabled(false);
        binding.stopPreviewButton.setEnabled(false);
        binding.disconnectButton.setEnabled(false);
        setStatus(message);
    }

    @Override
    protected void onDestroy() {
        if (previewProbe != null) {
            previewProbe.close();
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
