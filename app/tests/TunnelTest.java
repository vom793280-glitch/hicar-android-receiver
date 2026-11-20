package local.hicarlab;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

/** Host test for the relay tunnel framing. No Android device needed. */
public final class TunnelTest {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        roundTrip();
        splitAcrossReads();
        coalescedFrames();
        allChannels();
        emptyPayload();
        maxPayload();
        badMagic();
        badCrc();
        oversize();
        readWriteStream();
        randomFuzz();
        if (args.length > 0) protocolBytesSurviveTunnel(args[0]);
        System.out.println("TunnelTest passed: framing, fragmentation, coalescing, CRC, limits, fuzz"
                + (args.length > 0 ? ", real protocol frames intact" : ""));
    }

    static void roundTrip() throws Exception {
        byte[] p = "hello tunnel".getBytes("US-ASCII");
        byte[] f = TunnelProtocol.encode(TunnelProtocol.CH_FE35, p);
        TunnelProtocol.Decoder d = new TunnelProtocol.Decoder();
        List<TunnelProtocol.Frame> out = d.feed(f, f.length);
        check(out.size() == 1, "one frame");
        check(out.get(0).channel == TunnelProtocol.CH_FE35, "channel preserved");
        check(new String(out.get(0).payload, "US-ASCII").equals("hello tunnel"), "payload preserved");
    }

    static void splitAcrossReads() throws Exception {
        byte[] p = new byte[100];
        for (int i = 0; i < p.length; i++) p[i] = (byte) i;
        byte[] f = TunnelProtocol.encode(TunnelProtocol.CH_FE36, p);
        // Fragmented feed: slice each segment into a fresh array; decoder keeps state across reads.
        TunnelProtocol.Decoder d2 = new TunnelProtocol.Decoder();
        int off = 0, frames = 0;
        int[] cuts = {1, 1, 2, 3, 7, 11, 30};
        for (int c : cuts) {
            if (off >= f.length) break;
            int n = Math.min(c, f.length - off);
            byte[] seg = new byte[n];
            System.arraycopy(f, off, seg, 0, n);
            frames += d2.feed(seg, n).size();
            off += n;
        }
        if (off < f.length) {
            byte[] seg = new byte[f.length - off];
            System.arraycopy(f, off, seg, 0, seg.length);
            frames += d2.feed(seg, seg.length).size();
        }
        check(frames == 1, "one frame after fragmentation, got " + frames);
    }

    static void coalescedFrames() throws Exception {
        byte[] a = TunnelProtocol.encode(TunnelProtocol.CH_CONTROL, "ch=4".getBytes("US-ASCII"));
        byte[] b = TunnelProtocol.encode(TunnelProtocol.CH_FE35, new byte[]{1, 2, 3});
        byte[] c = TunnelProtocol.encode(TunnelProtocol.CH_FE36, new byte[]{9, 8});
        byte[] all = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        System.arraycopy(c, 0, all, a.length + b.length, c.length);
        TunnelProtocol.Decoder d = new TunnelProtocol.Decoder();
        List<TunnelProtocol.Frame> out = d.feed(all, all.length);
        check(out.size() == 3, "three coalesced frames, got " + out.size());
        check(out.get(0).channel == TunnelProtocol.CH_CONTROL, "frame0 channel");
        check(out.get(1).channel == TunnelProtocol.CH_FE35, "frame1 channel");
        check(out.get(2).channel == TunnelProtocol.CH_FE36, "frame2 channel");
    }

    static void allChannels() throws Exception {
        for (int ch = 0; ch <= 2; ch++) {
            byte[] f = TunnelProtocol.encode(ch, new byte[]{42});
            TunnelProtocol.Decoder d = new TunnelProtocol.Decoder();
            check(d.feed(f, f.length).get(0).channel == ch, "channel " + ch);
        }
    }

    static void emptyPayload() throws Exception {
        byte[] f = TunnelProtocol.encode(TunnelProtocol.CH_CONTROL, new byte[0]);
        TunnelProtocol.Decoder d = new TunnelProtocol.Decoder();
        List<TunnelProtocol.Frame> out = d.feed(f, f.length);
        check(out.size() == 1 && out.get(0).payload.length == 0, "empty payload");
    }

    static void maxPayload() throws Exception {
        byte[] p = new byte[TunnelProtocol.MAX_PAYLOAD];
        new Random(1).nextBytes(p);
        byte[] f = TunnelProtocol.encode(TunnelProtocol.CH_FE36, p);
        TunnelProtocol.Decoder d = new TunnelProtocol.Decoder();
        List<TunnelProtocol.Frame> out = d.feed(f, f.length);
        check(out.size() == 1 && out.get(0).payload.length == p.length, "max payload");
        for (int i = 0; i < p.length; i++) check(out.get(0).payload[i] == p[i], "max payload byte " + i);
    }

    static void badMagic() throws Exception {
        byte[] f = TunnelProtocol.encode(TunnelProtocol.CH_FE35, new byte[]{1});
        f[0] = 0x00;
        TunnelProtocol.Decoder d = new TunnelProtocol.Decoder();
        boolean threw = false;
        try { d.feed(f, f.length); } catch (IOException e) { threw = true; }
        check(threw, "bad magic rejected");
    }

    static void badCrc() throws Exception {
        byte[] f = TunnelProtocol.encode(TunnelProtocol.CH_FE35, new byte[]{1, 2, 3});
        f[f.length - 1] ^= 0x01;
        TunnelProtocol.Decoder d = new TunnelProtocol.Decoder();
        boolean threw = false;
        try { d.feed(f, f.length); } catch (IOException e) { threw = true; }
        check(threw, "bad crc rejected");
    }

    static void oversize() throws Exception {
        boolean threw = false;
        try { TunnelProtocol.encode(TunnelProtocol.CH_FE36, new byte[TunnelProtocol.MAX_PAYLOAD + 1]); }
        catch (IOException e) { threw = true; }
        check(threw, "oversize encode rejected");
    }

    static void readWriteStream() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        TunnelProtocol.writeFrame(bos, TunnelProtocol.CH_FE36, new byte[]{7, 6, 5, 4});
        TunnelProtocol.writeFrame(bos, TunnelProtocol.CH_FE35, new byte[]{3, 2, 1});
        ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
        TunnelProtocol.Frame a = TunnelProtocol.readFrame(bis);
        TunnelProtocol.Frame b = TunnelProtocol.readFrame(bis);
        check(a.channel == TunnelProtocol.CH_FE36 && a.payload.length == 4, "stream frame a");
        check(b.channel == TunnelProtocol.CH_FE35 && b.payload[0] == 3, "stream frame b");
    }

    static void randomFuzz() throws Exception {
        Random r = new Random(7);
        for (int iter = 0; iter < 200; iter++) {
            int ch = r.nextInt(3);
            int len = r.nextInt(TunnelProtocol.MAX_PAYLOAD + 1);
            byte[] p = new byte[len];
            r.nextBytes(p);
            byte[] f = TunnelProtocol.encode(ch, p);
            TunnelProtocol.Decoder d = new TunnelProtocol.Decoder();
            int off = 0;
            int frames = 0;
            while (off < f.length) {
                int n = 1 + r.nextInt(37);
                n = Math.min(n, f.length - off);
                byte[] seg = new byte[n];
                System.arraycopy(f, off, seg, 0, n);
                frames += d.feed(seg, n).size();
                off += n;
            }
            check(frames == 1, "fuzz one frame iter " + iter + " got " + frames);
        }
    }

    /** Wrap real NearbyWire frames in tunnel frames, fragment them, and prove the
     *  decoded bytes still parse into the exact same frames. */
    static void protocolBytesSurviveTunnel(String vectorPath) throws Exception {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get(vectorPath));
        int checked = 0;
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;
            byte[] wire = hex(parts[1]);
            // FE35 link, wrap in a tunnel frame, split into odd chunks, reassemble via Decoder.
            byte[] framed = TunnelProtocol.encode(TunnelProtocol.CH_FE35, wire);
            TunnelProtocol.Decoder d = new TunnelProtocol.Decoder();
            ByteArrayOutputStream recovered = new ByteArrayOutputStream();
            int off = 0;
            while (off < framed.length) {
                int n = Math.min(5, framed.length - off);
                byte[] seg = new byte[n];
                System.arraycopy(framed, off, seg, 0, n);
                for (TunnelProtocol.Frame f : d.feed(seg, n)) recovered.write(f.payload);
                off += n;
            }
            // The tunnel must deliver the bytes bit-for-bit; whether a given vector is a
            // valid NearbyWire frame is a separate concern (this file includes rejecting cases).
            check(java.util.Arrays.equals(wire, recovered.toByteArray()), "wire bytes intact for " + parts[0]);
            checked++;
        }
        check(checked > 0, "at least one real vector checked");
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
