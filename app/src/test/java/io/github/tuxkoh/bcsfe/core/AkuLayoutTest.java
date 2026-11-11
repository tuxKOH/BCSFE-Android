package io.github.tuxkoh.bcsfe.core;

import org.junit.Test;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AkuLayoutTest {
    private static SaveDocument save(SaveDocument.Region region,boolean latest)throws Exception {
        byte[] raw=Files.readAllBytes(Path.of("src/main/assets/new_saves/"+region.code()+".save"));
        return latest?SaveDocument.fromNewSaveTemplate(raw):SaveDocument.open(raw);
    }
    private static int base(SaveDocument save)throws Exception {
        Method m=SaveDocument.class.getDeclaredMethod("akuTableOffset");m.setAccessible(true);
        return (Integer)m.invoke(save);
    }
    private static void hash(byte[] raw,SaveDocument.Region region)throws Exception {
        MessageDigest md=MessageDigest.getInstance("MD5");
        md.update(region.packageSuffix().getBytes(StandardCharsets.US_ASCII));
        md.update(raw,0,raw.length-32);
        byte[] encoded=java.util.HexFormat.of().formatHex(md.digest()).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded,0,raw,raw.length-32,32);
    }

    /** Previously every stage triggered several full-save scans (~6 seconds per batch on desktop). */
    @Test(timeout=5000) public void repeatedPageReadsAndBatchesUseOneLayout()throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values())for(boolean latest:new boolean[]{false,true}){
            SaveDocument save=save(region,latest);
            assertEquals(1,save.akuChapterCount());assertEquals(1,save.akuStarCount());
            assertEquals(49,save.akuStageCount());
            for(int run=0;run<100;run++){
                assertEquals(1,save.akuChapterCount());assertEquals(1,save.akuStarCount());
                save.setAkuProgress(run%50,7);
            }
            assertEquals(7,save.akuClearTimes(0,0,48));
            assertTrue(save.checksumValid());
        }
    }

    @Test public void bulkEditsOnlyChangeTheRequestedStageShorts()throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            SaveDocument save=save(region,true);int stages=save.akuStageCount(),base=base(save);
            byte[] before=save.toBytes();int start=base+4+save.akuChapterCount()*save.akuStarCount();
            save.setAkuProgress(10,5);byte[] after=save.toBytes();
            for(int i=0;i<before.length-32;i++)if(i<start||i>=start+stages*2)
                assertEquals("Unexpected write at "+i,before[i],after[i]);
            SaveDocument reopened=SaveDocument.open(after);
            for(int stage=0;stage<stages;stage++)assertEquals(stage<10?5:0,reopened.akuClearTimes(0,0,stage));
            reopened.clearAkuChapter(0,0,true);
            for(int stage=0;stage<stages;stage++)assertEquals(1,reopened.akuClearTimes(0,0,stage));
            reopened.clearAkuChapter(0,0,false);
            for(int stage=0;stage<stages;stage++)assertEquals(0,reopened.akuClearTimes(0,0,stage));
            assertArrayEquals(before,reopened.toBytes());
        }
    }

    @Test public void cachedLayoutIsDiscardedAfterVariableLengthEdits()throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            SaveDocument save=save(region,true);int old=base(save);
            save.addTalentOrb(304,7);assertEquals(old+4,base(save));
            save.setAkuProgress(49,3);
            save.removeTalentOrb(0);assertEquals(old,base(save));
            save.setAkuClearTimes(0,0,48,8);
            SaveDocument reopened=SaveDocument.open(save.toBytes());
            assertEquals(8,reopened.akuClearTimes(0,0,48));
            assertEquals(3,reopened.akuClearTimes(0,0,47));
            assertEquals(0,reopened.talentOrbCount());assertTrue(reopened.checksumValid());
        }
    }

    @Test public void brokenAkuRecordCannotFallBackToAnUnrelatedHeader()throws Exception {
        SaveDocument original=save(SaveDocument.Region.JP,true);byte[] raw=original.toBytes();
        raw[base(original)-4]^=1;hash(raw,SaveDocument.Region.JP);
        SaveDocument broken=SaveDocument.open(raw);byte[] before=broken.toBytes();
        assertThrows(IllegalStateException.class,()->broken.setAkuProgress(49,1));
        assertArrayEquals(before,broken.toBytes());
    }

    @Test public void invalidBatchParametersNeverPartiallyWrite()throws Exception {
        SaveDocument save=save(SaveDocument.Region.TW,true);byte[] before=save.toBytes();
        assertThrows(IllegalArgumentException.class,()->save.setAkuProgress(50,1));
        assertThrows(IllegalArgumentException.class,()->save.setAkuProgress(1,32768));
        assertThrows(IndexOutOfBoundsException.class,()->save.clearAkuChapter(1,0,true));
        assertArrayEquals(before,save.toBytes());
    }
}
