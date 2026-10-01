package io.github.tuxkoh.bcsfe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cheap local checks only. Probability estimates are NOT proofs of non-existence. */
final class SeedTargetAssessment {
    enum Kind { READY, DIFFICULT, IMPOSSIBLE }
    final Kind kind;
    final double expectedMatches;

    private SeedTargetAssessment(Kind kind, double expectedMatches) {
        this.kind = kind;
        this.expectedMatches = expectedMatches;
    }

    static SeedTargetAssessment assess(JSONObject pool, List<Integer> targets) {
        JSONObject rates = pool.optJSONObject("rates"), slots = pool.optJSONObject("slots");
        if (rates == null || slots == null) return new SeedTargetAssessment(Kind.IMPOSSIBLE, 0);
        String[] buckets = {"rare", "superRare", "uber", "legend"};
        Map<Integer, Double> probabilities = new HashMap<>();
        Map<Integer, Boolean> rareOnly = new HashMap<>();
        for (String bucket : buckets) {
            JSONArray cats = slots.optJSONArray(bucket);
            if (cats == null || cats.length() == 0 || rates.optInt(bucket) <= 0) continue;
            double perSlot = rates.optInt(bucket) / 10000.0 / cats.length();
            for (int i = 0; i < cats.length(); i++) {
                int id = cats.optInt(i, -1);
                probabilities.put(id, probabilities.getOrDefault(id, 0.0) + perSlot);
                rareOnly.put(id, rareOnly.getOrDefault(id, true) && "rare".equals(bucket));
            }
        }
        Map<Integer, Integer> repeats = new HashMap<>();
        double probability = 1;
        int maximumRepeat = 0;
        for (int i = 0; i < targets.size(); i++) {
            int cat = targets.get(i);
            if (cat == -1) continue;
            double chance = probabilities.getOrDefault(cat, 0.0);
            if (chance <= 0) return new SeedTargetAssessment(Kind.IMPOSSIBLE, 0);
            // Consecutive copies of a rare-only cat must reroll into a different cat.
            if (i > 0 && targets.get(i - 1) == cat && rareOnly.getOrDefault(cat, false)) {
                JSONArray rare = slots.optJSONArray("rare");
                for (int index = 0; rare != null && index < rare.length(); index++) {
                    if (rare.optInt(index, cat) != cat) return new SeedTargetAssessment(Kind.IMPOSSIBLE, 0);
                }
            }
            probability *= chance;
            int count = repeats.getOrDefault(cat, 0) + 1;
            repeats.put(cat, count);
            maximumRepeat = Math.max(maximumRepeat, count);
        }
        double expected = probability * 4294967296.0;
        return new SeedTargetAssessment(expected < 1 || maximumRepeat >= 5 ? Kind.DIFFICULT : Kind.READY, expected);
    }
}
