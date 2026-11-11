package io.github.tuxkoh.bcsfe.core;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ItemRecordLayoutTest {
    private static SaveDocument template(SaveDocument.Region region)throws Exception {
        return SaveDocument.fromNewSaveTemplate(Files.readAllBytes(
                Path.of("src/main/assets/new_saves/"+region.code()+".save")));
    }
    private static int offset(SaveDocument save,String name)throws Exception {
        Method method=SaveDocument.class.getDeclaredMethod(name);method.setAccessible(true);
        return (Integer)method.invoke(save);
    }
    private static void onlyChanged(byte[] before,byte[] after,int start,int length) {
        assertEquals(before.length,after.length);
        for(int i=0;i<before.length-32;i++)if(i<start||i>=start+length)
            assertEquals("Unexpected write at "+i,before[i],after[i]);
    }

    @Test public void everyGradeAddsTheCompleteCatalogAndPreservesUnknownOrbsAndSuffix()throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            for(int grade:new int[]{3,4,-1})for(int amount:new int[]{1,998}){
                SaveDocument save=template(region);
                save.addTalentOrb(50000,17);save.addTalentOrb(4,6);
                byte[] before=save.toBytes();int table=offset(save,"talentOrbTableOffset");
                byte[] suffix=Arrays.copyOfRange(before,table+2+save.talentOrbCount()*4,before.length-32);
                int[] ids=save.talentOrbCatalogIds(grade);
                assertEquals(grade<0?310:62,ids.length);
                save.setTalentOrbsAtGrade(grade,amount);
                SaveDocument reopened=SaveDocument.open(save.toBytes());
                java.util.Map<Integer,Integer> entries=new java.util.LinkedHashMap<>();
                for(int i=0;i<reopened.talentOrbCount();i++)
                    assertNull(entries.put(reopened.talentOrbId(i),reopened.talentOrbAmount(i)));
                for(int id:ids)assertEquals(Integer.valueOf(amount),entries.get(id));
                assertEquals(Integer.valueOf(17),entries.get(50000));
                assertEquals(Integer.valueOf(grade==3?6:amount),entries.get(4));
                assertArrayEquals(suffix,Arrays.copyOfRange(reopened.toBytes(),
                        offset(reopened,"talentOrbTableOffset")+2+reopened.talentOrbCount()*4,
                        reopened.toBytes().length-32));
                assertTrue(reopened.checksumValid());
            }
        }
    }

    @Test public void individualSOrbInsertsRemainCompleteAtAmountOne()throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            SaveDocument save=template(region);
            for(int id:save.talentOrbCatalogIds(4))save.addTalentOrb(id,1);
            SaveDocument reopened=SaveDocument.open(save.toBytes());
            assertEquals(62,reopened.talentOrbCount());
            for(int i=0;i<62;i++){
                assertEquals(i*5+4,reopened.talentOrbId(i));
                assertEquals(1,reopened.talentOrbAmount(i));
            }
        }
    }

    @Test public void leadershipIgnoresVersionIntegersInEarlierFields()throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            SaveDocument save=template(region);save.setRareSeed(80200);
            for(int map=0;map<3;map++)save.setStageMapClearTimes(SaveDocument.StageMap.BEHEMOTH,map,0,map,7+map);
            byte[] before=save.toBytes();int leader=offset(save,"officerPassCatOffset")-2;
            save.setLeadership(117);
            onlyChanged(before,save.toBytes(),leader,2);
            SaveDocument reopened=SaveDocument.open(save.toBytes());
            assertEquals(117,reopened.leadership());assertEquals(80200,reopened.rareSeed());
            for(int map=0;map<3;map++)assertEquals(7+map,reopened.stageMapClearTimes(SaveDocument.StageMap.BEHEMOTH,map,0,map));
        }
    }

    @Test public void endlessEditsAndReceivedTokenPreserveEveryCullingStage()throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            SaveDocument save=template(region);save.setRareSeed(100300);save.setNormalSeed(110000);
            for(int map=0;map<3;map++)for(int stage=0;stage<save.stageMapStageCount(SaveDocument.StageMap.BEHEMOTH,map,0);stage++)
                save.setStageMapClearTimes(SaveDocument.StageMap.BEHEMOTH,map,0,stage,stage+1);
            for(int index=0;index<6;index++){
                byte[] before=save.toBytes();
                int base=offset(save,"passwordRefreshTokenOffset")+75+index*19;
                save.setEndlessBattleDurationMinutes(index,index==5?Double.POSITIVE_INFINITY:90+index);
                onlyChanged(before,save.toBytes(),base,19);
            }
            byte[] before=save.toBytes();int token=offset(save,"passwordRefreshTokenOffset");
            save.setPasswordRefreshToken("0123456789012345678901234567890123456789");
            onlyChanged(before,save.toBytes(),token+4,40);
            SaveDocument reopened=SaveDocument.open(save.toBytes());
            for(int index=0;index<5;index++)assertEquals(90+index,reopened.endlessBattleDurationMinutes(index),0.001);
            assertTrue(Double.isInfinite(reopened.endlessBattleDurationMinutes(5)));
            for(int map=0;map<3;map++)for(int stage=0;stage<reopened.stageMapStageCount(SaveDocument.StageMap.BEHEMOTH,map,0);stage++)
                assertEquals(stage+1,reopened.stageMapClearTimes(SaveDocument.StageMap.BEHEMOTH,map,0,stage));
            assertEquals(110000,reopened.normalSeed());assertTrue(reopened.checksumValid());
        }
    }

    @Test public void brokenNativeItemRecordsRejectWritesWithoutChangingSource()throws Exception {
        for(String record:new String[]{"talentOrbTableOffset","officerPassCatOffset","passwordRefreshTokenOffset"}){
            SaveDocument save=template(SaveDocument.Region.JP);byte[] raw=save.toBytes();
            int field=offset(save,record);
            if(record.equals("talentOrbTableOffset"))raw[field-4]^=1;
            else if(record.equals("officerPassCatOffset"))raw[field+4]^=1;
            else raw[field+71]^=1;
            java.security.MessageDigest md=java.security.MessageDigest.getInstance("MD5");
            md.update(SaveDocument.Region.JP.packageSuffix().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            md.update(raw,0,raw.length-32);
            String hash=java.util.HexFormat.of().formatHex(md.digest());
            System.arraycopy(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,raw,raw.length-32,32);
            final SaveDocument broken=SaveDocument.open(raw);byte[] before=broken.toBytes();
            assertThrows(RuntimeException.class,()->{
                if(record.equals("talentOrbTableOffset"))broken.setTalentOrbsAtGrade(4,1);
                else if(record.equals("officerPassCatOffset"))broken.setLeadership(1);
                else broken.setEndlessBattleDurationMinutes(0,90);
            });
            assertArrayEquals(before,broken.toBytes());
        }
    }
}
