package local.hicarlab;

import android.content.*;
import android.net.wifi.p2p.*;
import android.os.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;

/** Owns only the temporary group created by this experiment. */
final class P2pHotspot implements AutoCloseable {
    interface Events {void ready(NetworkResource.Offer offer);void failed(String reason);void log(String message);}
    /** 5220 first: the phone advertised 5220 as its preferred 5 GHz channel. */
    private static final int[] PREFERRED_FREQUENCY={5220,5745,2437};
    private static final String OWN_SSID_PREFIX="DIRECT-HLHiCarLab-";
    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private final Events events;
    private volatile boolean closed,created;
    private int attempts,bandAttempt,cleanupRetries;
    P2pHotspot(Context c,Events events){context=c.getApplicationContext();this.events=events;manager=(WifiP2pManager)c.getSystemService(Context.WIFI_P2P_SERVICE);}
    void start(){
        if(Build.VERSION.SDK_INT<29||manager==null){events.failed("系统未提供所需 Wi-Fi Direct 接口");return;}
        try{
            channel=manager.initialize(context,Looper.getMainLooper(),()->{if(!closed)events.failed("Wi-Fi Direct 服务断开");});
            if(channel==null){events.failed("Wi-Fi Direct 初始化失败");return;}
            manager.requestGroupInfo(channel,existing->{
                if(closed)return;
                if(existing!=null){
                    String ssid=existing.getNetworkName();
                    if(ssid!=null&&ssid.startsWith(OWN_SSID_PREFIX)){
                        // A previous round left our temporary group behind. It may belong to
                        // a stopped process, so reuse it after verifying we are the GO.
                        events.log("发现本实验遗留的临时 Wi-Fi Direct 组，清理后重建本轮热点。");
                        created=true;
                        manager.removeGroup(channel,new WifiP2pManager.ActionListener(){
                            public void onSuccess(){if(!closed){created=false;handler.postDelayed(P2pHotspot.this::startCreate,1500);}}
                            public void onFailure(int reason){
                                if(closed)return;
                                // code 2 = busy: a just-stopped group is still being torn down.
                                // Retry cleanup a few times instead of giving up immediately.
                                if(cleanupRetries++<3){
                                    events.log("遗留热点清理未确认（系统代码 "+reason+"），稍后重试。");
                                    handler.postDelayed(P2pHotspot.this::start,1500);
                                }else events.failed("遗留临时热点清理失败，系统代码 "+reason);
                            }
                        });
                        return;
                    }
                    events.failed("已有其它 Wi-Fi Direct 连接，实验未修改它");return;
                }
                create();
            });
        }catch(RuntimeException e){events.failed("Wi-Fi Direct 接口拒绝："+e.getClass().getSimpleName());}
    }
    /** Delayed entry to create(), used by retries so the P2P state machine can settle. */
    private void startCreate(){if(!closed)create();}
    private void create(){
        final int frequency=PREFERRED_FREQUENCY[Math.min(bandAttempt,PREFERRED_FREQUENCY.length-1)];
        try{
            byte[] random=new byte[12];new SecureRandom().nextBytes(random);StringBuilder password=new StringBuilder();for(byte b:random)password.append(String.format(Locale.ROOT,"%02x",b&255));
            WifiP2pConfig config=new WifiP2pConfig.Builder().setNetworkName(OWN_SSID_PREFIX+password.substring(0,4))
                .setPassphrase(password.toString()).setGroupOperatingFrequency(frequency).enablePersistentMode(false).build();
            events.log("正在创建本轮临时 Wi-Fi Direct 热点（"+NetworkResource.band(frequency)+"，"+frequency+" MHz）；不记录密码。");
            manager.createGroup(channel,config,new WifiP2pManager.ActionListener(){
                public void onSuccess(){created=true;if(closed){remove();return;}poll();}
                public void onFailure(int reason){
                    if(closed)return;
                    if(bandAttempt<PREFERRED_FREQUENCY.length-1){
                        bandAttempt++;
                        events.log("Wi-Fi Direct 建组失败（系统代码 "+reason+"），1.5 秒后改用 "
                                +PREFERRED_FREQUENCY[bandAttempt]+" MHz 重试。");
                        // Give the P2P state machine time to settle; immediate retries all
                        // returned code 2 (busy) when a previous group had just been removed.
                        handler.postDelayed(P2pHotspot.this::startCreate,1500);return;
                    }
                    events.failed("临时热点创建失败，系统代码 "+reason);
                }
            });
        }catch(RuntimeException e){events.failed("热点配置失败："+e.getClass().getSimpleName());}
    }
    private void poll(){
        if(closed)return;
        manager.requestGroupInfo(channel,group->{
            if(closed)return;
            if(group==null){if(++attempts<30)handler.postDelayed(this::poll,500);else events.failed("临时热点就绪超时");return;}
            if(!group.isGroupOwner()){events.failed("未成为本轮热点所有者");return;}
            try{
                String bssid=null;
                if(Build.VERSION.SDK_INT>=36){
                    try{
                        Object address=group.getClass().getMethod("getGroupOwnerBssid").invoke(group);
                        if(address!=null){
                            String raw=address.toString();
                            events.log("系统 getGroupOwnerBssid 返回："+(raw!=null&&raw.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")?"标准MAC格式（长度 "+raw.length()+"）":"非标准格式（长度 "+(raw==null?0:raw.length())+"）"));
                            if(usableMac(raw))bssid=raw;
                        }else events.log("系统 getGroupOwnerBssid 返回：null");
                    }
                    catch(ReflectiveOperationException | RuntimeException e){events.log("系统未提供 getGroupOwnerBssid："+e.getClass().getSimpleName());}
                    // Diagnose what BSSID-ish accessors this Android build actually exposes.
                    StringBuilder accessors=new StringBuilder();
                    for(java.lang.reflect.Method m:group.getClass().getMethods()){
                        String n=m.getName().toLowerCase(java.util.Locale.ROOT);
                        if(n.contains("bssid")||n.contains("address")||n.contains("owner"))accessors.append(m.getName()).append(" ");
                    }
                    events.log("本系统 WifiP2pGroup 可用地址相关方法："+(accessors.length()==0?"无":accessors.toString().trim()));
                }
                if(!usableMac(bssid)){
                    try{
                        Object ownerDevice=group.getClass().getMethod("getOwner").invoke(group);
                        if(ownerDevice!=null){
                            Object address=ownerDevice.getClass().getField("deviceAddress").get(ownerDevice);
                            if(address instanceof String&&usableMac((String)address)){
                                bssid=(String)address;
                                events.log("已从 Wi-Fi Direct 组所有者取得热点地址（值不写日志）。");
                            }
                        }
                    }catch(ReflectiveOperationException | RuntimeException ignored){}
                }
                String owner=null;NetworkInterface network=NetworkInterface.getByName(group.getInterface());
                if(network!=null)for(InterfaceAddress a:network.getInterfaceAddresses())if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress()&&a.getNetworkPrefixLength()==24)owner=a.getAddress().getHostAddress();
                if(owner==null){if(++attempts<30){handler.postDelayed(this::poll,500);return;}events.failed("未取得本轮热点真实 IPv4 地址");return;}
                if(!usableMac(bssid))bssid=OwnGroupAddress.read(network,group.getInterface(),events);
                if(!usableMac(bssid)&&owner!=null)bssid=OwnGroupAddress.byOwnerIp(owner);
                // The P2P interface MAC may not be published immediately after group setup.
                // Retry a few times before giving up on the BSSID.
                if(!usableMac(bssid)&&attempts<10&&owner!=null){attempts++;handler.postDelayed(this::poll,400);return;}
                int frequency=group.getFrequency();
                if(NetworkResource.frequencyMask(frequency)==0){events.failed("热点信道 "+frequency+" MHz 不在支持列表内");return;}
                int dot=owner.lastIndexOf('.');String client=owner.substring(0,dot+1)+(owner.endsWith(".2")?"3":"2");
                NetworkResource.Offer offer;
                if(usableMac(bssid)){
                    offer=new NetworkResource.Offer(group.getNetworkName(),bssid,group.getPassphrase(),owner,client,frequency,4005);
                    events.log("临时热点已就绪：频率 "+frequency+" MHz（"+NetworkResource.band(frequency)+"），真实 BSSID 与 IPv4 已取得（值不写日志）。");
                }else{
                    // Android 16 hides the GO BSSID from ordinary apps. The phone can still
                    // associate by SSID + passphrase, so degrade to an SSID-only offer
                    // instead of blocking the whole network negotiation.
                    offer=NetworkResource.Offer.ssidOnly(group.getNetworkName(),group.getPassphrase(),owner,client,frequency,4005);
                    events.log("临时热点已就绪：频率 "+frequency+" MHz（"+NetworkResource.band(frequency)+"）；系统未暴露 BSSID，按仅 SSID 方式上报，地址不写日志。");
                }
                events.ready(offer);
            }catch(Exception e){events.failed("热点参数核验失败："+e.getClass().getSimpleName());}
        });
    }
    private static boolean usableMac(String value){
        return value!=null&&value.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")&&!value.equals("02:00:00:00:00:00")&&!value.equals("00:00:00:00:00:00")&&(Integer.parseInt(value.substring(0,2),16)&1)==0;
    }
    private void remove(){if(created&&channel!=null){created=false;manager.removeGroup(channel,new WifiP2pManager.ActionListener(){public void onSuccess(){events.log("本轮临时热点已移除。");}public void onFailure(int reason){events.log("临时热点移除未确认，系统代码 "+reason);}});}}
    @Override public void close(){closed=true;handler.removeCallbacksAndMessages(null);remove();}
}
