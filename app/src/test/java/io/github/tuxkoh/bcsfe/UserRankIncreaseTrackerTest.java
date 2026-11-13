package io.github.tuxkoh.bcsfe;

import org.junit.Test;
import static org.junit.Assert.*;

public class UserRankIncreaseTrackerTest {
    @Test public void cumulativeEditsPromptOnlyAboveOneThousand() {
        UserRankIncreaseTracker tracker = new UserRankIncreaseTracker(5000);
        assertFalse(tracker.record(5600));
        assertFalse(tracker.record(6000));
        assertTrue(tracker.record(6001));
        assertFalse(tracker.record(6001));
        assertFalse(tracker.record(6500));
        assertFalse(tracker.record(7001));
        assertTrue(tracker.record(7002));
    }

    @Test public void decreasesAndAnotherDocumentDoNotReuseAnUnrelatedBaseline() {
        UserRankIncreaseTracker tracker = new UserRankIncreaseTracker(5000);
        assertFalse(tracker.record(4000));
        assertFalse(tracker.record(5000));
        assertTrue(tracker.record(5001));
        UserRankIncreaseTracker anotherDocument = new UserRankIncreaseTracker(25000);
        assertFalse(anotherDocument.record(25000));
        assertFalse(anotherDocument.record(26000));
        assertTrue(anotherDocument.record(26001));
    }
}
