package tw.anan.bletest;

import android.Manifest;
import android.app.Activity;
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
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.UUID;
import java.util.ArrayDeque;
import android.bluetooth.BluetoothGattDescriptor;
import android.graphics.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import java.util.Set;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private LinearLayout devices;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning = false;
    private BluetoothGattCharacteristic tx, data;
    private boolean ready, busy, waiting, paused;
    private int stage, generation;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private BluetoothGattCharacteristic channel;
    private static UUID uuid(int n) { return UUID.fromString(String.format(java.util.Locale.US,"0000%04x-0000-1000-8000-00805f9b34fb",n)); }
    private Button a,b,q;
    private final Set<String> seen = new HashSet<>();
    private static final int PERMISSION_REQUEST = 271;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        Button scan = new Button(this);
        scan.setText("搜尋附近藍牙標籤機（12 秒）");
        scan.setOnClickListener(v -> checkAndScan());
        root.addView(scan);
        a = button(root,"A 趣列印協定：直接列印", () -> print(true));
        b = button(root,"B Cat 協定：直接列印", () -> print(false));
        q = button(root,"讀取狀態 A / B", () -> { if(!ready || busy) return; begin(tx); add(frame(true,0xA1,0)); add(frame(false,0xA3,0)); deadline(); pump(); });
        button(root,"複製全部紀錄", () -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("BLE",status.getText())));
        button(root,"停止傳送／斷線", () -> { reset(); if(gatt!=null){gatt.disconnect();gatt.close();gatt=null;} append("已停止；已送出的資料可能仍會列印。"); });
        status = new TextView(this);
        status.setTextSize(15);
        status.setText("AQ-V268 測試版：直印 V2，兩種實驗協定。\n請先在趣列印中斷開印表機連線。\n");
        ScrollView log = new ScrollView(this); log.addView(status); root.addView(log,new LinearLayout.LayoutParams(-1,0,2));
        ScrollView scroll = new ScrollView(this);
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(devices);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); refresh(); handler.post(this::checkAndScan);
    }

    private void append(String message) {
        runOnUiThread(() -> status.append(message + "\n"));
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkAndScan() {
        if (!hasPermissions()) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST);
            return;
        }
        startScan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (hasPermissions()) startScan();
            else append("請允許附近裝置／位置權限，才能搜尋 BLE 裝置。");
        }
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            if (!seen.add(address)) return;
            String name;
            try { name = device.getName(); } catch (SecurityException e) { name = null; }
            if (name == null || name.isEmpty()) name = "未命名 BLE 裝置";
            final String label = name + "  (RSSI " + result.getRssi() + ")";
            runOnUiThread(() -> {
                Button button = new Button(MainActivity.this);
                button.setAllCaps(false);
                button.setText(label);
                button.setOnClickListener(v -> connect(device, label));
                devices.addView(button);
                if(scanning && label.toUpperCase(java.util.Locale.ROOT).replace("-", "").contains("AQV268")) connect(device,label);
            });
        }
        @Override public void onScanFailed(int errorCode) { append("搜尋失敗，代碼：" + errorCode); scanning = false; }
    };

    private void startScan() {
        BluetoothManager manager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { append("請先開啟手機藍牙。"); return; }
        stopScan();
        seen.clear();
        devices.removeAllViews();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { append("無法取得 BLE 掃描器。"); return; }
        try {
            scanner.startScan(callback);
            scanning = true;
            append("正在搜尋附近 BLE 裝置…");
            handler.postDelayed(this::stopScan, 12000);
        } catch (Exception e) { append("搜尋錯誤：" + e.getMessage()); }
    }

    private void stopScan() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(callback); } catch (Exception ignored) { }
            scanning = false;
            append("搜尋已結束，可點選 AQ-V268 裝置連線。");
        }
    }

    private void connect(BluetoothDevice device, String label) {
        stopScan();
        reset();
        if (gatt != null) { gatt.close(); gatt = null; }
        append("正在連接：" + label);
        try { gatt = device.connectGatt(this, false, new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt connection, int statusCode, int newState) {
                append("連線狀態：" + newState + "，狀態碼：" + statusCode);
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try { connection.discoverServices(); } catch (Exception e) { append("服務探索錯誤：" + e.getMessage()); }
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) handler.post(() -> {if(connection==gatt){reset();append("已斷線。");}});
            }
            @Override public void onServicesDiscovered(BluetoothGatt connection, int statusCode) {
                append("BLE 服務探索完成，狀態碼：" + statusCode);
                if (statusCode != BluetoothGatt.GATT_SUCCESS) return;
                for (BluetoothGattService service : connection.getServices()) {
                    append("服務：" + service.getUuid());
                    for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                        append("  特徵：" + c.getUuid() + "，屬性：" + c.getProperties());
                    }
                }
                handler.post(() -> setup(connection));
            }
            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d,int code){handler.post(() -> {if(g!=gatt)return; ready=code==0; refresh(); append("通知訂閱狀態："+code);});}
            @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){byte[] v=c.getValue().clone();handler.post(() -> {if(g==gatt) receive(v);});}
            @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code){handler.post(() -> {if(g!=gatt || !waiting)return; waiting=false; if(code!=0){fail("寫入錯誤 "+code);return;} handler.postDelayed(MainActivity.this::pump,25);});}
        }); } catch (Exception e) { append("連線失敗：" + e.getMessage()); }
    }

    private Button button(LinearLayout root,String label,Runnable task){Button v=new Button(this);v.setText(label);v.setOnClickListener(w->task.run());root.addView(v);return v;}
    private void refresh(){a.setEnabled(ready && !busy && data!=null);b.setEnabled(ready && !busy);q.setEnabled(ready && !busy);}
    private void reset(){generation++;ready=false;busy=false;waiting=false;paused=false;stage=0;queue.clear();tx=null;data=null;refresh();}
    private void fail(String reason){append(reason+"；請斷線後重試。");reset();}
    private void setup(BluetoothGatt g){if(g!=gatt)return; BluetoothGattService svc=g.getService(uuid(0xae30));if(svc==null)svc=g.getService(uuid(0xaf30));if(svc==null){append("找不到列印服務 AE30/AF30");return;}
        tx=svc.getCharacteristic(uuid(0xae01));data=svc.getCharacteristic(uuid(0xae03));BluetoothGattCharacteristic rx=svc.getCharacteristic(uuid(0xae02));
        if(tx==null || rx==null){append("缺少 AE01/AE02");return;} BluetoothGattDescriptor d=rx.getDescriptor(uuid(0x2902));
        if(d==null || !g.setCharacteristicNotification(rx,true)){append("無法訂閱通知");return;}d.setValue((rx.getProperties()&16)!=0?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);if(!g.writeDescriptor(d))append("通知設定失敗");}
    private static byte[] frame(boolean a,int cmd,int... payload){byte[] f=new byte[payload.length+8];f[0]=(byte)(a?0x22:0x51);f[1]=(byte)(a?0x21:0x78);f[2]=(byte)cmd;f[4]=(byte)payload.length;f[5]=(byte)(payload.length>>8);int crc=0;for(int i=0;i<payload.length;i++){f[i+6]=(byte)payload[i];crc^=payload[i]&255;for(int j=0;j<8;j++)crc=((crc<<1)^((crc&128)!=0?7:0))&255;}f[f.length-2]=(byte)crc;f[f.length-1]=(byte)255;return f;}
    private String hex(byte[] v){StringBuilder s=new StringBuilder();for(byte x:v)s.append(String.format(java.util.Locale.US,"%02X ",x&255));return s.toString();}
    private void begin(BluetoothGattCharacteristic c){generation++;busy=true;channel=c;refresh();}
    private void add(byte[] v){for(int i=0;i<v.length;i+=20)queue.add(java.util.Arrays.copyOfRange(v,i,Math.min(i+20,v.length)));}
    private void deadline(){int token=generation;handler.postDelayed(()->{if(token==generation && busy)fail("等待逾時");},45000);}
    private void pump(){if(!busy || waiting || paused)return;if(queue.isEmpty()){if(stage==3){stage=4;channel=tx;add(frame(true,0xad,0));pump();return;}if(stage!=0)return;busy=false;refresh();append("傳輸結束，請確認紙張實際輸出。");return;}
        byte[] v=queue.remove();int props=channel.getProperties();channel.setWriteType((props&4)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);channel.setValue(v);waiting=true;try{if(!gatt.writeCharacteristic(channel)){waiting=false;fail("無法送出資料");return;}}catch(Exception e){fail(e.toString());return;}
        int token=generation;handler.postDelayed(()->{if(token==generation && busy && waiting) { /* overall deadline handles stalled callback */ }},1000);}
    private byte[][] raster(){Bitmap bm=Bitmap.createBitmap(384,240,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bm);c.drawColor(Color.WHITE);Paint p=new Paint(3);p.setColor(Color.BLACK);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(34);c.drawText("阿南檳榔",160,55,p);c.drawText("藍牙直印測試",160,105,p);p.setTextSize(30);c.drawText("AQ-V268",160,155,p);c.drawText("40 x 30 mm",160,200,p);byte[][] rows=new byte[240][48];for(int y=0;y<240;y++)for(int x=0;x<320;x++)if(Color.red(bm.getPixel(x,y))<128)rows[y][x/8]|=1<<(x%8);bm.recycle();return rows;}
    private void print(boolean a){if(!ready || busy)return;begin(tx);deadline();append("開始協定 "+(a?"A":"B")+" 測試，320x240 內容／384 點資料列");if(a){stage=1;add(frame(true,0xa1,0));}else{add(frame(false,0xa3,0));add(frame(false,0xa6,0xaa,0x55,0x17,0x38,0x44,0x5f,0x5f,0x5f,0x44,0x38,0x2c));add(frame(false,0xbe,0));for(byte[] row:raster()){int[] v=new int[48];for(int i=0;i<48;i++)v[i]=row[i]&255;add(frame(false,0xa2,v));}add(frame(false,0xa6,0xaa,0x55,0x17,0,0,0,0,0,0,0,0x17));add(frame(false,0xa3,0));}pump();}
    private void receive(byte[] v){append("RX: "+hex(v));if(v.length<9)return;int cmd=v[2]&255;if(v[0]==0x51 && v[1]==0x78 && cmd==0xae){int flags=v[6]&255;if((flags&15)!=0){fail("印表機回報可能異常");return;}paused=(flags&16)!=0;if(!paused)pump();}if(v[0]!=0x22 || v[1]!=0x21 || !busy)return;
        if(stage==1 && cmd==0xa1){if(v.length<21 || v[12]!=0 || v[18]!=0){fail("A 狀態未確認就緒");return;}stage=2;add(frame(true,0xa9,240,0,48,0));pump();}
        else if(stage==2 && cmd==0xa9){if(v[6]!=0){fail("A 啟動被拒絕");return;}stage=3;channel=data;for(byte[] row:raster())add(row);pump();}
        else if(stage==4 && cmd==0xaa){stage=0;append("收到 A 完成通知，請確認實物。");pump();}}
    @Override protected void onDestroy() {
        stopScan();
        reset();
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
        super.onDestroy();
    }
}
