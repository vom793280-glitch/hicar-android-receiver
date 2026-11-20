package local.hicarlab;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.View;
import android.widget.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Route B relay role. Runs on an Android phone that HAS BLE advertising (e.g. the OnePlus),
 * standing in front of a head unit that has none. It:
 *   1. broadcasts the same FDEE beacon the head unit would, so the Huawei phone discovers it;
 *   2. registers FE35/FE36 RFCOMM services and accepts the Huawei phone's connections;
 *   3. connects to the head unit's TUNNEL_UUID service over classic Bluetooth (pre-paired);
 *   4. forwards FE35/FE36 bytes both ways over the multiplexed tunnel.
 *
 * Authentication (HiChain keys) and all media stay on the head unit. The relay moves only
 * the small FE35/FE36 control + auth byte streams. The Huawei phone bonds to the RELAY's
 * Bluetooth address, so the pairing record lives here, not on the head unit — this is what
 * breaks the "no advertising -> no first pairing" deadlock.
 */
public final class RelayActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView logText;
    private Button startBtn, stopBtn;
    private BluetoothAdapter adapter;
    private final CopyOnWriteArrayList<BluetoothServerSocket> servers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Thread> workers = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private final AtomicInteger generation = new AtomicInteger();
    private static final UUID FE35 = UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb");
    private static final UUID FE36 = UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb");
    private static final UUID FDEE = UUID.fromString("0000fdee-0000-1000-8000-00805f9b34fb");
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advCallback;
    private TunnelLink.Mux mux;
    private String headUnitAddress;

    private void log(String s) {
        DiagnosticLog.add(this, "[中继] " + s);
        handler.post(() -> { if (logText != null) logText.setText(DiagnosticLog.history(this)); });
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager mgr = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = mgr == null ? null : mgr.getAdapter();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(20));
        scroll.addView(root);
        TextView title = new TextView(this);
        title.setText("中继转发（路线 B）"); title.setTextSize(24); root.addView(title);
        TextView help = new TextView(this);
        help.setText("用途：本机有 BLE 广播，代替无广播车机被华为手机发现；FE35/FE36 字节经蓝牙隧道转发给车机，认证与画面都在车机。\n\n使用前：1) 先在系统蓝牙里把本机与车机配对；2) 在下方填入车机蓝牙地址；3) 车机 App 须开启“中继隧道（车机端）”开关。连接码请在车机 App 上查看。");
        help.setTextSize(14); help.setPadding(0, dp(10), 0, dp(16)); root.addView(help);
        EditText addr = new EditText(this);
        addr.setSingleLine(true);
        addr.setHint("车机蓝牙地址 如 12:34:56:78:9A:BC");
        addr.setText(getSharedPreferences("relay", 0).getString("head_unit_addr", ""));
        root.addView(addr);
        startBtn = new Button(this); startBtn.setText("启动中继"); root.addView(startBtn);
        stopBtn = new Button(this); stopBtn.setText("停止中继"); stopBtn.setEnabled(false); root.addView(stopBtn);
        logText = new TextView(this); logText.setTextIsSelectable(true); logText.setTextSize(12);
        logText.setPadding(0, dp(14), 0, dp(0)); root.addView(logText);
        setContentView(scroll);

        startBtn.setOnClickListener(v -> {
            headUnitAddress = addr.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (!headUnitAddress.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) { log("请先填写正确的车机蓝牙地址。"); return; }
            if (!permitted()) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.ACCESS_FINE_LOCATION}, 70);
                return;
            }
            if (adapter == null || !adapter.isEnabled()) { log("蓝牙不可用或未开启。"); return; }
            getSharedPreferences("relay", 0).edit().putString("head_unit_addr", headUnitAddress).apply();
            start();
        });
        stopBtn.setOnClickListener(v -> stop());
        log("中继就绪。车机地址：" + (headUnitAddress == null || headUnitAddress.isEmpty() ? "未填" : headUnitAddress));
    }

    private boolean permitted() {
        if (Build.VERSION.SDK_INT < 31) return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == 70) log(permitted() ? "权限已就绪，请再点启动中继。" : "权限未全部授予，中继无法启动。");
    }

    private void start() {
        if (running) { log("中继已在运行。"); return; }
        running = true;
        startBtn.setEnabled(false); stopBtn.setEnabled(true);
        int token = generation.incrementAndGet();
        Thread boot = new Thread(() -> {
            try {
                log("连接车机隧道服务 " + headUnitAddress + " ...");
                BluetoothDevice car = adapter.getRemoteDevice(headUnitAddress);
                BluetoothSocket tunnel = car.createRfcommSocketToServiceRecord(TunnelLink.TUNNEL_UUID);
                try { adapter.cancelDiscovery(); } catch (RuntimeException ignored) { }
                tunnel.connect();
                if (generation.get() != token || !running) { tunnel.close(); return; }
                mux = new TunnelLink.Mux(tunnel.getInputStream(), tunnel.getOutputStream(), headUnitAddress);
                TunnelLink.Link fe35Link = mux.open(TunnelProtocol.CH_FE35);
                TunnelLink.Link fe36Link = mux.open(TunnelProtocol.CH_FE36);
                mux.start("relay-tunnel-rx");

                BluetoothServerSocket business = adapter.listenUsingInsecureRfcommWithServiceRecord("HiCarLab Business", FE36);
                servers.add(business);
                int ch = resolveChannel(business);
                if (ch > 0) mux.sendControl("fe36_channel", String.valueOf(ch));
                log("中继 FE36 端口 " + (ch > 0 ? ch : "未取得") + " 已通过隧道告知车机。");

                BluetoothServerSocket control = adapter.listenUsingInsecureRfcommWithServiceRecord("NearbyServerSocket", FE35);
                servers.add(control);

                startAdvertising(token);
                log("中继已就绪：等待华为手机连接 FE35/FE36；字节将转发到车机隧道。");
                // Each acceptLoop blocks on server.accept(); they MUST run on separate
                // threads. Running them inline meant FE35 was never listened on, so the
                // phone's control-channel connection was refused and pairing stalled at
                // the PIN dialog with no video.
                Thread fe36Accept = new Thread(() -> acceptLoop(business, fe36Link, token, "FE36"), "relay-accept-fe36");
                Thread fe35Accept = new Thread(() -> acceptLoop(control, fe35Link, token, "FE35"), "relay-accept-fe35");
                workers.add(fe36Accept); workers.add(fe35Accept);
                fe36Accept.start(); fe35Accept.start();
                log("FE35 / FE36 监听线程已启动（" + fe35Accept.isAlive() + "/" + fe36Accept.isAlive() + "）。");
            } catch (Exception e) {
                log("中继启动失败：" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " / " + e.getMessage()));
                stop();
            }
        }, "relay-start");
        workers.add(boot);
        boot.start();
    }

    private int resolveChannel(BluetoothServerSocket server) {
        try {
            Object v = server.getClass().getMethod("getChannel").invoke(server);
            if (v instanceof Integer) return RfcommPort.valid((Integer) v);
        } catch (Exception ignored) { }
        try {
            java.lang.reflect.Field f = server.getClass().getDeclaredField("mSocket");
            f.setAccessible(true);
            Object s = f.get(server);
            try {
                java.lang.reflect.Method m = s.getClass().getDeclaredMethod("getPort");
                m.setAccessible(true);
                Object v = m.invoke(s);
                if (v instanceof Integer) return RfcommPort.valid((Integer) v);
            } catch (Exception inner) {
                try {
                    java.lang.reflect.Field p = s.getClass().getDeclaredField("mPort");
                    p.setAccessible(true);
                    return RfcommPort.valid(p.getInt(s));
                } catch (Exception ignored2) { }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private void acceptLoop(BluetoothServerSocket server, TunnelLink.Link link, int token, String label) {
        while (running && generation.get() == token) {
            try {
                log(label + " 等待华为手机连接...");
                BluetoothSocket phone = server.accept();
                if (!running || generation.get() != token) { phone.close(); return; }
                log(label + " 华为手机已连接，开始双向转发。");
                final OutputStream toCar = link.out();
                final InputStream fromCar = link.in();
                final InputStream fromPhone = phone.getInputStream();
                final OutputStream toPhone = phone.getOutputStream();
                Thread toCarThread = new Thread(() -> pump(fromPhone, toCar, label + "->车机"), "relay-" + label + "-up");
                workers.add(toCarThread);
                toCarThread.start();
                pump(fromCar, toPhone, label + "->手机");
                try { phone.close(); } catch (IOException ignored) { }
            } catch (IOException e) {
                if (running) log(label + " 监听结束：" + e.getClass().getSimpleName());
                return;
            }
        }
    }

    private void pump(InputStream in, OutputStream out, String name) {
        byte[] buf = new byte[4096];
        long total = 0, nextReport = 4096;
        try {
            int n;
            while (running && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n); out.flush();
                total += n;
                if (total >= nextReport) { log(name + " 已转发 " + total + " 字节"); nextReport = total + 4096; }
            }
        } catch (IOException e) {
            if (running) log(name + " 转发结束：" + e.getClass().getSimpleName() + "（累计 " + total + " 字节）");
        }
    }

    private void startAdvertising(int token) {
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) { log("本机无 BLE 广播器，手机无法发现中继。"); return; }
            String model = getSharedPreferences("identity", 0).getString("wire_model", "00048000");
            if (model == null || !model.matches("[0-9a-fA-F]{8}")) model = "00048000";
            byte[] payload = DiscoveryBeacon.encodeExtended(model, 1);
            AdvertiseData data = new AdvertiseData.Builder().addServiceData(new ParcelUuid(FDEE), payload).build();
            advCallback = new AdvertiseCallback() {
                @Override public void onStartSuccess(AdvertiseSettings s) {
                    if (generation.get() == token) log("FDEE 广播已启动（" + payload.length + " 字节）；等待华为手机发现。");
                }
                @Override public void onStartFailure(int e) {
                    if (generation.get() == token) log("FDEE 广播启动失败，错误码 " + e + "。");
                }
            };
            advertiser.startAdvertising(new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true).setTimeout(0).build(), data, advCallback);
        } catch (RuntimeException e) { log("广播异常：" + e.getClass().getSimpleName()); }
    }

    private void stop() {
        running = false;
        generation.incrementAndGet();
        if (advertiser != null && advCallback != null) try { advertiser.stopAdvertising(advCallback); } catch (RuntimeException ignored) { }
        advertiser = null; advCallback = null;
        if (mux != null) { mux.close(); mux = null; }
        for (BluetoothServerSocket s : servers) try { s.close(); } catch (IOException ignored) { }
        servers.clear();
        for (Thread t : workers) if (t != null && t.isAlive() && t != Thread.currentThread()) t.interrupt();
        workers.clear();
        startBtn.setEnabled(true); stopBtn.setEnabled(false);
        log("中继已停止。");
    }

    @Override protected void onDestroy() { stop(); super.onDestroy(); }
}
