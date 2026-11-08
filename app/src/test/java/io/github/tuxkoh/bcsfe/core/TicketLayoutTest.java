package io.github.tuxkoh.bcsfe.core;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public class TicketLayoutTest {
    /** A valid upstream variable new_dialogs_2 list can resemble gatya_seen. */
    @Test public void ticketReadsDoNotSelectAFalseCatCountInVariableDialogs() throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()){
          for(boolean latest:new boolean[]{false,true}){
           for(int hashLength:new int[]{0,32}){
            byte[] source=Files.readAllBytes(Path.of("src/main/assets/new_saves/"+region.code()+".save"));
            if(latest)source=SaveDocument.fromNewSaveTemplate(source).toBytes();
            SaveDocument baseline=SaveDocument.open(source);
            Method method=SaveDocument.class.getDeclaredMethod("newDialogs2Offset");method.setAccessible(true);
            int header=(Integer)method.invoke(baseline),oldCount=readInt(source,header),count=1200;
            byte[] shifted=new byte[source.length+(count-oldCount)*4];
            System.arraycopy(source,0,shifted,0,header+4);
            System.arraycopy(source,header+4+oldCount*4,shifted,header+4+count*4,source.length-header-4-oldCount*4);
            putInt(shifted,header,count);
            putInt(shifted,header+4,-1);putInt(shifted,header+8,-1);
            putInt(shifted,header+12,baseline.catCount());
            if(hashLength>0){
                int hashOffset=header+4+count*4+88+(region==SaveDocument.Region.JP?7:8)
                        +24+200+(region==SaveDocument.Region.JP?0:1)+24+24;
                int oldLength=readInt(shifted,hashOffset);
                byte[] withHash=new byte[shifted.length+hashLength-oldLength];
                System.arraycopy(shifted,0,withHash,0,hashOffset+4);
                java.util.Arrays.fill(withHash,hashOffset+4,hashOffset+4+hashLength,(byte)'0');
                System.arraycopy(shifted,hashOffset+4+oldLength,withHash,hashOffset+4+hashLength,
                        shifted.length-hashOffset-4-oldLength);
                putInt(withHash,hashOffset,hashLength);shifted=withHash;
            }
            refreshHash(shifted,region);
            SaveDocument actual=SaveDocument.open(shifted);
            assertEquals(region+" normal",baseline.normalTickets(),actual.normalTickets());
            assertEquals(region+" rare",baseline.rareTickets(),actual.rareTickets());
            actual.setNormalTickets(12);actual.setRareTickets(34);
            assertEquals(-1,readInt(actual.toBytes(),header+4));
            assertEquals(-1,readInt(actual.toBytes(),header+8));
            SaveDocument reopened=SaveDocument.open(actual.toBytes());
            assertEquals(12,reopened.normalTickets());assertEquals(34,reopened.rareTickets());
            assertTrue(reopened.checksumValid());
           }
          }
        }
    }

    @Test public void platinumShardsIgnoreTheEarlierEnRegionMarkerAndPreserveOtherBytes()throws Exception{
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            byte[] source=Files.readAllBytes(Path.of("src/main/assets/new_saves/"+region.code()+".save"));
            SaveDocument document=SaveDocument.fromNewSaveTemplate(source);
            byte[] before=document.toBytes();
            assertEquals(region+" shards",0,document.platinumShards());
            Method method=SaveDocument.class.getDeclaredMethod("platinumShardOffset");method.setAccessible(true);
            int offset=(Integer)method.invoke(document);
            document.setPlatinumShards(8);byte[] edited=document.toBytes();
            for(int i=0;i<before.length-32;i++){
                if(i>=offset&&i<offset+4)continue;
                assertEquals("Unexpected shard edit at "+i,before[i],edited[i]);
            }
            SaveDocument reopened=SaveDocument.open(edited);
            assertEquals(8,reopened.platinumShards());assertTrue(reopened.checksumValid());

            // A broken real suffix must not fall back to the EN marker.
            edited[offset+5]^=1;refreshHash(edited,region);
            SaveDocument broken=SaveDocument.open(edited);byte[] unchanged=broken.toBytes();
            assertThrows(UnsupportedOperationException.class,()->broken.setPlatinumShards(1));
            assertArrayEquals(unchanged,broken.toBytes());
        }
    }

    @Test public void accountTokenSurvivesLargeValidLaterTables() throws Exception {
        for (SaveDocument.Region region : SaveDocument.Region.values()) {
          for (boolean latest : new boolean[]{false,true}) {
            byte[] source=Files.readAllBytes(Path.of("src/main/assets/new_saves/"+region.code()+".save"));
            if(latest)source=SaveDocument.fromNewSaveTemplate(source).toBytes();
            else {
                SaveDocument versioned=SaveDocument.open(source);
                versioned.convertGameVersion(150600);
                source=versioned.toBytes();
            }
            SaveDocument baseline=SaveDocument.open(source);
            Method field=SaveDocument.class.getDeclaredMethod("passwordRefreshTokenOffset");
            field.setAccessible(true);
            int token=(Integer)field.invoke(baseline), marker=token+67;
            while (marker<source.length-36 && readInt(source,marker)!=100900) marker++;
            assertTrue("Missing upstream 10.9 block",marker<source.length-36);
            // Upstream: ushdd and ushdd2 are ushort-counted maps of
            // (ushort, double), followed by ub18 and marker 100900.
            // Each bundled template has one entry in each of these maps.
            int start=marker-25;
            java.nio.ByteBuffer original=java.nio.ByteBuffer.wrap(source)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            assertEquals(1,original.getShort(start));
            assertEquals(1,original.getShort(start+12));
            int count=6000, extra=(count-1)*10*2;
            byte[] expanded=new byte[source.length+extra];
            System.arraycopy(source,0,expanded,0,start);
            java.nio.ByteBuffer table=java.nio.ByteBuffer.wrap(expanded)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            table.position(start);
            for (int map=0;map<2;map++) {
                table.putShort((short)count);
                for (int i=0;i<count;i++) table.putShort((short)i).putDouble(0);
            }
            System.arraycopy(source,start+24,expanded,table.position(),source.length-start-24);
            refreshHash(expanded,region);
            // Scrubbed artifacts for independent upstream serializer checks.
            Path fixture=Path.of("build/upload-regression/"+region.code()+"-"+(latest?"latest":"150600")+".save");
            Files.createDirectories(fixture.getParent());Files.write(fixture,expanded);
            assertTrue(expanded.length-token>100000);
            SaveDocument actual=SaveDocument.openForUpload(expanded,region);
            assertEquals(baseline.passwordRefreshToken(),actual.passwordRefreshToken());
            assertEquals(baseline.legendTickets(),actual.legendTickets());
            assertEquals(baseline.platinumShards(),actual.platinumShards());
            assertArrayEquals(expanded,actual.toBytes());
            String replacement="0123456789012345678901234567890123456789";
            actual.setPasswordRefreshToken(replacement);
            byte[] edited=actual.toBytes();
            for(int i=0;i<expanded.length-32;i++) {
                if(i>=token+4 && i<token+44) continue;
                assertEquals("Unexpected account edit at "+i,expanded[i],edited[i]);
            }
            SaveDocument reopened=SaveDocument.open(edited);
            assertEquals(replacement,reopened.passwordRefreshToken());
            assertTrue(reopened.checksumValid());
          }
        }
    }

    @Test public void ambiguousAccountRecordsCannotModifySaveBytes() throws Exception {
        for(SaveDocument.Region region:SaveDocument.Region.values()) {
            byte[] source=Files.readAllBytes(Path.of("src/main/assets/new_saves/"+region.code()+".save"));
            SaveDocument baseline=SaveDocument.open(source);
            Method tokenField=SaveDocument.class.getDeclaredMethod("passwordRefreshTokenOffset");
            Method legendField=SaveDocument.class.getDeclaredMethod("legendTicketOffset");
            Method dialogsField=SaveDocument.class.getDeclaredMethod("newDialogs2Offset");
            tokenField.setAccessible(true);legendField.setAccessible(true);dialogsField.setAccessible(true);
            int token=(Integer)tokenField.invoke(baseline);
            int marker=(Integer)legendField.invoke(baseline)-4;
            int header=(Integer)dialogsField.invoke(baseline),oldCount=readInt(source,header);
            int insert=header+4+oldCount*4,copyLength=token+75-marker;
            int extra=(copyLength+3)/4*4;
            byte[] ambiguous=new byte[source.length+extra];
            System.arraycopy(source,0,ambiguous,0,insert);
            System.arraycopy(source,insert,ambiguous,insert+extra,source.length-insert);
            putInt(ambiguous,header,oldCount+extra/4);
            // Dialog values are arbitrary ints and can mimic even a complete
            // version-delimited account record. Never pick one arbitrarily.
            System.arraycopy(source,marker,ambiguous,insert,copyLength);
            refreshHash(ambiguous,region);
            SaveDocument actual=SaveDocument.openForUpload(ambiguous,region);
            IllegalStateException error=assertThrows(IllegalStateException.class,
                    ()->actual.setPasswordRefreshToken("0123456789012345678901234567890123456789"));
            assertTrue(error.getMessage().contains("Ambiguous"));
            assertArrayEquals(ambiguous,actual.toBytes());
        }
    }

    private static int readInt(byte[] bytes,int p){return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(p);}
    private static void putInt(byte[] bytes,int p,int value){java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(p,value);}
    private static void refreshHash(byte[] source,SaveDocument.Region region)throws Exception{
        MessageDigest md5=MessageDigest.getInstance("MD5");md5.update(("battlecats"+(region==SaveDocument.Region.JP?"":region.code())).getBytes(StandardCharsets.US_ASCII));
        md5.update(source,0,source.length-32);byte[] hash=java.util.HexFormat.of().formatHex(md5.digest()).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(hash,0,source,source.length-32,32);
    }
}
