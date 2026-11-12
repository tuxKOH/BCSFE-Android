package io.github.tuxkoh.bcsfe.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModernSaveTailTest {
    private static byte[] template(SaveDocument.Region region) throws Exception {
        return Files.readAllBytes(Path.of("src/main/assets/new_saves/" + region.code() + ".save"));
    }
    private static byte[] rehash(byte[] data, SaveDocument.Region region) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update(region.salt().getBytes(StandardCharsets.US_ASCII));
        digest.update(data, 0, data.length - 32);
        byte[] hash = HexFormat.of().formatHex(digest.digest()).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(hash, 0, data, data.length - 32, 32);
        return data;
    }
    private static byte[] broken(SaveDocument.Region region) throws Exception {
        byte[] bytes = template(region);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(GameVersions.latest(region));
        return rehash(bytes, region);
    }

    @Test public void defaultsMatchIndependentNativeSerializerFixture() throws Exception {
        byte[] nativeDefault = HexFormat.of().parseHex(new String(Files.readAllBytes(Path.of(
                "src/test/resources/native_tails/jp-16.0.1-default-continuation.hex")),
                StandardCharsets.US_ASCII).replaceAll("\\s", ""));
        assertEquals(695, nativeDefault.length);
        assertArrayEquals(nativeDefault, ModernSaveTail.emptyContinuation(160001));
        byte[] twDefault = HexFormat.of().parseHex(new String(Files.readAllBytes(Path.of(
                "src/test/resources/native_tails/tw-15.7.0-default-continuation.hex")),
                StandardCharsets.US_ASCII).replaceAll("\\s", ""));
        assertEquals(191, twDefault.length);
        assertArrayEquals(twDefault, ModernSaveTail.emptyContinuation(150700));
        assertArrayEquals(Arrays.copyOf(twDefault, 171), ModernSaveTail.emptyContinuation(150600));
        assertArrayEquals(Arrays.copyOf(twDefault, 18), ModernSaveTail.emptyContinuation(150500));
    }

    @Test public void importingIsLosslessAndExplicitRepairPreservesEveryExistingByteAndEdit() throws Exception {
        for (SaveDocument.Region region : SaveDocument.Region.values()) {
            byte[] raw = broken(region), original = raw.clone();
            SaveDocument save = SaveDocument.open(raw);
            assertTrue(save.hasIncompleteModernTail());
            assertArrayEquals(raw, save.toBytes());
            save.setCatFood(30000); save.setXp(99999999);
            save.setAkuProgress(10, 7); save.setTalentOrbsAtGrade(4, 1);
            byte[] edited = save.toBytes();
            assertTrue(save.repairIncompleteModernTail());
            byte[] repaired = save.toBytes();
            assertArrayEquals(Arrays.copyOf(edited, edited.length - 32),
                    Arrays.copyOf(repaired, edited.length - 32));
            assertArrayEquals(original, raw);
            assertFalse(save.hasIncompleteModernTail());
            assertFalse(save.repairIncompleteModernTail());
            SaveDocument reopened = SaveDocument.open(repaired);
            assertTrue(reopened.checksumValid());
            assertEquals(30000, reopened.catFood());
            assertEquals(99999999, reopened.xp());
            assertEquals(62, reopened.talentOrbCount());
            assertEquals(7, reopened.akuClearTimes(0, 0, 9));
        }
    }

    @Test public void factoryProducesCompleteRecordsAndLeavesBundledInputUntouched() throws Exception {
        for (SaveDocument.Region region : SaveDocument.Region.values()) {
            byte[] raw = template(region), original = raw.clone();
            SaveDocument save = SaveDocument.fromNewSaveTemplate(raw);
            assertFalse(save.hasIncompleteModernTail());
            assertArrayEquals(original, raw);
            byte[] tail = ModernSaveTail.emptyContinuation(save.gameVersion());
            byte[] bytes = save.toBytes();
            assertArrayEquals(tail, Arrays.copyOfRange(bytes, bytes.length - 32 - tail.length, bytes.length - 32));
        }
    }

    @Test public void unrelatedOrPartialTrailerDoesNotTriggerTemplateRepair() throws Exception {
        byte[] raw = broken(SaveDocument.Region.JP);
        raw[raw.length - 32 - 112 + 77] ^= 1;
        rehash(raw, SaveDocument.Region.JP);
        SaveDocument save = SaveDocument.open(raw);
        assertFalse(save.hasIncompleteModernTail());
        assertFalse(save.repairIncompleteModernTail());
        assertArrayEquals(raw, save.toBytes());
    }
}
