package local.hicarlab;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire framing for the relays' transparent Bluetooth tunnel.
 *
 * A single classic-Bluetooth RFCOMM connection carries three logical channels:
 *   0 = control metadata (small key=value text frames, e.g. the relay's FE36 RFCOMM channel)
 *   1 = FE35 control link bytes (NearbyWire frames)
 *   2 = FE36 business link bytes (HiChain / AuthBusiness bytes)
 *
 * Frame layout (all integers big-endian):
 *   [0x5A][ch:1][len:2][payload:len][crc16:2]
 * crc16 is CRC-CCITT (poly 0x1021, init 0xFFFF) over ch|len|payload.
 *
 * This class deliberately uses only java.io so the framing can be unit-tested on the
 * host without an Android device. It does not perform authentication itself; it only
 * moves opaque bytes between the phone-facing RFCOMM sockets and the head unit stack.
 */
final class TunnelProtocol {
    static final int CH_CONTROL = 0, CH_FE35 = 1, CH_FE36 = 2;
    static final int MAX_PAYLOAD = 8192;
    private static final int MAGIC = 0x5A;

    private TunnelProtocol() {}

    /** One decoded frame; payload is a defensive copy. */
    static final class Frame {
        final int channel;
        final byte[] payload;
        Frame(int channel, byte[] payload) { this.channel = channel; this.payload = payload; }
    }

    static byte[] encode(int channel, byte[] payload, int off, int len) throws IOException {
        if (channel < CH_CONTROL || channel > CH_FE36) throw new IOException("tunnel channel");
        if (len < 0 || len > MAX_PAYLOAD) throw new IOException("tunnel payload size " + len);
        ByteArrayOutputStream o = new ByteArrayOutputStream(len + 7);
        o.write(MAGIC);
        o.write(channel);
        o.write((len >>> 8) & 255);
        o.write(len & 255);
        o.write(payload, off, len);
        int crc = crc(channel, payload, off, len);
        o.write((crc >>> 8) & 255);
        o.write(crc & 255);
        return o.toByteArray();
    }

    static byte[] encode(int channel, byte[] payload) throws IOException {
        return encode(channel, payload, 0, payload.length);
    }

    /**
     * Streaming decoder. Call feed() with whatever a socket read returns; completed frames
     * accumulate in the returned list. Partial frames are buffered across calls. A bad
     * magic or CRC throws IOException so the caller can drop the tunnel connection rather
     * than feed corrupted bytes into HiChain.
     */
    static final class Decoder {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        List<Frame> feed(byte[] data, int len) throws IOException {
            if (data == null || len <= 0) return new ArrayList<>();
            buf.write(data, 0, len);
            List<Frame> out = new ArrayList<>();
            while (true) {
                byte[] all = buf.toByteArray();
                if (all.length < 5) break;
                if ((all[0] & 255) != MAGIC) throw new IOException("tunnel magic");
                int channel = all[1] & 255;
                if (channel > CH_FE36) throw new IOException("tunnel channel " + channel);
                int size = ((all[2] & 255) << 8) | (all[3] & 255);
                if (size > MAX_PAYLOAD) throw new IOException("tunnel size " + size);
                int total = 4 + size + 2;
                if (all.length < total) break;
                int want = ((all[4 + size] & 255) << 8) | (all[5 + size] & 255);
                int got = crc(channel, all, 4, size);
                if (want != got) throw new IOException("tunnel crc");
                byte[] payload = new byte[size];
                System.arraycopy(all, 4, payload, 0, size);
                out.add(new Frame(channel, payload));
                byte[] rest = new byte[all.length - total];
                System.arraycopy(all, total, rest, 0, rest.length);
                buf.reset();
                buf.write(rest, 0, rest.length);
            }
            if (buf.size() > MAX_PAYLOAD + 64) throw new IOException("tunnel buffer overflow");
            return out;
        }
        int buffered() { return buf.size(); }
    }

    /** One frame in, one frame out; used by the small request/response control channel. */
    static Frame readFrame(InputStream in) throws IOException {
        int magic = in.read();
        if (magic < 0) throw new EOFException("tunnel eof");
        if (magic != MAGIC) throw new IOException("tunnel magic");
        int channel = in.read();
        if (channel < 0) throw new EOFException("tunnel eof");
        if (channel > CH_FE36) throw new IOException("tunnel channel " + channel);
        int h = in.read(), l = in.read();
        if (h < 0 || l < 0) throw new EOFException("tunnel eof");
        int size = (h << 8) | l;
        if (size > MAX_PAYLOAD) throw new IOException("tunnel size " + size);
        byte[] payload = new byte[size];
        int read = 0;
        while (read < size) {
            int n = in.read(payload, read, size - read);
            if (n < 0) throw new EOFException("tunnel eof");
            read += n;
        }
        int ch = in.read(), cl = in.read();
        if (ch < 0 || cl < 0) throw new EOFException("tunnel eof");
        int want = (ch << 8) | cl;
        if (want != crc(channel, payload, 0, size)) throw new IOException("tunnel crc");
        return new Frame(channel, payload);
    }

    static void writeFrame(OutputStream out, int channel, byte[] payload) throws IOException {
        byte[] frame = encode(channel, payload);
        out.write(frame);
        out.flush();
    }

    private static int crc(int channel, byte[] data, int off, int len) {
        int c = 0xFFFF;
        c = step(c, channel);
        c = step(c, (len >>> 8) & 255);
        c = step(c, len & 255);
        for (int i = 0; i < len; i++) c = step(c, data[off + i] & 255);
        return c & 0xFFFF;
    }
    private static int step(int crc, int b) {
        crc ^= b << 8;
        for (int i = 0; i < 8; i++) {
            if ((crc & 0x8000) != 0) crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
            else crc = (crc << 1) & 0xFFFF;
        }
        return crc;
    }
}
