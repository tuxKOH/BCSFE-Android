package io.github.tuxkoh.bcsfe.core;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class UpstreamGameDataTest {
    private static byte[] template(SaveDocument.Region region) throws Exception {
        return Files.readAllBytes(Path.of("src/main/assets/new_saves/"+region.code()+".save"));
    }

    @Test public void newSavesUseLatestRegionalVersionAndExpandAllCatLists() throws Exception {
        for (SaveDocument.Region region : SaveDocument.Region.values()) {
            byte[] source=template(region), before=source.clone();
            SaveDocument legacy=SaveDocument.open(source);
            SaveDocument created=SaveDocument.fromNewSaveTemplate(source);
            int count=region==SaveDocument.Region.JP?888:882;
            assertArrayEquals(before,source);
            assertEquals(region,created.region());
            assertEquals(GameVersions.latest(region),created.gameVersion());
            assertTrue(created.isOfficiallySupportedVersion());
            assertFalse(created.needsUnsupportedImportWarning());
            assertTrue(created.hasItemProfile());
            assertEquals(count,created.catCount());
            assertEquals(region==SaveDocument.Region.JP?352:349,created.knownUserRankRewardCount());
            assertEquals(legacy.normalTickets(),created.normalTickets());
            assertEquals(legacy.rareTickets(),created.rareTickets());
            assertArrayEquals(legacy.catfruit(),created.catfruit());
            assertArrayEquals(legacy.catseyes(),created.catseyes());
            assertArrayEquals(legacy.catamins(),created.catamins());
            assertEquals(legacy.inquiryCode(),created.inquiryCode());
            assertEquals(legacy.passwordRefreshToken(),created.passwordRefreshToken());
            for(int cat=861;cat<count;cat++){
                assertFalse(created.catUnlocked(cat));
                assertEquals(1,created.catBaseLevel(cat));
                assertEquals(0,created.catPlusLevel(cat));
                assertEquals(0,created.catCurrentForm(cat));
                assertEquals(0,created.catUnlockedForms(cat));
                assertEquals(0,created.catFourthForm(cat));
                assertFalse(created.catGuideCollected(cat));
            }
            assertTrue(created.checksumValid());
            SaveDocument reopened=SaveDocument.open(created.toBytes());
            assertEquals(count,reopened.catCount());
            assertArrayEquals(created.toBytes(),reopened.toBytes());
        }
    }

    @Test public void newJpCatsCanBeEditedAndExportedWithoutTouchingTickets() throws Exception {
        SaveDocument save=SaveDocument.fromNewSaveTemplate(template(SaveDocument.Region.JP));
        int tickets=save.normalTickets(), rare=save.rareTickets();
        save.setCatUnlocked(881,true);
        save.setCatBaseLevel(881,10);
        save.setCatPlusLevel(881,3);
        save.setCatCurrentForm(881,1);
        SaveDocument reopened=SaveDocument.open(save.toBytes());
        assertTrue(reopened.catUnlocked(881));
        assertEquals(10,reopened.catBaseLevel(881));
        assertEquals(3,reopened.catPlusLevel(881));
        assertEquals(1,reopened.catCurrentForm(881));
        assertEquals(tickets,reopened.normalTickets());
        assertEquals(rare,reopened.rareTickets());
        assertTrue(reopened.checksumValid());
    }

    @Test public void importedSavesKeepTheirOriginalVersionAndCapacity() throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            byte[] raw=template(region);
            SaveDocument document=SaveDocument.open(raw);
            assertEquals(150500,document.gameVersion());
            assertEquals(861,document.catCount());
            assertArrayEquals(raw,document.toBytes());
            document.convertGameVersion(GameVersions.latest(region));
            assertEquals(861,document.catCount());
            assertTrue(document.isOfficiallySupportedVersion());
            document.convertGameVersion(150500);
            assertArrayEquals(raw,document.toBytes());
        }
    }

    @Test public void currentSnapshotsRespectRegionalAvailabilityAndLegacyTables() {
        assertEquals(888,UpstreamGameData.forRegion(SaveDocument.Region.JP).unitBuy.length);
        assertEquals(882,UpstreamGameData.forVersion(SaveDocument.Region.JP,150704).unitBuy.length);
        assertEquals(2,GameDataRules.totalForms(SaveDocument.Region.JP,150704,881));
        assertTrue(GameDataRules.catObtainable(SaveDocument.Region.JP,150704,881));
        assertEquals(5,GameDataRules.catRarity(SaveDocument.Region.JP,150704,881));
        assertEquals(9,GameDataRules.catMaxPlus(SaveDocument.Region.JP,150704,881));
        assertEquals(-1,GameDataRules.totalForms(SaveDocument.Region.TW,150700,881));
        assertFalse(GameDataRules.catObtainable(SaveDocument.Region.TW,150700,881));
        assertFalse(GameDataRules.catObtainable(SaveDocument.Region.JP,150500,881));
        assertEquals(349,GameDataRules.rankGiftCount(SaveDocument.Region.TW,150601));
        assertEquals(28500,GameDataRules.rankGiftThreshold(SaveDocument.Region.JP,150702,348));
        assertEquals(28500,GameDataRules.rankGiftThreshold(SaveDocument.Region.TW,150700,348));
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            int latest=GameVersions.latest(region);
            assertTrue(GameVersions.supported(region,latest));
            assertEquals(latest,GameVersions.conversionTargets(region)[0]);
            for(int i=0;i<GameDataRules.dropPairCount(region,latest);i++){
                assertTrue(GameDataRules.dropCat(region,latest,i)<UpstreamGameData.forRegion(region).unitBuy.length);
                assertTrue(GameDataRules.dropSlot(region,latest,i)<400);
            }
        }
        assertFalse(GameVersions.supported(SaveDocument.Region.TW,150704));
        assertFalse(GameVersions.supported(SaveDocument.Region.JP,160002));
        assertFalse(GameVersions.supported(SaveDocument.Region.JP,150800));
        assertArrayEquals(new int[]{160001,160000,150704,150703,150702,150701,150700},
                java.util.Arrays.copyOf(GameVersions.conversionTargets(SaveDocument.Region.JP),7));
        for(SaveDocument.Region region:new SaveDocument.Region[]{SaveDocument.Region.EN,
                SaveDocument.Region.TW,SaveDocument.Region.KR}){
            assertArrayEquals(new int[]{150700,150601,150600,150501,150500},
                    java.util.Arrays.copyOf(GameVersions.conversionTargets(region),5));
        }
    }

    @Test public void latestSharedLayoutConversionsAreReversibleAndRegionBounded() throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            byte[] raw=SaveDocument.fromNewSaveTemplate(template(region)).toBytes();
            for(int version:GameVersions.conversionTargets(region)){
                // Downgrades across layout gates deliberately discard fields.
                if(version<150500)continue;
                SaveDocument save=SaveDocument.open(raw);
                save.convertGameVersion(version);
                save.convertGameVersion(GameVersions.latest(region));
                assertArrayEquals(region+" via "+version,raw,save.toBytes());
            }
            SaveDocument save=SaveDocument.open(raw);
            assertThrows(UnsupportedOperationException.class,()->save.convertGameVersion(GameVersions.latest(region)+1));
            assertThrows(UnsupportedOperationException.class,()->save.convertGameVersion(150800));
            assertArrayEquals(raw,save.toBytes());
        }
    }

    @Test public void intermediateRevisionsKeepTheirVersionAndEnableEditing() throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            byte[] latest=SaveDocument.fromNewSaveTemplate(template(region)).toBytes();
            int[] versions=region==SaveDocument.Region.JP
                    ?new int[]{150501,150601,150700,150701,150702,150703,150704,160000}:new int[]{150501,150601};
            for(int version:versions){
                SaveDocument save=SaveDocument.open(latest);
                save.convertGameVersion(version);
                byte[] imported=save.toBytes();
                save=SaveDocument.open(imported);
                assertEquals(version,save.gameVersion());
                assertTrue(save.isOfficiallySupportedVersion());
                assertFalse(save.needsUnsupportedImportWarning());
                assertFalse(save.canAttemptUnsafeUpload());
                assertTrue(save.hasItemProfile());
                assertTrue(save.hasCatProfile());
                assertArrayEquals(imported,save.toBytes());
                assertSame(UpstreamGameData.forVersion(region,version),UpstreamGameData.matching(region,version));
                assertEquals(GameDataRules.totalForms(region,version,0),
                        GameDataRules.totalForms(region,version,0));
                save.setNormalTickets(123);
                save.setRareTickets(23);
                save.setCatBaseLevel(0,10);
                SaveDocument reopened=SaveDocument.open(save.toBytes());
                assertEquals(version,reopened.gameVersion());
                assertEquals(123,reopened.normalTickets());
                assertEquals(23,reopened.rareTickets());
                assertEquals(10,reopened.catBaseLevel(0));
                assertTrue(reopened.checksumValid());
            }
            assertFalse(GameVersions.supported(region,150499));
            assertFalse(GameVersions.supported(region,GameVersions.latest(region)+1));
            assertNull(UpstreamGameData.matching(region,GameVersions.latest(region)+1));
            assertNull(UpstreamGameData.matching(region,150500));
            assertNull(UpstreamGameData.matching(region,150600));
            int previous=Integer.MAX_VALUE;
            for(int version:GameVersions.conversionTargets(region)){
                assertTrue(version<previous);
                previous=version;
            }
        }
    }

    @Test public void intermediateConversionsPreserveImportedListCapacities() throws Exception {
        byte[] original=template(SaveDocument.Region.JP);
        for(int version:new int[]{150501,150601,150700,150701,150702,150703,150704}){
            SaveDocument save=SaveDocument.open(original);
            save.convertGameVersion(version);
            assertEquals(861,save.catCount());
            assertTrue(save.hasItemProfile());
            assertEquals(version,SaveDocument.open(save.toBytes()).gameVersion());
            save.convertGameVersion(150500);
            assertArrayEquals("Round trip via "+version,original,save.toBytes());
        }
    }
}
