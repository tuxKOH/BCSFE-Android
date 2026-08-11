package io.github.tuxkoh.bcsfe;

import static org.junit.Assert.*;

import java.nio.file.Files;
import org.junit.Test;

public class SessionStoreTest {
    @Test public void sessionSurvivesStoreRecreationAndCanBeReplaced() throws Exception {
        java.io.File directory = Files.createTempDirectory("bcsfe-session").toFile();
        SessionStore first = new SessionStore(directory);
        first.save(new byte[]{1, 2, 3}, "received-save");

        SessionStore.Session restored = new SessionStore(directory).load();
        assertNotNull(restored);
        assertArrayEquals(new byte[]{1, 2, 3}, restored.save);
        assertEquals("received-save", restored.name);

        first.save(new byte[]{9, 8}, "uploaded-save");
        restored = new SessionStore(directory).load();
        assertArrayEquals(new byte[]{9, 8}, restored.save);
        assertEquals("uploaded-save", restored.name);
    }

    @Test public void clearIsTheOnlyOperationThatRemovesSession() throws Exception {
        java.io.File directory = Files.createTempDirectory("bcsfe-session-clear").toFile();
        SessionStore store = new SessionStore(directory);
        store.save(new byte[]{4, 5, 6}, null);
        assertNotNull(store.load());

        store.clear();
        assertNull(store.load());
    }

    @Test public void privateNetworkPasswordSurvivesAndIsClearedWithSession() throws Exception {
        java.io.File directory=Files.createTempDirectory("bcsfe-session-password").toFile();
        SessionStore store=new SessionStore(directory);store.save(new byte[]{7},"SAVE_DATA","temporary-password");
        assertEquals("temporary-password",new SessionStore(directory).load().password);
        store.save(new byte[]{8},"local-import",null);assertNull(store.load().password);
        store.save(new byte[]{9},"SAVE_DATA","second-password");store.clear();
        assertNull(store.load());assertTrue(store.list().isEmpty());
    }

    @Test public void replacingSaveWhileConvertingPreservesPasswordWhenProvided() throws Exception {
        java.io.File directory=Files.createTempDirectory("bcsfe-session-convert").toFile();SessionStore store=new SessionStore(directory);
        store.save(new byte[]{1},"save","password");store.save(new byte[]{2},"save","password");assertEquals("password",store.load().password);
    }

    @Test public void multipleSessionsCanSwitchRenameAndDeleteIndependently() throws Exception {
        java.io.File directory=Files.createTempDirectory("bcsfe-multi-session").toFile();SessionStore store=new SessionStore(directory);
        SessionStore.Session first=store.create(new byte[]{1},"first","one"),second=store.create(new byte[]{2},"second","two");
        assertEquals(2,store.list().size());assertEquals(second.id,store.load().id);
        store.setCurrent(first.id);assertArrayEquals(new byte[]{1},store.load().save);store.rename(first.id,"renamed");assertEquals("renamed",store.load(first.id).name);
        store.delete(first.id);assertNull(store.load(first.id));assertEquals(second.id,store.load().id);assertEquals("two",store.load().password);
    }

    @Test public void historyKeepsInitialStateAndOnlyFiftyLaterSteps() throws Exception {
        java.io.File directory=Files.createTempDirectory("bcsfe-history-limit").toFile();SessionStore store=new SessionStore(directory);
        SessionStore.Session session=store.create(new byte[]{0},"save",null);
        for(int i=1;i<=55;i++)store.save(session.id,new byte[]{(byte)i},"save",null,"step "+i);
        SessionStore.History history=store.history(session.id);
        assertEquals(51,history.entries.size());assertTrue(history.entries.get(0).initial);assertEquals(50,history.currentIndex);
        assertEquals("step 6",history.entries.get(1).label);assertEquals("step 55",history.entries.get(50).label);
        assertArrayEquals(new byte[]{0},store.restoreHistory(session.id,0).save);
    }

    @Test public void historySurvivesRestartAndNewEditDropsRedoBranch() throws Exception {
        java.io.File directory=Files.createTempDirectory("bcsfe-history-branch").toFile();SessionStore store=new SessionStore(directory);
        SessionStore.Session session=store.create(new byte[]{1},"save","one-password");store.save(session.id,new byte[]{2},"save","two-password","two");store.save(session.id,new byte[]{3},"save","three-password","three");
        store=new SessionStore(directory);assertEquals(2,store.history(session.id).currentIndex);SessionStore.Session restored=store.restoreHistory(session.id,1);assertArrayEquals(new byte[]{2},restored.save);assertEquals("two-password",restored.password);
        store.save(session.id,new byte[]{9},"save","nine-password","nine");SessionStore.History history=store.history(session.id);
        assertEquals(3,history.entries.size());assertEquals(2,history.currentIndex);assertEquals("nine",history.entries.get(2).label);assertArrayEquals(new byte[]{1},store.restoreHistory(session.id,0).save);
        assertEquals("one-password",store.load(session.id).password);
        store.delete(session.id);assertNull(store.load(session.id));
    }

    @Test public void metadataOnlySaveDoesNotCreateHistoryStep() throws Exception {
        java.io.File directory=Files.createTempDirectory("bcsfe-history-metadata").toFile();SessionStore store=new SessionStore(directory);
        SessionStore.Session session=store.create(new byte[]{4},"before",null);store.rename(session.id,"after");
        assertEquals(1,store.history(session.id).entries.size());
    }
}
