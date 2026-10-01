package io.github.tuxkoh.bcsfe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SeedTargetAssessmentTest {
    private JSONObject pool() throws Exception {
        JSONArray supers = new JSONArray();
        supers.put(448);
        for (int i = 0; i < 28; i++) supers.put(1000 + i);
        return new JSONObject().put("rates", new JSONObject()
                .put("rare", 6970).put("superRare", 2500).put("uber", 500).put("legend", 30))
                .put("slots", new JSONObject().put("rare", new JSONArray(List.of(1, 1, 2)))
                        .put("superRare", supers).put("uber", new JSONArray(List.of(3)))
                        .put("legend", new JSONArray(List.of(4))));
    }

    @Test public void triple448IsAllowedAndFive448NeedsOnlyAWarning() throws Exception {
        assertEquals(SeedTargetAssessment.Kind.READY,
                SeedTargetAssessment.assess(pool(), List.of(448, 448, 448)).kind);
        SeedTargetAssessment five = SeedTargetAssessment.assess(pool(), List.of(448, 448, 448, 448, 448));
        assertEquals(SeedTargetAssessment.Kind.DIFFICULT, five.kind);
        assertTrue(five.expectedMatches > 0 && five.expectedMatches < 1);
    }

    @Test public void consecutiveRareCopiesAreImpossibleEvenWithDuplicateSlots() throws Exception {
        assertEquals(SeedTargetAssessment.Kind.IMPOSSIBLE,
                SeedTargetAssessment.assess(pool(), List.of(1, 1)).kind);
        assertEquals(SeedTargetAssessment.Kind.READY,
                SeedTargetAssessment.assess(pool(), List.of(1, -1, 1)).kind);
    }

    @Test public void absentAndZeroRateCatsAreRejected() throws Exception {
        assertEquals(SeedTargetAssessment.Kind.IMPOSSIBLE,
                SeedTargetAssessment.assess(pool(), List.of(9999)).kind);
        JSONObject pool = pool();
        pool.getJSONObject("rates").put("uber", 0);
        assertEquals(SeedTargetAssessment.Kind.IMPOSSIBLE,
                SeedTargetAssessment.assess(pool, List.of(3)).kind);
    }

    @Test public void mixedRarityCatsAreNotMistakenForRareOnlyCats() throws Exception {
        JSONObject pool = pool();
        pool.getJSONObject("slots").getJSONArray("superRare").put(1);
        assertEquals(SeedTargetAssessment.Kind.READY,
                SeedTargetAssessment.assess(pool, List.of(1, 1)).kind);
        assertEquals(SeedTargetAssessment.Kind.READY,
                SeedTargetAssessment.assess(pool, List.of(-1, -1, -1)).kind);
    }
}
