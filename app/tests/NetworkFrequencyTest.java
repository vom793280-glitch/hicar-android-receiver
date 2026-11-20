package local.hicarlab;
import java.util.*;
public final class NetworkFrequencyTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 private static byte[] caps(int mask){
  return NearbyWire.join(NearbyWire.uint(2),NearbyWire.uint(1),NearbyWire.uint(4),NearbyWire.uint(1),NearbyWire.uint(5),NearbyWire.uint(mask));
 }
 private static byte[] request(int mask){
  return NearbyWire.tlv(1,NearbyWire.join(NearbyWire.uint(3),NearbyWire.tlv(3,caps(mask))));
 }
 public static void main(String[] args)throws Exception{
  // Original 5 GHz bit positions must not move: they are visible on the wire.
  check(NetworkResource.frequencyMask(5180)==1&&NetworkResource.frequencyMask(5220)==4&&NetworkResource.frequencyMask(5745)==16&&NetworkResource.frequencyMask(5825)==1<<8);
  // 2.4 GHz channels are appended after them.
  check(NetworkResource.frequencyMask(2412)==1<<9&&NetworkResource.frequencyMask(2437)==1<<14&&NetworkResource.frequencyMask(2472)==1<<21);
  check(NetworkResource.is24GHz(2437)&&!NetworkResource.is24GHz(5745)&&NetworkResource.is5GHz(5745)&&!NetworkResource.is5GHz(2437));
  NetworkResource.Offer offer=new NetworkResource.Offer("LAB","02:11:22:33:44:66","test-only-pass","192.168.50.1","192.168.50.2",5220,4005);
  for(int mask:new int[]{4,16}){
   NetworkResource r=new NetworkResource(offer);r.authenticated=true;
   check(r.respond(new NearbyWire.Frame(4,0,request(mask)))!=null);
   // Credentials are delivered even on a reported band mismatch: some phones advertise
   // a narrow mask but still associate with the AP, so let the phone decide.
   check(r.negotiated);
  }
  // A 2.4 GHz-only hotspot is a valid offer and negotiates with a 2.4 GHz capable phone.
  NetworkResource.Offer low=new NetworkResource.Offer("LAB","02:11:22:33:44:66","test-only-pass","192.168.43.1","192.168.43.2",2437,4005);
  check(NetworkResource.band(low.frequency).equals("2.4 GHz"));
  NetworkResource low2g=new NetworkResource(low);low2g.authenticated=true;
  check(low2g.respond(new NearbyWire.Frame(4,0,request(NetworkResource.frequencyMask(2437))))!=null);
  check(low2g.negotiated&&low2g.lastReason.isEmpty());
  // A reported 5 GHz-only mask no longer blocks delivery; the readable reason is kept.
  NetworkResource mismatch=new NetworkResource(low);mismatch.authenticated=true;
  mismatch.respond(new NearbyWire.Frame(4,0,request(NetworkResource.frequencyMask(5745))));
  check(mismatch.negotiated&&mismatch.lastReason.contains("2437"));
  // The pending "busy" style reply is still a valid frame for an unservable request.
  byte[] rejected=NetworkResource.rejectNetwork();
  check(rejected.length>2&&(rejected[0]&255)==127);
  // SSID-only offers keep the channel that was actually used.
  check(new String(NetworkResource.Offer.ssidOnly("LAB","test-only-pass","192.168.43.1","192.168.43.2",2437,4005).credentials(),java.nio.charset.StandardCharsets.US_ASCII).contains("LAB\n\ntest-only-pass\n2437"));
  System.out.println("PASS: 5 GHz bit positions unchanged, 2.4 GHz accepted, band mismatch reported, SSID-only offer keeps 2437");
 }
}
