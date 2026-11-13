package io.github.tuxkoh.bcsfe.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class MedalListTest {
    @Test public void fullMedalListSurvivesEditsBeforeAndWithinCachedTable() throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()) {
            SaveDocument save=SaveDocument.fromNewSaveTemplate(Files.readAllBytes(Path.of("src/main/assets/new_saves/"+region.code()+".save")));
            for(int id=0;id<127;id++)save.addMedal(id);
            int[] expected=new int[127];for(int i=0;i<127;i++)expected[i]=i;
            assertArrayEquals(expected,save.medalIds());
            // Scheme entries precede medals and insert bytes before the cached base.
            save.addSchemeItem(3);
            save.grantGoldPass(123,1700000000L,30);
            assertArrayEquals(expected,save.medalIds());
            save.removeMedal(63);save.addMedal(63);save.addTalentOrb(77,2);
            int[] moved=save.medalIds();assertEquals(127,moved.length);assertEquals(63,moved[126]);
            SaveDocument reopened=SaveDocument.open(save.toBytes());assertArrayEquals(moved,reopened.medalIds());
            moved[0]=999;assertEquals(0,save.medalIds()[0]);
            assertTrue(reopened.checksumValid());
        }
    }
}
