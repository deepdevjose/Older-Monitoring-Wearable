package org.caremonitor.demo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Standalone JVM tests; no Bluetooth hardware or Android runtime required. */
public final class NdjsonFramerTest {
    private static int checked;

    public static void main(String[] args) {
        NdjsonFramer f = new NdjsonFramer();
        expect("incomplete is not a record", f.feed(bytes("{\"device_id\":")));
        expect("completion", f.feed(bytes("\"elder-demo-01\"}\n")), "{\"device_id\":\"elder-demo-01\"}");
        expect("coalesced records", f.feed(bytes("{\"a\":1}\n{\"b\":2}\n")), "{\"a\":1}", "{\"b\":2}");
        expect("CRLF and empty lines", f.feed(bytes("\n\r\n{\"x\":3}\r\n")), "{\"x\":3}");
        expect("null input", f.feed(null));
        expect("empty input", f.feed(new byte[0]));

        String unicode = "{\"name\":\"老人🫀\"}";
        byte[] utf8 = bytes(unicode + "\n");
        List<String> records = new ArrayList<String>();
        for (byte value : utf8) records.addAll(f.feed(new byte[]{value}));
        expect("UTF-8 characters split at every byte", records, unicode);
        for (int split = 0; split <= utf8.length; split++) {
            f.reset();
            records = new ArrayList<String>();
            records.addAll(f.feed(Arrays.copyOfRange(utf8, 0, split)));
            records.addAll(f.feed(Arrays.copyOfRange(utf8, split, utf8.length)));
            expect("UTF-8 split boundary " + split, records, unicode);
        }

        f.reset();
        expect("malformed UTF-8 is dropped and next line recovers", f.feed(new byte[]{'{', (byte) 0xc3, 0x28, '}', '\n', '{', '}', '\n'}), "{}");
        expect("truncated UTF-8 is dropped", f.feed(new byte[]{'{', (byte) 0xe2, (byte) 0x82, '\n'}));
        expect("lone continuation is dropped", f.feed(new byte[]{(byte) 0x80, '\n'}));
        expect("overlong UTF-8 is dropped", f.feed(new byte[]{(byte) 0xc0, (byte) 0xaf, '\n'}));
        expect("surrogate UTF-8 is dropped", f.feed(new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80, '\n'}));
        expect("valid next line", f.feed(bytes("{\"valid\":true}\n")), "{\"valid\":true}");

        byte[] maximum = new byte[NdjsonFramer.MAX_LINE_BYTES];
        Arrays.fill(maximum, (byte) 'x');
        expect("exact limit remains buffered", f.feed(maximum));
        expect("exact limit accepted at LF", f.feed(bytes("\n")), new String(maximum, StandardCharsets.UTF_8));
        expect("overflow prefix buffered", f.feed(maximum));
        expect("overflow discarded until LF", f.feed(bytes("x{\"false\":1}")));
        expect("overflow recovers only after LF", f.feed(bytes("\n{\"next\":2}\n")), "{\"next\":2}");

        NdjsonFramer small = new NdjsonFramer(4);
        expect("small limit oversized and recovery coalesced", small.feed(bytes("12345\n{}\n")), "{}");
        expect("CR counts toward byte limit", small.feed(bytes("1234\r\n")));
        expect("CRLF within byte limit", small.feed(bytes("123\r\n")), "123");
        expect("multibyte limit is bytes", small.feed(bytes("老人\n{}\n")), "{}");
        expect("complete multibyte within limit", small.feed(bytes("老\n")), "老");

        f.feed(bytes("{\"from_old_connection\":"));
        f.reset();
        expect("disconnect clears partial line", f.feed(bytes("{\"new_connection\":1}\n")), "{\"new_connection\":1}");
        small.feed(bytes("12345"));
        small.reset();
        expect("reconnect resets overflow discard state", small.feed(bytes("{}\n")), "{}");
        f.feed(new byte[]{(byte) 0xe2});
        f.reset();
        expect("reconnect cannot join split UTF-8 sessions", f.feed(new byte[]{(byte) 0x82, (byte) 0xac, '\n'}));
        expect("recovery after split-session UTF-8", f.feed(bytes("{}\n")), "{}");
        try {
            new NdjsonFramer(0);
            throw new AssertionError("zero limit should be rejected");
        } catch (IllegalArgumentException expected) { checked++; }
        System.out.println("PASS: " + checked + " NdjsonFramer checks");
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static void expect(String label, List<String> actual, String... expected) {
        checked++;
        if (!actual.equals(Arrays.asList(expected))) {
            throw new AssertionError(label + ": expected " + Arrays.toString(expected) + ", got " + actual);
        }
    }
}
