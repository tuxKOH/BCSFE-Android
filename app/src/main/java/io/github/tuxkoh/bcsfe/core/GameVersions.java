package io.github.tuxkoh.bcsfe.core;

/** Shared 15.5 save layout, bounded by the verified regional BCData revisions. */
public final class GameVersions {
    private GameVersions() {}

    public static int latest(SaveDocument.Region region) {
        if (region == null) throw new IllegalArgumentException("Missing region");
        return UpstreamGameData.forRegion(region).version;
    }

    public static boolean supported(SaveDocument.Region region, int version) {
        return version >= 150500 && version <= latest(region);
    }

    public static int[] conversionTargets(SaveDocument.Region region) {
        int[] candidates={150703,150702,150701,150700,150601,150600,150501,150500,
                150400,150300,150200,150100,150000,140700,140500,140300,140000};
        int latest=latest(region);
        return java.util.stream.IntStream.concat(java.util.stream.IntStream.of(latest),
                java.util.Arrays.stream(candidates).filter(version->version<latest)).toArray();
    }
}
