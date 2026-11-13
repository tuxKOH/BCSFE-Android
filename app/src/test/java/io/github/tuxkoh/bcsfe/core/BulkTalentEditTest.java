package io.github.tuxkoh.bcsfe.core;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public class BulkTalentEditTest {
    @Test public void latestRegionalSavesCanMaxAllTalentsAndReopen() throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()) {
            SaveDocument save=SaveDocument.fromNewSaveTemplate(Files.readAllBytes(Path.of("src/main/assets/new_saves/"+region.code()+".save")));
            save.maxAllCatTalents();
            SaveDocument reopened=SaveDocument.open(save.toBytes());
            for(int cat=0;cat<reopened.catCount();cat++)for(SaveDocument.TalentValue talent:reopened.catTalents(cat))
                assertEquals(region+" cat "+cat+" talent "+talent.id,talent.maxLevel,talent.level);
            assertTrue(reopened.checksumValid());
        }
    }

    @Test public void bulkMatchesIndividualMaxAndFailureNeverChangesSource() throws Exception {
        SaveDocument bulk=SaveDocument.fromNewSaveTemplate(Files.readAllBytes(Path.of("src/main/assets/new_saves/tw.save")));
        SaveDocument individual=SaveDocument.open(bulk.toBytes());
        for(int cat=0;cat<individual.catCount();cat++)if(!individual.catTalents(cat).isEmpty())individual.maxCatTalents(cat);
        bulk.maxAllCatTalents();assertArrayEquals(individual.toBytes(),bulk.toBytes());

        // Cache a valid table, then simulate a damaged record count. The draft
        // must validate again instead of partially applying the cached table.
        java.lang.reflect.Method table=SaveDocument.class.getDeclaredMethod("talentTableOffset");table.setAccessible(true);
        int offset=(Integer)table.invoke(bulk);
        java.lang.reflect.Field buffer=SaveDocument.class.getDeclaredField("bytes");buffer.setAccessible(true);
        byte[] corrupted=(byte[])buffer.get(bulk);
        for(int i=0;i<4;i++)corrupted[offset+i]=(byte)0x7f;
        byte[] before=bulk.toBytes();
        assertThrows(RuntimeException.class,bulk::maxAllCatTalents);
        assertArrayEquals(before,bulk.toBytes());
    }

}
