package local.hicarlab;

import java.nio.charset.StandardCharsets;

/** Host test for peerAuthId decoding used by the trusted-reconnect path. */
public final class TrustReconnectTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static String hex(String s) {
        byte[] b=s.getBytes(StandardCharsets.UTF_8);StringBuilder o=new StringBuilder();
        for(byte x:b)o.append(String.format("%02x",x&255));return o.toString();
    }
    public static void main(String[] args) {
        String id="0123456789ABCDEF0123456789ABCDEF";
        check(id.equals(AuthBusiness.peerIdFromAuthId(hex(id))), "round trip upper id");
        String id2="a-b_c 123";
        check(id2.equals(AuthBusiness.peerIdFromAuthId(hex(id2))), "round trip punctuation");
        check(AuthBusiness.peerIdFromAuthId(null)==null, "null rejected");
        check(AuthBusiness.peerIdFromAuthId("xyz")==null, "non-hex rejected");
        check(AuthBusiness.peerIdFromAuthId("ABC")==null, "odd length rejected");
        check(AuthBusiness.peerIdFromAuthId("")==null, "empty rejected");
        check(AuthBusiness.peerIdFromAuthId("00FF")==null, "non-printable rejected");
        StringBuilder longId=new StringBuilder();for(int i=0;i<65;i++)longId.append('a');
        check(AuthBusiness.peerIdFromAuthId(hex(longId.toString()))==null, "too long rejected");
        check(AuthBusiness.peerIdFromAuthId(Integer.valueOf(1))==null, "non-string rejected");
        System.out.println("TrustReconnectTest passed: peerAuthId hex decode and validation");
    }
}
