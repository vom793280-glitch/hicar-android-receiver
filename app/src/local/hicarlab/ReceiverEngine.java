package local.hicarlab;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

final class ReceiverEngine {
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<BluetoothServerSocket> servers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TunnelLink.WireLink> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean authBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final CopyOnWriteArrayList<TestSession> sessions=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MicrophoneSender> microphones=new CopyOnWriteArrayList<>();
    private boolean microphoneAllowed;
    String microphoneStatus(){if(!MicrophoneSender.enabled(context))return "麦克风已关闭";for(MicrophoneSender m:microphones)return m.status();return isActive()?"麦克风尚未就绪，重新连接后启用":"麦克风已启用，等待连接";}
    void disableMicrophone(){microphoneAllowed=false;for(MicrophoneSender m:microphones)m.stop();}
    private final CopyOnWriteArrayList<AudioReceiver> audioReceivers=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<HiSightClient> videoClients=new CopyOnWriteArrayList<>();
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advCallback;
    private volatile boolean running;
    private final ReceiverStatus status=new ReceiverStatus();
    private final ReconnectGate reconnect = new ReconnectGate();
    private volatile boolean continuous,extendedBeacon;
    private P2pHotspot hotspot;
    private LocalHotspot localHotspot;
    private P2pHotspot.Events networkEvents;
    private volatile String networkProblem;
    private boolean p2pFallback,p2pPrimary;
    private volatile NetworkResource networkResource;
    private final Object networkLock = new Object();
    private final CopyOnWriteArrayList<java.net.ServerSocket> tcpServers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<java.net.Socket> tcpClients = new CopyOnWriteArrayList<>();
    private boolean nativeReady;
    private String localId,localPin,pendingModel;
    private int sequence;
    private Runnable onStopped = () -> {};
    private static final UUID FDEE = UUID.fromString("0000fdee-0000-1000-8000-00805f9b34fb");
    ReceiverEngine(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager manager = (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        initIdentity();
    }
    boolean isRunning() { return running; }
    boolean isActive(){return running || continuous;}
    ReceiverStatus.Snapshot displayStatus(){
        boolean decoded=false;
        if(running)for(TestSession session:sessions)if(session.mediaBusy.get()&&session.controlBusy.get()&&((LabApplication)context).video().hasDecoded()){decoded=true;break;}
        return status.snapshot(decoded);
    }
    String connectionStatus(){return displayStatus().title;}
    void reportProblem(String hint){if(!isActive())status.failed(generation.get(),hint);}
    private void fail(String hint){stop(true);status.failed(generation.get(),hint);}
    private void fail(int token,String hint){handler.post(()->{if(generation.get()==token)fail(hint);});}
    void setOnStopped(Runnable onStopped) { this.onStopped = onStopped; }
    private void log(String text) { DiagnosticLog.add(context, text); }
    private static final class TestSession {
        final String model; final boolean video;
        final java.util.concurrent.atomic.AtomicBoolean mediaBusy=new java.util.concurrent.atomic.AtomicBoolean();
        TestSession(String model,boolean video){this.model=model;this.video=video;}
        volatile boolean authenticated;
        volatile NetworkResource network;
        private final Object networkGate=new Object();
        private String peer;
        private byte[] key;
        final java.util.concurrent.atomic.AtomicBoolean controlBusy=new java.util.concurrent.atomic.AtomicBoolean();
        void setNetwork(NetworkResource value){synchronized(networkGate){network=value;networkGate.notifyAll();}}
        /** Waits for the hotspot prepared in parallel; the phone may ask before it is ready. */
        NetworkResource awaitNetwork(long millis){
            synchronized(networkGate){
                long deadline=System.currentTimeMillis()+millis;
                while(network==null){
                    long left=deadline-System.currentTimeMillis();
                    if(left<=0)return null;
                    try{networkGate.wait(left);}catch(InterruptedException e){Thread.currentThread().interrupt();return null;}
                }
                return network;
            }
        }
        synchronized void setKey(byte[] k){if(key!=null)Arrays.fill(key,(byte)0);key=k.clone();}
        synchronized byte[] copyKey(){return key==null?null:key.clone();}
        synchronized void clearKey(){if(key!=null)Arrays.fill(key,(byte)0);key=null;}
        synchronized boolean select(TunnelLink.WireLink link) {
            String address = link.peer();
            if (address == null || address.isEmpty()) return true;
            if (peer == null) peer = address;
            return peer.equals(address);
        }
    }
    private void initIdentity() {
        SharedPreferences pref = context.getSharedPreferences("identity", 0);
        localId = pref.getString("id", null); localPin = pref.getString("pin", null);
        java.security.SecureRandom random = new java.security.SecureRandom();
        if (localId == null || localPin == null) {
            byte[] seed = new byte[32]; random.nextBytes(seed); StringBuilder id = new StringBuilder();
            for (byte b : seed) id.append(String.format(Locale.ROOT, "%02X", b & 255));
            localId = id.toString(); localPin = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
            if (!pref.edit().putString("id", localId).putString("pin", localPin).commit()) throw new IllegalStateException("identity save");
        }
        try {
            File keys = context.getDir("huks", 0); android.system.Os.chmod(keys.getAbsolutePath(), 0700);
            nativeReady = NativeAuth.init(keys.getAbsolutePath(), localId) == 0;
            if (!nativeReady) log("HiChain 原生库已加载但初始化返回失败；认证无法进行。");
        } catch (Exception | LinkageError e) {
            nativeReady = false;
            String detail = e.getMessage() == null ? "" : "（" + e.getMessage() + "）";
            log("HiChain 原生库加载失败：" + e.getClass().getSimpleName() + detail
                    + "；当前进程 " + (android.os.Process.is64Bit() ? "64 位" : "32 位")
                    + "，系统 ABI " + Arrays.toString(Build.SUPPORTED_ABIS) + "。");
        }
    }
    void start(String model, boolean withNetwork) {start(model,withNetwork,false);}
    void start(String model,boolean withNetwork,boolean withVideo){start(model,withNetwork,withVideo,false);}
    void start(String model,boolean withNetwork,boolean withVideo,boolean withMicrophone){start(model,withNetwork,withVideo,withMicrophone,true);}
    private void start(String model, boolean withNetwork, boolean withVideo, boolean withMicrophone, boolean manual) {
        if (running) { log("认证测试仍在运行，不重复启动。"); return; }
        if(android.os.Build.VERSION.SDK_INT>=31 && (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)){fail("蓝牙权限不足，请在系统设置中允许附近设备权限。");return;}
        if(!nativeReady){fail("连接组件未就绪：HiChain 原生库加载失败（常见于 ABI 不匹配）。请查看诊断页日志。");return;}
        if(adapter==null){fail("这台设备没有可用的蓝牙适配器。");return;}
        try{if(!adapter.isEnabled()){fail("蓝牙已关闭，请打开蓝牙后重新连接。");return;}}catch(SecurityException e){fail("蓝牙权限不足，请在系统设置中允许附近设备权限。");return;}
        pendingModel = model;
        SharedPreferences identity = context.getSharedPreferences("identity", 0);
        // The HUKS backend was registered once with the original seed in initIdentity().
        // Using a derived wire identity here changes the self_auth_id that is passed to
        // NativeAuth.create while the key store still belongs to the seed: HiChain then
        // rejects the PIN and the phone prompts for the code again and again. Keep the
        // auth identity equal to the seed; the derived identity remains opt-in only for
        // explicitly marked reconnect experiments (it is not used for authentication).
        localId = identity.getString("id", null);
        log("本轮认证身份与 HUKS 种子一致；蓝牙地址派生身份 experiment 不参与认证，避免反复弹连接码。");
        stopInternal(false,false,false); running = true;
        continuous = withVideo;microphoneAllowed=withVideo&&withMicrophone;
        if(manual)reconnect.reset();
        networkProblem = null; p2pFallback = false; p2pPrimary = false; networkEvents = null;
        sequence = context.getSharedPreferences("identity", 0).getInt("discovery_sequence", 0) & 255;
        context.getSharedPreferences("identity", 0).edit().putInt("discovery_sequence", (sequence + 1) & 255).apply();
        int token = generation.incrementAndGet();
        status.begin(token);
        log("========== 新一轮会话开始 token=" + token + (manual?"（手动）":"（自动重连）") + " ==========");
        extendedBeacon = context.getSharedPreferences("identity",0).getBoolean("beacon_extended",true);
        final byte[] payload = DiscoveryBeacon.select(pendingModel, sequence, extendedBeacon);
        log("FDEE 实验开始：型号 " + pendingModel + "，序号 " + sequence
                + "，" + (extendedBeacon ? "13 字节兼容变体（含 11 E7 能力字段）" : "11 字节原始布局")
                + "；未验证手机识别。");
        sequence = (sequence + 1) & 255;
        // Discovery is started first and never waits for the hotspot. A device that cannot
        // create an AP (Android 8, 2.4 GHz only, vendor restriction) must still be findable,
        // otherwise the phone shows nothing and the real reason stays invisible.
        // In relay head-unit mode this device must not advertise (it has no BLE advertising,
        // and the relay phone is doing it). Keep the session in WAITING without a beacon.
        startProtocol(token, pendingModel, withVideo,
                () -> { if (!relayHeadUnitMode()) startFdeeAdvertising(token, payload); });
        if (withNetwork) startNetwork(token);
        if (!continuous) handler.postDelayed(() -> { if (generation.get() == token) { log("FDEE 实验时间结束。"); stop(true); } }, 120000);
        else log("持续接收已启动：不再120秒自动停止；断线后重新等待连接，可随时手动停止。");
    }
    /**
     * Prepares the hotspot in parallel with discovery. Any failure here is reported but
     * no longer cancels the session: the phone can still find, pair and authenticate, and
     * the reason is written to the diagnostic log and the status line.
     */
    private void startNetwork(final int token) {
        networkEvents = new P2pHotspot.Events() {
            public void log(String message) { ReceiverEngine.this.log(message); }
            public void ready(NetworkResource.Offer offer) { if (generation.get() == token) attachNetwork(token, offer); }
            public void failed(String reason) {
                if (generation.get() != token) return;
                ReceiverEngine.this.log(reason);
                // Only retry P2P when LocalOnlyHotspot was the primary path. On API 29-32
                // P2P is already primary, so a second attempt there would be pointless.
                if (Build.VERSION.SDK_INT >= 29 && !p2pPrimary && !p2pFallback) {
                    p2pFallback = true;
                    ReceiverEngine.this.log("本地热点不可用，改用 Wi-Fi Direct 临时热点重试。");
                    closeHotspots();
                    hotspot = new P2pHotspot(context, networkEvents); hotspot.start();
                    return;
                }
                networkProblem = reason;
                status.issue(token, "无线网络未就绪：" + reason + "。手机仍可发现本机，但暂时无法投屏。");
            }
        };
        try {
            // API 29-32: startLocalOnlyHotspot(config, executor, callback) is public only
            // from API 30, and Android 10 ignores the requested 5 GHz channel in practice.
            // Wi-Fi Direct group ownership is the path that produced a 5745 MHz AP and real
            // CCMP exchanges in the field reports. Keep it as primary on these versions.
            // LocalOnlyHotspot is primary from API 30: it exposes SSID/passphrase/BSSID
            // via configuration readback. Wi-Fi Direct is the fallback (it can pin 5 GHz
            // but hides the BSSID on some Android 16 builds). API 29 uses P2P because
            // its LocalOnlyHotspot has no configuration overload; 8/9 use legacy LOHS.
            if (Build.VERSION.SDK_INT >= 30) {
                localHotspot = new LocalHotspot(context, networkEvents); localHotspot.start();
            } else if (Build.VERSION.SDK_INT == 29) {
                p2pPrimary = true;
                hotspot = new P2pHotspot(context, networkEvents); hotspot.start();
            } else {
                localHotspot = new LocalHotspot(context, networkEvents); localHotspot.start();
            }
        } catch (RuntimeException e) {
            log("本地热点初始化异常：" + e.getClass().getSimpleName());
            networkEvents.failed("本地热点初始化异常：" + e.getClass().getSimpleName());
        }
    }
    private void closeHotspots() {
        if (hotspot != null) { hotspot.close(); hotspot = null; }
        if (localHotspot != null) { localHotspot.close(); localHotspot = null; }
    }
    private void attachNetwork(int token, NetworkResource.Offer offer) {
        final NetworkResource resource;
        try { resource = new NetworkResource(offer); }
        catch (IllegalArgumentException e) { log("热点参数未通过校验，未用于连接：" + e.getMessage()); status.issue(token, "热点参数未通过校验，暂时无法投屏。"); return; }
        synchronized (networkLock) {
            networkResource = resource;
            log("本轮热点已就绪：" + offer.frequency + " MHz（" + NetworkResource.band(offer.frequency) + "），开始等待手机入网。");
            for (TestSession session : sessions) attachSessionNetwork(session, token);
        }
    }
    private void attachSessionNetwork(TestSession session, int token) {
        synchronized (networkLock) {
            if (session.network != null) return;
            NetworkResource resource = networkResource;
            if (resource == null) return;
            session.setNetwork(resource);
            if (session.authenticated) resource.authenticated = true;
            try { startTcpListeners(session, token, session.model); }
            catch (IOException e) { log("TCP 监听启动失败：" + e.getClass().getSimpleName() + "；本轮投屏不可用。"); }
        }
    }
    private void startFdeeAdvertising(int token, byte[] payload) {
        if (generation.get() != token) return;
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                log("BLE 广播器不可用：系统未向应用提供 BluetoothLeAdvertiser。");
                // A head unit may have no BLE advertising at all. Do not tear down the
                // session: a phone with an existing pairing record can still discover us
                // over classic Bluetooth SDP and connect FE35/FE36 directly.
                status.advance(token, ReceiverStatus.Stage.WAITING);
                status.issue(token, "本机不支持 BLE 发现广播；已配对手机仍可直接连接，未配对手机需先手动配对。");
                return;
            }
            AdvertiseData data = new AdvertiseData.Builder().addServiceData(new ParcelUuid(FDEE), payload).build();
            advertiseWith(advertiser, token, data, true);
        } catch (RuntimeException e) {
            log("FDEE 广播异常：" + e.getClass().getSimpleName());
            status.advance(token, ReceiverStatus.Stage.WAITING);
            status.issue(token, "蓝牙发现广播不可用；已配对手机仍可直接连接。");
        }
    }
    /**
     * Some Android 8/9 stacks reject connectable advertising with service data. The
     * non-connectable retry keeps discovery alive; HiCar pairing itself uses RFCOMM.
     */
    private void advertiseWith(final BluetoothLeAdvertiser target, final int token, final AdvertiseData data, final boolean connectable) {
        advCallback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settings) {
                if (generation.get() == token) {
                    log("FDEE 广播启动成功：" + (extendedBeacon ? "13 字节兼容变体（含 11 E7）" : "11 字节原始布局")
                            + (connectable ? "（可连接）" : "（不可连接回退）") + "；这不是 HiCar 连接成功。");
                    status.advance(token,ReceiverStatus.Stage.WAITING);
                } else try { target.stopAdvertising(this); } catch (RuntimeException ignored) { }
            }
            @Override public void onStartFailure(int error) {
                if (generation.get() != token) return;
                log("FDEE 广播失败：" + advertiseError(error) + (connectable ? "（可连接模式）" : "（不可连接模式）"));
                if (connectable) {
                    log("改用不可连接广播重试一次。");
                    try { target.stopAdvertising(this); } catch (RuntimeException ignored) { }
                    advertiseWith(target, token, data, false);
                    return;
                }
                log("FDEE 广播两种模式均失败；保留经典蓝牙监听，等待已配对手机直连。");
                status.advance(token, ReceiverStatus.Stage.WAITING);
                status.issue(token, "BLE 发现广播不可用；已配对手机仍可直接连接本机。");
            }
        };
        target.startAdvertising(new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(connectable).setTimeout(0).build(), data, advCallback);
    }
    /** True when this device is the head unit behind a relay phone (route B tunnel). */
    private boolean relayHeadUnitMode() {
        return context.getSharedPreferences("relay", 0).getBoolean("head_unit", false);
    }

    /**
     * Tunnel server for route B. The head unit has no BLE advertising, so a relay phone
     * (which does advertise) connects here over a pre-paired classic-Bluetooth RFCOMM link
     * and forwards the phone's FE35/FE36 bytes. This method accepts that tunnel, splits it
     * into two logical links, and feeds both into the same protocol stack used locally.
     * Authentication keys and all media remain on the head unit; the relay only moves the
     * FE35/FE36 control + auth bytes.
     */
    private void startTunnelProtocol(int token, String model, boolean withVideo, Runnable onReady) {
        new Thread(() -> {
            BluetoothServerSocket server = null;
            try {
                server = adapter.listenUsingInsecureRfcommWithServiceRecord(TunnelLink.TUNNEL_NAME, TunnelLink.TUNNEL_UUID);
                servers.add(server);
                if (generation.get() != token) { server.close(); return; }
                log("隧道服务端已就绪（等待中继手机连接）；本机不直接广播，FE35/FE36 字节将由中继转发。");
                status.advance(token, ReceiverStatus.Stage.WAITING);
                handler.post(() -> { if (generation.get() == token) onReady.run(); });
                BluetoothSocket socket = server.accept();
                if (generation.get() != token || socket == null) { if (socket != null) socket.close(); return; }
                final TestSession session = new TestSession(model, withVideo);
                sessions.add(session);
                attachSessionNetwork(session, token);
                String peer;
                try { peer = socket.getRemoteDevice().getAddress(); } catch (RuntimeException e) { peer = "relay"; }
                TunnelLink.Mux mux = new TunnelLink.Mux(socket.getInputStream(), socket.getOutputStream(), peer);
                clients.add(mux);
                mux.start("lab-tunnel-rx");
                // The relay tells us the RFCOMM channel it registered for FE36 on the phone
                // side; Negotiator must echo that number, not our local (unused) channel.
                String relayChannel = mux.awaitControl("fe36_channel", 8000);
                int channel = -1;
                if (relayChannel != null) try { channel = Integer.parseInt(relayChannel.trim()); } catch (NumberFormatException ignored) { }
                final int fe36Channel = channel;
                if (fe36Channel < 1 || fe36Channel > 30)
                    log("中继未提供可核验的 FE36 端口；业务通道协商可能被手机拒绝。");
                else log("已从中继取得 FE36 端口 " + fe36Channel + "，将用于业务通道协商。");
                TunnelLink.Link fe35 = mux.open(TunnelProtocol.CH_FE35);
                TunnelLink.Link fe36 = mux.open(TunnelProtocol.CH_FE36);
                clients.add(fe35); clients.add(fe36);
                new Thread(() -> protocolReceive(fe36, token, model, fe36Channel, false, session), "lab-tunnel-fe36").start();
                protocolReceive(fe35, token, model, fe36Channel, true, session);
            } catch (Exception e) {
                if (generation.get() == token) { log("隧道服务端结束：" + e.getClass().getSimpleName()); requestReconnect(token, model); }
            } finally {
                if (server != null) { servers.remove(server); try { server.close(); } catch (IOException ignored) { } }
            }
        }, "lab-tunnel-server").start();
    }

    private void startProtocol(int token, String model,boolean withVideo, Runnable onReady) {
        if (relayHeadUnitMode()) { startTunnelProtocol(token, model, withVideo, onReady); return; }
        new Thread(() -> {
            try {
                BluetoothServerSocket business = adapter.listenUsingInsecureRfcommWithServiceRecord("HiCarLab Business", UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb"));
                servers.add(business);
                int resolved = -1;
                try {
                    Object value = business.getClass().getMethod("getChannel").invoke(business);
                    if (value instanceof Integer) resolved = RfcommPort.valid((Integer)value);
                    if (resolved > 0) log("RFCOMM 端口获取：getChannel。");
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
                if (resolved < 1) {
                    try {
                        java.lang.reflect.Field transport = business.getClass().getDeclaredField("mSocket");
                        transport.setAccessible(true);
                        Object socket = transport.get(business);
                        try {
                            java.lang.reflect.Method port = socket.getClass().getDeclaredMethod("getPort");
                            port.setAccessible(true);
                            Object value = port.invoke(socket);
                            if (value instanceof Integer) resolved = RfcommPort.valid((Integer)value);
                        } catch (ReflectiveOperationException | RuntimeException methodUnavailable) {
                            java.lang.reflect.Field port = socket.getClass().getDeclaredField("mPort");
                            port.setAccessible(true);
                            resolved = RfcommPort.valid(port.getInt(socket));
                        }
                        if (resolved > 0) log("RFCOMM 端口获取：已绑定的底层 socket；外层缓存未更新。");
                    } catch (ReflectiveOperationException | RuntimeException e) { log("底层端口读取未获支持：" + e.getClass().getSimpleName()); }
                }
                if (resolved < 1) {
                    resolved = RfcommPort.fromDescription(business.toString());
                    if (resolved > 0) log("RFCOMM 端口获取：系统 socket 描述中的 Channel 字段（待真实连接验证）。");
                }
                final int channel = resolved;
                if (channel < 1) {
                    // Discovery still starts: without it the phone shows nothing and the
                    // real cause (no verifiable RFCOMM channel) stays invisible.
                    log("系统未提供可核验的 RFCOMM 端口；未猜测或固定端口。发现广播继续，业务通道协商会失败。");
                    status.issue(token, "这台设备未提供可核验的蓝牙业务通道，连接可能停在验证阶段；请查看诊断页日志。");
                }
                BluetoothServerSocket control = adapter.listenUsingInsecureRfcommWithServiceRecord("NearbyServerSocket", UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb"));
                servers.add(control);
                if (generation.get() != token) { business.close(); control.close(); return; }
                log("FE35 / FE36 已注册；动态业务端口 " + (channel < 1 ? "未取得" : channel) + "，等待手机连接。");
                final TestSession session = new TestSession(model,withVideo); sessions.add(session);
                attachSessionNetwork(session, token);
                new Thread(() -> acceptProtocol(business, token, model, channel, false, session), "lab-business").start();
                handler.post(() -> { if (generation.get() == token) onReady.run(); });
                acceptProtocol(control, token, model, channel, true, session);
            } catch (Exception e) { if (generation.get() == token) { log("协议监听启动失败：" + e.getClass().getSimpleName()); fail(token,"蓝牙连接服务启动失败，请重新连接。"); } }
        }, "lab-protocol").start();
    }
    private void acceptProtocol(BluetoothServerSocket server, int token, String model, int channel, boolean control, TestSession session) {
        try {
            while (generation.get() == token) {
                BluetoothSocket socket = server.accept();
                admitLink(new TunnelLink.BtLink(socket), token, model, channel, control, session);
            }
        } catch (IOException | RuntimeException e) { if (generation.get() == token) { log("协议监听结束：" + e.getClass().getSimpleName()); requestReconnect(token,model); } }
    }
    /** Common admission path for a local Bluetooth link and for a relayed tunnel link. */
    private void admitLink(TunnelLink.WireLink link, int token, String model, int channel, boolean control, TestSession session) {
        try {
            if (clients.size() >= 6) { link.close(); return; }
            clients.add(link);
            if (generation.get() != token) { link.close(); clients.remove(link); return; }
            if (!session.select(link)) { log("拒绝其他设备加入当前实验会话；未记录地址。"); link.close(); clients.remove(link); return; }
            if (!control && !authBusy.compareAndSet(false, true)) { log("已有认证会话正在处理或清理，暂未接收新的业务连接。"); link.close(); clients.remove(link); return; }
            if(!control)status.advance(token,ReceiverStatus.Stage.VERIFYING);
            log((control ? "FE35" : "FE36") + " 收到连接；对端尚未认证。");
            if (networkProblem != null) log("注意：本轮无线网络未就绪（" + networkProblem + "）；手机可以完成认证，但无法投屏。");
            new Thread(() -> protocolReceive(link, token, model, channel, control, session), "lab-protocol-rx").start();
        } catch (IOException e) { try { link.close(); } catch (IOException ignored) { } clients.remove(link); }
    }
    private void protocolReceive(TunnelLink.WireLink link, int token, String model, int channel, boolean control, TestSession session) {
        AuthBusiness auth = null;
        try {
            byte[] buffer = new byte[4096]; InputStream in = link.in(); OutputStream out = link.out();
            NearbyWire.Decoder decoder = new NearbyWire.Decoder();
            NearbyWire.Negotiator negotiation = new NearbyWire.Negotiator(localId.getBytes(StandardCharsets.US_ASCII), channel);
            if (!control) auth = new AuthBusiness(localId, localPin, model, new AuthBusiness.Events() {
                @Override public void log(String message) { if (generation.get() == token) ReceiverEngine.this.log(message); }
                @Override public void authenticated(byte[] key) { synchronized(session){if (generation.get() == token) { session.setKey(key); session.authenticated = true; status.advance(token,ReceiverStatus.Stage.NETWORK); if (session.network != null) session.network.authenticated = true; }} }
                @Override public void identityExchanged() {
                    // The phone is now connected on FE36 and is showing the PIN dialog.
                    // Continued advertising makes it discover us again and stack another
                    // prompt before the user can type the six digits.
                    handler.post(()->{
                        if(generation.get()!=token)return;
                        if(advertiser!=null&&advCallback!=null)try{advertiser.stopAdvertising(advCallback);}catch(RuntimeException ignored){}
                        advertiser=null;advCallback=null;
                        log("手机已进入连接码输入；本轮停止 FDEE 广播，避免重复弹窗。");
                    });
                }
            });
            while (generation.get() == token) {
                int n = in.read(buffer); if (n < 0) break;
                if (control) {
                    for (NearbyWire.Frame frame : decoder.feed(buffer, n)) {
                        byte[] reply;
                        if (frame.kind == 4 || frame.kind == 33) {
                            if (!session.authenticated) throw new IOException("network before authentication");
                            NetworkResource resource = session.network;
                            if (resource == null) {
                                log("手机请求网络参数，但本轮热点尚未就绪；最多等待 10 秒。");
                                resource = session.awaitNetwork(10000);
                            }
                            if (resource != null) { replyNetwork(frame, resource, out); reply = null; }
                            else {
                                inspectNetworkRequest(frame);
                                if (frame.kind == 4) { reply = NetworkResource.rejectNetwork(); log("本轮热点仍未就绪，已回复网络协商失败；原因见上方日志。"); }
                                else reply = null;
                            }
                        } else reply = negotiation.respond(frame);
                        if (reply != null) { out.write(reply); out.flush(); }
                        if (generation.get() == token) log("FE35 控制帧：类型 " + frame.kind + "，载荷 " + frame.payload.length + " 字节，响应 " + (reply == null ? 0 : reply.length) + " 字节。");
                    }
                } else {
                    if (!auth.done()) auth.feed(buffer, n, out);
                    else if (generation.get() == token) log("FE36 认证后收到 " + n + " 字节；未记录内容，等待后续实现。");
                }
            }
        } catch (Exception e) { if (generation.get() == token) log((control ? "FE35 协商" : "FE36 认证") + " 结束：" + e.getClass().getSimpleName() + "；未确认成功时请勿视为已连接。"); }
        finally {
            if (auth != null) auth.close(); if (!control) authBusy.set(false);
            if(!control && !session.authenticated && generation.get()==token){
                log("FE36 本轮认证未成功：本机身份与 HUKS 种子一致，连接码为页面显示的六位值；"
                        + "若手机持续弹码，请先在手机 HiCar 设置删除本设备的旧配对记录，再重新输入连接码。");
                status.issue(token,"连接验证未完成：请在手机删除旧 HiCar 记录后重新输入连接码，或点重新连接。");
            }
            clients.remove(link); try { link.close(); } catch (IOException ignored) { }
        }
    }
    private void inspectNetworkRequest(NearbyWire.Frame frame) throws IOException {
        if ((frame.flags & 1) != 0) throw new IOException("encrypted network control unsupported");
        for (NearbyWire.Field f : NearbyWire.fields(frame.payload)) {
            log("认证后网络请求：类型 " + frame.kind + "，字段 " + f.tag + "，长度 " + f.value.length + "。尚未回复网络能力。");
            if (frame.kind == 4 && f.tag == 1) {
                NearbyWire.Cursor c = new NearbyWire.Cursor(f.value, 0);
                try {
                    int channel = c.uint();
                    if (channel != 3) continue;
                    for (NearbyWire.Field detail : NearbyWire.fields(java.util.Arrays.copyOfRange(f.value, c.p, f.value.length))) {
                        if (detail.tag != 3 || detail.value.length > 256) continue;
                        NearbyWire.Cursor caps = new NearbyWire.Cursor(detail.value, 0);
                        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
                        while (caps.p < detail.value.length) {
                            int key = caps.uint(), value = caps.uint();
                            if (!seen.add(key) || seen.size() > 32) throw new IOException("network capability fields");
                            log("手机网络能力字段 " + key + " = " + value + "（仅记录能力数值）。");
                        }
                    }
                } catch (NearbyWire.Short e) { throw new IOException("network request truncated"); }
            }
        }
    }
    private void replyNetwork(NearbyWire.Frame frame, NetworkResource resource, OutputStream out) throws IOException {
        synchronized (networkLock) {
            byte[] reply = resource.respond(frame);
            if (reply != null) { out.write(reply); out.flush(); }
            for (byte[] pending : resource.takePending()) { out.write(pending); out.flush(); }
            log("网络协商：类型 " + frame.kind + "，响应 " + (reply == null ? 0 : reply.length) + " 字节；角色已协商 " + resource.negotiated + "，临时凭据已发送 " + resource.credentialsSent + "。");
            if (!resource.lastReason.isEmpty()) log("网络协商未通过：" + resource.lastReason + "；手机与本轮热点频段不一致时无法投屏。");
        }
    }
    private void startTcpListeners(TestSession session, int token, String model) throws IOException {
        NetworkResource resource = session.network;
        for (int mode = 0; mode < 3; mode++) {
            final int kind = mode, port = resource.offer.controlPort + mode;
            java.net.ServerSocket server = new java.net.ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(resource.offer.ownerIp(), port), 4);
            tcpServers.add(server);
            new Thread(() -> acceptTcp(server, session, token, kind), "lab-network-" + mode).start();
        }
        resource.dataPort = resource.offer.controlPort + 1;
        log("本轮热点的三个 TCP 监听已就绪；尚未收到手机网络连接。");
    }
    private void acceptTcp(java.net.ServerSocket server, TestSession session, int token, int kind) {
        try {
            while (generation.get() == token) {
                java.net.Socket socket = server.accept();
                if (generation.get() != token || !session.authenticated || !session.network.credentialsSent || tcpClients.size() >= 8
                        || !socket.getInetAddress().getHostAddress().equals(session.network.offer.clientIp())) { socket.close(); log("拒绝未选定或未认证的 TCP 连接。"); continue; }
                if(kind==2&&!session.controlBusy.compareAndSet(false,true)){socket.close();log("已有本轮 DMSDP 控制连接，拒绝重复连接。");continue;}
                if(kind==2)status.advance(token,ReceiverStatus.Stage.MEDIA);
                tcpClients.add(socket);
                log("收到手机 TCP 连接：阶段 " + kind + "（0 网络控制，1 DMSDP 入口，2 DMSDP 控制）。");
                new Thread(() -> receiveTcp(socket, session, token, kind), "lab-tcp-rx").start();
            }
        } catch (IOException e) { if (generation.get() == token) {log("TCP 监听结束：I/O 错误。"); requestReconnect(token,session.model); } }
    }
    private void receiveTcp(java.net.Socket socket, TestSession session, int token, int kind) {
        ControlSession controlSession=null;AudioReceiver audioReceiver=null;MicrophoneSender microphone=null;
        try {
            socket.setSoTimeout(kind==2?250:1000);
            byte[] buffer = new byte[4096]; int requests = 0;
            InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream();
            NearbyWire.Decoder decoder = new NearbyWire.Decoder();
            NearbyWire.Negotiator negotiator = new NearbyWire.Negotiator(localId.getBytes(StandardCharsets.US_ASCII), -1);
            DmsdpWire.Decoder dmsdp = new DmsdpWire.Decoder();
            long lastPeer=System.nanoTime(),lastHeartbeat=lastPeer;
            if(kind==2){
                byte[] key=session.copyKey();if(key==null)throw new IOException("missing authenticated key");
                if(session.video){AudioSink sink=null;try{
                    sink=new AudioSink(context);audioReceiver=new AudioReceiver(session.network.offer.ownerIp(),session.network.offer.clientIp(),key,sink,new AudioReceiver.Events(){public boolean active(){return generation.get()==token;}public void log(String text){if(active())ReceiverEngine.this.log(text);}});audioReceivers.add(audioReceiver);
                }catch(IOException|RuntimeException e){if(sink!=null)sink.close();log("本轮音频后端未就绪："+e.getClass().getSimpleName()+"；继续画面与触控。");}}
                if(session.video&&microphoneAllowed&&MicrophoneSender.enabled(context))try{
                    microphone=new MicrophoneSender(context,session.network.offer.ownerIp(),session.network.offer.clientIp(),key,new MicrophoneSender.Events(){public boolean active(){return generation.get()==token;}public void log(String text){if(active())ReceiverEngine.this.log(text);}});microphones.add(microphone);
                }catch(IOException|RuntimeException e){log("麦克风实验初始化失败："+e.getClass().getSimpleName()+"；继续画面与播放。");}
                final MicrophoneSender selectedMicrophone=microphone;
                final MicrophoneControl micControl=microphone==null?null:new MicrophoneControl(microphone);
                final AudioReceiver selectedAudio=audioReceiver;
                final SpeakerControl speakerControl=selectedAudio==null?null:new SpeakerControl(selectedAudio);
                final ControlSession.Events controlEvents=new ControlSession.Events(){public void log(String text){if(generation.get()==token)ReceiverEngine.this.log(text);}public void clock(long high,long low){if(selectedMicrophone!=null)selectedMicrophone.clock(high,low);}};
                final ControlSession.TextHandler audioControl=(start,headers,body)->{List<byte[]> replies=micControl==null?null:micControl.receive(start,headers,body);return replies!=null?replies:speakerControl==null?null:speakerControl.receive(start,headers,body);};
                try{controlSession=new ControlSession(key,controlEvents,new DeviceBusiness(localId,session.model,session.network.offer.ownerIp(),session.network.offer.clientIp(),this::log,new DeviceBusiness.Display(){
                    public boolean ready(){return session.video&&((LabApplication)context).video().ready()&&generation.get()==token;}
                    public void endpoint(int port)throws IOException{startHiSight(session,token,port);}
                },selectedAudio,selectedMicrophone),audioControl);}finally{Arrays.fill(key,(byte)0);}
                output.write(controlSession.initial());output.flush();log("DMSDP OPTIONS 首包已发送，使用本轮认证密钥协商 AES-CTR；未发送媒体能力。");
            }
            while (generation.get() == token) {
                if(kind==2&&controlSession.negotiated()&&System.nanoTime()-lastHeartbeat>1000000000L){output.write(controlSession.heartbeat());output.flush();lastHeartbeat=System.nanoTime();}
                int n;
                try{n=input.read(buffer);}catch(java.net.SocketTimeoutException e){
                    if(kind==2){
                        long now=System.nanoTime();
                        // The phone sends control heartbeats irregularly while video is
                        // streaming on a separate channel. A quiet control socket does not
                        // mean the session is dead. Use a long idle timeout and treat our
                        // own heartbeats as activity too.
                        if(now-lastPeer>180000000000L)throw e;
                        if(controlSession.negotiated()&&now-lastHeartbeat>1000000000L){output.write(controlSession.heartbeat());output.flush();lastHeartbeat=now;lastPeer=now;}
                    }
                    continue;
                }
                if (n < 0) break; lastPeer=System.nanoTime();
                if (kind == 0) for (NearbyWire.Frame frame : decoder.feed(buffer, n)) {
                    if (frame.kind == 4 || frame.kind == 33) replyNetwork(frame, session.network, output);
                    else { byte[] reply = negotiator.respond(frame); if (reply != null) { output.write(reply); output.flush(); } }
                    log("TCP 网络控制帧：类型 " + frame.kind + "，载荷 " + frame.payload.length + " 字节。");
                } else if (kind == 1) for (DmsdpWire.Packet packet : dmsdp.feed(buffer, n)) {
                    if (++requests > 3) throw new IOException("DMSDP request limit");
                    output.write(DmsdpWire.controlOffer(packet, session.network.offer.controlPort + 2)); output.flush();
                    log("DMSDP 入口协商已响应；等待独立控制连接。");
                } else {for(byte[] reply:controlSession.feed(buffer,n)){output.write(reply);output.flush();}}
            }
        } catch (IOException | java.security.GeneralSecurityException e) { if (generation.get() == token) log("TCP 阶段 " + kind + " 结束：" + e.getClass().getSimpleName()); }
        finally {if(microphone!=null){microphone.close();microphones.remove(microphone);}if(audioReceiver!=null){audioReceiver.close();audioReceivers.remove(audioReceiver);}if(controlSession!=null)controlSession.close();if(kind==2)session.controlBusy.set(false);tcpClients.remove(socket); try {socket.close();} catch (IOException ignored) {}if(kind==2)requestReconnect(token, session.model);}
    }
    private void startHiSight(TestSession session,int token,int port)throws IOException{
        VideoSink sink=((LabApplication)context).video();
        if(!session.video||!sink.ready()||generation.get()!=token||!session.mediaBusy.compareAndSet(false,true))throw new IOException("video state");
        byte[] key=session.copyKey();if(key==null)throw new IOException("video key unavailable");
        int configuredTier=context.getSharedPreferences(VideoQuality.PREF,0).getInt("tier",-1);
        int tier=VideoQuality.resolve(configuredTier,session.network.offer.frequency);
        String formats=VideoQuality.formats(tier);
        log("视频画质档位："+VideoQuality.label(tier)+"（"+(configuredTier<0?"按热点频段自动":"手动指定")+"）。");
        final HiSightClient client;
        try{client=new HiSightClient(session.network.offer.ownerIp(),session.network.offer.clientIp(),key,sink,new HiSightClient.Events(){
            public boolean active(){return generation.get()==token;}
            public void log(String text){if(active())ReceiverEngine.this.log(text);}
            public void add(java.net.Socket s){tcpClients.add(s);}
            public void remove(java.net.Socket s){tcpClients.remove(s);}
        },((LabApplication)context).input(),formats);}finally{Arrays.fill(key,(byte)0);}
        videoClients.add(client);new Thread(()->{try{client.run(port);}finally{videoClients.remove(client);session.mediaBusy.set(false);requestReconnect(token,session.model);}},"lab-hisight-control").start();
    }
    private void requestReconnect(int token,String model){
        if(!continuous || generation.get()!=token || !reconnect.request(token))return;
        handler.post(()->{
            if(!continuous || !running || generation.get()!=token)return;
            log("连接已断开：释放本轮网络和认证会话，保留视频窗口，重新等待手机连接。");
            stopInternal(false,true,false);
            if(!((LabApplication)context).video().restart()){fail("画面解码器未能恢复，请重新连接或重新打开应用。");return;}
            final int waiting=generation.get();
            final long delay=reconnect.delay();
            status.retrying(waiting);
            log("本轮不立即重播发现广播：" + (delay/1000) + " 秒后再等待手机，避免手机连续弹连接码来不及输入。");
            handler.postDelayed(()->{
                if(generation.get()==waiting && continuous && ((LabApplication)context).video().ready())start(model,true,true,microphoneAllowed,false);
            },delay);
        });
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
    void stop(boolean announce) {
        boolean preserveProblem=!announce && !isActive() && status.snapshot(false).problem;
        String previousProblem=status.snapshot(false).detail;
        boolean wasContinuous=continuous;
        continuous=false;
        stopInternal(announce,false,true);
        if(preserveProblem)status.failed(generation.get(),previousProblem);else status.stopped(generation.get());
        if(wasContinuous)((LabApplication)context).video().close();
    }
    private void stopInternal(boolean announce,boolean preserveVideo,boolean notifyStopped) {
        boolean wasRunning = running;
        running = false; generation.incrementAndGet();
        if (advertiser != null && advCallback != null) try { advertiser.stopAdvertising(advCallback); } catch (RuntimeException ignored) { }
        advertiser = null; advCallback = null;
        for (BluetoothServerSocket server : servers) try { server.close(); } catch (IOException ignored) { }
        servers.clear();
        for (TunnelLink.WireLink link : clients) try { link.close(); } catch (IOException ignored) { }
        clients.clear();
        for (java.net.Socket socket : tcpClients) try { socket.close(); } catch (IOException ignored) { }
        tcpClients.clear();
        for (java.net.ServerSocket server : tcpServers) try { server.close(); } catch (IOException ignored) { }
        tcpServers.clear();
        for(MicrophoneSender mic:microphones)mic.close();microphones.clear();
        for(AudioReceiver audio:audioReceivers)audio.close();audioReceivers.clear();
        for(HiSightClient video:videoClients)video.close();videoClients.clear();
        if(!preserveVideo)for(TestSession session:sessions)if(session.video){((LabApplication)context).video().close();break;}
        for(TestSession session:sessions)session.clearKey();sessions.clear();
        networkResource = null;
        networkProblem = null;
        closeHotspots();
        networkEvents = null;
        if (notifyStopped) {
            final int stoppedGeneration=generation.get();
            handler.post(()->{if(!running && generation.get()==stoppedGeneration)onStopped.run();});
        }
        if (announce && wasRunning) log("测试已停止，实验监听与 BLE 广播已关闭。系统蓝牙可发现状态按系统倒计时结束。");
    }
}
