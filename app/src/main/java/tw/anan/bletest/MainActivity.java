package tw.anan.bletest;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private TextView log;
    private LinearLayout devices;
    private Button testButton;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> found = new HashSet<>();
    private boolean scanning = false;
    private int scanGeneration = 0;
    private final StringBuilder history = new StringBuilder();
    private static final UUID SERVICE_AE30 = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_AE01 = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb");
    private static final UUID SERVICE_AE3A = UUID.fromString("0000ae3a-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_AE3B = UUID.fromString("0000ae3b-0000-1000-8000-00805f9b34fb");

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String address;
            try { address = device.getAddress(); } catch (SecurityException ex) { return; }
            if (!found.add(address)) return;
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null || name.isEmpty()) {
                try { name = device.getName(); } catch (SecurityException ignored) { name = null; }
            }
            final String label = (name == null ? "未知裝置" : name) + " / " + address;
            runOnUiThread(() -> {
                Button button = new Button(MainActivity.this);
                button.setText(label);
                devices.addView(button);
                button.setOnClickListener(v -> connect(device));
            });
        }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            message("搜尋失敗：" + errorCode);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 120, 16, 16);
        Button scan = new Button(this);
        scan.setText("搜尋藍牙印表機");
        root.addView(scan);
        testButton = new Button(this);
        testButton.setText("測試通道（不列印）");
        testButton.setEnabled(false);
        root.addView(testButton);
        log = new TextView(this);
        log.setTextIsSelectable(true);
        root.addView(log);
        ScrollView scroll = new ScrollView(this);
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(devices);
        root.addView(scroll);
        setContentView(root);
        message("AQ-V268 藍牙測試版\n尚未搜尋");
        scan.setOnClickListener(v -> startScan());
        testButton.setOnClickListener(v -> testChannel());
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        if (!hasPermissions()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 100);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 100);
            }
            message("請允許藍牙權限，再按一次搜尋");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) { message("請先開啟手機藍牙"); return; }
        stopScan();
        found.clear();
        devices.removeAllViews();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { message("無法啟動 BLE 搜尋"); return; }
        try {
            scanner.startScan(scanCallback);
            scanning = true;
            final int generation = ++scanGeneration;
            message("正在搜尋附近 BLE 裝置...");
            handler.postDelayed(() -> {
                if (generation != scanGeneration) return;
                stopScan();
                message("搜尋結束，請點選 AQ-V268 印表機");
            }, 12000);
        } catch (SecurityException e) { message("藍牙權限不足"); }
    }

    private void stopScan() {
        ++scanGeneration;
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) {}
        }
        scanning = false;
    }

    private void connect(BluetoothDevice device) {
        stopScan();
        writeCharacteristic = null;
        testButton.setEnabled(false);
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        message("正在連接：" + device.getAddress());
        try {
            gatt = device.connectGatt(this, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                    if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                        message("已連接，正在讀取服務...");
                        try { g.discoverServices(); } catch (SecurityException e) { message("讀取服務權限不足"); }
                    } else {
                        writeCharacteristic = null;
                        runOnUiThread(() -> testButton.setEnabled(false));
                        message("連線結束，狀態碼：" + status);
                        g.close();
                    }
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) { message("讀取服務失敗：" + status); return; }
                    StringBuilder result = new StringBuilder("已找到 BLE 服務：\n");
                    for (BluetoothGattService service : g.getServices()) {
                        result.append("\n服務：").append(service.getUuid()).append("\n");
                        for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                            result.append("特徵：").append(c.getUuid()).append(" 權限：").append(c.getProperties()).append("\n");
                        }
                    }
                    BluetoothGattService ae30 = g.getService(SERVICE_AE30);
                    BluetoothGattService ae3a = g.getService(SERVICE_AE3A);
                    BluetoothGattCharacteristic candidate = ae30 == null ? null : ae30.getCharacteristic(CHAR_AE01);
                    if (candidate == null && ae3a != null) candidate = ae3a.getCharacteristic(CHAR_AE3B);
                    if (candidate != null && (candidate.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                        writeCharacteristic = candidate;
                        result.append("\n找到可寫入通道：").append(candidate.getUuid()).append("\n");
                        runOnUiThread(() -> testButton.setEnabled(true));
                    } else { result.append("\n未找到可寫入通道\n"); }
                    message(result.toString());
                }
                @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    message("寫入回報：" + status + "（0 表示 BLE 寫入成功，不代表已列印）");
                }
            });
        } catch (Exception e) { message("連接失敗：" + e.getMessage()); }
    }

    private void testChannel() {
        if (gatt == null || writeCharacteristic == null) { message("尚未連線"); return; }
        // 不傳送未知列印指令，避免造成走紙或改變印表機設定。
        message("通道可寫入：" + writeCharacteristic.getUuid() + "。尚未發送資料；需先確認 AQ-V268 列印協定。");
    }

    private void message(String text) {
        runOnUiThread(() -> {
            history.append(text).append("\n\n");
            if (history.length() > 10000) history.delete(0, history.length() - 8000);
            log.setText(history.toString());
        });
    }

    @Override protected void onDestroy() {
        stopScan();
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
