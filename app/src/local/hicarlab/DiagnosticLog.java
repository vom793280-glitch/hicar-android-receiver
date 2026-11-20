package local.hicarlab;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Bounded protocol metadata only: never PIN, packet bodies, addresses or keys. */
final class DiagnosticLog {
    interface Listener { void update(String text); }
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static StringBuilder history;
    private static Listener listener;
    private static File logFile;
    private static final Object fileLock = new Object();
    static synchronized String history(Context c) {
        if(history==null)history=new StringBuilder(c.getSharedPreferences("diagnostics",0).getString("history",""));
        return history.toString();
    }
    static void add(Context c,String message) {
        final String snapshot;
        final String line=new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+' '+message+'\n';
        synchronized(DiagnosticLog.class) {
            history(c);
            history.append(line);
            if(history.length()>24000)history.delete(0,history.length()-16000);
            snapshot=history.toString();
            c.getSharedPreferences("diagnostics",0).edit().putString("history",snapshot).apply();
            appendFile(c,line);
        }
        android.util.Log.i("HiCarLab",message);
        handler.post(()->{ Listener target; synchronized(DiagnosticLog.class){target=listener;} if(target!=null)target.update(snapshot); });
    }
    /**
     * Mirror the diagnostic history to the app's external files directory so a full
     * session can be pulled with `adb pull /sdcard/Android/data/local.hicarlab/files/hicarlab-trace.txt`.
     * The logcat ring buffer on some phones is only 256 KiB and system logs flush it
     * within seconds, which loses exactly the disconnect sequence we need.
     */
    private static void appendFile(Context c,String line) {
        try {
            if(logFile==null) {
                File dir=c.getExternalFilesDir(null);
                if(dir==null)return;
                if(!dir.exists()&&!dir.mkdirs())return;
                logFile=new File(dir,"hicarlab-trace.txt");
            }
            synchronized(fileLock) {
                FileOutputStream out=new FileOutputStream(logFile,true);
                try{out.write(line.getBytes("UTF-8"));out.flush();}finally{out.close();}
            }
        } catch (Exception ignored) { }
    }
    static void attach(Context c,Listener target) { synchronized(DiagnosticLog.class){listener=target;} target.update(history(c)); }
    static synchronized void detach(Listener target) {if(listener==target)listener=null;}
}
