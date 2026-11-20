package local.hicarlab;
import android.content.*;
import android.net.wifi.*;
import android.os.*;
import java.util.*;

/** Bounded public scan. Never exports or logs surrounding network identities. */
final class OwnApScan implements AutoCloseable {
    /** {@code frequency} is the observed channel of the matched hotspot, or 0 when unknown. */
    interface Events {void found(String bssid,int frequency);void failed(String reason);void log(String text);}
    private final Context context; private final WifiManager wifi; private final String ssid;
    private final int frequency; private final long since; private final Events events;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean closed,registered;
    /** {@code f} may be 0: then any band is accepted and the observed one is reported. */
    OwnApScan(Context c,String s,int f,long t,Events e){context=c.getApplicationContext();ssid=s;frequency=f;since=t;events=e;wifi=(WifiManager)c.getSystemService(Context.WIFI_SERVICE);}
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(i.getAction()))inspect();}};
    void start(){
        try{
            IntentFilter filter=new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);else context.registerReceiver(receiver,filter);
            registered=true;
            events.log(frequency>0?"尝试本机扫描本轮热点；仅匹配本轮名称、信道与新扫描时间，不保存附近网络。":"尝试本机扫描本轮热点；本机无法报告信道，因此只匹配本轮名称与新扫描时间。");
            // Only one scan, short deadline. Head units rarely see their own AP in a
            // public scan; the caller degrades to an SSID-only offer, so a long wait here
            // just delayed the phone joining the network. First results still win if found.
            request();
            handler.postDelayed(()->{if(!closed){close();events.failed("本机扫描未取得本轮热点的唯一新地址；未发送配置，尚不能证明此设备能扫描自身热点。");}},8000);
        }catch(RuntimeException e){close();events.failed("本机热点扫描接口不可用："+e.getClass().getSimpleName());}
    }
    private void request(){if(closed)return;try{events.log(wifi.startScan()?"已请求 Wi-Fi 扫描，等待系统结果。":"系统未接受扫描请求，等待可用扫描结果。");inspect();}catch(RuntimeException e){close();events.failed("扫描权限或系统位置开关未就绪："+e.getClass().getSimpleName());}}
    private void inspect(){
        if(closed)return;
        try{
            List<ApScanMatch.Entry> candidates=new ArrayList<>();
            for(ScanResult r:wifi.getScanResults())if(ssid.equals(r.SSID))candidates.add(new ApScanMatch.Entry(r.SSID,r.BSSID,r.frequency,r.timestamp));
            ApScanMatch.Entry entry=ApScanMatch.selectEntry(ssid,frequency,since,candidates);
            candidates.clear();
            if(entry!=null){close();events.log("本机扫描找到本轮热点的唯一新地址（地址不写日志）。");events.found(entry.bssid,entry.frequency);}
        }catch(RuntimeException e){close();events.failed("读取本轮热点扫描结果失败："+e.getClass().getSimpleName());}
    }
    public void close(){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);if(registered){registered=false;try{context.unregisterReceiver(receiver);}catch(RuntimeException ignored){}}}
}
