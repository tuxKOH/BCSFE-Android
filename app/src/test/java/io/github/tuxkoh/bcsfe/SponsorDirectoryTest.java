package io.github.tuxkoh.bcsfe;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class SponsorDirectoryTest {
    @Test public void everyVisitFetchesTheUpdatedDirectoryWithoutHttpCaching() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"sponsors\":[\"first\"]}"));
            server.enqueue(new MockResponse().setBody("{\"sponsors\":[\"new name\"]}"));
            String url = server.url("/sponsors.json").toString();
            assertEquals("first", SponsorDirectory.fetch(url).get(0));
            assertEquals("new name", SponsorDirectory.fetch(url).get(0));
            assertEquals(2, server.getRequestCount());
            assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"));
        }
    }

    @Test public void rejectsFailureMalformedAndOversizedResponses() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404));
            server.enqueue(new MockResponse().setBody("{\"sponsors\":[null]}"));
            server.enqueue(new MockResponse().setBody(" ".repeat(131073)));
            String url = server.url("/sponsors.json").toString();
            for (int i = 0; i < 3; i++) assertThrows(Exception.class, () -> SponsorDirectory.fetch(url));
        }
        assertThrows(Exception.class, () -> SponsorDirectory.parse("{\"sponsors\":[\"   \"]}"));
    }

    @Test public void reachableMirrorWinsWithoutWaitingForBlockedOrigin() throws Exception {
        try (MockWebServer origin = new MockWebServer(); MockWebServer mirror = new MockWebServer()) {
            origin.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            mirror.enqueue(new MockResponse().setBody("{\"sponsors\":[\"mirror name\"]}"));
            assertEquals(List.of("mirror name"), SponsorDirectory.fetch(List.of(
                    origin.url("/sponsors.json").toString(), mirror.url("/sponsors.json").toString()), 1500));
            assertNotNull(mirror.takeRequest(1, TimeUnit.SECONDS));
        }
    }

    @Test public void malformedAndFailedSourcesCannotWinOverValidDirectory() throws Exception {
        try (MockWebServer origin = new MockWebServer(); MockWebServer broken = new MockWebServer();
             MockWebServer mirror = new MockWebServer()) {
            origin.enqueue(new MockResponse().setResponseCode(503));
            broken.enqueue(new MockResponse().setBody("<html>proxy error</html>"));
            mirror.enqueue(new MockResponse().setHeadersDelay(100, TimeUnit.MILLISECONDS)
                    .setBody("{\"sponsors\":[\"valid name\"]}"));
            assertEquals(List.of("valid name"), SponsorDirectory.fetch(List.of(
                    origin.url("/").toString(), broken.url("/").toString(), mirror.url("/").toString()), 1500));
        }
    }

    @Test public void unavailableSourcesFailAndEmptyDirectoryRemainsValid() throws Exception {
        try (MockWebServer first = new MockWebServer(); MockWebServer second = new MockWebServer()) {
            first.enqueue(new MockResponse().setResponseCode(502));
            second.enqueue(new MockResponse().setBody("{\"sponsors\":[42]}"));
            assertThrows(Exception.class, () -> SponsorDirectory.fetch(List.of(
                    first.url("/").toString(), second.url("/").toString()), 1500));
            first.enqueue(new MockResponse().setBody("{\"sponsors\":[]}"));
            assertTrue(SponsorDirectory.fetch(first.url("/").toString()).isEmpty());
        }
    }

    @Test public void overallDeadlineBoundsStalledSourcesAndNextFetchStillWorks() throws Exception {
        try (MockWebServer first = new MockWebServer(); MockWebServer second = new MockWebServer()) {
            first.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            second.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            List<String> urls = List.of(first.url("/").toString(), second.url("/").toString());
            long started = System.nanoTime();
            IOException error = assertThrows(IOException.class, () -> SponsorDirectory.fetch(urls, 200));
            assertTrue(error.getMessage().contains("timed out"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
            first.enqueue(new MockResponse().setBody("{\"sponsors\":[\"recovered\"]}"));
            assertEquals(List.of("recovered"), SponsorDirectory.fetch(first.url("/").toString()));
        }
    }

    @Test public void interruptionCancelsPendingFetchAndPreservesInterruptFlag() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            String url = server.url("/").toString();
            AtomicBoolean interrupted = new AtomicBoolean();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { SponsorDirectory.fetch(List.of(url), 5000); }
                catch (InterruptedException expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
                catch (Throwable error) { unexpected.set(error); }
            });
            worker.start();
            try { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); }
            finally { worker.interrupt(); worker.join(2000); }
            assertFalse(worker.isAlive());
            assertNull(unexpected.get());
            assertTrue(interrupted.get());
        }
    }

}
