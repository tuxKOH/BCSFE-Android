package io.github.tuxkoh.bcsfe;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class UpdateCheckerTest {
    @Test public void detectsNewerReleaseTags(){assertTrue(UpdateChecker.isNewer("v0.2.0","0.1.9"));assertTrue(UpdateChecker.isNewer("1.0.1","1.0"));}
    @Test public void rejectsSameOlderAndInvalidTags(){assertFalse(UpdateChecker.isNewer("v0.1.0","0.1.0"));assertFalse(UpdateChecker.isNewer("0.0.9","0.1.0"));assertFalse(UpdateChecker.isNewer("latest","0.1.0"));}
    @Test public void parsesReleaseBodyForUpdateLog(){
        UpdateChecker.Result result=UpdateChecker.parseRelease("{\"tag_name\":\"v0.2.0\",\"html_url\":\"https://github.com/tuxKOH/BCSFE-Android/releases/tag/v0.2.0\",\"body\":\"- Regional cat lists\\n- JP 15.1.1 import warning\"}","0.1.9");
        assertEquals("v0.2.0",result.version);assertEquals("- Regional cat lists\n- JP 15.1.1 import warning",result.body);
    }
    @Test public void ignoresReleaseBodyWhenReleaseIsNotNewOrUrlIsNotGithub(){
        assertNull(UpdateChecker.parseRelease("{\"tag_name\":\"v0.1.9\",\"html_url\":\"https://github.com/tuxKOH/BCSFE-Android/releases/tag/v0.1.9\",\"body\":\"notes\"}","0.1.9"));
        assertNull(UpdateChecker.parseRelease("{\"tag_name\":\"v0.2.0\",\"html_url\":\"https://example.invalid/release\",\"body\":\"notes\"}","0.1.9"));
    }
}
