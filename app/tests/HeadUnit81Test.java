package local.hicarlab;
import java.util.*;
/** Host-side checks for the Android 8.1 head-unit profile: 2.4 GHz only, no BLE advertising. */
public final class HeadUnit81Test {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        String model="00048000";
        // Discovery: 13-byte extended beacon remains the default and carries the same core.
        byte[] ext=DiscoveryBeacon.encodeExtended(model,7);
        check(ext.length==13&&(ext[11]&255)==0x11&&(ext[12]&255)==0xE7);
        check(Arrays.equals(Arrays.copyOf(ext,11),DiscoveryBeacon.encode(model,7)));
        // All 2.4 GHz channels must be accepted (a head unit has no 5 GHz radio).
        for(int f:new int[]{2412,2417,2422,2427,2432,2437,2442,2447,2452,2457,2462,2467,2472})
            check(NetworkResource.frequencyMask(f)!=0&&NetworkResource.is24GHz(f));
        // A 2.4 GHz-only offer produces usable credentials with the actual channel.
        NetworkResource.Offer offer=NetworkResource.Offer.ssidOnly("LAB","test-only-pass","192.168.43.1","192.168.43.2",2437,4005);
        String creds=new String(offer.credentials(),java.nio.charset.StandardCharsets.US_ASCII);
        check(creds.contains("LAB\n\ntest-only-pass\n2437"));
        // A phone reporting only 5 GHz no longer blocks delivery: credentials still go out.
        NetworkResource nr=new NetworkResource(offer);nr.authenticated=true;
        byte[] caps=NearbyWire.join(NearbyWire.uint(2),NearbyWire.uint(1),NearbyWire.uint(4),NearbyWire.uint(1),NearbyWire.uint(5),NearbyWire.uint(511));
        byte[] payload=NearbyWire.tlv(1,NearbyWire.join(NearbyWire.uint(3),NearbyWire.tlv(3,caps)));
        check(nr.respond(new NearbyWire.Frame(4,0,payload))!=null);
        check(nr.negotiated&&nr.credentialsSent&&nr.lastReason.contains("2437"));
        System.out.println("PASS: 2.4 GHz-only head unit - beacon, channels, SSID-only credentials and no-band-mismatch blocking");
    }
}
