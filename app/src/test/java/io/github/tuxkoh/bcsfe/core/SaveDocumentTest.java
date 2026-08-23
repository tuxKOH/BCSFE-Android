package io.github.tuxkoh.bcsfe.core;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.Test;
import org.junit.Assume;

public class SaveDocumentTest {
    @Test public void jp1551CanBeExplicitlyForceLoadedForInspection() throws Exception {
        byte[] source = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/jp.save"));
        source[Offsets.offsets_23] = (byte) 0xE5;
        source[Offsets.offsets_23 + 1] = (byte) 0x4B;
        source[Offsets.offsets_23 + 2] = (byte) 0x02;
        source[Offsets.offsets_23 + 3] = 0;
        SaveDocument document = SaveDocument.openForInspection(source, SaveDocument.Region.JP);
        assertEquals(SaveDocument.Region.JP, document.region());
        assertEquals(150501, document.gameVersion());
        assertTrue(document.needsUnsupportedImportWarning());
        assertFalse(document.isOfficiallySupportedVersion());
    }

    @Test public void bundledNewSaveTemplatesAreValid155Saves() throws Exception {
        java.nio.file.Path templates=java.nio.file.Path.of("src/main/assets/new_saves");
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(templates.resolve(region.code()+".save")));
            assertEquals(region,document.region());
            assertEquals(150500,document.gameVersion());
            assertEquals(861,document.catCount());
            assertTrue(document.checksumValid());
            assertEquals("000000000",document.inquiryCode());
            assertEquals("________________________________________",document.passwordRefreshToken());
        }
        for(SaveDocument.Region target:SaveDocument.Region.values()){
            SaveDocument converted=SaveDocument.open(java.nio.file.Files.readAllBytes(templates.resolve("tw.save")));
            assertEquals(81,converted.stageMapCount(SaveDocument.StageMap.GAUNTLETS));
            assertEquals(26,converted.stageMapCount(SaveDocument.StageMap.COLLAB_GAUNTLETS));
            assertEquals(9,converted.outbreakChapterCount());
            converted.accountCreatedAt();converted.convertRegion(target);
            assertArrayEquals(java.nio.file.Files.readAllBytes(templates.resolve(target.code()+".save")),converted.toBytes());
            converted.convertRegion(SaveDocument.Region.TW);
            assertEquals(SaveDocument.Region.TW,SaveDocument.open(converted.toBytes()).region());
            assertTrue(converted.checksumValid());
            if(target!=SaveDocument.Region.JP)assertArrayEquals(java.nio.file.Files.readAllBytes(templates.resolve("tw.save")),converted.toBytes());
        }

        SaveDocument jpCats=SaveDocument.open(java.nio.file.Files.readAllBytes(templates.resolve("jp.save")));
        jpCats.setCatUnlockedForms(12,3);jpCats.setCatFourthForm(12,2);
        assertEquals(3,jpCats.catUnlockedForms(12));assertEquals(2,jpCats.catFourthForm(12));
        assertTrue(jpCats.checksumValid());

        SaveDocument cleared=SaveDocument.open(java.nio.file.Files.readAllBytes(templates.resolve("tw.save")));
        for(int chapter=0;chapter<cleared.storyChapterCount();chapter++)cleared.clearStoryChapter(chapter,true);
        for(int chapter=0;chapter<cleared.storyChapterCount();chapter++)for(int stage=0;stage<cleared.storyStageCount();stage++)assertEquals(1,cleared.storyClearTimes(chapter,stage));
        assertTrue(cleared.checksumValid());

        SaveDocument treasures=SaveDocument.open(java.nio.file.Files.readAllBytes(templates.resolve("tw.save")));
        for(int chapter=0;chapter<treasures.storyChapterCount();chapter++)treasures.setStoryChapterTreasures(chapter,3);
        for(int chapter=0;chapter<treasures.storyChapterCount();chapter++)for(int stage=0;stage<treasures.storyStageCount();stage++)assertEquals(3,treasures.storyTreasure(chapter,stage));
        assertTrue(treasures.checksumValid());
    }

    @Test public void battleItemsUseThe155ArrayAndBulkCatsStayInsideTheirArray() throws Exception {
        byte[] original=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument battle=SaveDocument.open(original);battle.setBattleItem(0,1000);battle.setBattleItem(5,1005);byte[] battleBytes=battle.toBytes();
        assertEquals(1000,littleInt(battleBytes,18862));assertEquals(1005,littleInt(battleBytes,18882));
        SaveDocument cats=SaveDocument.open(original);assertEquals(861,cats.catCount());int end=8402+cats.catCount()*4;
        byte[] following=java.util.Arrays.copyOfRange(original,end,end+48);cats.removeAllCats();
        assertArrayEquals(following,java.util.Arrays.copyOfRange(cats.toBytes(),end,end+48));assertTrue(cats.checksumValid());
    }

    @Test public void battleItemsWorkInEveryBundledRegionLayout() throws Exception {
        for (SaveDocument.Region region : SaveDocument.Region.values()) {
            byte[] source=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/"+region.code()+".save"));
            SaveDocument document=SaveDocument.open(source);
            document.setBattleItem(0,1234);document.setBattleItem(5,5678);
            SaveDocument reopened=SaveDocument.open(document.toBytes());
            assertArrayEquals(region.code(),new int[]{1234,0,0,0,0,5678},reopened.battleItems());
            assertTrue(reopened.checksumValid());
        }
    }

    @Test public void cataminEditUsesTheSerializedListAndRemainsParsable() throws Exception {
        byte[] source = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument document = SaveDocument.open(source);
        int[] before = document.catamins();
        assertTrue(before.length > 0);
        document.setCatamin(before.length - 1, 1234);
        SaveDocument reopened = SaveDocument.open(document.toBytes());
        int[] after = reopened.catamins();
        assertEquals(before.length, after.length);
        assertEquals(1234, after[after.length - 1]);
        assertTrue(reopened.checksumValid());
    }

    @Test public void legacySyntheticVariableCatProfilesKeepBattleItemsEditable() throws Exception {
        String[] names={"synthetic-800.save","synthetic-860.save","synthetic-862.save",
                "synthetic-jp-800.save","synthetic-jp-860.save","synthetic-jp-862.save"};
        for(String name:names){
            java.nio.file.Path path=java.nio.file.Path.of("/tmp",name);
            Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
            SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
            document.setBattleItem(0,1234);
            assertEquals(1234,document.battleItems()[0]);
            assertTrue(document.checksumValid());
        }
    }

    @Test public void transferCodeBattleItemsUseTheShiftedSerializedTable() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
        byte[] original=java.nio.file.Files.readAllBytes(path);
        SaveDocument document=SaveDocument.open(original);
        assertArrayEquals(new int[]{501,498,496,500,496,496},document.battleItems());
        document.setBattleItem(0,1234);document.setBattleItem(5,5678);
        byte[] edited=document.toBytes();
        assertEquals(1234,littleInt(edited,19006));assertEquals(5678,littleInt(edited,19026));
        assertEquals(31,littleInt(edited,19030));
        SaveDocument reopened=SaveDocument.open(edited);
        assertArrayEquals(new int[]{1234,498,496,500,496,5678},reopened.battleItems());
        assertTrue(reopened.checksumValid());
    }

    @Test public void transferCodeBulkBaseLevelsMatchUpstreamIncludingDynamicDrops() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save"), expected=java.nio.file.Path.of("/tmp/upstream-batch-transfer-base-20.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        document.setAllCatBaseLevels(20);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),document.toBytes());
    }

    @Test public void jpTransferBaseLevelsUseJpDropCharaMapping() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-session-561d5a87-c556-437b-93d1-5d69a56b64d0.save");
        java.nio.file.Path fresh=java.nio.file.Path.of("/tmp/fresh-op-now2/device-session-561d5a87-c556-437b-93d1-5d69a56b64d0-base20.save");
        java.nio.file.Path expected=java.nio.file.Files.isRegularFile(fresh) ? fresh : java.nio.file.Path.of("/tmp/correct-device-session-561d5a87-c556-437b-93d1-5d69a56b64d0-base20.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        document.setAllCatBaseLevels(20);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),document.toBytes());
        SaveDocument reopened=SaveDocument.open(document.toBytes());
        assertEquals(861,reopened.catCount());
        assertTrue(reopened.checksumValid());
    }

    @Test public void jpReceivedSingleCatFieldEditsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-session-561d5a87-c556-437b-93d1-5d69a56b64d0.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        SaveDocument forms=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        forms.setCatUnlockedForms(0,3);
        assertEquals(3,forms.catUnlockedForms(0));
        assertEquals(0,forms.catCurrentForm(0));
        assertTrue(forms.catUnlocked(0));
        SaveDocument fourth=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        fourth.setCatFourthForm(0,2);
        assertEquals(2,fourth.catFourthForm(0));
        assertEquals(0,fourth.catCurrentForm(0));
        assertEquals(0,fourth.catUnlockedForms(0));
        assertTrue(fourth.catUnlocked(0));
        SaveDocument current=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        current.setCatCurrentForm(0,2);
        assertEquals(2,current.catCurrentForm(0));
        assertEquals(3,current.catUnlockedForms(0));
        assertTrue(current.catUnlocked(0));
        SaveDocument plus=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        plus.setCatPlusLevel(0,5);
        assertEquals(5,plus.catPlusLevel(0));
        assertTrue(plus.catUnlocked(0));
    }

    @Test public void receivedSessionBattleItemsMatchUpstreamAcrossRegions() throws Exception {
        String[] names={
                "device-session-21a7a90f-4478-4fac-841b-7fd8c56b4b4c",
                "device-session-561d5a87-c556-437b-93d1-5d69a56b64d0",
                "device-session-64ee34a8-99d8-456b-b80c-3e5e5a207d97",
                "device-session-842951b9-e594-4856-983b-c722da97075f",
                "device-session-9bdbe0c3-1621-4924-823f-83ce6731f802"};
        for(String name:names){
            java.nio.file.Path source=java.nio.file.Path.of("/tmp/"+name+".save");
            java.nio.file.Path expected=java.nio.file.Path.of("/tmp/correct-"+name+"-battle5.save");
            Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
            SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
            document.setBattleItem(5,5678);
            assertArrayEquals(java.nio.file.Files.readAllBytes(expected),document.toBytes());
            assertEquals(5678,SaveDocument.open(document.toBytes()).battleItems()[5]);
        }
    }

    @Test public void receivedSessionBatchBaseLevelsMatchUpstreamMatrix() throws Exception {
        String[] names={
                "device-session-21a7a90f-4478-4fac-841b-7fd8c56b4b4c",
                "device-session-561d5a87-c556-437b-93d1-5d69a56b64d0",
                "device-session-64ee34a8-99d8-456b-b80c-3e5e5a207d97",
                "device-session-842951b9-e594-4856-983b-c722da97075f",
                "device-session-9bdbe0c3-1621-4924-823f-83ce6731f802",
                "device-session-c22c3b53-5b7f-46de-86f9-551da352e4d0",
                "device-session-fb88deae-3897-4150-b111-b4d478d5bbc5",
                "device-transfer-507617"};
        int[] targets={1,5,10,20,30,40,60};
        for(String name:names) {
            java.nio.file.Path source=java.nio.file.Path.of("/tmp/"+name+".save");
            Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
            for(int target:targets) {
                java.nio.file.Path expected=java.nio.file.Path.of("/tmp/correct-"+name+"-base"+target+".save");
                Assume.assumeTrue(java.nio.file.Files.isRegularFile(expected));
                SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
                document.setAllCatBaseLevels(target);
                assertArrayEquals(name+" base"+target,
                        java.nio.file.Files.readAllBytes(expected),document.toBytes());
                SaveDocument reopened=SaveDocument.open(document.toBytes());
                assertEquals(name+" base"+target,document.catCount(),reopened.catCount());
                assertTrue(name+" base"+target,reopened.checksumValid());
            }
        }
    }

    @Test public void transferCodeDynamicItemsUseSerializedListLocations() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        d.setCatfruit(0,777); d.setCatseye(0,778); d.setCatamin(0,779);
        byte[] edited=d.toBytes();
        assertEquals(777,littleInt(edited,386138));
        assertEquals(778,littleInt(edited,393250));
        assertEquals(779,littleInt(edited,393278));
        assertTrue(d.checksumValid());
    }

    @Test public void transferCodeLateFieldsUseSerializedStructureLocations() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        d.setRareSeed(1234); d.setNormalSeed(1235); d.setEventSeed(1236);
        d.setGamatotoXp(1237); d.setGamatotoDestination(7);
        d.setLuckyTicket(0,1238); d.setEventTicket(0,1239);
        d.setDojoRanking(1240); d.setOfficerPassCatId(1241); d.setOfficerPassCatForm(2);
        d.setBaseMaterial(0,1242);
        byte[] out=d.toBytes();
        assertEquals(1234,littleInt(out,296555));
        assertEquals(1235,littleInt(out,296559));
        assertEquals(1236,littleInt(out,383598));
        assertEquals(1237,littleInt(out,393299));
        assertEquals(7,littleInt(out,393303));
        assertEquals(1238,littleInt(out,450796));
        assertEquals(1239,littleInt(out,383606));
        assertEquals(1240,littleInt(out,421603));
        assertEquals(1241,littleUshort(out,462247));
        assertEquals(2,littleUshort(out,462249));
        assertEquals(1242,littleInt(out,406998));
        SaveDocument reopened=SaveDocument.open(out);
        assertEquals(1234,reopened.rareSeed()); assertEquals(1235,reopened.normalSeed());
        assertEquals(1236,reopened.eventSeed()); assertEquals(1237,reopened.gamatotoXp());
        assertEquals(7,reopened.gamatotoDestination()); assertEquals(1238,reopened.luckyTicket(0));
        assertEquals(1239,reopened.eventTicket(0)); assertEquals(1240,reopened.dojoRanking());
        assertEquals(1241,reopened.officerPassCatId()); assertEquals(2,reopened.officerPassCatForm());
        assertEquals(1242,reopened.baseMaterial(0)); assertTrue(reopened.checksumValid());
    }

    @Test public void transferCodeChallengeMapUsesRepeatedChapterHeaders() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save"), expected=java.nio.file.Path.of("/tmp/upstream-transfer-stage-challenge.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        assertEquals(1,document.stageMapCount(SaveDocument.StageMap.CHALLENGE));
        assertEquals(4,document.stageMapStarCount(SaveDocument.StageMap.CHALLENGE,0));
        assertEquals(50,document.stageMapStageCount(SaveDocument.StageMap.CHALLENGE,0,0));
        document.setStageMapClearTimes(SaveDocument.StageMap.CHALLENGE,0,0,7,1);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),document.toBytes());
        SaveDocument reopened=SaveDocument.open(document.toBytes());
        assertEquals(1,reopened.stageMapClearTimes(SaveDocument.StageMap.CHALLENGE,0,0,7));
        assertTrue(reopened.checksumValid());
    }

    @Test public void transferCodeLegendQuestUsesByteSizedVariableLayout() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save"), expected=java.nio.file.Path.of("/tmp/upstream-transfer-stage-legend.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        assertEquals(1,document.stageMapCount(SaveDocument.StageMap.LEGEND_QUEST));
        assertEquals(1,document.stageMapStarCount(SaveDocument.StageMap.LEGEND_QUEST,0));
        assertEquals(48,document.stageMapStageCount(SaveDocument.StageMap.LEGEND_QUEST,0,0));
        document.setStageMapClearTimes(SaveDocument.StageMap.LEGEND_QUEST,0,0,0,7);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),document.toBytes());
        assertEquals(7,SaveDocument.open(document.toBytes()).stageMapClearTimes(SaveDocument.StageMap.LEGEND_QUEST,0,0,0));
    }

    @Test public void transferCodeRareTicketTradeUsesDynamicGatyaLocation() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save"), expected=java.nio.file.Path.of("/tmp/upstream-transfer-rare-trade-operation.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        document.tradeRareTickets(1234);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),document.toBytes());
        assertEquals(6170,document.rareTicketTradeProgress());
    }

    @Test public void transferCodeTalentOrbMutationsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        java.nio.file.Path set=java.nio.file.Path.of("/tmp/upstream-transfer-orb-set.save"), add=java.nio.file.Path.of("/tmp/upstream-transfer-orb-add.save"), remove=java.nio.file.Path.of("/tmp/upstream-transfer-orb-remove.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(set)&&java.nio.file.Files.isRegularFile(add)&&java.nio.file.Files.isRegularFile(remove));
        SaveDocument a=SaveDocument.open(java.nio.file.Files.readAllBytes(source));a.setTalentOrbAmount(0,123);assertArrayEquals(java.nio.file.Files.readAllBytes(set),a.toBytes());
        SaveDocument b=SaveDocument.open(java.nio.file.Files.readAllBytes(source));b.addTalentOrb(999,44);assertArrayEquals(java.nio.file.Files.readAllBytes(add),b.toBytes());
        SaveDocument c=SaveDocument.open(java.nio.file.Files.readAllBytes(source));c.removeTalentOrb(0);assertArrayEquals(java.nio.file.Files.readAllBytes(remove),c.toBytes());
    }

    @Test public void transferCodeLateMapLayoutsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        String[] names={"challenge","gauntlets","enigma","collab","event","uncanny","catamin","behemoth","legend","tower","zero","dojo"};
        SaveDocument.StageMap[] types={SaveDocument.StageMap.CHALLENGE,SaveDocument.StageMap.GAUNTLETS,SaveDocument.StageMap.ENIGMA_CLEARS,SaveDocument.StageMap.COLLAB_GAUNTLETS,SaveDocument.StageMap.EVENT,SaveDocument.StageMap.UNCANNY,SaveDocument.StageMap.CATAMIN,SaveDocument.StageMap.BEHEMOTH,SaveDocument.StageMap.LEGEND_QUEST,SaveDocument.StageMap.TOWER,SaveDocument.StageMap.ZERO_LEGENDS,SaveDocument.StageMap.DOJO};
        byte[] original=java.nio.file.Files.readAllBytes(source);
        for(int i=0;i<names.length;i++) {
            java.nio.file.Path expected=java.nio.file.Path.of("/tmp/upstream-transfer-stage-"+names[i]+".save");
            Assume.assumeTrue(java.nio.file.Files.isRegularFile(expected));
            SaveDocument d=SaveDocument.open(original);
            int map=names[i].equals("event")?0:0;
            int stage=names[i].equals("challenge")?7:0;
            d.setStageMapClearTimes(types[i],map,0,stage,names[i].equals("challenge")?1:7);
            assertArrayEquals("map="+names[i],java.nio.file.Files.readAllBytes(expected),d.toBytes());
        }
    }

    @Test public void transferCodeEventMapUsesStageMajorSerialization() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        java.nio.file.Path expected=java.nio.file.Path.of("/tmp/upstream-transfer-stage-event.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        document.setStageMapClearTimes(SaveDocument.StageMap.EVENT,0,0,0,7);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),document.toBytes());
        SaveDocument reopened=SaveDocument.open(document.toBytes());
        assertEquals(7,reopened.stageMapClearTimes(SaveDocument.StageMap.EVENT,0,0,0));
        assertTrue(reopened.checksumValid());
    }

    @Test public void transferCodeRemainingVariableSectionsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        String[] names={"story-clear","story-treasure","aku","mission","cannon","cannon-part","storage","medal"};
        byte[] original=java.nio.file.Files.readAllBytes(source);
        for(String name:names) {
            java.nio.file.Path expected=java.nio.file.Path.of("/tmp/upstream-transfer-"+name+".save");
            Assume.assumeTrue(java.nio.file.Files.isRegularFile(expected));
            SaveDocument d=SaveDocument.open(original);
            switch(name) {
                case "story-clear" -> d.setStoryClearTimes(0,0,7);
                case "story-treasure" -> d.setStoryTreasure(0,0,7);
                case "aku" -> d.setAkuClearTimes(0,0,0,7);
                case "mission" -> d.setMissionCompletion(d.missionIds()[0],2);
                case "cannon" -> d.setCannonDevelopment(1,3);
                case "cannon-part" -> d.setCannonPartLevel(1,0,7);
                case "storage" -> d.setStorageItem(0,1,7);
                case "medal" -> d.addMedal(1);
            }
            assertArrayEquals("section="+name,java.nio.file.Files.readAllBytes(expected),d.toBytes());
        }
    }

    @Test public void transferCodeOutbreakTablesAreLocatedAfterReceivedProfileShift() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        assertEquals(9,d.outbreakChapterCount());
        assertEquals(48,d.outbreakStageCount(0));
        d.setOutbreakCleared(0,0,true);
        java.nio.file.Path expected=java.nio.file.Path.of("/tmp/upstream-transfer-outbreak-clear.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(expected));
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),d.toBytes());
        SaveDocument whole=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        whole.setOutbreakChapterCleared(0,true);
        expected=java.nio.file.Path.of("/tmp/upstream-transfer-outbreak-whole-clear.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(expected));
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),whole.toBytes());
    }

    @Test public void receivedProfilesKeepVariableTailTablesAligned() throws Exception {
        java.nio.file.Path tw=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        java.nio.file.Path jp=java.nio.file.Path.of("/tmp/device-session-561d5a87-c556-437b-93d1-5d69a56b64d0.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(tw)&&java.nio.file.Files.isRegularFile(jp));
        SaveDocument twDoc=SaveDocument.open(java.nio.file.Files.readAllBytes(tw));
        SaveDocument jpDoc=SaveDocument.open(java.nio.file.Files.readAllBytes(jp));
        assertEquals(39,twDoc.treasureChests().length); assertEquals(30,jpDoc.treasureChests().length);
        assertEquals(873,twDoc.catCount()); assertEquals(861,jpDoc.catCount());
        twDoc.resetAllCats(); jpDoc.resetAllCats();
        assertEquals(39,SaveDocument.open(twDoc.toBytes()).treasureChests().length);
        assertEquals(30,SaveDocument.open(jpDoc.toBytes()).treasureChests().length);
        assertTrue(twDoc.checksumValid()); assertTrue(jpDoc.checksumValid());
    }

    @Test public void transferCodeBulkPlusAndUnlockMatchUpstreamWhenSamplesAreAvailable() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        java.nio.file.Path plus=java.nio.file.Path.of("/tmp/upstream-transfer-plus-5.save");
        java.nio.file.Path unlock=java.nio.file.Path.of("/tmp/upstream-transfer-unlock-all.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)
                && java.nio.file.Files.isRegularFile(plus)
                && java.nio.file.Files.isRegularFile(unlock));
        SaveDocument plusDocument=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        plusDocument.setAllCatPlusLevels(5);
        assertArrayEquals(java.nio.file.Files.readAllBytes(plus),plusDocument.toBytes());
        SaveDocument unlockDocument=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        unlockDocument.unlockAllCats();
        assertArrayEquals(java.nio.file.Files.readAllBytes(unlock),unlockDocument.toBytes());
    }

    @Test public void transferCodeSingleCatBaseAndBattleItemsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        java.nio.file.Path base=java.nio.file.Path.of("/tmp/upstream-transfer-single-base20.save");
        java.nio.file.Path battle=java.nio.file.Path.of("/tmp/upstream-transfer-single-battle_item_5.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(base)&&java.nio.file.Files.isRegularFile(battle));
        SaveDocument cat=SaveDocument.open(java.nio.file.Files.readAllBytes(source));cat.setCatBaseLevel(0,20);
        assertArrayEquals(java.nio.file.Files.readAllBytes(base),cat.toBytes());
        SaveDocument item=SaveDocument.open(java.nio.file.Files.readAllBytes(source));item.setBattleItem(5,1234);
        assertArrayEquals(java.nio.file.Files.readAllBytes(battle),item.toBytes());
    }

    @Test public void transferCodeAllBattleItemsMatchUpstreamWhenSamplesAreAvailable() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        byte[] original=java.nio.file.Files.readAllBytes(source);
        for(int index=0;index<6;index++) {
            java.nio.file.Path expected=java.nio.file.Path.of("/tmp/upstream-transfer-single-battle_item_"+index+".save");
            Assume.assumeTrue(java.nio.file.Files.isRegularFile(expected));
            SaveDocument document=SaveDocument.open(original);
            document.setBattleItem(index,1234);
            assertArrayEquals(java.nio.file.Files.readAllBytes(expected),document.toBytes());
        }
    }

    @Test public void transferCodeBaseUpgradeUsesDynamicMaxTableForRepresentativeCats() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        byte[] original=java.nio.file.Files.readAllBytes(source);
        // These cover normal, special, rare, collaboration and late-game
        // records.  Received saves commonly have a zeroed max-upgrade table;
        // every edit must rebuild the same companion record as upstream.
        int[] cats={0,1,12,100,400,700,859,860,861,872};
        for(int cat:cats){
            SaveDocument document=SaveDocument.open(original);
            document.setCatBaseLevel(cat,20);
            SaveDocument reopened=SaveDocument.open(document.toBytes());
            assertEquals("cat="+cat,20,reopened.catBaseLevel(cat));
            assertTrue("cat="+cat,reopened.checksumValid());
        }
    }

    @Test public void transferCodeBattleItemsRoundTripPreservesTrailingStructure() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        byte[] original=java.nio.file.Files.readAllBytes(source);
        SaveDocument document=SaveDocument.open(original);
        int[] expected={101,202,303,404,505,606};
        for(int i=0;i<expected.length;i++) document.setBattleItem(i,expected[i]);
        byte[] edited=document.toBytes();
        // The serialized list header and the first lock/date records follow
        // immediately after the six amounts and must remain untouched.
        assertEquals(31,littleInt(edited,19030));
        assertEquals(2,littleInt(edited,19034));
        assertEquals(2026,littleInt(edited,19254));
        SaveDocument reopened=SaveDocument.open(edited);
        assertArrayEquals(expected,reopened.battleItems());
        assertTrue(reopened.checksumValid());
    }

    @Test public void transferCodeCredentialRefreshPreservesDynamicProfiles() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        int base=document.catBaseLevel(0), plus=document.catPlusLevel(0), fruit=document.catfruit()[0];
        int[] battle=document.battleItems();
        document.setPasswordRefreshToken("0123456789012345678901234567890123456789");
        SaveDocument reopened=SaveDocument.open(document.toBytes());
        assertEquals(base,reopened.catBaseLevel(0));
        assertEquals(plus,reopened.catPlusLevel(0));
        assertEquals(fruit,reopened.catfruit()[0]);
        assertArrayEquals(battle,reopened.battleItems());
        assertEquals(31,reopened.normalTickets());
        assertEquals(20,reopened.catBaseLevel(0));
        assertEquals("0123456789012345678901234567890123456789",reopened.passwordRefreshToken());
        assertTrue(reopened.checksumValid());
    }

    @Test public void transferCodeVariableCoreFieldsUseUpstreamLocations() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        assertFalse(d.showBanMessage());
        assertEquals(2147483647,d.rankUpSaleValue());
        assertEquals(400,d.userRankRewardCount());
        d.setShowBanMessage(true); d.fixGamatotoCrash(); d.unlockEquipMenu();
        d.setUserRankRewardClaimed(399,true); d.fixTimeErrors(1700000000L);
        byte[] out=d.toBytes();
        assertEquals(1,out[385182]&255); assertEquals(2,littleInt(out,398318));
        assertEquals(2,littleInt(out,18942)); assertEquals(1,out[310265+399]&255);
        java.time.ZonedDateTime now=java.time.Instant.ofEpochSecond(1700000000L).atZone(java.time.ZoneId.systemDefault());
        assertEquals(now.getYear(),littleInt(out,19479)); assertEquals(now.getMonthValue(),littleInt(out,19483));
        assertEquals(now.getDayOfMonth(),littleInt(out,19487)); assertEquals(1700000000.0,littleDouble(out,39),0.0);
        assertEquals(1700000000.0,littleDouble(out,400514),0.0);
        SaveDocument reopened=SaveDocument.open(out); assertTrue(reopened.showBanMessage());
        assertTrue(reopened.userRankRewardClaimed(399)); assertEquals(1700000000L,reopened.accountCreatedAt());
        assertTrue(reopened.checksumValid());
    }

    @Test public void transferCodeGoldPassMutationKeepsVariableProfileIntact() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        byte[] original=java.nio.file.Files.readAllBytes(source);
        SaveDocument document=SaveDocument.open(original);
        document.grantGoldPass(12345,1700000000L,30);
        assertEquals("Gold Pass must not consume unrelated transfer payload",original.length,document.toBytes().length);
        SaveDocument granted=SaveDocument.open(document.toBytes());
        assertEquals(12345,granted.goldPassOfficerId());
        assertEquals(2,granted.goldPassRenewals());
        assertEquals(1700000000L,granted.goldPassDate(0));
        assertTrue(granted.checksumValid());
        granted.removeGoldPass();
        SaveDocument removed=SaveDocument.open(granted.toBytes());
        assertEquals(-1,removed.goldPassOfficerId());
        assertEquals(original.length,removed.toBytes().length);
        assertTrue(removed.checksumValid());
    }

    @Test public void transferCodeSpecialSkillAndEnemyGuideUseSerializedLocations() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/device-transfer-507617.save");
        java.nio.file.Path skillPlus=java.nio.file.Path.of("/tmp/upstream-transfer-ui-skill-plus.save");
        java.nio.file.Path skillBase=java.nio.file.Path.of("/tmp/upstream-transfer-ui-skill-base.save");
        java.nio.file.Path enemy=java.nio.file.Path.of("/tmp/upstream-transfer-single-enemy_guide_0.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)
                && java.nio.file.Files.isRegularFile(skillPlus)
                && java.nio.file.Files.isRegularFile(skillBase)
                && java.nio.file.Files.isRegularFile(enemy));
        byte[] original=java.nio.file.Files.readAllBytes(source);
        SaveDocument plus=SaveDocument.open(original);plus.setSpecialSkillPlusLevel(0,7);
        assertArrayEquals(java.nio.file.Files.readAllBytes(skillPlus),plus.toBytes());
        SaveDocument base=SaveDocument.open(original);base.setSpecialSkillBaseLevel(0,8);
        assertArrayEquals(java.nio.file.Files.readAllBytes(skillBase),base.toBytes());
        SaveDocument enemyDocument=SaveDocument.open(original);enemyDocument.setEnemyGuideUnlocked(0,true);
        assertArrayEquals(java.nio.file.Files.readAllBytes(enemy),enemyDocument.toBytes());
    }

    @Test public void endlessBattleDurationIncludesStoredItemTime() throws Exception {
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save")));
        document.setEndlessBattleDurationMinutes(0,90);
        java.lang.reflect.Method offsetMethod=SaveDocument.class.getDeclaredMethod("endlessBattleOffset",int.class);offsetMethod.setAccessible(true);
        java.lang.reflect.Field bytesField=SaveDocument.class.getDeclaredField("bytes");bytesField.setAccessible(true);
        int offset=(int)offsetMethod.invoke(document,0);byte[] bytes=(byte[])bytesField.get(document);bytes[offset+2]=2;
        assertEquals(450,document.endlessBattleDurationMinutes(0),0.0001);
    }

    @Test public void editsPreserveLengthAndRefreshChecksum() throws Exception {
        byte[] bytes = fixture(SaveDocument.Region.EN, 5);
        SaveDocument document = SaveDocument.open(bytes);
        assertEquals(120200, document.gameVersion());
        document.setCatFood(45000);
        document.setXp(1234567);
        document.setCurrentEnergy(4321);
        document.setMuteBgm(true);
        document.setMuteSe(true);
        assertEquals(45000, document.catFood());
        assertEquals(1234567, document.xp());
        assertEquals(4321, document.currentEnergy());
        assertTrue(document.muteBgm());
        assertTrue(document.muteSe());
        assertTrue(document.checksumValid());
        assertEquals(bytes.length, document.toBytes().length);
        document.convertRegion(SaveDocument.Region.TW);assertEquals(SaveDocument.Region.TW,document.region());assertTrue(document.checksumValid());assertEquals(SaveDocument.Region.TW,SaveDocument.open(document.toBytes()).region());
    }

    @Test public void basicItemLimitsMatchUpstreamDefaults() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        document.setCatFood(45000);document.setXp(99999999);document.setNormalTickets(2999);
        document.setRareTickets(299);document.setPlatinumTickets(9);document.setLegendTickets(4);
        document.setPlatinumShards(9);document.setNp(9999);document.setLeadership(9999);
        assertThrows(IllegalArgumentException.class,()->document.setCatFood(-1));
        assertThrows(IllegalArgumentException.class,()->document.setCatFood(45001));
        assertThrows(IllegalArgumentException.class,()->document.setXp(100000000));
        assertThrows(IllegalArgumentException.class,()->document.setNormalTickets(3000));
        assertThrows(IllegalArgumentException.class,()->document.setRareTickets(300));
        assertThrows(IllegalArgumentException.class,()->document.setPlatinumTickets(10));
        assertThrows(IllegalArgumentException.class,()->document.setLegendTickets(5));
        assertThrows(IllegalArgumentException.class,()->document.setPlatinumShards(10));
        assertThrows(IllegalArgumentException.class,()->document.setNp(10000));
        assertThrows(IllegalArgumentException.class,()->document.setLeadership(10000));
        assertTrue(document.checksumValid());
    }

    @Test public void itemArrayLimitsMatchUpstreamDefaults() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        document.setBattleItem(5,9999);document.setCatseye(5,9999);document.setCatamin(2,9999);
        document.setCatfruit(28,998);document.setAllCatfruit(document.catfruitLimit());document.setTreasureChest(38,9999);
        document.setLabyrinthMedal(3,9999);document.setHundredMillionTicket(9999);
        document.setBaseMaterial(document.baseMaterialCount()-1,9999);
        document.setLuckyTicket(document.luckyTicketCount()-1,9999);
        document.setEventTicket(document.eventTicketCount()-1,9999);
        document.addTalentOrb(321,998);document.setTalentOrbAmount(document.talentOrbCount()-1,998);
        assertThrows(IllegalArgumentException.class,()->document.setBattleItem(0,10000));
        assertThrows(IllegalArgumentException.class,()->document.setCatseye(0,-1));
        assertThrows(IllegalArgumentException.class,()->document.setCatamin(0,10000));
        assertThrows(IllegalArgumentException.class,()->document.setCatfruit(0,999));
        assertThrows(IllegalArgumentException.class,()->document.setAllCatfruit(document.catfruitLimit()+1));
        assertThrows(IllegalArgumentException.class,()->document.setTreasureChest(0,10000));
        assertThrows(IllegalArgumentException.class,()->document.setLabyrinthMedal(0,10000));
        assertThrows(IllegalArgumentException.class,()->document.setHundredMillionTicket(10000));
        assertThrows(IllegalArgumentException.class,()->document.setBaseMaterial(0,10000));
        assertThrows(IllegalArgumentException.class,()->document.setLuckyTicket(0,10000));
        assertThrows(IllegalArgumentException.class,()->document.setEventTicket(0,10000));
        assertThrows(IllegalArgumentException.class,()->document.setTalentOrbAmount(document.talentOrbCount()-1,999));
        for(int value:document.catfruit())assertEquals(document.catfruitLimit(),value);
        assertTrue(document.checksumValid());
    }

    @Test public void androidUiBasicItemEditsMatchUpstreamBytes() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");
        java.nio.file.Path expected=java.nio.file.Path.of("/tmp/bcsfe-upstream-ui-items.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        document.setCatFood(1234);document.setXp(5678);document.setNormalTickets(12);
        document.setRareTickets(13);document.setPlatinumTickets(2);document.setLegendTickets(3);
        document.setPlatinumShards(7);document.setNp(89);document.setLeadership(90);
        document.setBattleItem(5,91);document.setCatseye(5,92);document.setCatamin(2,93);
        document.setCatfruit(28,94);document.setTreasureChest(38,95);
        document.setLabyrinthMedal(3,96);document.setHundredMillionTicket(97);
        document.setBaseMaterial(document.baseMaterialCount()-1,98);
        document.setLuckyTicket(document.luckyTicketCount()-1,99);
        document.setEventTicket(document.eventTicketCount()-1,100);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),document.toBytes());
    }

    @Test public void verifiedTwProfileEditsMajorFields() throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument document = SaveDocument.open(bytes);
        document.setRareSeed(123); document.setNormalSeed(456); document.setEventSeed(789);
        document.setGamatotoXp(321); document.setChallengeScore(654);
        document.fixGamatotoCrash(); document.unlockEquipMenu();
        document.resetGoldenCpuCount();assertEquals(0,document.goldenCpuCount());document.enableFilibusterStage(23);assertTrue(document.filibusterStageEnabled());assertEquals(23,document.filibusterStageId());
        assertEquals(123, document.rareSeed()); assertEquals(456, document.normalSeed());
        assertEquals(789, document.eventSeed()); assertEquals(321, document.gamatotoXp());
        assertEquals(654, document.challengeScore()); assertTrue(document.checksumValid());
    }

    @Test public void verifiedProfileAllowsAndPreservesUnknownTrailingPayload() throws Exception {
        byte[] original = fixture(SaveDocument.Region.TW, 5, 507008);
        byte[] padded = new byte[original.length + 13];
        int payloadLength = original.length - 32;
        System.arraycopy(original, 0, padded, 0, payloadLength);
        for (int i = 0; i < 13; i++) padded[payloadLength + i] = (byte) (0x70 + i);
        System.arraycopy(original, payloadLength, padded, payloadLength + 13, 32);
        refreshHash(padded, SaveDocument.Region.TW);

        SaveDocument document = SaveDocument.open(padded);
        assertTrue(document.hasItemProfile());
        document.setNormalTickets(123);
        document.setCatBaseLevel(0, 20);
        byte[] edited = document.toBytes();
        assertEquals(123, document.normalTickets());
        assertEquals(20, document.catBaseLevel(0));
        assertArrayEquals(java.util.Arrays.copyOfRange(padded, payloadLength, payloadLength + 13),
                java.util.Arrays.copyOfRange(edited, payloadLength, payloadLength + 13));
        assertTrue(document.checksumValid());
    }

    @Test public void verifiedTwProfileEditsCatsGuidesAndRewards() throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument document = SaveDocument.open(bytes);
        document.setCatUnlocked(12, true);
        document.setCatBaseLevel(12, 50);
        document.setCatPlusLevel(12, 0);
        document.setCatUnlockedForms(12, 3);
        document.setCatGuideCollected(12, true);
        document.setSpecialSkillBaseLevel(0, 7);
        document.setEnemyGuideUnlocked(801, true);
        int reward=document.userRankRewardCount()-1;document.setUserRankRewardClaimed(reward, true);
        assertTrue(document.catUnlocked(12)); assertEquals(50,document.catBaseLevel(12));
        assertEquals(0,document.catPlusLevel(12)); assertEquals(3,document.catUnlockedForms(12)); assertTrue(document.catGuideCollected(12));
        assertEquals(7,document.specialSkillBaseLevel(0)); assertTrue(document.enemyGuideUnlocked(801));
        assertTrue(document.userRankRewardClaimed(reward)); assertTrue(document.checksumValid());
    }

    @Test public void basicUpgradeLimitsMatchUpstreamAbilityData() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        for(int i=0;i<document.specialSkillCount();i++){
            int maxBase=i==1?10:20,maxPlus=i==1?0:10;
            document.setSpecialSkillBaseLevel(i,maxBase);document.setSpecialSkillPlusLevel(i,maxPlus);
            final int skill=i;
            assertThrows(IllegalArgumentException.class,()->document.setSpecialSkillBaseLevel(skill,maxBase+1));
            assertThrows(IllegalArgumentException.class,()->document.setSpecialSkillPlusLevel(skill,maxPlus+1));
        }
        assertTrue(document.checksumValid());
    }

    @Test public void catLevelLimitsMatchUpstreamUnitBuyData() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        assertEquals(20,GameDataRules.catMaxBase(0));assertEquals(90,GameDataRules.catMaxPlus(0));
        assertEquals(50,GameDataRules.catMaxBase(9));assertEquals(0,GameDataRules.catMaxPlus(9));
        assertEquals(60,GameDataRules.catMaxBase(872));assertEquals(70,GameDataRules.catMaxPlus(872));
        document.setCatBaseLevel(0,20);document.setCatPlusLevel(0,90);
        assertThrows(IllegalArgumentException.class,()->document.setCatBaseLevel(0,21));
        assertThrows(IllegalArgumentException.class,()->document.setCatPlusLevel(9,1));
        document.setAllCatBaseLevels(60);document.setAllCatPlusLevels(90);
        assertEquals(20,document.catBaseLevel(0));assertEquals(90,document.catPlusLevel(0));
        assertEquals(50,document.catBaseLevel(9));assertEquals(0,document.catPlusLevel(9));
        assertEquals(60,document.catBaseLevel(872));assertEquals(70,document.catPlusLevel(872));
        assertThrows(IllegalArgumentException.class,()->document.setAllCatBaseLevels(61));
        assertThrows(IllegalArgumentException.class,()->document.setAllCatPlusLevels(91));
        assertTrue(document.checksumValid());
    }

    @Test public void talentEditorHidesPlaceholdersAndUsesUpstreamMaximums() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        java.util.List<SaveDocument.TalentValue> talents=document.catTalents(9);
        assertEquals(5,talents.size());assertEquals(10,talents.get(0).id);assertEquals(10,talents.get(0).maxLevel);
        assertEquals(55,talents.get(4).id);assertEquals(1,talents.get(4).maxLevel);
        document.setCatUnlocked(9,false);document.setCatTalentLevel(9,4,1);assertTrue(document.catUnlocked(9));
        assertThrows(IllegalArgumentException.class,()->document.setCatTalentLevel(9,4,2));
        document.maxAllCatTalents();SaveDocument reopened=SaveDocument.open(document.toBytes());
        for(SaveDocument.TalentValue talent:reopened.catTalents(9))assertEquals(talent.maxLevel,talent.level);
        assertTrue(reopened.checksumValid());
    }

    @Test public void verifiedTwProfileEditsStoryAkuAndMissions() throws Exception {
        byte[] bytes=fixture(SaveDocument.Region.TW,5,507008);
        putInt(bytes,415665,1);putInt(bytes,415669,42);putInt(bytes,415673,0);
        putInt(bytes,415677,1);putInt(bytes,415681,42);putInt(bytes,415685,5);
        bytes[494327]=1;bytes[494329]=49;bytes[494330]=1;
        refreshHash(bytes,SaveDocument.Region.TW);
        SaveDocument document=SaveDocument.open(bytes);
        document.setStoryClearTimes(2,47,3);document.setStoryTreasure(2,47,3);
        document.setAkuClearTimes(0,0,48,2);document.setMissionCompletion(42,4);
        assertEquals(3,document.storyClearTimes(2,47));assertEquals(3,document.storyTreasure(2,47));
        assertEquals(2,document.akuClearTimes(0,0,48));assertEquals(4,document.missionClearState(42));assertEquals(0,document.missionRequirement(42));document.setMissionCompletion(42,0);assertEquals(0,document.missionClearState(42));assertEquals(0,document.missionRequirement(42));assertThrows(IllegalArgumentException.class,()->document.setMissionCompletion(42,1));assertTrue(document.checksumValid());
    }

    @Test public void realTwSaveStageLayoutsMatchUpstream() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        SaveDocument.StageMap[] types=SaveDocument.StageMap.values();
        for(SaveDocument.StageMap type:types){
            int expected=switch(type){case CHALLENGE->1;case GAUNTLETS->82;case ENIGMA_CLEARS->73;case COLLAB_GAUNTLETS->28;case EVENT->2500;case UNCANNY->49;case CATAMIN->52;case BEHEMOTH->3;case LEGEND_QUEST->1;case TOWER->12;case ZERO_LEGENDS->34;case DOJO->12;};
            assertEquals(type.name(),expected,document.stageMapCount(type));
            int map=type==SaveDocument.StageMap.EVENT?501:0;assertTrue(document.stageMapStarCount(type,map)>0);assertTrue(document.stageMapStageCount(type,map,0)>0);
            int old=document.stageMapClearTimes(type,map,0,0);document.setStageMapClearTimes(type,map,0,0,old==7?6:7);assertTrue(document.checksumValid());document.setStageMapClearTimes(type,map,0,0,old);
        }
    }

    @Test public void realTwSaveGamatotoOtotoAndShrineMatchUpstream() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        assertEquals(256,d.gamatotoHelperCount());assertEquals(16,d.baseMaterialCount());assertEquals(8,d.cannonCount());
        d.setGamatotoHelper(255,7);d.setBaseMaterial(15,8);d.setOtotoEngineers(5);d.setCannonDevelopment(1,0);d.setCannonPartLevel(1,0,5);d.setCannonPartLevel(0,0,29);d.setCatShrineXp(123456789L);d.setCatShrineGone(true);
        assertEquals(7,d.gamatotoHelper(255));assertEquals(8,d.baseMaterial(15));assertEquals(5,d.ototoEngineers());assertThrows(IllegalArgumentException.class,()->d.setOtotoEngineers(6));assertEquals(3,d.cannonDevelopment(1));assertEquals(5,d.cannonPartLevel(1,0));assertEquals(3,d.cannonDevelopment(0));assertEquals(29,d.cannonPartLevel(0,0));assertThrows(IllegalArgumentException.class,()->d.setCannonPartLevel(0,0,30));assertEquals(123456789L,d.catShrineXp());assertTrue(d.catShrineGone());assertTrue(d.checksumValid());
    }

    @Test public void ototoMaterialsEngineersAndCannonsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument material=SaveDocument.open(bytes);material.setBaseMaterial(15,8);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-material15.save")),material.toBytes());
        SaveDocument engineers=SaveDocument.open(bytes);engineers.setOtotoEngineers(5);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-engineers5.save")),engineers.toBytes());
        SaveDocument development=SaveDocument.open(bytes);development.setCannonDevelopment(1,0);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cannon1-development0.save")),development.toBytes());
        SaveDocument normal=SaveDocument.open(bytes);normal.setCannonPartLevel(1,0,4);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cannon1-part0-level5.save")),normal.toBytes());
        SaveDocument base=SaveDocument.open(bytes);base.setCannonPartLevel(0,0,29);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cannon0-part0-level30.save")),base.toBytes());
    }

    @Test public void gamatotoHelpersRebuildByRarityLikeUpstream() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));int material=d.baseMaterial(0);
        assertArrayEquals(new int[]{0,0,0,0,0},d.gamatotoHelperRarityAmounts());d.setGamatotoHelperRarityAmounts(new int[]{2,1,3,0,4});
        assertEquals(10,d.gamatotoHelperCount());assertArrayEquals(new int[]{0,0,0,0,0},new int[]{d.gamatotoHelper(0)-1,d.gamatotoHelper(1)-2,d.gamatotoHelper(2)-54,d.gamatotoHelper(3)-84,d.gamatotoHelper(6)-129});
        assertArrayEquals(new int[]{2,1,3,0,4},d.gamatotoHelperRarityAmounts());assertEquals(material,d.baseMaterial(0));assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-gamatoto-helpers.save")),d.toBytes());assertThrows(IllegalArgumentException.class,()->d.setGamatotoHelperRarityAmounts(new int[]{3,3,3,3,0}));assertTrue(d.checksumValid());
    }

    @Test public void gamatotoLevelAndXpMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument level2=SaveDocument.open(bytes);level2.setGamatotoLevel(2);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-gamatoto-level2.save")),level2.toBytes());
        SaveDocument xp=SaveDocument.open(bytes);xp.setGamatotoXp(12345);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-gamatoto-xp.save")),xp.toBytes());assertThrows(IllegalArgumentException.class,()->xp.setGamatotoXp(-1));
        SaveDocument level130=SaveDocument.open(bytes);level130.setGamatotoLevel(130);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-gamatoto-level130.save")),level130.toBytes());assertThrows(IllegalArgumentException.class,()->level130.setGamatotoLevel(131));
    }

    @Test public void gatyaSeedsUseUnsigned32BitValuesAndMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument rare=SaveDocument.open(bytes);rare.setRareSeed(4000000000L);assertEquals(4000000000L,rare.rareSeed());assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-rare-seed.save")),rare.toBytes());
        SaveDocument normal=SaveDocument.open(bytes);normal.setNormalSeed(3000000000L);assertEquals(3000000000L,normal.normalSeed());assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-normal-seed.save")),normal.toBytes());
        SaveDocument event=SaveDocument.open(bytes);event.setEventSeed(3500000000L);assertEquals(3500000000L,event.eventSeed());assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-event-seed.save")),event.toBytes());assertThrows(IllegalArgumentException.class,()->event.setEventSeed(0x100000000L));
    }

    @Test public void unlockedLineupsEnemyGuideAndLabyrinthMedalsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument lineups=SaveDocument.open(bytes);assertEquals(19,lineups.unlockableLineupCount());lineups.setUnlockedLineups(7);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-unlocked-lineups7.save")),lineups.toBytes());assertThrows(IllegalArgumentException.class,()->lineups.setUnlockedLineups(20));
        SaveDocument enemy=SaveDocument.open(bytes);enemy.setEnemyGuideUnlocked(0,true);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-enemy0-unlock.save")),enemy.toBytes());
        SaveDocument allEnemy=SaveDocument.open(bytes);allEnemy.setAllEnemyGuide(true);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-enemy-all-unlock.save")),allEnemy.toBytes());
        SaveDocument resetEnemy=SaveDocument.open(bytes);resetEnemy.setAllEnemyGuide(false);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-enemy-all-reset.save")),resetEnemy.toBytes());
        SaveDocument medals=SaveDocument.open(bytes);medals.setLabyrinthMedal(3,9999);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-labyrinth3.save")),medals.toBytes());
    }

    @Test public void catShrineAppearMatchesUpstreamAndCatUnlockSetsSeenState() throws Exception {
        java.nio.file.Path before=java.nio.file.Path.of("/tmp/shrine-before.save"),after=java.nio.file.Path.of("/tmp/shrine-appear.save"),tw=java.nio.file.Path.of("/tmp/bcsfe-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(before)&&java.nio.file.Files.isRegularFile(after)&&java.nio.file.Files.isRegularFile(tw));
        SaveDocument shrine=SaveDocument.open(java.nio.file.Files.readAllBytes(before));shrine.setCatShrineGone(false);assertArrayEquals(java.nio.file.Files.readAllBytes(after),shrine.toBytes());
        SaveDocument cat=SaveDocument.open(java.nio.file.Files.readAllBytes(tw));cat.setCatUnlocked(0,true);byte[] output=cat.toBytes();assertEquals(1,output[20289]&255);assertTrue(cat.checksumValid());
    }

    @Test public void catShrineLevelXpAndVisibilityMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument level=SaveDocument.open(bytes);level.setCatShrineLevel(2);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-shrine-level2.save")),level.toBytes());
        SaveDocument xp=SaveDocument.open(bytes);xp.setCatShrineXp(123456789L);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-shrine-xp.save")),xp.toBytes());assertThrows(IllegalArgumentException.class,()->xp.setCatShrineXp(575600001L));
        SaveDocument appear=SaveDocument.open(bytes);appear.setCatShrineGone(false);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-shrine-appear.save")),appear.toBytes());
        SaveDocument disappear=SaveDocument.open(bytes);disappear.setCatShrineGone(true);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-shrine-disappear.save")),disappear.toBytes());
    }

    @Test public void gameDataDrivenFormsDropsAndLevelsUseUpstreamRules() throws Exception {
        java.nio.file.Path tw=java.nio.file.Path.of("/tmp/bcsfe-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(tw));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(tw));
        d.removeTrueForms();d.unlockTrueForms();
        assertEquals(2,d.catCurrentForm(0));
        assertEquals("Two-form cats should be switched to their second form like upstream",1,d.catCurrentForm(27));
        d.removeFourthForms();d.unlockFourthForms();
        assertEquals(3,d.catCurrentForm(59));
        assertEquals("Three-form cats should retain their available True Form like upstream",2,d.catCurrentForm(0));
        assertEquals("Two-form cats should retain their available second form like upstream",1,d.catCurrentForm(27));
        d.removeTrueForms();assertEquals(0,d.catFourthForm(59));assertTrue(d.catCurrentForm(59)<=1);
        d.setCatUnlocked(45,true);
        assertEquals("Cat 45 unlocks drop slot 0",1,littleInt(d.toBytes(),295008));
        d.setGamatotoLevel(2);assertEquals(600,d.gamatotoXp());assertEquals(2,d.gamatotoLevel());
        d.setGamatotoXp(12345);assertEquals(12345,d.gamatotoXp());
        d.setGamatotoDestination(7);assertEquals(7,d.gamatotoDestination());
        d.setGamatotoLevel(130);assertEquals(100104200,d.gamatotoXp());assertEquals(130,d.gamatotoLevel());
        d.setCatShrineLevel(2);assertEquals(100000L,d.catShrineXp());assertEquals(2,d.catShrineLevel());assertEquals(1,d.catShrineDialogs());
        assertTrue(d.checksumValid());
    }

    @Test public void realTwSaveEventItemsLineupsAndPassMatchUpstream() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        assertEquals(55,d.luckyTicketCount());assertEquals(62,d.eventTicketCount());assertEquals(21,d.lineupCount());assertEquals(0,d.talentOrbCount());
        d.setEndlessBattleItem(5,true);d.setLuckyTicket(54,7);d.setEventTicket(61,8);d.setLineupCat(20,9,12);d.setUnlockedLineups(7);d.setRestartPackState(2);d.setGoldPassOfficerId(4);d.setGoldPassRenewals(3);d.setGoldPassDate(0,1700000000L);d.setGoldPassStateUpdates(6);d.setOfficerPassCatId(5);d.setOfficerPassCatForm(2);
        assertTrue(d.endlessBattleItem(5));assertEquals(7,d.luckyTicket(54));assertEquals(8,d.eventTicket(61));assertEquals(12,d.lineupCat(20,9));assertEquals(7,d.unlockedLineups());assertEquals(2,d.restartPackState());assertEquals(4,d.goldPassOfficerId());assertEquals(3,d.goldPassRenewals());assertEquals(1700000000L,d.goldPassDate(0));assertEquals(6,d.goldPassStateUpdates());assertEquals(5,d.officerPassCatId());assertEquals(2,d.officerPassCatForm());assertTrue(d.checksumValid());
    }

    @Test public void endlessBattleItemsUseUpstreamDurationRecord() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        d.setEndlessBattleDurationMinutes(0,90);assertEquals(90,d.endlessBattleDurationMinutes(0),0.0001);assertTrue(d.endlessBattleItem(0));
        d.setEndlessBattleDurationMinutes(5,Double.POSITIVE_INFINITY);assertTrue(Double.isInfinite(d.endlessBattleDurationMinutes(5)));
        byte[] out=d.toBytes();int base=494168;assertEquals(1,out[base]&255);assertEquals(1,out[base+1]&255);assertEquals(0,out[base+2]&255);
        assertThrows(IllegalArgumentException.class,()->d.setEndlessBattleDurationMinutes(0,-1));assertThrows(IllegalArgumentException.class,()->d.setEndlessBattleDurationMinutes(0,Double.NaN));assertTrue(d.checksumValid());
    }

    @Test public void realTwSaveScoresAndEnigmaMatchUpstream() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        assertEquals(3,d.timedScoreChapterCount());assertEquals(48,d.timedScoreStageCount());assertEquals(0,d.enigmaStageCount());
        d.setDojoScore(123);d.setDojoRanking(7);d.setTimedScore(2,47,456);d.setEnigmaLevel(5);d.setEnigmaEnergy(789);
        assertEquals(123,d.dojoScore());assertEquals(7,d.dojoRanking());assertEquals(456,d.timedScore(2,47));assertEquals(5,d.enigmaLevel());assertEquals(789,d.enigmaEnergy());assertTrue(d.checksumValid());
    }

    @Test public void itfTimedScoreMatchesUpstreamStoryField() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save"),expected=java.nio.file.Path.of("/tmp/bcsfe-upstream-itf-score.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path)&&java.nio.file.Files.isRegularFile(expected));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        d.setTimedScore(0,0,1234);assertEquals(1234,d.timedScore(0,0));assertArrayEquals(java.nio.file.Files.readAllBytes(expected),d.toBytes());assertThrows(IllegalArgumentException.class,()->d.setTimedScore(0,0,10000));assertThrows(IndexOutOfBoundsException.class,()->d.setTimedScore(3,0,1));
    }

    @Test public void storyVisibleChapterMappingAndProgressMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);assertEquals(9,SaveDocument.open(bytes).storyChapterCount());assertEquals(48,SaveDocument.open(bytes).storyStageCount());
        SaveDocument single=SaveDocument.open(bytes);single.setStoryClearTimes(3,10,0);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-story-single.save")),single.toBytes());
        SaveDocument cleared=SaveDocument.open(bytes);cleared.clearStoryChapter(3,true);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-story-whole-clear.save")),cleared.toBytes());
        SaveDocument zeroed=SaveDocument.open(bytes);zeroed.clearStoryChapter(3,false);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-story-whole-zero.save")),zeroed.toBytes());assertThrows(IndexOutOfBoundsException.class,()->zeroed.storyClearTimes(9,0));
    }

    @Test public void storyTreasureStageOrderAndCustomLevelMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument single=SaveDocument.open(bytes);single.setStoryTreasure(3,0,3);assertEquals(3,single.storyTreasure(3,0));assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-treasure-single.save")),single.toBytes());
        SaveDocument whole=SaveDocument.open(bytes);whole.setStoryChapterTreasures(3,9999);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-treasure-whole.save")),whole.toBytes());assertThrows(IllegalArgumentException.class,()->whole.setStoryTreasure(0,0,10000));
    }

    @Test public void filibusterReclearMatchesUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save"),expected=java.nio.file.Path.of("/tmp/bcsfe-upstream-filibuster.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));d.enableFilibusterStage(13);assertEquals(13,d.filibusterStageId());assertTrue(d.filibusterStageEnabled());assertArrayEquals(java.nio.file.Files.readAllBytes(expected),d.toBytes());
    }

    @Test public void realTwSaveStorageAndRepairsMatchUpstream() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));byte[] original=java.nio.file.Files.readAllBytes(path);SaveDocument d=SaveDocument.open(original);
        assertEquals(128,d.storageCount());d.setStorageItem(127,2,9);assertEquals(2,d.storageItemType(127));assertEquals(9,d.storageItemId(127));assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-storage-slot127.save")),d.toBytes());
        int missions=d.missionCount(),maps=d.stageMapCount(SaveDocument.StageMap.ZERO_LEGENDS);
        d.fixTimeErrors(1700000000L);d.fixOtotoValues();
        assertEquals(original.length-247,d.toBytes().length);assertEquals(0,d.cannonCount());
        assertEquals(missions,d.missionCount());assertEquals(maps,d.stageMapCount(SaveDocument.StageMap.ZERO_LEGENDS));
        d.fixOfficerPass();
        assertEquals(0,d.ototoEngineers());assertEquals(-1,d.goldPassOfficerId());assertEquals(0,d.playTime());
        assertTrue(d.checksumValid());assertEquals(0,SaveDocument.open(d.toBytes()).cannonCount());
    }

    @Test public void repairActionsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument gamatoto=SaveDocument.open(bytes);gamatoto.fixGamatotoCrash();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-fix-gamatoto.save")),gamatoto.toBytes());
        SaveDocument ototo=SaveDocument.open(bytes);ototo.fixOtotoValues();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-fix-ototo.save")),ototo.toBytes());
        SaveDocument time=SaveDocument.open(bytes);time.fixTimeErrors(1700000000L);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-fix-time.save")),time.toBytes());
        SaveDocument equip=SaveDocument.open(bytes);equip.unlockEquipMenu();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-unlock-equip.save")),equip.toBytes());
        SaveDocument officer=SaveDocument.open(bytes);officer.fixOfficerPass();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-fix-officer.save")),officer.toBytes());
    }

    @Test public void wholeMapProgressOperationsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-map-input.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument.StageMap[] types={SaveDocument.StageMap.GAUNTLETS,SaveDocument.StageMap.ENIGMA_CLEARS,SaveDocument.StageMap.COLLAB_GAUNTLETS,SaveDocument.StageMap.EVENT,SaveDocument.StageMap.UNCANNY,SaveDocument.StageMap.CATAMIN,SaveDocument.StageMap.BEHEMOTH,SaveDocument.StageMap.LEGEND_QUEST,SaveDocument.StageMap.TOWER,SaveDocument.StageMap.ZERO_LEGENDS,SaveDocument.StageMap.DOJO};
        String[] names={"gauntlets","enigma_clears","collab_gauntlets","event","uncanny","catamin","behemoth","legend_quest","tower","zero_legends","dojo"};
        int[] maps={1,2,1,510,1,2,1,0,1,1,1};
        for(int i=0;i<types.length;i++){
            SaveDocument cleared=SaveDocument.open(bytes);cleared.clearStageMap(types[i],maps[i],true);assertArrayEquals(names[i]+" clear",java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-"+names[i]+"-map-clear.save")),cleared.toBytes());
            SaveDocument reset=SaveDocument.open(bytes);reset.clearStageMap(types[i],maps[i],false);assertArrayEquals(names[i]+" reset",java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-"+names[i]+"-map-reset.save")),reset.toBytes());
        }
    }

    @Test public void mapCrownLimitsMatchTw155MapOptions() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        assertEquals(1,d.stageMapStarCount(SaveDocument.StageMap.EVENT,500));assertEquals(3,d.stageMapStarCount(SaveDocument.StageMap.EVENT,508));assertEquals(4,d.stageMapStarCount(SaveDocument.StageMap.EVENT,509));
        assertEquals(1,d.stageMapStarCount(SaveDocument.StageMap.EVENT,1000));assertEquals(4,d.stageMapStarCount(SaveDocument.StageMap.EVENT,1001));assertEquals(3,d.stageMapStarCount(SaveDocument.StageMap.EVENT,1003));
        assertEquals(4,d.stageMapStarCount(SaveDocument.StageMap.UNCANNY,48));assertEquals(1,d.stageMapStarCount(SaveDocument.StageMap.TOWER,11));assertEquals(1,d.stageMapStarCount(SaveDocument.StageMap.ZERO_LEGENDS,22));assertEquals(2,d.stageMapStarCount(SaveDocument.StageMap.ZERO_LEGENDS,23));
        assertThrows(IndexOutOfBoundsException.class,()->d.stageMapStageCount(SaveDocument.StageMap.TOWER,0,1));assertThrows(IndexOutOfBoundsException.class,()->d.stageMapStageCount(SaveDocument.StageMap.ZERO_LEGENDS,22,1));
    }

    @Test public void bulkCatAndGuideActionsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument unlock=SaveDocument.open(bytes);unlock.unlockAllCats();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-unlock-all.save")),unlock.toBytes());
        SaveDocument remove=SaveDocument.open(bytes);remove.removeAllCats();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-remove-keep.save")),remove.toBytes());
        SaveDocument reset=SaveDocument.open(bytes);reset.resetAllCats();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-reset-all.save")),reset.toBytes());
        SaveDocument guide=SaveDocument.open(bytes);guide.setAllCatGuideCollected(true);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-catguide-all.save")),guide.toBytes());
        SaveDocument guideRemove=SaveDocument.open(bytes);guideRemove.setAllCatGuideCollected(false);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-catguide-remove-all.save")),guideRemove.toBytes());
    }

    @Test public void remainingBulkCatActionsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument base=SaveDocument.open(bytes);base.setAllCatBaseLevels(60);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-base-60.save")),base.toBytes());
        SaveDocument plus=SaveDocument.open(bytes);plus.setAllCatPlusLevels(90);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-plus-90.save")),plus.toBytes());
        SaveDocument trueForm=SaveDocument.open(bytes);trueForm.unlockTrueForms();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-true.save")),trueForm.toBytes());
        SaveDocument forceTrue=SaveDocument.open(bytes);forceTrue.forceTrueForms();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-true-force.save")),forceTrue.toBytes());
        SaveDocument removeTrue=SaveDocument.open(bytes);removeTrue.removeTrueForms();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-true-remove.save")),removeTrue.toBytes());
        SaveDocument fourth=SaveDocument.open(bytes);fourth.unlockFourthForms();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-fourth.save")),fourth.toBytes());
        SaveDocument forceFourth=SaveDocument.open(bytes);forceFourth.forceFourthForms();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-fourth-force.save")),forceFourth.toBytes());
        SaveDocument removeFourth=SaveDocument.open(bytes);removeFourth.removeFourthForms();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-fourth-remove.save")),removeFourth.toBytes());
        SaveDocument talents=SaveDocument.open(bytes);talents.maxAllCatTalents();assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-cats-talents-max.save")),talents.toBytes());
    }

    @Test public void storageEditorUsesUpstreamItemTypesAndAtomicCapacityChecks() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));d.clearStorage();
        d.addStorageCats(12,2);d.addStorageSpecialSkills(4,3);assertEquals(5,d.occupiedStorageCount());assertEquals(1,d.storageItemType(0));assertEquals(12,d.storageItemId(1));assertEquals(2,d.storageItemType(2));assertEquals(4,d.storageItemId(4));
        d.removeOccupiedStorageItem(1);assertEquals(0,d.storageItemType(1));assertEquals(4,d.occupiedStorageCount());int before=d.occupiedStorageCount();assertThrows(IllegalStateException.class,()->d.addStorageCats(1,d.storageCount()));assertEquals(before,d.occupiedStorageCount());assertThrows(IllegalArgumentException.class,()->d.addStorageSpecialSkills(10,1));assertTrue(d.checksumValid());
    }

    @Test public void realTwSaveOutbreaksMatchUpstream() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        assertEquals(9,d.outbreakChapterCount());assertEquals(0,d.outbreakChapterId(0));assertEquals(48,d.outbreakStageCount(0));
        assertTrue(d.currentOutbreakCleared(0,0));d.setOutbreakCleared(0,0,false);assertTrue(d.currentOutbreakCleared(0,0));d.setOutbreakCleared(0,0,true);assertFalse(d.currentOutbreakCleared(0,0));
        int stageId=d.outbreakStageId(0,47);boolean old=d.outbreakCleared(0,47);d.setOutbreakCleared(0,47,!old);
        assertEquals(stageId,d.outbreakStageId(0,47));assertEquals(!old,d.outbreakCleared(0,47));assertTrue(d.checksumValid());
        d.setOutbreakChapterCleared(0,true);for(int i=0;i<48;i++)assertTrue(d.outbreakCleared(0,i));
        d.setOutbreakChapterCleared(0,false);for(int i=0;i<48;i++)assertFalse(d.outbreakCleared(0,i));assertTrue(d.checksumValid());
    }

    @Test public void outbreakSingleAndWholeOperationsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument singleClear=SaveDocument.open(bytes);singleClear.setOutbreakCleared(0,0,true);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-outbreak-clear.save")),singleClear.toBytes());
        SaveDocument singleUnclear=SaveDocument.open(bytes);singleUnclear.setOutbreakCleared(0,0,false);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-outbreak-unclear.save")),singleUnclear.toBytes());
        SaveDocument wholeClear=SaveDocument.open(bytes);wholeClear.setOutbreakChapterCleared(0,true);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-outbreak-whole-clear.save")),wholeClear.toBytes());
        SaveDocument wholeUnclear=SaveDocument.open(bytes);wholeUnclear.setOutbreakChapterCleared(0,false);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-outbreak-whole-unclear.save")),wholeUnclear.toBytes());
    }

    @Test public void akuProgressMatchesUpstreamEditor() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));byte[] bytes=java.nio.file.Files.readAllBytes(source);
        SaveDocument progress=SaveDocument.open(bytes);progress.setAkuProgress(10,2);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-aku-progress10.save")),progress.toBytes());
        SaveDocument clear=SaveDocument.open(bytes);clear.setAkuProgress(49,1);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-aku-clear.save")),clear.toBytes());
        SaveDocument reset=SaveDocument.open(bytes);reset.setAkuProgress(0,0);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-upstream-aku-reset.save")),reset.toBytes());assertThrows(IllegalArgumentException.class,()->reset.setAkuProgress(50,1));
    }

    @Test public void realTwSaveCanAddAndRemoveTalentOrbsWithoutBreakingLaterFields() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));byte[] original=java.nio.file.Files.readAllBytes(path);SaveDocument d=SaveDocument.open(original);
        assertEquals(0,d.talentOrbCount());int aku=d.akuChapterCount(),chests=d.treasureChests()[0],maps=d.stageMapCount(SaveDocument.StageMap.ZERO_LEGENDS);
        d.addTalentOrb(321,7);assertEquals(original.length+4,d.toBytes().length);assertEquals(1,d.talentOrbCount());assertEquals(321,d.talentOrbId(0));assertEquals(7,d.talentOrbAmount(0));
        assertEquals(aku,d.akuChapterCount());assertEquals(chests,d.treasureChests()[0]);assertEquals(maps,d.stageMapCount(SaveDocument.StageMap.ZERO_LEGENDS));assertTrue(d.checksumValid());
        SaveDocument reopened=SaveDocument.open(d.toBytes());assertEquals(321,reopened.talentOrbId(0));reopened.setTalentOrbAmount(0,9);assertEquals(9,reopened.talentOrbAmount(0));
        reopened.removeTalentOrb(0);assertEquals(original.length,reopened.toBytes().length);assertEquals(0,reopened.talentOrbCount());assertEquals(aku,reopened.akuChapterCount());assertTrue(reopened.checksumValid());
    }

    @Test public void talentOrbAddReplacesExistingIdLikeUpstreamDictionary() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        d.addTalentOrb(333,6);assertEquals(1,d.talentOrbCount());d.addTalentOrb(333,9);assertEquals(1,d.talentOrbCount());assertEquals(333,d.talentOrbId(0));assertEquals(9,d.talentOrbAmount(0));assertTrue(d.checksumValid());
    }

    @Test public void realTwSaveCanAddAndRemoveMedalsWithoutBreakingVariableTables() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));byte[] original=java.nio.file.Files.readAllBytes(path);SaveDocument d=SaveDocument.open(original);
        assertEquals(0,d.medalCount());int gauntlets=d.stageMapCount(SaveDocument.StageMap.GAUNTLETS),enigma=d.enigmaEnergy(),aku=d.akuChapterCount();
        d.addMedal(42);assertEquals(original.length+5,d.toBytes().length);assertEquals(1,d.medalCount());assertEquals(42,d.medalId(0));assertEquals(gauntlets,d.stageMapCount(SaveDocument.StageMap.GAUNTLETS));assertEquals(enigma,d.enigmaEnergy());assertEquals(aku,d.akuChapterCount());
        d.addTalentOrb(77,3);SaveDocument reopened=SaveDocument.open(d.toBytes());assertEquals(42,reopened.medalId(0));assertEquals(77,reopened.talentOrbId(0));assertEquals(aku,reopened.akuChapterCount());assertTrue(reopened.checksumValid());
        reopened.removeMedal(0);assertEquals(0,reopened.medalCount());assertEquals(77,reopened.talentOrbId(0));reopened.removeTalentOrb(0);assertEquals(original.length,reopened.toBytes().length);assertTrue(reopened.checksumValid());
    }

    @Test public void realTwSaveCanAddAndRemoveEnigmaStagesWithoutBreakingLaterTables() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));byte[] original=java.nio.file.Files.readAllBytes(path);SaveDocument d=SaveDocument.open(original);
        assertEquals(0,d.enigmaStageCount());int collab=d.stageMapCount(SaveDocument.StageMap.COLLAB_GAUNTLETS),aku=d.akuChapterCount();
        d.addEnigmaStage(25001,3,2,1700000000L);assertEquals(original.length+17,d.toBytes().length);assertEquals(1,d.enigmaStageCount());assertEquals(25001,d.enigmaStageId(0));assertEquals(3,d.enigmaStageLevel(0));assertEquals(2,d.enigmaStageDecoding(0));assertEquals(1700000000L,d.enigmaStageStartTime(0));
        assertEquals(collab,d.stageMapCount(SaveDocument.StageMap.COLLAB_GAUNTLETS));assertEquals(aku,d.akuChapterCount());d.addTalentOrb(88,4);SaveDocument reopened=SaveDocument.open(d.toBytes());assertEquals(25001,reopened.enigmaStageId(0));assertEquals(88,reopened.talentOrbId(0));assertTrue(reopened.checksumValid());
        reopened.removeEnigmaStage(0);assertEquals(0,reopened.enigmaStageCount());assertEquals(88,reopened.talentOrbId(0));reopened.removeTalentOrb(0);assertEquals(original.length,reopened.toBytes().length);assertTrue(reopened.checksumValid());
    }

    @Test public void activeEnigmaOperationMatchesUpstreamCompletionDictionary() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save"),expected=java.nio.file.Path.of("/tmp/bcsfe-upstream-enigma-one.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));d.addActiveEnigmaStage(1,1700000000L);assertArrayEquals(java.nio.file.Files.readAllBytes(expected),d.toBytes());assertThrows(IllegalArgumentException.class,()->d.addActiveEnigmaStage(73,0));d.clearActiveEnigmaStages();assertEquals(0,d.enigmaStageCount());assertTrue(d.checksumValid());
    }

    @Test public void realTwSaveCanEditBothGamblingTablesAndPreserveDownstreamData() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));byte[] original=java.nio.file.Files.readAllBytes(path);SaveDocument d=SaveDocument.open(original);
        assertEquals(1,d.gamblingStartCount(SaveDocument.GamblingTable.WILDCAT_SLOTS));assertEquals(7,d.gamblingStartKey(SaveDocument.GamblingTable.WILDCAT_SLOTS,0));assertEquals(20260715,d.gamblingStartDate(SaveDocument.GamblingTable.WILDCAT_SLOTS,0));assertEquals(0,d.gamblingStartCount(SaveDocument.GamblingTable.CAT_SCRATCHER));
        int enigma=d.enigmaEnergy(),aku=d.akuChapterCount();d.addGamblingStart(SaveDocument.GamblingTable.WILDCAT_SLOTS,8,20270101);d.addGamblingStart(SaveDocument.GamblingTable.CAT_SCRATCHER,9,20270202);
        assertEquals(original.length+12,d.toBytes().length);assertEquals(2,d.gamblingStartCount(SaveDocument.GamblingTable.WILDCAT_SLOTS));assertEquals(1,d.gamblingStartCount(SaveDocument.GamblingTable.CAT_SCRATCHER));assertEquals(enigma,d.enigmaEnergy());assertEquals(aku,d.akuChapterCount());
        SaveDocument reopened=SaveDocument.open(d.toBytes());assertEquals(20270101,reopened.gamblingStartDate(SaveDocument.GamblingTable.WILDCAT_SLOTS,1));assertEquals(20270202,reopened.gamblingStartDate(SaveDocument.GamblingTable.CAT_SCRATCHER,0));reopened.removeGamblingStart(SaveDocument.GamblingTable.CAT_SCRATCHER,0);reopened.removeGamblingStart(SaveDocument.GamblingTable.WILDCAT_SLOTS,1);assertEquals(original.length,reopened.toBytes().length);assertTrue(reopened.checksumValid());
    }

    @Test public void realTwSaveCanReplaceFixedLengthAccountCredentials() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        assertEquals(9,d.inquiryCode().length());assertEquals(40,d.passwordRefreshToken().length());
        String inquiry="123456789",refresh="0123456789012345678901234567890123456789";d.setInquiryCode(inquiry);d.setPasswordRefreshToken("__________EXPECT_THIS_TO_FAIL___________");d.setPasswordRefreshToken(refresh);d.setAccountCreatedAt(1700000000L);assertEquals(inquiry,d.inquiryCode());assertEquals(refresh,d.passwordRefreshToken());assertEquals(1700000000L,d.accountCreatedAt());assertTrue(d.checksumValid());SaveDocument reopened=SaveDocument.open(d.toBytes());assertEquals(inquiry,reopened.inquiryCode());assertEquals(refresh,reopened.passwordRefreshToken());assertEquals(1700000000L,reopened.accountCreatedAt());
    }

    @Test public void receivedVariableLengthSaveExposesNetworkCredentials() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-network-response.bin");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        assertEquals(9,document.inquiryCode().length());assertEquals(40,document.passwordRefreshToken().length());
        assertEquals(5,document.normalTickets());assertEquals(2,document.rareTickets());assertEquals(9,document.platinumTickets());
        assertEquals(16322,document.playTime());assertEquals(755,document.userRank());
        int legend=document.legendTickets();document.setLegendTickets(legend+1);assertEquals(legend+1,document.legendTickets());
        assertTrue(document.checksumValid());
    }

    @Test public void realTwSaveCanEditSchemeItemsAndPrepareRareTicketTrade() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));byte[] original=java.nio.file.Files.readAllBytes(path);SaveDocument d=SaveDocument.open(original);
        assertEquals(0,d.schemeToObtainCount());assertEquals(0,d.schemeReceivedCount());int slot=d.firstEmptyStorageSlot(),cannons=d.cannonCount(),aku=d.akuChapterCount();d.addSchemeItem(47);assertEquals(original.length+4,d.toBytes().length);assertEquals(1,d.schemeToObtainCount());assertEquals(47,d.schemeToObtainId(0));assertEquals(cannons,d.cannonCount());assertEquals(aku,d.akuChapterCount());assertThrows(IllegalArgumentException.class,()->d.addSchemeItem(1234));
        d.tradeRareTickets(6);assertEquals(30,d.rareTicketTradeProgress());assertEquals(2,d.storageItemType(slot));assertEquals(1,d.storageItemId(slot));SaveDocument reopened=SaveDocument.open(d.toBytes());assertEquals(47,reopened.schemeToObtainId(0));reopened.removeSchemeItem(47);assertEquals(0,reopened.schemeToObtainCount());assertEquals(original.length,reopened.toBytes().length);assertTrue(reopened.checksumValid());
    }

    @Test public void upstreamGeneratedEnAndKrProfilesSupportDeepFields() throws Exception {
        for (String code : new String[]{"en", "kr"}) {
            java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-"+code+".save");
            Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
            SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
            assertTrue(code,d.hasItemProfile());
            assertEquals(code,150500,d.gameVersion());
            assertEquals(code,861,d.catCount());
            assertEquals(code,82,d.stageMapCount(SaveDocument.StageMap.GAUNTLETS));
            assertEquals(code,28,d.stageMapCount(SaveDocument.StageMap.COLLAB_GAUNTLETS));
            int old=d.treasureChests()[38];d.setTreasureChest(38,old+1);
            d.addTalentOrb(456,8);d.addMedal(71);d.addEnigmaStage(25002,4,2,1700000001L);
            SaveDocument reopened=SaveDocument.open(d.toBytes());
            assertEquals(code,old+1,reopened.treasureChests()[38]);
            assertEquals(code,456,reopened.talentOrbId(reopened.talentOrbCount()-1));
            assertEquals(code,71,reopened.medalId(reopened.medalCount()-1));
            assertEquals(code,25002,reopened.enigmaStageId(reopened.enigmaStageCount()-1));
            assertTrue(code,reopened.checksumValid());
        }
    }

    @Test public void nonJpRegionConversionAddsAndRemovesEnVersionBlock() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
        byte[] original=java.nio.file.Files.readAllBytes(path);
        SaveDocument d=SaveDocument.open(original);
        d.convertRegion(SaveDocument.Region.EN);
        assertEquals(original.length+5,d.toBytes().length);
        assertEquals(SaveDocument.Region.EN,SaveDocument.open(d.toBytes()).region());
        assertTrue(d.hasItemProfile());
        d.setTreasureChest(38,123);
        d.convertRegion(SaveDocument.Region.KR);
        assertEquals(original.length,d.toBytes().length);
        SaveDocument reopened=SaveDocument.open(d.toBytes());
        assertEquals(SaveDocument.Region.KR,reopened.region());
        assertEquals(123,reopened.treasureChests()[38]);
    }

    @Test public void upstreamGeneratedJpProfileSupportsMajorEditors() throws Exception {
        java.nio.file.Path path=java.nio.file.Path.of("/tmp/bcsfe-jp.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(path));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(path));
        assertEquals(SaveDocument.Region.JP,d.region());assertTrue(d.hasItemProfile());
        assertEquals(1,d.akuChapterCount());assertEquals(49,d.akuStageCount());assertEquals(1,d.akuStarCount());
        assertEquals(82,d.stageMapCount(SaveDocument.StageMap.GAUNTLETS));assertEquals(28,d.stageMapCount(SaveDocument.StageMap.COLLAB_GAUNTLETS));
        assertEquals(4,d.leadership());assertEquals(128,d.storageCount());assertEquals(21,d.lineupCount());
        d.setCatFood(1234);d.setXp(5678);d.setRareTickets(12);d.setLeadership(44);d.setCatBaseLevel(0,20);d.setCatPlusLevel(0,4);d.setEventSeed(9876);d.setTreasureChest(38,55);d.addTalentOrb(333,6);
        SaveDocument reopened=SaveDocument.open(d.toBytes());
        assertEquals(1234,reopened.catFood());assertEquals(5678,reopened.xp());assertEquals(12,reopened.rareTickets());assertEquals(44,reopened.leadership());assertEquals(20,reopened.catBaseLevel(0));assertEquals(4,reopened.catPlusLevel(0));assertEquals(9876,reopened.eventSeed());assertEquals(55,reopened.treasureChests()[38]);assertEquals(333,reopened.talentOrbId(reopened.talentOrbCount()-1));assertTrue(reopened.checksumValid());
    }

    @Test public void jpRegionConversionMatchesUpstreamReserialization() throws Exception {
        java.nio.file.Path tw=java.nio.file.Path.of("/tmp/bcsfe-tw.save"),jp=java.nio.file.Path.of("/tmp/bcsfe-jp.save"),jpToTw=java.nio.file.Path.of("/tmp/bcsfe-jp-to-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(tw)&&java.nio.file.Files.isRegularFile(jp)&&java.nio.file.Files.isRegularFile(jpToTw));

        SaveDocument toJp=SaveDocument.open(java.nio.file.Files.readAllBytes(tw));
        toJp.convertRegion(SaveDocument.Region.JP);
        assertArrayEquals(java.nio.file.Files.readAllBytes(jp),toJp.toBytes());
        assertEquals(SaveDocument.Region.JP,SaveDocument.open(toJp.toBytes()).region());

        SaveDocument toTw=SaveDocument.open(java.nio.file.Files.readAllBytes(jp));
        toTw.convertRegion(SaveDocument.Region.TW);
        assertArrayEquals(java.nio.file.Files.readAllBytes(jpToTw),toTw.toBytes());
        assertEquals(SaveDocument.Region.TW,SaveDocument.open(toTw.toBytes()).region());

        SaveDocument toEn=SaveDocument.open(java.nio.file.Files.readAllBytes(jp));
        toEn.convertRegion(SaveDocument.Region.EN);
        SaveDocument reopened=SaveDocument.open(toEn.toBytes());
        assertEquals(SaveDocument.Region.EN,reopened.region());
        assertEquals(toTw.catFood(),reopened.catFood());
        assertEquals(toTw.xp(),reopened.xp());
    }

    @Test public void jpConversionPreservesVariableLengthEditors() throws Exception {
        java.nio.file.Path tw=java.nio.file.Path.of("/tmp/bcsfe-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(tw));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(tw));
        d.addMedal(112);d.addTalentOrb(345,9);d.addEnigmaStage(25003,5,2,1700000002L);d.addSchemeItem(48);d.grantGoldPass(54321,1700000000L,30);
        d.convertRegion(SaveDocument.Region.JP);
        SaveDocument jp=SaveDocument.open(d.toBytes());
        assertEquals(SaveDocument.Region.JP,jp.region());assertEquals(112,jp.medalId(jp.medalCount()-1));assertEquals(345,jp.talentOrbId(jp.talentOrbCount()-1));assertEquals(25003,jp.enigmaStageId(jp.enigmaStageCount()-1));assertEquals(48,jp.schemeToObtainId(jp.schemeToObtainCount()-1));assertEquals(54321,jp.goldPassOfficerId());
        jp.convertRegion(SaveDocument.Region.KR);
        SaveDocument kr=SaveDocument.open(jp.toBytes());
        assertEquals(SaveDocument.Region.KR,kr.region());assertEquals(112,kr.medalId(kr.medalCount()-1));assertEquals(345,kr.talentOrbId(kr.talentOrbCount()-1));assertEquals(25003,kr.enigmaStageId(kr.enigmaStageCount()-1));assertEquals(48,kr.schemeToObtainId(kr.schemeToObtainCount()-1));assertEquals(54321,kr.goldPassOfficerId());assertTrue(kr.checksumValid());
    }

    @Test public void modernGameVersionConversionsMatchUpstreamBytes() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        int[] versions={150400,150300,150200,150100,150000,140700,140500,140300};
        for(int version:versions){java.nio.file.Path expected=java.nio.file.Path.of("/tmp/bcsfe-tw-"+version+".save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(expected));SaveDocument converted=SaveDocument.open(java.nio.file.Files.readAllBytes(source));converted.convertGameVersion(version);assertArrayEquals("version "+version,java.nio.file.Files.readAllBytes(expected),converted.toBytes());assertEquals(version,SaveDocument.open(converted.toBytes()).gameVersion());}
        SaveDocument upgraded=SaveDocument.open(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-tw-150200.save")));upgraded.convertGameVersion(150500);assertArrayEquals(java.nio.file.Files.readAllBytes(source),upgraded.toBytes());
        SaveDocument v140000=SaveDocument.open(java.nio.file.Files.readAllBytes(source));v140000.convertGameVersion(140000);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-tw-140000.save")),v140000.toBytes());
        SaveDocument from140000=SaveDocument.open(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-tw-140000.save")));from140000.convertGameVersion(150500);assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/tmp/bcsfe-tw-140000-to-150500.save")),from140000.toBytes());
    }

    @Test public void oneTapProgressionOperationsMatchUpstreamBytes() throws Exception {
        java.nio.file.Path tutorialBefore=java.nio.file.Path.of("/tmp/bcsfe-upstream-tutorial-before.save"),tutorialAfter=java.nio.file.Path.of("/tmp/bcsfe-upstream-tutorial-after.save"),akuAfter=java.nio.file.Path.of("/tmp/bcsfe-upstream-aku-unlock.save"),source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(tutorialBefore)&&java.nio.file.Files.isRegularFile(tutorialAfter)&&java.nio.file.Files.isRegularFile(akuAfter)&&java.nio.file.Files.isRegularFile(source));
        SaveDocument tutorial=SaveDocument.open(java.nio.file.Files.readAllBytes(tutorialBefore));tutorial.clearTutorial();assertArrayEquals(java.nio.file.Files.readAllBytes(tutorialAfter),tutorial.toBytes());
        SaveDocument aku=SaveDocument.open(java.nio.file.Files.readAllBytes(source));aku.unlockAkuRealm();assertArrayEquals(java.nio.file.Files.readAllBytes(akuAfter),aku.toBytes());byte[] once=aku.toBytes();aku.unlockAkuRealm();assertArrayEquals(once,aku.toBytes());assertEquals(1,aku.akuChapterCount());assertEquals(49,aku.akuStageCount());
    }

    @Test public void unlockObtainableCatsExcludesHiddenCatGuideEntries() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(source));document.removeAllCats();document.unlockAllObtainableCats();
        int obtainable=0;for(int id=0;id<document.catCount();id++){if(GameDataRules.catObtainable(id)){assertTrue("obtainable cat "+id,document.catUnlocked(id));obtainable++;}else assertFalse("hidden cat "+id,document.catUnlocked(id));}
        assertEquals(759,obtainable);assertTrue(document.checksumValid());
    }

    @Test public void obtainableCatRulesAreRegionSpecific() {
        assertTrue(GameDataRules.catObtainable(SaveDocument.Region.JP,150500,28));
        assertFalse(GameDataRules.catObtainable(SaveDocument.Region.TW,150500,28));
        assertFalse(GameDataRules.catObtainable(SaveDocument.Region.EN,150500,77));
        assertTrue(GameDataRules.catObtainable(SaveDocument.Region.TW,150500,77));
        assertFalse(GameDataRules.catObtainable(SaveDocument.Region.JP,150101,28));
    }

    @Test public void each155RegionUnlocksItsOwnObtainableCatSubset() throws Exception {
        int[] expected={737,816,759,758};
        SaveDocument.Region[] regions=SaveDocument.Region.values();
        for(int i=0;i<regions.length;i++){
            SaveDocument document=SaveDocument.open(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/"+regions[i].code()+".save")));
            document.removeAllCats();document.unlockAllObtainableCats();int unlocked=0;
            for(int cat=0;cat<document.catCount();cat++)if(document.catUnlocked(cat))unlocked++;
            assertEquals(regions[i].code(),expected[i],unlocked);assertTrue(document.checksumValid());
        }
    }

    @Test public void goldPassOperationMatchesUpstreamAndHandlesClaimDictionary() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save"),expected=java.nio.file.Path.of("/tmp/bcsfe-upstream-gold-pass.save"),claims=java.nio.file.Path.of("/tmp/bcsfe-tw-gold-claims.save");
        Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected)&&java.nio.file.Files.isRegularFile(claims));
        SaveDocument granted=SaveDocument.open(java.nio.file.Files.readAllBytes(source));granted.grantGoldPass(12345,1700000000L,30);assertArrayEquals(java.nio.file.Files.readAllBytes(expected),granted.toBytes());
        SaveDocument removed=SaveDocument.open(java.nio.file.Files.readAllBytes(claims));assertTrue(removed.hasItemProfile());removed.removeGoldPass();assertEquals(507008,removed.toBytes().length);assertEquals(-1,removed.goldPassOfficerId());assertEquals(39,removed.treasureChests().length);assertTrue(SaveDocument.open(removed.toBytes()).checksumValid());
    }

    @Test public void catResetClearsFullProfileAndRefreshesRankUpSale() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));int cat=0;
        d.setCatUnlocked(cat,true);d.setCatBaseLevel(cat,20);d.setCatPlusLevel(cat,13);d.setCatCurrentForm(cat,3);d.setCatUnlockedForms(cat,3);d.setCatFourthForm(cat,2);d.setCatGuideCollected(cat,true);
        assertEquals(0x7fffffff,d.rankUpSaleValue());
        d.resetCat(cat);SaveDocument reopened=SaveDocument.open(d.toBytes());
        assertFalse(reopened.catUnlocked(cat));assertEquals(1,reopened.catBaseLevel(cat));assertEquals(0,reopened.catPlusLevel(cat));assertEquals(0,reopened.catCurrentForm(cat));assertEquals(0,reopened.catUnlockedForms(cat));assertEquals(0,reopened.catFourthForm(cat));assertFalse(reopened.catGuideCollected(cat));assertEquals(0x7fffffff,reopened.rankUpSaleValue());assertTrue(reopened.checksumValid());
    }

    @Test public void bulkCatLevelsKeepRankLimitsAndResetDoesNotRelockThenRelock() throws Exception {
        byte[] original=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument levels=SaveDocument.open(original);levels.setAllCatBaseLevels(1);byte[] levelBytes=levels.toBytes();
        assertEquals(0,littleUshort(levelBytes,Offsets.offsets_62));
        assertEquals(GameDataRules.catRankLimitBase(9,id->true),littleUshort(levelBytes,Offsets.offsets_62+9*4));
        assertEquals(GameDataRules.catRankLimitBase(34,id->true),littleUshort(levelBytes,Offsets.offsets_62+34*4));
        assertEquals(0,littleInt(levelBytes,Offsets.offsets_63+9*4));
        assertEquals(0,littleInt(levelBytes,Offsets.offsets_63+34*4));
        SaveDocument high=SaveDocument.open(original);high.setAllCatBaseLevels(50);byte[] highBytes=high.toBytes();
        assertEquals(30,littleUshort(highBytes,Offsets.offsets_62+9*4));assertEquals(20,littleInt(highBytes,Offsets.offsets_63+9*4));
        assertEquals(30,littleUshort(highBytes,Offsets.offsets_62+34*4));assertEquals(20,littleInt(highBytes,Offsets.offsets_63+34*4));

        SaveDocument reset=SaveDocument.open(original);reset.setCatUnlocked(9,true);reset.setCatTalentLevel(9,0,5);reset.resetCat(9);
        assertFalse(reset.catUnlocked(9));assertEquals(0,littleInt(reset.toBytes(),Offsets.offsets_58+9*4));
        for(SaveDocument.TalentValue talent:reset.catTalents(9))assertEquals(0,talent.level);
        assertTrue(reset.checksumValid());
    }

    @Test public void bulkStoryOperationsUseDirectUpstreamStageFields() throws Exception {
        byte[] original=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument d=SaveDocument.open(original);d.setStoryTreasure(3,0,3);byte[] treasure=d.toBytes();int rawChapter=4;
        assertEquals(3,littleInt(treasure,Offsets.offsets_67+(rawChapter*49)*4));
        assertEquals(0,littleInt(treasure,Offsets.offsets_67+(rawChapter*49+45)*4));
        d.clearStoryChapter(3,false);assertEquals(0,d.storyClearTimes(3,0));assertEquals(0,littleInt(d.toBytes(),Offsets.offsets_68+rawChapter*4));
        assertTrue(d.checksumValid());
    }

    @Test public void akuRealmBulkUnlockPreservesExistingClearCounts() throws Exception {
        byte[] original=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument d=SaveDocument.open(original);d.setStageMapClearTimes(SaveDocument.StageMap.EVENT,755,0,0,4);d.unlockAkuRealm();
        assertEquals(4,d.stageMapClearTimes(SaveDocument.StageMap.EVENT,755,0,0));assertTrue(d.checksumValid());
    }

    @Test public void bulkPlusLevelsOnlyTouchPlusFields() throws Exception {
        byte[] original=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument d=SaveDocument.open(original);int baseLimit=littleUshort(original,Offsets.offsets_62),plusLimit=littleUshort(original,Offsets.offsets_102);
        d.setAllCatPlusLevels(0);byte[] out=d.toBytes();
        assertEquals(0,littleUshort(out,Offsets.offsets_40));assertEquals(baseLimit,littleUshort(out,Offsets.offsets_62));assertEquals(plusLimit,littleUshort(out,Offsets.offsets_102));assertTrue(d.checksumValid());
    }

    @Test public void clearingAStageMapDoesNotLowerExistingClearCounts() throws Exception {
        byte[] original=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument d=SaveDocument.open(original);SaveDocument.StageMap type=SaveDocument.StageMap.GAUNTLETS;
        d.setStageMapClearTimes(type,0,0,0,7);d.clearStageMap(type,0,true);
        assertEquals(7,d.stageMapClearTimes(type,0,0,0));assertTrue(d.checksumValid());
    }

    @Test public void replacementAccountCanClearBanMessageFlag() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        d.setShowBanMessage(true);assertTrue(d.showBanMessage());d.setShowBanMessage(false);SaveDocument reopened=SaveDocument.open(d.toBytes());assertFalse(reopened.showBanMessage());assertTrue(reopened.checksumValid());
    }

    @Test public void playTimeComponentsMatchUpstreamThirtyFpsConversion() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        d.setPlayTimeComponents(2,3,4);assertEquals(221520,d.playTime());assertEquals(2,d.playTimeHours());assertEquals(3,d.playTimeMinutesPart());assertEquals(4,d.playTimeSecondsPart());d.setPlayTimeComponents(Integer.MAX_VALUE,0,0);assertEquals(Integer.MAX_VALUE,d.playTime());assertTrue(d.checksumValid());
    }

    @Test public void userRankRewardsRespectUpstreamRankGiftThresholds() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        assertEquals(343,d.knownUserRankRewardCount());assertFalse(d.userRankRewardEligible(0));assertTrue(d.userRankRewardEligible(52));assertThrows(IllegalArgumentException.class,()->d.setEligibleUserRankRewardClaimed(0,true));d.setEligibleUserRankRewardClaimed(52,true);assertTrue(d.userRankRewardClaimed(52));d.setUserRankRewardClaimed(0,true);d.fixUserRankRewards();assertFalse(d.userRankRewardClaimed(0));assertTrue(d.userRankRewardClaimed(52));assertTrue(d.checksumValid());
    }

    @Test public void officerPassRepairRemovesClaimDictionaryAndAllPassState() throws Exception {
        java.nio.file.Path claims=java.nio.file.Path.of("/tmp/bcsfe-tw-gold-claims.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(claims));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(claims));d.fixOfficerPass();SaveDocument reopened=SaveDocument.open(d.toBytes());
        assertEquals(507008,reopened.toBytes().length);assertEquals(0,reopened.playTime());assertEquals(-1,reopened.goldPassOfficerId());assertEquals(0,reopened.goldPassRenewals());for(int i=0;i<6;i++)assertEquals(0,reopened.goldPassDate(i));assertEquals(0,reopened.officerPassCatId());assertEquals(0,reopened.officerPassCatForm());assertTrue(reopened.checksumValid());
    }

    @Test public void allGauntletLayoutsMatchUpstreamStageMajorOrdering() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-tw.save"),expected=java.nio.file.Path.of("/tmp/bcsfe-upstream-four-gauntlets.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        assertEquals(73,d.stageMapCount(SaveDocument.StageMap.ENIGMA_CLEARS));
        d.setStageMapClearTimes(SaveDocument.StageMap.GAUNTLETS,1,0,3,7);
        d.setStageMapClearTimes(SaveDocument.StageMap.ENIGMA_CLEARS,2,0,4,8);
        d.setStageMapClearTimes(SaveDocument.StageMap.COLLAB_GAUNTLETS,1,0,5,9);
        d.setStageMapClearTimes(SaveDocument.StageMap.BEHEMOTH,1,0,6,10);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),d.toBytes());
    }

    @Test public void standardChapterMapsMatchUpstreamUnlockAndStageOrdering() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-tw.save"),expected=java.nio.file.Path.of("/tmp/bcsfe-upstream-four-standard-maps.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        d.setStageMapClearTimes(SaveDocument.StageMap.CHALLENGE,0,0,3,7);
        d.setStageMapClearTimes(SaveDocument.StageMap.UNCANNY,1,2,3,8);
        d.setStageMapClearTimes(SaveDocument.StageMap.CATAMIN,2,0,4,9);
        d.setStageMapClearTimes(SaveDocument.StageMap.TOWER,1,0,5,10);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),d.toBytes());
    }

    @Test public void eventMapEditMatchesUpstreamAndPreservesTypeBoundary() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-tw.save"),expected=java.nio.file.Path.of("/tmp/bcsfe-upstream-event-map.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));assertEquals(2500,d.stageMapCount(SaveDocument.StageMap.EVENT));
        d.setStageMapClearTimes(SaveDocument.StageMap.EVENT,510,2,3,7);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),d.toBytes());
    }

    @Test public void legendQuestZeroLegendsAndDojoMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-tw.save"),expected=java.nio.file.Path.of("/tmp/bcsfe-upstream-variable-maps.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(expected));
        SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        d.setStageMapClearTimes(SaveDocument.StageMap.LEGEND_QUEST,0,0,3,7);
        d.setStageMapClearTimes(SaveDocument.StageMap.ZERO_LEGENDS,1,0,2,8);
        d.setStageMapClearTimes(SaveDocument.StageMap.DOJO,1,0,2,9);
        assertArrayEquals(java.nio.file.Files.readAllBytes(expected),d.toBytes());
    }

    @Test public void clearingWholeMapPreservesEarlierCrownCountsAndRebuildsLastCrown() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-tw.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source));SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));
        d.setStageMapClearTimes(SaveDocument.StageMap.EVENT,510,0,0,4);d.clearStageMap(SaveDocument.StageMap.EVENT,510,true);assertEquals(4,d.stageMapClearTimes(SaveDocument.StageMap.EVENT,510,0,0));assertEquals(1,d.stageMapClearTimes(SaveDocument.StageMap.EVENT,510,0,1));d.clearStageMap(SaveDocument.StageMap.EVENT,510,false);assertEquals(0,d.stageMapClearTimes(SaveDocument.StageMap.EVENT,510,0,0));
        d.setStageMapClearTimes(SaveDocument.StageMap.GAUNTLETS,1,0,0,3);d.clearStageMap(SaveDocument.StageMap.GAUNTLETS,1,true);assertEquals(1,d.stageMapClearTimes(SaveDocument.StageMap.GAUNTLETS,1,0,0));assertTrue(d.checksumValid());
    }

    @Test public void selectedCrownsAndBatchMapWritesUseOnlyValidatedRecords() throws Exception {
        byte[] original=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument d=SaveDocument.open(original);
        assertEquals(4,d.stageMapMaxStarCount(SaveDocument.StageMap.EVENT,1));
        int[] crownOffsets=new int[4];for(int star=0;star<4;star++)crownOffsets[star]=mapStageOffset(d,SaveDocument.StageMap.EVENT,1,star,0);
        d.setStageMapClearTimes(SaveDocument.StageMap.EVENT,1,0,0,7);
        d.clearStageMap(SaveDocument.StageMap.EVENT,1,true,4);
        assertEquals(7,d.stageMapClearTimes(SaveDocument.StageMap.EVENT,1,0,0));
        byte[] cleared=d.toBytes();for(int offset:crownOffsets)assertTrue(((cleared[offset]&255)|((cleared[offset+1]&255)<<8))>0);
        assertTrue(d.checksumValid());

        byte[] beforeInvalid=d.toBytes();
        assertThrows(IllegalArgumentException.class,()->d.clearStageMaps(SaveDocument.StageMap.EVENT,1,2,true,5));
        assertArrayEquals(beforeInvalid,d.toBytes());

        SaveDocument zero=SaveDocument.open(original);assertEquals(4,zero.stageMapMaxStarCount(SaveDocument.StageMap.ZERO_LEGENDS,1));
        zero.clearStageMaps(SaveDocument.StageMap.ZERO_LEGENDS,1,2,true,4);
        assertEquals(1,zero.stageMapClearTimes(SaveDocument.StageMap.ZERO_LEGENDS,1,0,0));assertTrue(zero.checksumValid());
    }

    @Test public void zeroLegendsBatchUsesUpstreamCrownAvailability() throws Exception {
        byte[] original=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument zero=SaveDocument.open(original);
        assertEquals(2,zero.stageMapStarCount(SaveDocument.StageMap.ZERO_LEGENDS,0));
        assertEquals(2,zero.stageMapStarCount(SaveDocument.StageMap.ZERO_LEGENDS,10));
        assertEquals(1,zero.stageMapStarCount(SaveDocument.StageMap.ZERO_LEGENDS,11));
        int[] supportedSecond=new int[zero.stageMapCount(SaveDocument.StageMap.ZERO_LEGENDS)];
        int[] unsupportedSecond=new int[supportedSecond.length];
        for(int map=0;map<supportedSecond.length;map++){
            supportedSecond[map]=mapStageOffset(zero,SaveDocument.StageMap.ZERO_LEGENDS,map,1,0);
            unsupportedSecond[map]=mapStageOffset(zero,SaveDocument.StageMap.ZERO_LEGENDS,map,2,0);
        }
        zero.clearStageMaps(SaveDocument.StageMap.ZERO_LEGENDS,0,supportedSecond.length-1,true);
        for(int map=0;map<supportedSecond.length;map++){
            byte[] bytes=zero.toBytes();
            assertEquals(map<11?1:0,(bytes[supportedSecond[map]]&255)|((bytes[supportedSecond[map]+1]&255)<<8));
            assertEquals(0,(bytes[unsupportedSecond[map]]&255)|((bytes[unsupportedSecond[map]+1]&255)<<8));
        }
        assertTrue(zero.checksumValid());

        SaveDocument requestedTwo=SaveDocument.open(original);
        requestedTwo.clearStageMapsUpToConfiguredCrowns(SaveDocument.StageMap.ZERO_LEGENDS,0,supportedSecond.length-1,true,2);
        assertArrayEquals("Upstream zero-legends crown-capped batch",zero.toBytes(),requestedTwo.toBytes());
    }

    @Test public void challengeAndDojoScoreOperationsMatchUpstream() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of("/tmp/bcsfe-tw.save"),challenge=java.nio.file.Path.of("/tmp/bcsfe-upstream-challenge-score.save"),dojo=java.nio.file.Path.of("/tmp/bcsfe-upstream-dojo-score.save");Assume.assumeTrue(java.nio.file.Files.isRegularFile(source)&&java.nio.file.Files.isRegularFile(challenge)&&java.nio.file.Files.isRegularFile(dojo));
        SaveDocument c=SaveDocument.open(java.nio.file.Files.readAllBytes(source));c.setChallengeScore(123456);assertArrayEquals(java.nio.file.Files.readAllBytes(challenge),c.toBytes());SaveDocument d=SaveDocument.open(java.nio.file.Files.readAllBytes(source));assertEquals(0,d.dojoScore());d.setDojoScore(654321);assertEquals(654321,d.dojoScore());assertArrayEquals(java.nio.file.Files.readAllBytes(dojo),d.toBytes());assertEquals(654321,SaveDocument.open(d.toBytes()).dojoScore());
    }

    private static byte[] fixture(SaveDocument.Region region, int start) throws Exception { return fixture(region,start,256); }
    private static int mapStageOffset(SaveDocument document,SaveDocument.StageMap type,int map,int star,int stage) throws Exception {
        java.lang.reflect.Method layoutMethod=SaveDocument.class.getDeclaredMethod("mapLayout",SaveDocument.StageMap.class);layoutMethod.setAccessible(true);Object layout=layoutMethod.invoke(document,type);
        java.lang.reflect.Method offsetMethod=layout.getClass().getDeclaredMethod("stageOffset",int.class,int.class,int.class);offsetMethod.setAccessible(true);return (Integer)offsetMethod.invoke(layout,map,star,stage);
    }
    private static byte[] fixture(SaveDocument.Region region, int start, int size) throws Exception {
        byte[] bytes = new byte[size];
        putInt(bytes, 0, size == 507008 ? 150500 : 120200); putInt(bytes, start + 2, 100); putInt(bytes, start + 71, 200);
        if(size==507008){putInt(bytes,8398,861);int cannon=405173;putInt(bytes,cannon,8);cannon+=4;for(int i=0;i<8;i++){putInt(bytes,cannon,i);putInt(bytes,cannon+4,i==7?10:5);cannon+=8+(i==7?10:5)*4;}bytes[cannon]=1;bytes[463000]=1;bytes[463002]=7;putInt(bytes,463004,20260715);bytes[499600]=7;bytes[499601]=91;}
        String salt=region==SaveDocument.Region.EN?"battlecatsen":region==SaveDocument.Region.TW?"battlecatstw":"battlecats";
        MessageDigest md = MessageDigest.getInstance("MD5"); md.update(salt.getBytes(StandardCharsets.UTF_8)); md.update(bytes, 0, bytes.length - 32);
        String hash = hex(md.digest()); System.arraycopy(hash.getBytes(StandardCharsets.US_ASCII), 0, bytes, bytes.length - 32, 32); return bytes;
    }
    private static void putInt(byte[] bytes, int offset, int value) { bytes[offset] = (byte)value; bytes[offset+1] = (byte)(value>>8); bytes[offset+2] = (byte)(value>>16); bytes[offset+3] = (byte)(value>>24); }
    private static int littleUshort(byte[] bytes,int offset) { return (bytes[offset]&255)|((bytes[offset+1]&255)<<8); }
    private static int littleInt(byte[] bytes,int offset) { return (bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16)|(bytes[offset+3]<<24); }
    private static double littleDouble(byte[] bytes,int offset) { long value=0;for(int i=7;i>=0;i--)value=(value<<8)|(bytes[offset+i]&255L);return Double.longBitsToDouble(value); }
    private static void refreshHash(byte[] bytes, SaveDocument.Region region) throws Exception { String salt=region==SaveDocument.Region.EN?"battlecatsen":region==SaveDocument.Region.TW?"battlecatstw":"battlecats";MessageDigest md=MessageDigest.getInstance("MD5");md.update(salt.getBytes(StandardCharsets.UTF_8));md.update(bytes,0,bytes.length-32);String hash=hex(md.digest());System.arraycopy(hash.getBytes(StandardCharsets.US_ASCII),0,bytes,bytes.length-32,32); }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString(); }
}
