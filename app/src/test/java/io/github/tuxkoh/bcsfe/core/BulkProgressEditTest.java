package io.github.tuxkoh.bcsfe.core;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public class BulkProgressEditTest {
    private SaveDocument fixture(SaveDocument.Region region) throws Exception {
        return SaveDocument.fromNewSaveTemplate(Files.readAllBytes(Path.of("src/main/assets/new_saves/"+region.code()+".save")));
    }

    @Test public void forcedEvolutionNeverIntroducesHiddenCats() throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values())for(boolean fourth:new boolean[]{false,true}) {
            SaveDocument save=fixture(region);
            save.removeAllCats();save.unlockAllObtainableCats();
            int hidden=-1,visible=-1;
            for(int id=0;id<save.catCount();id++)if(!GameDataRules.catObtainable(region,save.gameVersion(),id))hidden=id;else if(GameDataRules.totalForms(region,save.gameVersion(),id)>=(fourth?4:3)) visible=id;
            assertTrue(hidden>=0&&visible>=0);
            save.setCatBaseLevel(hidden,17);save.setCatPlusLevel(hidden,4);save.setCatCurrentForm(hidden,1);save.setCatUnlocked(hidden,false);
            int hiddenForms=save.catUnlockedForms(hidden),hiddenFourth=save.catFourthForm(hidden);
            SaveDocument before=SaveDocument.open(save.toBytes());
            if(fourth)save.forceFourthForms();else save.forceTrueForms();
            SaveDocument reopened=SaveDocument.open(save.toBytes());
            for(int id=0;id<save.catCount();id++)assertEquals(region+" ID "+id,GameDataRules.catObtainable(region,save.gameVersion(),id),reopened.catUnlocked(id));
            assertEquals(17,reopened.catBaseLevel(hidden));assertEquals(4,reopened.catPlusLevel(hidden));
            assertEquals(1,reopened.catCurrentForm(hidden));assertEquals(hiddenForms,reopened.catUnlockedForms(hidden));assertEquals(hiddenFourth,reopened.catFourthForm(hidden));
            for(int id=0;id<save.catCount();id++)if(GameDataRules.totalForms(region,save.gameVersion(),id)<(fourth?4:3)) {
                assertEquals(before.catCurrentForm(id),reopened.catCurrentForm(id));
                assertEquals(before.catUnlockedForms(id),reopened.catUnlockedForms(id));
                assertEquals(before.catFourthForm(id),reopened.catFourthForm(id));
            }
            assertEquals(fourth?3:2,reopened.catCurrentForm(visible));assertTrue(reopened.checksumValid());
        }
    }

    @Test public void bulkClaimMatchesEligibleIndividualFlagsAndIsIdempotent() throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()) {
            SaveDocument save=fixture(region);
            save.removeAllCats();save.setCatUnlocked(0,true);save.setCatPlusLevel(0,1100);
            save.setAllUserRankRewards(false);
            // An existing out-of-range claim and an unknown flag must be preserved.
            save.setUserRankRewardClaimed(0,true);
            if(save.userRankRewardCount()>save.knownUserRankRewardCount())save.setUserRankRewardClaimed(save.userRankRewardCount()-1,true);
            SaveDocument expected=SaveDocument.open(save.toBytes());int count=0;
            for(int i=0;i<expected.knownUserRankRewardCount();i++)if(expected.userRankRewardEligible(i)&&!expected.userRankRewardClaimed(i)) {expected.setEligibleUserRankRewardClaimed(i,true);count++;}
            assertTrue(count>0);assertEquals(count,save.markAllEligibleUserRankRewardsClaimed());
            assertArrayEquals(expected.toBytes(),save.toBytes());
            byte[] first=save.toBytes();assertEquals(0,save.markAllEligibleUserRankRewardsClaimed());assertArrayEquals(first,save.toBytes());
            assertTrue(SaveDocument.open(first).checksumValid());
        }
    }

    @Test public void crownSnapshotMatchesStageReadsWithoutChangingSave() throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()) {
            SaveDocument save=fixture(region);
            for(SaveDocument.StageMap type:SaveDocument.StageMap.values()) {
                int map=type==SaveDocument.StageMap.EVENT?1:0;
                if(save.stageMapCount(type)<=map||save.stageMapStarCount(type,map)==0)continue;
                int crown=save.stageMapStarCount(type,map)-1;
                save.setStageMapClearTimes(type,map,crown,0,7);
                byte[] before=save.toBytes();int[] counts=save.stageMapClearTimes(type,map,crown);
                assertEquals(save.stageMapStageCount(type,map,crown),counts.length);
                assertEquals(7,counts[0]);
                for(int stage:new int[]{0,counts.length/2,counts.length-1})assertEquals(save.stageMapClearTimes(type,map,crown,stage),counts[stage]);
                assertArrayEquals(before,save.toBytes());
                assertThrows(IndexOutOfBoundsException.class,()->save.stageMapClearTimes(type,map,save.stageMapStarCount(type,map)));
            }
        }
    }
}
