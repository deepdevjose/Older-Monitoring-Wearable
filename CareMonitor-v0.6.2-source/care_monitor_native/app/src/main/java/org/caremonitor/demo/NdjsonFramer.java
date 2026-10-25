package org.caremonitor.demo;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded byte-oriented framing for newline-delimited UTF-8 JSON over BLE. */
public final class NdjsonFramer {
    public static final int MAX_LINE_BYTES = 16 * 1024;

    private final byte[] pending;
    private int length;
    private boolean discarding;

    public NdjsonFramer() {
        this(MAX_LINE_BYTES);
    }

    public NdjsonFramer(int maximumLineBytes) {
        if (maximumLineBytes < 1) throw new IllegalArgumentException("The line limit must be positive");
        pending = new byte[maximumLineBytes];
    }

    /**
     * Returns complete non-empty lines only. The size limit excludes LF and
     * includes an optional CR. Invalid UTF-8 and oversized records are dropped
     * in their entirety; the next LF establishes a new record boundary.
     * JSON syntax validation belongs to the caller.
     */
    public synchronized List<String> feed(byte[] bytes) {
        List<String> lines = new ArrayList<String>();
        if (bytes == null) return lines;
        for (byte value : bytes) {
            if (value == '\n') {
                if (!discarding && length > 0) {
                    int textLength = pending[length - 1] == '\r' ? length - 1 : length;
                    if (textLength > 0) {
                        try {
                            String text = StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(pending, 0, textLength)).toString();
                            lines.add(text);
                        } catch (CharacterCodingException ignored) {
                            // A replacement character could alter the JSON payload.
                        }
                    }
                }
                length = 0;
                discarding = false;
            } else if (!discarding) {
                if (length == pending.length) {
                    length = 0;
                    discarding = true;
                } else {
                    pending[length++] = value;
                }
            }
        }
        return lines;
    }

    /** Call when the transport session ends or starts; never join two sessions. */
    public synchronized void reset() {
        length = 0;
        discarding = false;
    }
}
