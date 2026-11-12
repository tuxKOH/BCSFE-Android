package io.github.tuxkoh.bcsfe.core;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public class CatBatchEditTest {
    private SaveDocument fixture(SaveDocument.Region region) throws Exception {
        return SaveDocument.fromNewSaveTemplate(Files.readAllBytes(Path.of(
                "src/main/assets/new_saves/" + region.code() + ".save")));
    }

    @Test public void selectionMatchesIndividualEditsAndPreservesUnselectedData() throws Exception {
        int[] selected = {0, 2, 5};
        for (SaveDocument.Region region : SaveDocument.Region.values()) {
            for (SaveDocument.CatBatchEdit edit : SaveDocument.CatBatchEdit.values()) {
                SaveDocument batch = fixture(region), individual = SaveDocument.open(batch.toBytes());
                int value = switch (edit) {
                    case UNLOCKED -> 0; case BASE_LEVEL -> 30; case PLUS_LEVEL -> 12;
                    case CURRENT_FORM -> 2; case RESET -> 0;
                };
                for (int id : selected) switch (edit) {
                    case UNLOCKED -> individual.setCatUnlocked(id, false);
                    case BASE_LEVEL -> individual.setCatBaseLevel(id, value);
                    case PLUS_LEVEL -> individual.setCatPlusLevel(id, value);
                    case CURRENT_FORM -> individual.setCatCurrentForm(id, value);
                    case RESET -> individual.resetCat(id);
                }
                batch.editSelectedCats(selected, edit, value);
                assertArrayEquals(region + " " + edit, individual.toBytes(), batch.toBytes());
                assertTrue(SaveDocument.open(batch.toBytes()).checksumValid());
            }
        }
    }

    @Test public void invalidSelectionOrValueLeavesEntireDocumentUnchanged() throws Exception {
        SaveDocument save = fixture(SaveDocument.Region.TW);
        byte[] original = save.toBytes();
        assertThrows(IndexOutOfBoundsException.class, () -> save.editSelectedCats(
                new int[]{0, save.catCount()}, SaveDocument.CatBatchEdit.UNLOCKED, 0));
        assertArrayEquals(original, save.toBytes());
        assertThrows(IllegalArgumentException.class, () -> save.editSelectedCats(
                new int[]{0, 1}, SaveDocument.CatBatchEdit.CURRENT_FORM, 4));
        assertArrayEquals(original, save.toBytes());
        assertThrows(IllegalArgumentException.class, () -> save.editSelectedCats(
                new int[]{}, SaveDocument.CatBatchEdit.RESET, 0));
        assertArrayEquals(original, save.toBytes());
        save.editSelectedCats(new int[]{0, 0, 2}, SaveDocument.CatBatchEdit.PLUS_LEVEL, 80);
        assertEquals(80, save.catPlusLevel(0));
        assertEquals(80, save.catPlusLevel(2));
        assertTrue(save.checksumValid());
    }
}
