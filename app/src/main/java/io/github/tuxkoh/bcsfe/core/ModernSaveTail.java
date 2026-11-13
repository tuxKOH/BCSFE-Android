package io.github.tuxkoh.bcsfe.core;

import java.io.ByteArrayOutputStream;

/** Native records after the 15.3 marker, absent from the legacy bundled templates.
 * Verified against JP 16.0.1/16.0.3 and TW 15.7.0 native serializers.
 * Defaults contain no account identifiers, device values or timestamps.
 */
final class ModernSaveTail {
    private ModernSaveTail() {}

    static byte[] emptyContinuation(int version) {
        if (version < 150500 || version > 160003)
            throw new IllegalArgumentException("Unverified modern save trailer version");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Markers terminate records. After marker 15.3 comes 15.4's boolean,
        // int, double and boolean; these are scalar fields, not a string.
        zeros(out, 14);
        integer(out, 150400);
        if (version < 150600) return out.toByteArray();
        integer(out, 0); // int list
        integer(out, 0); // int -> int dictionary
        // 15.6's compact chapters have four stars and twelve stages; 16.0
        // expands the initial chapter set from one to five. Each star has three
        // progress flags, a short count and short clear counts. Native 70c570.
        int chapters = version >= 160000 ? 5 : 1;
        shortValue(out, chapters);
        for (int chapter = 0; chapter < chapters; chapter++) {
            out.write(0); out.write(4);
            for (int star = 0; star < 4; star++) {
                zeros(out, 3); shortValue(out, 12); zeros(out, 24);
            }
        }
        integer(out, 0); integer(out, 0); // two variable dictionaries
        out.write(0);
        shortValue(out, 1); shortValue(out, 0);
        integer(out, 0); shortValue(out, 0); shortValue(out, 0);
        integer(out, 150600);
        if (version >= 150700) {
            zeros(out, version >= 160000 ? 21 : 16);
            integer(out, 150700);
        }
        if (version >= 160000) {
            zeros(out, 23); // 16.0's empty native records and final double
            integer(out, 160000);
        }
        if (version >= 160003) {
            // JP 16.0.3 adds two short-counted int lists (native 0x6b1448).
            shortValue(out, 0); shortValue(out, 0);
            integer(out, 160003);
        }
        return out.toByteArray();
    }

    static byte[] version160003Defaults() {
        return new byte[]{0, 0, 0, 0, 3, 0x71, 2, 0};
    }

    /** Validate the terminal record, including nonempty lists; reject ambiguous anchors. */
    static boolean hasVersion160003Record(byte[] bytes, int end) {
        if (end < 12 || integerAt(bytes, end - 4) != 160003) return false;
        int matches = 0;
        for (int start = Math.max(0, end - 262148); start <= end - 12; start++) {
            if (integerAt(bytes, start) != 160000) continue;
            int first = shortAt(bytes, start + 4);
            if (first > 32767) continue;
            int secondOffset = start + 6 + first * 4;
            if (secondOffset > end - 6) continue;
            int second = shortAt(bytes, secondOffset);
            if (second <= 32767 && secondOffset + 2 + second * 4 == end - 4) matches++;
        }
        if (matches > 1) throw new IllegalStateException("Ambiguous 16.0.3 save trailer");
        return matches == 1;
    }

    private static int shortAt(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }
    private static int integerAt(byte[] bytes, int offset) {
        return shortAt(bytes, offset) | (shortAt(bytes, offset + 2) << 16);
    }

    private static void zeros(ByteArrayOutputStream out, int count) {
        for (int i = 0; i < count; i++) out.write(0);
    }
    private static void shortValue(ByteArrayOutputStream out, int value) {
        out.write(value & 255); out.write((value >>> 8) & 255);
    }
    private static void integer(ByteArrayOutputStream out, int value) {
        shortValue(out, value); shortValue(out, value >>> 16);
    }
}
