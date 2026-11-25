
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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {

    private TextView log;
    private LinearLayout devices;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Set<String> found = new HashSet<>();
    private boolean scanning = false;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            if (!found.add(address)) return;

            String name = result.getScanRecord() == null
                ? null : result.getScanRecord().getDeviceName();

            if (name == null || name.isEmpty()) {
                try {
                    name = device.getName();
                } catch (SecurityException e) {
                    name = null;
                }
            }

            final String label = (name == null ? "未知裝置" : name)
                + " / " + address;

            runOnUiThread(() -> {
                Button button = new Button(MainActivity.this);
                button.setText(label);
                devices.addView(button);
                button.setOnClickListener(v -> connect(device));
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            message("搜尋失敗：" + errorCode);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        BluetoothManager manager =
            (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager.getAdapter();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        Button scan = new Button(this);
        scan.setText("搜尋藍牙印表機");
        root.addView(scan);

        log = new TextView(this);
        log.setText("AQ-V268 藍牙測試版\n尚未搜尋");
        log.setTextIsSelectable(true);
        root.addView(log);

        ScrollView scroll = new ScrollView(this);
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(devices);
        root.addView(scroll);

        setContentView(root);

        
scan.setOnClickListener(v -> {
    android.widget.Toast.makeText(
        MainActivity.this,
        "搜尋按鈕已點擊",
        android.widget.Toast.LENGTH_LONG
    ).show();
    startScan();
});
}

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        if (!hasPermissions()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                }, 100);
            } else {
                requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
                }, 100);
            }
            message("請允許藍牙權限，再按一次搜尋");
            return;
        }

        if (adapter == null || !adapter.isEnabled()) {
            message("請先開啟手機藍牙");
            return;
        }

        stopScan();
        found.clear();
        devices.removeAllViews();

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            message("無法啟動 BLE 搜尋");
            return;
        }

        try {
            scanner.startScan(scanCallback);
            scanning = true;
            message("正在搜尋附近 BLE 裝置...");
            handler.postDelayed(() -> {
                stopScan();
                message("搜尋結束，請點選印表機");
            }, 12000);
        } catch (SecurityException e) {
            message("藍牙權限不足");
        }
    }

    private void stopScan() {
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {}
        }
        scanning = false;
    }

    private void connect(BluetoothDevice device) {
        stopScan();

        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }

        message("正在連接：" + device.getAddress());

        try {
            gatt = device.connectGatt(
                this, false, new BluetoothGattCallback() {

                    @Override
                    public void onConnectionStateChange(
                        BluetoothGatt g, int status, int state) {

                        if (status == BluetoothGatt.GATT_SUCCESS
                            && state == BluetoothProfile.STATE_CONNECTED) {
                            message("已連接，正在讀取服務...");
                            try {
                                g.discoverServices();
                            } catch (SecurityException e) {
                                message("讀取服務權限不足");
                            }
                        } else {
                            message("連線結束，狀態碼：" + status);
                            g.close();
                        }
                    }

                    @Override
                    public void onServicesDiscovered(
                        BluetoothGatt g, int status) {

                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            message("讀取服務失敗：" + status);
                            return;
                        }

                        StringBuilder result = new StringBuilder();
                        result.append("已找到 BLE 服務：\n");

                        for (BluetoothGattService service : g.getServices()) {
                            result.append("\n服務：")
                                .append(service.getUuid()).append("\n");

                            for (BluetoothGattCharacteristic c
                                    : service.getCharacteristics()) {
                                result.append("特徵：")
                                    .append(c.getUuid())
                                    .append(" 權限：")
                                    .append(c.getProperties())
                                    .append("\n");
                            }
                        }

                        message(result.toString());
                    }
                });
        } catch (Exception e) {
            message("連接失敗：" + e.getMessage());
        }
    }

    private void message(String text) {
        runOnUiThread(() -> log.setText(text));
    }

    @Override
    protected void onDestroy() {
        stopScan();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
        }
        super.onDestroy();
    }
}
