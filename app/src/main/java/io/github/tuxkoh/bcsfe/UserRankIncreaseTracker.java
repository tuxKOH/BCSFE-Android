package io.github.tuxkoh.bcsfe;

/** Tracks cumulative rank gains since opening a document or the last notice. */
final class UserRankIncreaseTracker {
    private int baseline;

    UserRankIncreaseTracker(int initialRank) { baseline = initialRank; }

    boolean record(int currentRank) {
        baseline = Math.min(baseline, currentRank);
        if ((long) currentRank - baseline <= 1000) return false;
        baseline = currentRank;
        return true;
    }
}
