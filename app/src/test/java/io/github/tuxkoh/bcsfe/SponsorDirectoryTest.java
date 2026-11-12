package io.github.tuxkoh.bcsfe;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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
}
