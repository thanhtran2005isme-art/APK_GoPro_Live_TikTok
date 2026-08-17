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
import com.example.gopro.databinding.ActivityMainBinding;
import com.example.gopro.network.GoProNetworkManager;

/** Debug-first entry screen for validating HERO8 Wi-Fi and legacy gpControl access. */
public class MainActivity extends AppCompatActivity implements GoProNetworkManager.Listener {

    private ActivityMainBinding binding;
    private GoProNetworkManager networkManager;
    private GoProHttpClient httpClient;

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

        binding.connectButton.setOnClickListener(view -> prepareConnection());
        binding.disconnectButton.setOnClickListener(view -> networkManager.disconnect());
        binding.verifyButton.setOnClickListener(view -> verifyHero8Http());
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
                        setStatus("HTTP HERO8 OK. Sẵn sàng triển khai preview stream.");
                        binding.httpResponseText.setText(trimForScreen(body));
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        binding.verifyButton.setEnabled(true);
                        setStatus(message);
                        binding.httpResponseText.setText(R.string.http_response_empty);
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
        binding.disconnectButton.setEnabled(true);
        setStatus("Đang chờ Android kết nối Wi-Fi GoPro… Hãy xác nhận hộp thoại hệ thống.");
    }

    @Override
    public void onConnected(@NonNull Network network) {
        binding.connectButton.setEnabled(true);
        binding.verifyButton.setEnabled(true);
        binding.disconnectButton.setEnabled(true);
        setStatus("Đã kết nối network GoPro: " + network + ". Bấm Kiểm tra HTTP.");
    }

    @Override
    public void onDisconnected() {
        binding.connectButton.setEnabled(true);
        binding.verifyButton.setEnabled(false);
        binding.disconnectButton.setEnabled(false);
        setStatus("Đã ngắt kết nối GoPro.");
    }

    @Override
    public void onError(@NonNull String message) {
        binding.connectButton.setEnabled(true);
        binding.verifyButton.setEnabled(false);
        binding.disconnectButton.setEnabled(false);
        setStatus(message);
    }

    @Override
    protected void onDestroy() {
        if (networkManager != null) {
            networkManager.disconnect();
        }
        if (httpClient != null) {
            httpClient.close();
        }
        binding = null;
        super.onDestroy();
    }
}
