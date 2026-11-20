package local.hicarlab;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Experimental receiver: discovery, CCMP and native HiChain. No network/media. */
public class MainActivity extends ThemeActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<BluetoothServerSocket> servers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BluetoothSocket> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();
    private TextView deviceText, logText;
    private final DiagnosticLog.Listener diagnosticListener = text -> { if (logText != null) logText.setText(text); };
    private ReceiverEngine engine() { return ((LabApplication)getApplication()).engine(); }
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advCallback;
    private boolean running = false;
    private String snapshot = "";
    private static final int EXPORT = 20, DISCOVER = 21;
    private static final UUID FDEE = UUID.fromString("0000fdee-0000-1000-8000-00805f9b34fb");
    private EditText modelInput;
    private String localId, localPin;
    private final java.util.concurrent.atomic.AtomicBoolean authBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable backgroundStop = () -> stopBaseline(true);
    private boolean nativeReady;
    private void initIdentity() {
        SharedPreferences pref = getSharedPreferences("identity", MODE_PRIVATE);
        localId = pref.getString("id", null); localPin = pref.getString("pin", null);
        java.security.SecureRandom random = new java.security.SecureRandom();
        if (localId == null || localPin == null) {
            byte[] seed = new byte[32]; random.nextBytes(seed); StringBuilder id = new StringBuilder();
            for (byte b : seed) id.append(String.format(Locale.ROOT, "%02X", b & 255));
            localId = id.toString(); localPin = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
            if (!pref.edit().putString("id", localId).putString("pin", localPin).commit()) throw new IllegalStateException("identity save");
        }
        try {
            File keys = getDir("huks", MODE_PRIVATE); android.system.Os.chmod(keys.getAbsolutePath(), 0700);
            nativeReady = NativeAuth.init(keys.getAbsolutePath(), localId) == 0;
        } catch (Exception | LinkageError e) { nativeReady = false; }
    }
    private boolean pendingFdee, pendingNetwork;
    private String pendingModel;
    private int sequence = 1;
    private static final UUID BASELINE = UUID.fromString("f97dd6c0-636a-4806-b65e-9bb413d81641");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        initIdentity();
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        TextView title = new TextView(this); title.setText("设置与诊断 · 0.12.0"); title.setTextSize(25); root.addView(title);
        TextView note = new TextView(this);
        note.setText("设置与诊断\n日常连接请使用首页。下方提供接收设置、权限检查和连接诊断。\n下方保留分阶段测试，进行实验前请先停止当前连接。请停车后操作。");
        note.setTextSize(16); root.addView(note);
        deviceText = new TextView(this); deviceText.setTextIsSelectable(true); deviceText.setTextSize(15); root.addView(deviceText);
        button(root, "1. 授权并检查设备", () -> {
            if (!permitted()) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, 1);
            else refresh();
        });
        button(root, "2. 基础蓝牙测试（30 秒）", () -> start(false));
        TextView modelLabel = new TextView(this);
        modelLabel.setText("发现型号（高级设置）：00048000。仅在兼容性调试时修改。");
        root.addView(modelLabel);
        modelInput = new EditText(this); modelInput.setSingleLine(true); modelInput.setText("00048000"); root.addView(modelInput);
        button(root, "设置本机蓝牙身份（重连实验）", this::configureWireIdentity);
        TextView pinText = new TextView(this); pinText.setText("本机实验连接码：" + localPin); pinText.setTextSize(23); root.addView(pinText);
        TextView nativeText = new TextView(this); nativeText.setText(nativeReady ? "HiChain 原生库：已加载（不代表手机认证成功）" : "HiChain 原生库：初始化失败，不能启动认证实验"); root.addView(nativeText);
        button(root, "3. 发现与认证实验（120 秒）", () -> {
            if (!ready()) return;
            pendingNetwork = false;
            if (running || engine().isRunning()) { log("实验仍在运行，不重复启动。需要重试时请先点停止测试。"); return; }
            if (!nativeReady) { log("认证后端不可用，未启动实验。"); return; }
            pendingModel = modelInput.getText().toString().trim().toUpperCase(Locale.ROOT);
            try { DiscoveryBeacon.encode(pendingModel, sequence); }
            catch (IllegalArgumentException e) { log("实验型号必须恰好为 8 位十六进制字符。"); return; }
            stop(false); pendingFdee = true; pendingNetwork=false; pendingVideo=false;
            requestDiscoverable();
        });
        button(root, "4. 仅 FE35 / FE36 监听（120 秒）", () -> {
            if (!ready()) return;
            stop(false); pendingFdee = false;
            requestDiscoverable();
        });
        button(root,"5. 临时热点与网络实验（120 秒）",()->prepareNetwork(false));
        button(root,"6. 持续接收（画面、触控、声音）",()->prepareNetwork(true));
        button(root,"7. 发现广播自检（FDEE，30 秒）",this::advertisingSelfCheck);
        button(root,"8. 热点能力自检（读取实际信道，30 秒）",this::hotspotSelfCheck);
        button(root,"9. 蓝牙能力自检（广播/扫描/经典）",this::bluetoothCapabilitySelfCheck);
        button(root,"10. BLE 扫描独立自检（干净状态，15 秒）",this::scanSelfCheck);
        Button relayHeadUnit = new Button(this);
        root.addView(relayHeadUnit);
        Runnable refreshRelay = () -> {
            boolean on = getSharedPreferences("relay", 0).getBoolean("head_unit", false);
            relayHeadUnit.setText(on ? "11. 中继隧道（车机端）：已开启（点此关闭）" : "11. 中继隧道（车机端）：已关闭（点此开启）");
        };
        refreshRelay.run();
        relayHeadUnit.setOnClickListener(v -> {
            boolean on = getSharedPreferences("relay", 0).getBoolean("head_unit", false);
            getSharedPreferences("relay", 0).edit().putBoolean("head_unit", !on).apply();
            refreshRelay.run();
            log(!on ? "已开启车机端中继隧道：下次连接将等待中继手机经蓝牙隧道转发 FE35/FE36，本机不直接广播。"
                    : "已关闭车机端中继隧道：恢复本机直接广播与 FE35/FE36 监听。");
        });
        button(root,"12. 进入中继转发（有广播的手机用）",()->startActivity(new Intent(this,RelayActivity.class)));
        Button qualityBtn = new Button(this);
        root.addView(qualityBtn);
        Runnable refreshQuality = () -> {
            int t = getSharedPreferences(VideoQuality.PREF, 0).getInt("tier", -1);
            qualityBtn.setText("13. 视频画质：" + (t < 0 ? "自动（按热点频段）" : VideoQuality.label(t)) + "（点此切换）");
        };
        refreshQuality.run();
        qualityBtn.setOnClickListener(v -> {
            int t = getSharedPreferences(VideoQuality.PREF, 0).getInt("tier", -1);
            // cycle: auto(-1) -> smooth(0) -> balanced(1) -> HD(2) -> auto(-1)
            int next = t < 0 ? VideoQuality.TIER_SMOOTH : (t >= VideoQuality.TIER_HD ? -1 : t + 1);
            if (next < 0) getSharedPreferences(VideoQuality.PREF, 0).edit().remove("tier").apply();
            else getSharedPreferences(VideoQuality.PREF, 0).edit().putInt("tier", next).apply();
            refreshQuality.run();
            log("视频画质已设为：" + (next < 0 ? "自动（2.4G=均衡 / 5G=高清）" : VideoQuality.label(next))
                    + "；下次连接生效。" + (next >= VideoQuality.TIER_BALANCED ? " 2.4G 热点下高清可能卡顿，卡则用流畅档。" : ""));
        });
        button(root, "停止测试", () -> { log("用户点击停止测试。"); stop(true); });
        button(root, "导出诊断报告", () -> {
            log("导出报告请求停止当前测试。"); stop(false);
            refresh();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("text/plain"); intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_TITLE, "HiCarLab-report.txt");
            try { startActivityForResult(intent, EXPORT); }
            catch (RuntimeException e) { log("文件选择器不可用，可长按下方文字复制或拍照。"); }
        });
        TextView tips = new TextView(this);
        tips.setText("协议移植参考 Aki894/CarLink（MIT），认证后端采用 OpenHarmony DeviceAuth/HUKS。连接码保存在应用私有目录，不写入报告；删除应用数据会更换身份。0.8.1免输码重连已由用户确认；可在本机填写蓝牙地址保存派生身份。第5项创建临时热点并在结束时释放；Android 8/9 使用系统本地热点接口，Android 11 以上同时申请 2.4G 与 5G 信道，由系统决定实际信道。不调用 GPS 或扫描附近网络。第6项为画面与触控实验；触控已实测；声音已实测；0.8.3连续播放及断线重连已实测；本版锁屏/后台恢复待验证。\n第7项只发送 FDEE 发现广播，用来确认“手机搜不到”是广播能力问题还是热点问题；第8项创建一次热点并报告系统实际采用的信道（2.4G 或 5G），不连接手机。\n报告仅记录设备参数和协议状态，不保存对端身份、原始认证包、PIN 或密钥。"); root.addView(tips);
        logText = new TextView(this); logText.setTextIsSelectable(true); logText.setTextSize(14); root.addView(logText);
        setContentView(scroll);
        refresh();
        log("实验状态：VIDEO_INPUT_AUDIO_EXPERIMENTAL");
        LocalIdentityProbe.run(getApplicationContext());
    }
    private boolean pendingVideo;
    private LocalHotspot selfCheck;
    private void configureWireIdentity(){
        if(running || engine().isRunning()) { log("请先停止实验，再设置本机蓝牙身份。"); return; }
        final String model=modelInput.getText().toString().trim().toUpperCase(Locale.ROOT);
        try { DiscoveryBeacon.encode(model,0); } catch(IllegalArgumentException e){ log("请先填写正确的实验型号。"); return; }
        EditText input=new EditText(this); input.setSingleLine(true);
        input.setHint("AA:BB:CC:DD:EE:FF");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this)
            .setTitle("填写接收端本机蓝牙地址")
            .setMessage("从本机系统状态信息查看蓝牙地址。请勿填写华为手机地址或 Wi-Fi 地址。仅保存派生身份，不保存地址。保存后需在华为删除一次本次实验 HiCar 记录，再输码配对；随后再测试免输码重连。原连接码与密钥目录保持。")
            .setView(input).setNegativeButton("取消",(d,w)->input.setText(""))
            .setNeutralButton("打开本机信息",(d,w)->{
                input.setText("");
                try{startActivity(new Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS));}
                catch(RuntimeException e){log("请手动打开系统设置中的关于手机/状态信息，查看蓝牙地址。");}
            })
            .setPositiveButton("保存派生身份",null).create();
        dialog.setOnShowListener(d->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(running || engine().isRunning()) { input.setError("请先停止实验"); return; }
            try{
                String wireId=WireIdentity.derive(model,input.getText().toString());
                if(!getSharedPreferences("identity",0).edit().putString("wire_model",model).putString("wire_id",wireId).commit()) {
                    input.setError("保存失败，请重试"); return;
                }
                input.setText(""); dialog.dismiss();
                log("已保存本机派生身份；未保存地址，未更换 HUKS 种子或连接码。请先重新配对一次，再验证重连。");
            }catch(IllegalArgumentException e){input.setError("请填写系统显示的本机蓝牙地址，不能使用屏蔽地址");}
        }));
        dialog.show();
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void prepareNetwork(boolean video){
            if (!ready()) return;
            if (running || engine().isRunning()) { if(video && engine().isRunning()){startActivity(new Intent(this,VideoActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));}else log("实验仍在运行；要更换模式请先点停止。"); return; }
            if (Build.VERSION.SDK_INT < 26) { log("本轮网络实验需要 Android 8.0 以上。"); return; }
            String permission = Build.VERSION.SDK_INT >= 33 ? "android.permission.NEARBY_WIFI_DEVICES" : Manifest.permission.ACCESS_FINE_LOCATION;
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{permission}, 7); log("请允许本轮 Wi-Fi 权限后再点第5项。"); return; }
            pendingModel = modelInput.getText().toString().trim().toUpperCase(Locale.ROOT);
            try { DiscoveryBeacon.encode(pendingModel, 0); } catch (IllegalArgumentException e) { log("实验型号格式不正确。"); return; }
            stop(false); pendingFdee = true; pendingNetwork = true; pendingVideo=video;
            requestDiscoverable();
    }
    /** Classic discoverability is best effort: it must never block BLE discovery. */
    private void requestDiscoverable(){
        try{
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120), DISCOVER);
        }catch(RuntimeException e){
            log("系统无法显示蓝牙可发现请求：" + e.getClass().getSimpleName() + "；不影响 BLE 发现广播。");
            if (pendingFdee) startFdee(); else start(true);
        }
    }
    /** Creates one temporary hotspot and reports the channel it really got. */
    private void hotspotSelfCheck(){
        if (running || engine().isRunning()) { log("请先停止当前实验，再运行热点自检。"); return; }
        if (Build.VERSION.SDK_INT < 26) { log("热点自检需要 Android 8.0 以上。"); return; }
        String permission = Build.VERSION.SDK_INT >= 33 ? "android.permission.NEARBY_WIFI_DEVICES" : Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{permission}, 7); log("请允许 Wi-Fi 权限后再运行热点自检。"); return; }
        if (selfCheck != null) { selfCheck.close(); selfCheck = null; }
        log("热点自检开始：创建一次临时本地热点并读取系统实际采用的信道，30 秒后自动释放。");
        selfCheck = new LocalHotspot(this, new P2pHotspot.Events(){
            public void log(String message){ MainActivity.this.log(message); }
            public void ready(NetworkResource.Offer offer){ log("热点自检结果：系统实际使用 " + offer.frequency + " MHz（" + NetworkResource.band(offer.frequency) + "）；名称、密码与地址不写日志。"); }
            public void failed(String reason){ log("热点自检失败：" + reason); }
        });
        selfCheck.start();
        handler.postDelayed(() -> { if (selfCheck != null) { selfCheck.close(); selfCheck = null; log("热点自检结束，临时热点已释放。"); } }, 30000);
    }
    /** Reports what Bluetooth roles this head unit actually exposes. */
    private void bluetoothCapabilitySelfCheck(){
        if(!ready())return;
        stop(false);
        log("=== 蓝牙能力自检开始（12 秒）===");
        log("BLE 系统特性："+getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
        if(adapter==null){log("结论：无蓝牙适配器。");return;}
        log("经典蓝牙多播/扫描："+(adapter.isEnabled()?"已开启":"未开启"));
        try{log("BLE 广播器（Peripheral 角色，发广播）："+(adapter.getBluetoothLeAdvertiser()!=null?"可用":"不可用 ← 手机无法发现本机"));}
        catch(RuntimeException e){log("BLE 广播器查询异常："+e.getClass().getSimpleName());}
        final BluetoothLeScanner scanner;
        try{scanner=adapter.getBluetoothLeScanner();}
        catch(RuntimeException e){log("BLE 扫描器查询异常："+e.getClass().getSimpleName());return;}
        if(scanner==null){log("BLE 扫描器（Central 角色，找手机）：不可用");log("结论：本机既不能广播也不能扫描，只能等手机已配对后直连；无法建立首次连接。");return;}
        log("BLE 扫描器（Central 角色，找手机）：可用，开始实际扫描 10 秒...");
        final int[] found={0};
        ScanCallback cb=new ScanCallback(){
            @Override public void onScanResult(int callbackType,ScanResult result){
                found[0]++;
                if(found[0]<=5){
                    ScanRecord rec=result.getScanRecord();
                    String data=rec==null||rec.getServiceData()==null?"":rec.getServiceData().toString();
                    boolean near=data.toUpperCase(Locale.ROOT).contains("FDEE");
                    log("扫到设备 "+found[0]+"：含 FDEE 服务数据="+near+"，RSSI="+result.getRssi()+"（不记录地址）。");
                }
            }
            @Override public void onScanFailed(int error){log("BLE 扫描失败，错误码 "+error);}
        };
        try{scanner.startScan(cb);}catch(RuntimeException e){log("BLE 扫描启动异常："+e.getClass().getSimpleName()+"（可能需要定位权限或位置开关）");return;}
        handler.postDelayed(()->{
            try{scanner.stopScan(cb);}catch(RuntimeException ignored){}
            log("BLE 扫描结束：共扫到 "+found[0]+" 个设备。");
            if(found[0]>0)log("结论：本机可扫描周围设备，具备反向发现手机的可能；可进一步开发车机主动连接手机。");
            else log("结论：本机扫描不到任何设备，检查位置开关/附近设备权限后再测。");
        },10000);
    }
    /**
     * Clean-state BLE scan self-check. Runs on a fresh BLE stack (after stopping any
     * advertising), verifies location permission + location switch first, and reports
     * whether the head unit can actually see nearby phones. Distinguishes a hard
     * hardware/driver failure from a temporary state left over from a failed advertise.
     */
    private void scanSelfCheck() {
        stop(false);
        if (!ready()) return;
        final String locPerm = Manifest.permission.ACCESS_FINE_LOCATION;
        final boolean hasLoc = checkSelfPermission(locPerm) == PackageManager.PERMISSION_GRANTED;
        log("=== BLE 扫描独立自检开始（干净状态）===");
        log("精确位置权限：" + (hasLoc ? "已授予" : "未授予（Android 8/9 上无此权限无法 BLE 扫描）"));
        if (!hasLoc) {
            log("正在请求精确位置权限，请在系统弹窗中允许，然后再次点第 10 项。");
            try { requestPermissions(new String[]{locPerm}, 9); } catch (RuntimeException e) { log("权限请求异常：" + e.getClass().getSimpleName()); }
            return;
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            boolean on = lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            log("系统位置开关：" + (on ? "已开启" : "未开启 ← 扫描会返回内部错误 3，请到系统设置打开位置后再测"));
        } catch (RuntimeException e) { log("位置开关查询异常：" + e.getClass().getSimpleName()); }
        final BluetoothLeScanner scanner;
        try { scanner = adapter.getBluetoothLeScanner(); }
        catch (RuntimeException e) { log("扫描器查询异常：" + e.getClass().getSimpleName()); return; }
        if (scanner == null) { log("结论：系统未提供 BLE 扫描器（Central 角色不可用）。"); return; }
        // Let the BLE stack settle after any just-failed advertising before scanning.
        log("等待 2 秒让蓝牙栈从上一次广播状态恢复，然后扫描 15 秒...");
        handler.postDelayed(() -> {
            final int[] found = {0}, fdee = {0};
            final boolean[] failed = {false};
            ScanCallback cb = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult r) {
                    found[0]++;
                    ScanRecord rec = r.getScanRecord();
                    if (rec != null && rec.getServiceData(new ParcelUuid(FDEE)) != null) fdee[0]++;
                }
                @Override public void onScanFailed(int error) { failed[0] = true; log("扫描失败，错误码 " + error + "（3=内部错误，多为位置开关关闭或 BLE 栈异常；4=硬件不支持）。"); }
            };
            try { scanner.startScan(cb); }
            catch (RuntimeException e) { log("扫描启动异常：" + e.getClass().getSimpleName()); return; }
            handler.postDelayed(() -> {
                try { scanner.stopScan(cb); } catch (RuntimeException ignored) {}
                log("扫描结束：共扫到 " + found[0] + " 个设备，其中携带 FDEE 服务数据 " + fdee[0] + " 个。");
                if (failed[0]) log("结论：扫描底层失败。先确认系统位置开关已打开；若仍失败，这台车机的 BLE 扫描不可用。");
                else if (found[0] == 0) log("结论：扫描正常但周围无广播设备——请把华为手机放到车机旁、打开手机 HiCar/蓝牙后再测。这是“扫描能力可用”的证明。");
                else log("结论：扫描能力可用（已扫到 " + found[0] + " 个设备）。具备反向发现手机的条件。");
            }, 15000);
        }, 2000);
    }
    /** Reports whether this device can advertise the FDEE beacon at all. */
    private void advertisingSelfCheck(){
        if (!ready()) return;
        stop(false);
        running = true;
        final int token = generation.incrementAndGet();
        log("发现广播自检开始：30 秒，仅发送 FDEE service data，不启动认证与热点。");
        String model = modelInput.getText().toString().trim().toUpperCase(Locale.ROOT);
        boolean extended = getSharedPreferences("identity",0).getBoolean("beacon_extended",true);
        final byte[] payload;
        try { payload = DiscoveryBeacon.select(model, 1, extended); }
        catch (IllegalArgumentException e) { log("实验型号格式不正确。"); stop(false); return; }
        log("自检广播布局：" + (extended?"13 字节兼容变体（含 11 E7）":"11 字节原始布局") + "。");
        log("系统报告 BLE 多路广播支持：" + adapter.isMultipleAdvertisementSupported() + "；BLE 系统特性：" + getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                log("结论：系统未提供 BLE 广播器。未配对手机无法发现本机；已配对手机仍可通过经典蓝牙直连。请先在手机上手动配对一次。");
            } else {
                final BluetoothLeAdvertiser target = advertiser;
                advCallback = new AdvertiseCallback() {
                    @Override public void onStartSuccess(AdvertiseSettings settings) { if (generation.get() == token) log("结论：FDEE 广播已启动，广播链路可用；手机仍搜不到时请检查手机端 HiCar 开关与距离。"); }
                    @Override public void onStartFailure(int error) { if (generation.get() == token) log("结论：FDEE 广播启动失败：" + advertiseError(error)); }
                };
                advertiser.startAdvertising(new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true).setTimeout(30000).build(),
                    new AdvertiseData.Builder().addServiceData(new ParcelUuid(FDEE), payload).build(), advCallback);
            }
        } catch (RuntimeException e) { log("FDEE 广播自检异常：" + e.getClass().getSimpleName()); }
        handler.postDelayed(() -> { if (generation.get() == token) { log("发现广播自检结束。"); stop(false); } }, 30000);
    }
    private void button(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this); button.setText(label);
        button.setOnClickListener(v -> action.run()); root.addView(button);
    }
    private boolean permitted() {
        return Build.VERSION.SDK_INT < 31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED);
    }
    private boolean ready() {
        if (!permitted()) { log("请先点击“授权并检查设备”，允许附近设备权限。"); return false; }
        if (adapter == null) { log("Android 未向应用提供蓝牙适配器；车机蓝牙可能由独立模块控制。"); return false; }
        try { if (!adapter.isEnabled()) { log("请先在车机设置中打开蓝牙，再返回测试。"); return false; } }
        catch (RuntimeException e) { log("无法访问蓝牙状态：" + e.getClass().getSimpleName()); return false; }
        return true;
    }
    private void refresh() {
        StringBuilder s = new StringBuilder();
        s.append("\n厂商：").append(Build.MANUFACTURER).append("\n型号：").append(Build.MODEL);
        s.append("\nAndroid：").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT);
        s.append("\n系统报告 ABI：").append(Arrays.toString(Build.SUPPORTED_ABIS));
        s.append("\n32 位 ABI：").append(Arrays.toString(Build.SUPPORTED_32_BIT_ABIS));
        s.append("\n64 位 ABI：").append(Arrays.toString(Build.SUPPORTED_64_BIT_ABIS));
        s.append("\n当前进程：").append(android.os.Process.is64Bit() ? "64 位" : "32 位");
        s.append("\nAndroid 蓝牙适配器：").append(adapter != null ? "存在" : "不可见");
        s.append("\nBLE 系统特性：").append(getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
        s.append("\n附近设备权限：").append(permitted() ? "已授权／无需运行时申请" : "未授权");
        if (adapter != null && permitted()) try {
            s.append("\n蓝牙已开启：").append(adapter.isEnabled());
            s.append("\n多路广播能力（系统报告）：").append(adapter.isMultipleAdvertisementSupported());
            s.append("\nBLE 广播器：").append(adapter.getBluetoothLeAdvertiser() != null ? "可获取" : "不可获取");
        } catch (RuntimeException e) { s.append("\n蓝牙查询异常：").append(e.getClass().getSimpleName()); }
        s.append("\nH.264 解码器：");
        try {
            boolean found = false;
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) if (type.equalsIgnoreCase("video/avc")) {
                    s.append("\n  ").append(codec.getName()); found = true;
                }
            }
            if (!found) s.append("未枚举到");
        } catch (RuntimeException e) { s.append("枚举失败：").append(e.getClass().getSimpleName()); }
        s.append("\n以上为系统报告，未验证视频解码与实际无线互通。\n");
        snapshot = s.toString(); deviceText.setText(snapshot);
    }
    private void log(String message) { DiagnosticLog.add(getApplicationContext(), message); }
    private void start(boolean experimental) {
        if (!ready()) return;
        stop(false);
        running = true;
        int token = generation.incrementAndGet();
        if (experimental) {
            log("开始 FE35/FE36 实验监听：120 秒；无 HiCar 广播、无认证响应。");
            listen("HiCarLab FE35 probe", UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb"), token, "FE35");
            listen("HiCarLab FE36 probe", UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb"), token, "FE36");
        } else {
            log("开始基础测试：30 秒；私有测试 UUID，不模拟 HiCar。");
            listen("HiCarLab baseline", BASELINE, token, "基础 RFCOMM");
            try {
                advertiser = adapter.getBluetoothLeAdvertiser();
                if (advertiser == null) log("BLE 广播无法启动：广播器不可用。");
                else {
                    final BluetoothLeAdvertiser thisAdvertiser = advertiser;
                    advCallback = new AdvertiseCallback() {
                        @Override public void onStartSuccess(AdvertiseSettings settings) {
                            if (generation.get() == token) log("BLE 广播启动成功（私有 UUID，未验证手机接收）。");
                            else try { thisAdvertiser.stopAdvertising(this); } catch (RuntimeException ignored) { }
                        }
                        @Override public void onStartFailure(int error) {
                            if (generation.get() == token) log("BLE 广播失败：" + advertiseError(error));
                        }
                    };
                    advertiser.startAdvertising(new AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setConnectable(false).setTimeout(30000).build(),
                        new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(BASELINE)).build(), advCallback);
                }
            } catch (RuntimeException e) { log("BLE 广播异常：" + e.getClass().getSimpleName()); }
        }
        handler.postDelayed(() -> {
            if (generation.get() == token) { log("测试时间结束。"); stop(true); }
        }, experimental ? 120000 : 30000);
    }
    private void startFdee() {
        if (!ready()) return;
        Intent service = new Intent(this, ReceiverService.class).putExtra("model", pendingModel).putExtra("network", pendingNetwork).putExtra("video",pendingVideo);
        try { startForegroundService(service); if(pendingVideo)startActivity(new Intent(this,VideoActivity.class)); }
        catch (RuntimeException e) { log("无法启动前台测试服务：" + e.getClass().getSimpleName()); }
    }
    private void listen(String name, UUID uuid, int token, String label) {
        new Thread(() -> {
            BluetoothServerSocket server = null;
            try {
                server = adapter.listenUsingRfcommWithServiceRecord(name, uuid);
                servers.add(server);
                if (generation.get() != token) return;
                log(label + "：RFCOMM/SDP 注册成功；不代表收到手机连接。");
                while (generation.get() == token) {
                    BluetoothSocket socket = server.accept();
                    clients.add(socket);
                    if (generation.get() != token) { socket.close(); clients.remove(socket); break; }
                    log(label + "：收到蓝牙连接（未验证对端身份，未完成 HiCar 认证）。");
                    // No handshake guesses, no outbound packets; only count incoming bytes.
                    final BluetoothSocket accepted = socket;
                    new Thread(() -> receive(accepted, token, label), "lab-rx").start();
                }
            } catch (IOException e) {
                if (generation.get() == token) log(label + "：监听异常关闭（I/O 错误）。");
            } catch (RuntimeException e) {
                if (generation.get() == token) log(label + "：接口失败 " + e.getClass().getSimpleName());
            } finally {
                if (server != null) { servers.remove(server); try { server.close(); } catch (IOException ignored) { } }
            }
        }, "lab-listen").start();
    }
    private void receive(BluetoothSocket socket, int token, String label) {
        int total = 0;
        handler.postDelayed(() -> { try { socket.close(); } catch (IOException ignored) { } }, 10000);
        try {
            byte[] buffer = new byte[1024];
            InputStream input = socket.getInputStream();
            while (generation.get() == token && total < 4096) {
                int n = input.read(buffer, 0, Math.min(buffer.length, 4096 - total));
                if (n < 0) break;
                total += n;
            }
        } catch (IOException ignored) {
        } finally {
            clients.remove(socket); try { socket.close(); } catch (IOException ignored) { }
            if (generation.get() == token) log(label + "：接收 " + total + " 字节；内容未保存，未发送响应。");
        }
    }
    private static String advertiseError(int value) {
        switch (value) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE: return "广播数据过长 (1)";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: return "广播资源不足 (2)";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED: return "已经启动 (3)";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR: return "系统内部错误 (4)";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED: return "功能不支持 (5)";
            default: return "错误码 " + value;
        }
    }
    private void stop(boolean announce) { engine().stop(announce); stopBaseline(announce); }
    private void stopBaseline(boolean announce) {
        boolean wasRunning = running;
        running = false; generation.incrementAndGet();
        if (advertiser != null && advCallback != null) try { advertiser.stopAdvertising(advCallback); } catch (RuntimeException ignored) { }
        advertiser = null; advCallback = null;
        for (BluetoothServerSocket server : servers) try { server.close(); } catch (IOException ignored) { }
        servers.clear();
        for (BluetoothSocket socket : clients) try { socket.close(); } catch (IOException ignored) { }
        clients.clear();
        if (announce && wasRunning) log("测试已停止，实验监听与 BLE 广播已关闭。系统蓝牙可发现状态按系统倒计时结束。");
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results); refresh();
        if(code==9) log(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ? "精确位置权限已授予，请确认系统位置开关已打开，再点第 10 项扫描自检。" : "精确位置权限被拒绝，BLE 扫描无法进行。");
        else if(code==8) log(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ? "扫描权限已授予，请打开系统位置开关，再点第5项；本轮尚未开始。" : "扫描需要精确位置权限，本轮尚未开始。");
        else if(code==7) log("Wi-Fi 权限请求已返回，请再点第5项检查。");
        else log(permitted() ? "蓝牙权限已就绪，请继续测试。" : "蓝牙权限未全部授予。");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == DISCOVER) {
            log(result > 0 ? "系统已允许蓝牙可发现 " + result + " 秒。" : "系统未允许蓝牙可发现；BLE 发现广播与 RFCOMM 监听不受影响，继续启动。");
            if (pendingFdee) startFdee(); else start(true);
        }
        if (request == EXPORT && result == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new IOException("No output stream");
                String report = "HiCarLab 0.12.0\nAuthentication/network: EXPERIMENTAL; video/input/audio: EXPERIMENTAL\n" + snapshot + "\n事件日志\n" + DiagnosticLog.history(getApplicationContext());
                out.write(report.getBytes(StandardCharsets.UTF_8)); log("报告已导出。");
            } catch (IOException | RuntimeException e) { log("导出失败：" + e.getClass().getSimpleName()); }
        }
    }
    @Override protected void onStart() { super.onStart(); handler.removeCallbacks(backgroundStop); DiagnosticLog.attach(getApplicationContext(), diagnosticListener); }
    @Override protected void onStop() { DiagnosticLog.detach(diagnosticListener); if (!engine().isRunning()) handler.postDelayed(backgroundStop, 30000); super.onStop(); }
    @Override protected void onDestroy() { DiagnosticLog.detach(diagnosticListener); stopBaseline(false); if(selfCheck!=null){selfCheck.close();selfCheck=null;} handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
