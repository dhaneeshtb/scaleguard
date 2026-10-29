import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.LongAdder;

/** UDP load generator: N threads, each keeps a window of queries in flight, counts replies/sec. */
public class LoadGen {
    public static void main(String[] a) throws Exception {
        String name = a[0]; int type = Integer.parseInt(a[1]); int threads = Integer.parseInt(a[2]); int secs = Integer.parseInt(a[3]);
        int window = 64;
        LongAdder sent = new LongAdder(), recv = new LongAdder();
        long end = System.nanoTime() + secs * 1_000_000_000L;
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                try (DatagramChannel ch = DatagramChannel.open()) {
                    ch.connect(new InetSocketAddress("127.0.0.1", 5353));
                    ch.socket().setSoTimeout(200);
                    ch.configureBlocking(false);
                    ByteBuffer out = ByteBuffer.wrap(query(name, type)), in = ByteBuffer.allocate(4096);
                    int inflight = 0; long lastRecv = System.nanoTime();
                    while (System.nanoTime() < end) {
                        while (inflight < window) { out.rewind(); if (ch.write(out) > 0) { inflight++; sent.increment(); } else break; }
                        in.clear();
                        if (ch.read(in) > 0) { inflight--; recv.increment(); lastRecv = System.nanoTime(); }
                        else if (System.nanoTime() - lastRecv > 100_000_000L) { inflight = 0; lastRecv = System.nanoTime(); } // assume drops
                    }
                } catch (Exception e) { e.printStackTrace(); }
            });
            ts[t].start();
        }
        long start = System.nanoTime();
        for (Thread t : ts) t.join();
        double el = (System.nanoTime() - start) / 1e9;
        System.out.printf("%s type=%d threads=%d: %.0f answers/sec (%d sent, %d answered)%n", name, type, threads, recv.sum() / el, sent.sum(), recv.sum());
    }
    static byte[] query(String name, int type) {
        ByteBuffer b = ByteBuffer.allocate(512);
        b.putShort((short) 0x1234).putShort((short) 0x0100).putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String l : name.split("\\.")) { if (l.isEmpty()) continue; b.put((byte) l.length()).put(l.getBytes()); }
        b.put((byte) 0).putShort((short) type).putShort((short) 1);
        byte[] r = new byte[b.position()]; b.flip(); b.get(r); return r;
    }
}
