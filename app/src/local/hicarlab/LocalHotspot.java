package local.hicarlab;

import android.content.Context;
import android.net.wifi.*;
import android.os.*;
import android.util.SparseIntArray;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Public local-only hotspot for Android 8.0 and newer.
 *
 * <p>Android 11+ builds a {@code SoftApConfiguration} that lists both a 2.4 GHz and a
 * 5 GHz channel, so a device whose Wi-Fi chip only supports 2.4 GHz still starts an AP
 * instead of failing a hard coded channel 149 request. Android 8-10 has no public
 * configuration entry: {@code startLocalOnlyHotspot(callback, handler)} lets the
 * platform choose SSID, passphrase and channel, and those values are read back from
 * {@code WifiConfiguration}.
 *
 * <p>The offer is delivered only after SSID, passphrase and IPv4 address were verified.
 * BSSID and channel are used when the platform exposes them; when it does not
 * (common on Android 8-11) the offer degrades to SSID-only instead of aborting, so the
 * phone can still join the AP and the remaining steps stay observable.
 */
final class LocalHotspot implements AutoCloseable {
    private static final int CONTROL_PORT=4005;
    /** Channel 6, used only when the platform cannot report the real channel. */
    private static final int UNVERIFIED_FREQUENCY=2437;
    private final Context context;
    private final P2pHotspot.Events events;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Set<String> oldAddresses=new HashSet<>();
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private volatile boolean closed,delivered,probing,infoTried,probeDeadlineSet;
    private int attempts,channelPlan;
    private LocalApInfo watch;
    private OwnApScan scan;
    private String ssid,password,bssid,owner,ownerInterface;
    private int frequency;
    private boolean frequencyKnown;
    LocalHotspot(Context c,P2pHotspot.Events e){context=c.getApplicationContext();events=e;}
    /** IPv4 address to interface name; only the AP subnet added by this reservation matters. */
    private static Map<String,String> addresses()throws Exception{
        Map<String,String> result=new LinkedHashMap<>();Enumeration<NetworkInterface> networks=NetworkInterface.getNetworkInterfaces();
        while(networks.hasMoreElements()){
            NetworkInterface network=networks.nextElement();
            for(InterfaceAddress a:network.getInterfaceAddresses())
                if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress()&&a.getNetworkPrefixLength()==24)
                    result.put(a.getAddress().getHostAddress(),network.getName());
        }
        return result;
    }
    private static String random(int bytes){
        byte[] raw=new byte[bytes];new SecureRandom().nextBytes(raw);StringBuilder out=new StringBuilder();
        for(byte b:raw)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static String unquote(String value){
        if(value==null)return null;
        if(value.length()>=2&&value.charAt(0)=='"'&&value.charAt(value.length()-1)=='"')return value.substring(1,value.length()-1);
        return value;
    }
    void start(){
        if(Build.VERSION.SDK_INT<26){events.failed("本地热点接口需要 Android 8.0 以上");return;}
        WifiManager manager=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        if(manager==null){events.failed("系统未提供 Wi-Fi 服务，无法创建热点");return;}
        // Android 16 removed Builder.setSsid(String) (now handled in buildConfiguration).
        // The phone requires a 5 GHz hotspot, so request a single 5 GHz channel first;
        // the minimal fallback (platform-chosen band) is only used if that is refused.
        if(Build.VERSION.SDK_INT>=36)channelPlan=2;
        try{oldAddresses.addAll(addresses().keySet());}catch(Exception e){events.log("启动前地址快照读取失败："+e.getClass().getSimpleName());}
        WifiManager.LocalOnlyHotspotCallback callback=new WifiManager.LocalOnlyHotspotCallback(){
            @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r){if(closed){r.close();return;}reservation=r;handler.post(LocalHotspot.this::readback);}
            @Override public void onFailed(int reason){
                if(closed)return;
                // ERROR_GENERIC on Android 16 often means the requested channel is
                // unavailable. Retry once with a minimal config (platform picks band),
                // then fall back to P2P.
                if(Build.VERSION.SDK_INT>=36&&channelPlan<4){
                    channelPlan=4;
                    events.log("5 GHz 热点请求被系统拒绝（代码 "+reason+"），改用系统默认配置重试。");
                    try{
                        Object cfg=buildConfiguration();Executor exec=command->handler.post(command);
                        Class<?> cc=Class.forName("android.net.wifi.SoftApConfiguration");
                        WifiManager.class.getMethod("startLocalOnlyHotspotWithConfiguration",cc,Executor.class,WifiManager.LocalOnlyHotspotCallback.class)
                            .invoke(manager,cfg,exec,this);
                        return;
                    }catch(Exception ignored){}
                }
                events.failed("本地热点启动失败，系统代码 "+reason+hint(reason));
            }
            @Override public void onStopped(){if(!closed)events.failed("本地热点被系统停止");}
        };
        try{
            if(Build.VERSION.SDK_INT>=30){
                Object config=buildConfiguration();
                Executor executor=command->handler.post(command);
                Class<?> configClass=Class.forName("android.net.wifi.SoftApConfiguration");
                String method=Build.VERSION.SDK_INT>=36?"startLocalOnlyHotspotWithConfiguration":"startLocalOnlyHotspot";
                events.log("请求本地热点，候选信道同时包含 2.4 GHz 与 5 GHz；不记录名称或密码。");
                WifiManager.class.getMethod(method,configClass,Executor.class,WifiManager.LocalOnlyHotspotCallback.class).invoke(manager,config,executor,callback);
            }else{
                events.log("请求本地热点（Android 8/9 兼容接口，由系统决定名称与信道）；不记录名称或密码。");
                WifiManager.class.getMethod("startLocalOnlyHotspot",WifiManager.LocalOnlyHotspotCallback.class,Handler.class).invoke(manager,callback,handler);
            }
        }catch(InvocationTargetException e){
            Throwable cause=e.getCause()==null?e:e.getCause();
            if(retryWithNarrowerChannels(cause,manager,callback))return;
            // Do not fail immediately: the P2P path can still create a usable hotspot.
            events.log("公开本地热点接口被系统拒绝："+cause.getClass().getSimpleName()+hint(cause)+"；将尝试 Wi-Fi Direct。");
            events.failed("公开本地热点不可用（"+cause.getClass().getSimpleName()+"）");
        }catch(Exception e){
            if(retryWithNarrowerChannels(e,manager,callback))return;
            events.log("公开本地热点接口失败："+e.getClass().getSimpleName()+"；将尝试 Wi-Fi Direct。");
            events.failed("公开本地热点不可用（"+e.getClass().getSimpleName()+"）");
        }
    }
    /**
     * Retry with a narrower configuration. Android 16 rejects a dual-band request, and
     * some builds reject any explicit channel; the final attempt uses a minimal config.
     */
    private boolean retryWithNarrowerChannels(Throwable cause,WifiManager manager,WifiManager.LocalOnlyHotspotCallback callback){
        if(Build.VERSION.SDK_INT<30||channelPlan>=4)return false;
        channelPlan++;
        try{
            Object config=buildConfiguration();
            Executor executor=command->handler.post(command);
            Class<?> configClass=Class.forName("android.net.wifi.SoftApConfiguration");
            String method=Build.VERSION.SDK_INT>=36?"startLocalOnlyHotspotWithConfiguration":"startLocalOnlyHotspot";
            events.log(channelPlan==1?"系统不接受双频热点请求，改为只请求 2.4 GHz 信道 6 重试。":"2.4 GHz 请求同样被拒绝，改为只请求 5 GHz 信道 149 重试。");
            WifiManager.class.getMethod(method,configClass,Executor.class,WifiManager.LocalOnlyHotspotCallback.class).invoke(manager,config,executor,callback);
            return true;
        }catch(Exception e){return false;}
    }
    private Object buildConfiguration()throws Exception{
        Class<?> builderClass=Class.forName("android.net.wifi.SoftApConfiguration$Builder");
        Object builder=builderClass.getConstructor().newInstance();
        ssid="HiCarLab-"+random(4);
        password=random(12);
        // Android 16 (API 36+) removed SoftApConfiguration.Builder.setSsid(String);
        // the SSID must now be supplied as a WifiSsid created from UTF-8 bytes.
        boolean ssidSet=false;
        try{
            Class<?> wifiSsid=Class.forName("android.net.wifi.WifiSsid");
            Object value=wifiSsid.getMethod("fromBytes",byte[].class).invoke(null,(Object)ssid.getBytes("UTF-8"));
            builderClass.getMethod("setWifiSsid",wifiSsid).invoke(builder,value);
            ssidSet=true;
        }catch(Exception ignored){}
        if(!ssidSet)builderClass.getMethod("setSsid",String.class).invoke(builder,ssid);
        builderClass.getMethod("setPassphrase",String.class,int.class).invoke(builder,password,1);
        // Try setBand(BAND_5GHZ) first (available on real devices, hidden from the
        // stub android.jar). It is more widely accepted than explicit channels.
        if(channelPlan==2){
            try{
                Class<?> sac=Class.forName("android.net.wifi.SoftApConfiguration");
                int band5=sac.getField("BAND_5GHZ").getInt(null);
                java.lang.reflect.Method setBand=builderClass.getMethod("setBand",int.class);
                setBand.invoke(builder,band5);
                events.log("已请求 5 GHz 频段（setBand），等待系统确认。");
                return builderClass.getMethod("build").invoke(builder);
            }catch(Exception e){events.log("setBand 不可用（"+e.getClass().getSimpleName()+"），改用 setChannels。");}
        }
        // channelPlan 0/1/2 request explicit bands; 3+ use a minimal config with no
        // explicit channel at all. Android 16 rejects several channel combinations.
        if(channelPlan<3){
            try{
                SparseIntArray channels=new SparseIntArray();
                if(channelPlan==0){channels.put(1,2437);channels.put(2,5220);}
                else if(channelPlan==1)channels.put(1,2437);
                else channels.put(2,5220);
                builderClass.getMethod("setChannels",SparseIntArray.class).invoke(builder,channels);
            }catch(Exception e){events.log("系统拒绝指定的热点信道（"+e.getClass().getSimpleName()+"），将使用默认信道。");}
        }
        return builderClass.getMethod("build").invoke(builder);
    }
    private void readback(){
        if(closed||delivered)return;
        if(!readCredentials()){
            if(++attempts<10){handler.postDelayed(this::readback,300);return;}
            events.failed("无法读取本轮热点的名称或密码，未发送配置。");
            return;
        }
        handler.post(this::advance);
    }
    private boolean readCredentials(){
        try{
            if(Build.VERSION.SDK_INT>=30){
                Object config=reservation.getClass().getMethod("getSoftApConfiguration").invoke(reservation);
                if(config==null)return false;
                Object name=config.getClass().getMethod("getSsid").invoke(config);
                if(!(name instanceof String)){
                    try{
                        Object ws=config.getClass().getMethod("getWifiSsid").invoke(config);
                        if(ws!=null){Object bytes=ws.getClass().getMethod("getBytes").invoke(ws);if(bytes instanceof byte[])name=new String((byte[])bytes,"UTF-8");}
                    }catch(Exception ignored){}
                }
                Object key=config.getClass().getMethod("getPassphrase").invoke(config);
                if(!(name instanceof String)||!(key instanceof String))return false;
                ssid=(String)name;password=(String)key;
                Object address=config.getClass().getMethod("getBssid").invoke(config);
                String mac=address==null?null:address.toString();
                if(OwnGroupAddress.valid(mac))bssid=mac;
                SparseIntArray channels=(SparseIntArray)config.getClass().getMethod("getChannels").invoke(config);
                if(channels!=null&&channels.size()==1){int value=channels.valueAt(0);if(NetworkResource.frequencyMask(value)!=0){frequency=value;frequencyKnown=true;}}
                events.log("系统返回的本轮热点配置已读取；信道"+(frequencyKnown?"为 "+frequency+" MHz":"未由系统报告")+"，地址"+(bssid==null?"未提供":"已提供")+"（值不写日志）。");
                return true;
            }
            Object config=reservation.getClass().getMethod("getWifiConfiguration").invoke(reservation);
            if(config==null)return false;
            java.lang.reflect.Field nameField=config.getClass().getField("SSID");
            java.lang.reflect.Field keyField=config.getClass().getField("preSharedKey");
            String name=unquote((String)nameField.get(config)),key=unquote((String)keyField.get(config));
            if(name==null||name.isEmpty()||key==null||key.length()<8)return false;
            ssid=name;password=key;
            events.log("系统返回的本轮热点名称与密码已读取；Android 8/9 不提供热点地址与信道，稍后按本机扫描或默认值处理。");
            return true;
        }catch(ReflectiveOperationException|RuntimeException e){events.log("读取本轮热点配置未成功："+e.getClass().getSimpleName());return false;}
    }
    private void advance(){
        if(closed||delivered)return;
        if(owner==null){
            try{
                Map<String,String> added=addresses();added.keySet().removeAll(oldAddresses);
                if(added.size()==1){Map.Entry<String,String> only=added.entrySet().iterator().next();owner=only.getKey();ownerInterface=only.getValue();}
            }
            catch(Exception e){events.log("本轮热点 IPv4 地址读取失败："+e.getClass().getSimpleName());}
            if(owner==null){
                if(++attempts<30){handler.postDelayed(this::advance,500);return;}
                events.failed("无法唯一确认本轮热点的 IPv4 地址，未发送配置。");
                return;
            }
        }
        if(frequencyKnown&&bssid!=null){deliver();return;}
        if(probing)return;
        if(!probeDeadlineSet){
            // The phone asks for network parameters right after authentication, so a long
            // scan must not hold the answer back: fall back to the best known values.
            // Slow car head units need longer than a phone to publish the P2P/soft-ap
            // interface MAC. Give the scan time before falling back to a guessed address.
            // Deliver the offer quickly. A BSSID is optional in the credentials
            // (ssidOnly offer), and head units rarely expose their own AP BSSID via
            // public APIs; waiting 15s here delayed the phone's network join noticeably.
            probeDeadlineSet=true;handler.postDelayed(this::degraded,4000);
        }
        if(Build.VERSION.SDK_INT>=30&&!infoTried){
            infoTried=true;probing=true;
            watch=new LocalApInfo(context,new LocalApInfo.Events(){
                public void found(String address,int value){if(closed)return;bssid=address;frequency=value;frequencyKnown=NetworkResource.frequencyMask(value)!=0;watch=null;probing=false;advance();}
                public void failed(String reason){if(closed)return;events.log("热点状态回调未提供地址："+reason);watch=null;probing=false;scanOwn();}
                public void log(String text){events.log(text);}
            },bssid);
            watch.start();return;
        }
        scanOwn();
    }
    private void scanOwn(){
        if(closed||delivered)return;
        probing=true;
        scan=new OwnApScan(context,ssid,frequencyKnown?frequency:0,System.currentTimeMillis(),new OwnApScan.Events(){
            public void found(String address,int observed){
                if(closed)return;bssid=address;
                if(!frequencyKnown&&NetworkResource.frequencyMask(observed)!=0){frequency=observed;frequencyKnown=true;}
                scan=null;probing=false;advance();
            }
            public void failed(String reason){if(closed)return;events.log("本机扫描未取得热点地址："+reason);scan=null;probing=false;degraded();}
            public void log(String text){events.log(text);}
        });
        scan.start();
    }
    private void degraded(){
        if(closed||delivered)return;
        if(bssid==null&&ownerInterface!=null&&ownerInterface.matches("[A-Za-z0-9_.:-]{1,32}")){
            try{
                String address=OwnGroupAddress.read(NetworkInterface.getByName(ownerInterface),ownerInterface,events);
                if(OwnGroupAddress.valid(address)){bssid=address;events.log("本轮热点地址取自本机 Wi-Fi 接口硬件地址，未经扫描核验；个别机型该值与真实 BSSID 不同。");}
            }catch(Exception e){events.log("本机接口地址读取失败："+e.getClass().getSimpleName());}
        }
        if(!frequencyKnown){
            frequency=UNVERIFIED_FREQUENCY;frequencyKnown=true;
            events.log("系统未报告本轮热点信道，按 2.4 GHz 信道 6（2437 MHz）上报；手机若不入网，请改用 5 GHz 热点或更新系统。");
        }
        deliver();
    }
    private void deliver(){
        if(closed||delivered)return;
        int dot=owner==null?-1:owner.lastIndexOf('.');
        if(dot<0){events.failed("本轮热点 IPv4 地址无效，未发送配置。");return;}
        String client=owner.substring(0,dot+1)+(owner.endsWith(".2")?"3":"2");
        try{
            NetworkResource.Offer offer=bssid==null
                ? NetworkResource.Offer.ssidOnly(ssid,password,owner,client,frequency,CONTROL_PORT)
                : new NetworkResource.Offer(ssid,bssid,password,owner,client,frequency,CONTROL_PORT);
            if(bssid==null)events.log("本轮热点地址无法由普通应用核验，按仅 SSID 方式上报；个别手机可能要求完整地址。");
            events.log("本轮热点已就绪："+frequency+" MHz（"+NetworkResource.band(frequency)+"）；开始等待手机入网。");
            delivered=true;events.ready(offer);
        }catch(IllegalArgumentException e){events.failed("热点参数未通过校验："+e.getMessage());}
    }
    private static String hint(int reason){
        switch(reason){
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:return "（没有可用信道）";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:return "（与其他 Wi-Fi 模式冲突，请先关闭投屏或 Wi-Fi Direct）";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:return "（系统禁止热点，可能被厂商策略限制）";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:return "（系统内部错误）";
            default:return "";
        }
    }
    private static String hint(Throwable cause){
        if(cause instanceof SecurityException)return "（缺少 Wi-Fi 权限，请在系统设置中允许定位或附近设备）";
        if(cause instanceof UnsupportedOperationException)return "（这台设备不支持应用创建热点）";
        return "";
    }
    @Override public void close(){
        closed=true;handler.removeCallbacksAndMessages(null);
        if(watch!=null){watch.close();watch=null;}
        if(scan!=null){scan.close();scan=null;}
        if(reservation!=null){reservation.close();reservation=null;events.log("本轮本地热点保留请求已释放。");}
    }
}
