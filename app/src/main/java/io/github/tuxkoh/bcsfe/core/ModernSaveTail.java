package io.github.tuxkoh.bcsfe.core;

import java.io.ByteArrayOutputStream;

/** Native records after the 15.3 marker, absent from the legacy bundled templates.
 * Verified against game-written JP 16.0.1/TW 15.7.0 records and the native serializer.
 * Defaults contain no account identifiers, device values or timestamps.
 */
final class ModernSaveTail {
    private ModernSaveTail() {}

    static byte[] emptyContinuation(int version) {
        if (version < 150500 || version > 160001)
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
        return out.toByteArray();
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
