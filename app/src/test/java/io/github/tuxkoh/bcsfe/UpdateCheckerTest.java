package io.github.tuxkoh.bcsfe;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UpdateCheckerTest {
    @Test public void detectsNewerReleaseTags(){assertTrue(UpdateChecker.isNewer("v0.2.0","0.1.9"));assertTrue(UpdateChecker.isNewer("1.0.1","1.0"));}
    @Test public void rejectsSameOlderAndInvalidTags(){assertFalse(UpdateChecker.isNewer("v0.1.0","0.1.0"));assertFalse(UpdateChecker.isNewer("0.0.9","0.1.0"));assertFalse(UpdateChecker.isNewer("latest","0.1.0"));}
}
