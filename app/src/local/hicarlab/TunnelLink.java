package local.hicarlab;

import android.bluetooth.BluetoothSocket;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime half of the relay tunnel. A single classic-Bluetooth RFCOMM connection is
 * multiplexed into three logical links (control / FE35 / FE36) using TunnelProtocol
 * framing. Each logical link exposes an InputStream/OutputStream pair so the existing
 * protocol stack (NearbyWire / AuthBusiness / NetworkResource) can consume tunnel bytes
 * exactly as it consumes a local BluetoothSocket stream.
 *
 * Roles:
 *   - head unit (relay client of the protocol): open a server RFCOMM on TUNNEL_UUID,
 *     accept the relay phone, then create a Mux over that socket.
 *   - relay phone: connect to the head unit's TUNNEL_UUID service, create a Mux, open
 *     FE35/FE36 links, and bridge each to the phone-facing RFCOMM sockets.
 */
final class TunnelLink {
    /** Private service UUID for the relay<->head-unit tunnel (not a HiCar UUID). */
    static final java.util.UUID TUNNEL_UUID = java.util.UUID.fromString("7d3a1c62-9b14-4e08-8f2e-3a6c5d0e91b7");
    static final String TUNNEL_NAME = "HiCarLab Relay Tunnel";

    private TunnelLink() {}

    /** Uniform view over a byte stream, whether from a local RFCOMM socket or a tunnel link. */
    interface WireLink extends Closeable {
        InputStream in() throws IOException;
        OutputStream out() throws IOException;
        /** Logical peer identifier for the single-MAC admission filter; never used for auth. */
        String peer();
    }

    /** Wraps a real Bluetooth socket as a WireLink (normal, non-relay mode). */
    static final class BtLink implements WireLink {
        private final BluetoothSocket socket;
        BtLink(BluetoothSocket socket) { this.socket = socket; }
        public InputStream in() throws IOException { return socket.getInputStream(); }
        public OutputStream out() throws IOException { return socket.getOutputStream(); }
        public String peer() { try { return socket.getRemoteDevice().getAddress(); } catch (RuntimeException e) { return "bt"; } }
        public void close() throws IOException { socket.close(); }
    }

    /** InputStream that drains a queue of framed payloads, with graceful EOF and timeouts. */
    static final class QueueInputStream extends InputStream {
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean eof = new AtomicBoolean(false);
        private byte[] cur;
        private int pos;

        void deliver(byte[] payload) {
            if (eof.get()) return;
            if (payload.length == 0) return;
            queue.add(payload);
        }
        void end() { eof.set(true); }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 255);
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            while (cur == null || pos >= cur.length) {
                if (eof.get() && queue.isEmpty()) return -1;
                byte[] next;
                try { next = queue.poll(500, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("tunnel interrupted"); }
                if (next == null) continue;
                cur = next; pos = 0;
            }
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n);
            pos += n;
            return n;
        }
        @Override public int available() {
            int have = (cur == null ? 0 : cur.length - pos);
            for (byte[] q : queue) have += q.length;
            return have;
        }
    }

    /** One logical link carried over the tunnel's channel number. */
    static final class Link implements WireLink {
        final int channel;
        final QueueInputStream rx = new QueueInputStream();
        final OutputStream physical;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final String peer;

        Link(int channel, OutputStream physical, String peer) {
            this.channel = channel; this.physical = physical; this.peer = peer;
        }
        void deliver(byte[] payload) { rx.deliver(payload); }
        void end() { rx.end(); }

        public InputStream in() { return rx; }
        public OutputStream out() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (closed.get()) throw new IOException("tunnel link closed");
                    TunnelProtocol.writeFrame(physical, channel, java.util.Arrays.copyOfRange(b, off, off + len));
                }
                @Override public void flush() throws IOException { physical.flush(); }
            };
        }
        public String peer() { return peer; }
        public void close() { closed.set(true); rx.end(); }
    }

    /**
     * Multiplexer over one physical tunnel stream. A background reader decodes frames and
     * routes each payload to the matching logical Link. Control frames (channel 0) are kept
     * as key=value text and exposed through control()/awaitControl() for metadata such as
     * the relay's FE36 RFCOMM channel number.
     */
    static final class Mux implements WireLink {
        private final InputStream in;
        private final OutputStream out;
        private final Map<Integer, Link> links = new ConcurrentHashMap<>();
        private final Map<String, String> control = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final String peer;
        private Thread reader;

        Mux(InputStream in, OutputStream out, String peer) {
            this.in = in; this.out = out; this.peer = peer;
        }
        public InputStream in() { return in; }
        public OutputStream out() { return out; }
        public String peer() { return peer; }

        Link open(int channel) {
            Link link = new Link(channel, out, peer);
            links.put(channel, link);
            return link;
        }

        void sendControl(String key, String value) throws IOException {
            TunnelProtocol.writeFrame(out, TunnelProtocol.CH_CONTROL, (key + "=" + value).getBytes("US-ASCII"));
        }
        String control(String key) { return control.get(key); }
        String awaitControl(String key, long millis) {
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                String v = control.get(key);
                if (v != null) return v;
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            }
            return null;
        }

        void start(String name) {
            reader = new Thread(() -> {
                try {
                    TunnelProtocol.Decoder d = new TunnelProtocol.Decoder();
                    byte[] buf = new byte[4096];
                    while (!closed.get()) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        for (TunnelProtocol.Frame f : d.feed(buf, n)) {
                            if (f.channel == TunnelProtocol.CH_CONTROL) {
                                String text = new String(f.payload, "US-ASCII");
                                int eq = text.indexOf('=');
                                if (eq > 0) control.put(text.substring(0, eq), text.substring(eq + 1));
                            } else {
                                Link link = links.get(f.channel);
                                if (link != null) link.deliver(f.payload);
                            }
                        }
                    }
                } catch (IOException ignored) { }
                finally {
                    for (Link l : links.values()) l.end();
                }
            }, name);
            reader.setDaemon(true);
            reader.start();
        }

        public void close() {
            closed.set(true);
            for (Link l : links.values()) l.close();
            try { in.close(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
        }
    }
}
