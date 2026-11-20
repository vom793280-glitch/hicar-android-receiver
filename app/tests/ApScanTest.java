package local.hicarlab;
import java.util.*;
public final class ApScanTest {
    private static ApScanMatch.Entry e(String s,String b,int f,long t){return new ApScanMatch.Entry(s,b,f,t);}
    private static void check(String expected,ApScanMatch.Entry... entries){if(!Objects.equals(expected,ApScanMatch.select("lab",5745,100,Arrays.asList(entries))))throw new AssertionError();}
    public static void main(String[] args){
        check("02:12:34:56:78:90",e("lab","02:12:34:56:78:90",5745,101));
        check(null,e("other","02:12:34:56:78:90",5745,101));
        check(null,e("lab","02:12:34:56:78:90",2412,101));
        check(null,e("lab","02:12:34:56:78:90",5745,99));
        check(null,e("lab","02:00:00:00:00:00",5745,101));
        check(null,e("lab","01:12:34:56:78:90",5745,101));
        check(null,e("lab","02:12:34:56:78:90",5745,101),e("lab","02:12:34:56:78:91",5745,101));
        check("02:12:34:56:78:ab",e("lab","02:12:34:56:78:AB",5745,101),e("lab","02:12:34:56:78:ab",5745,102));
        // Unknown band (Android 8-11): the observed channel is reported instead of filtered.
        ApScanMatch.Entry any=ApScanMatch.selectEntry("lab",0,100,Arrays.asList(e("lab","02:12:34:56:78:90",2437,101)));
        if(any==null||any.frequency!=2437||!any.bssid.equals("02:12:34:56:78:90"))throw new AssertionError("any band");
        if(ApScanMatch.selectEntry("lab",0,100,Arrays.asList(e("lab","02:12:34:56:78:90",2437,99)))!=null)throw new AssertionError("stale any band");
        if(ApScanMatch.selectEntry("lab",0,100,Arrays.asList(e("other","02:12:34:56:78:90",2437,101)))!=null)throw new AssertionError("ssid any band");
        System.out.println("PASS: fresh exact SSID/frequency, stale/wrong/masked/multicast rejection, ambiguity and duplicate handling, unknown band keeps observed channel");
    }
}
