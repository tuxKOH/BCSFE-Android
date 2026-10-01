package io.github.tuxkoh.bcsfe;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class SeedApiClientTest {
    @Test public void sendsThreeSecondBudgetAndKeepsUnsignedSeeds() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"status\":\"FOUND\",\"seed\":4294967295}"));
            assertEquals(4294967295L, SeedApiClient.findSeed(server.url("").toString(), "tw", "test", List.of(448, -1)));
            JSONObject body = new JSONObject(server.takeRequest().getBody().readUtf8());
            assertEquals(3000, body.getJSONObject("search").getInt("timeoutMillis"));
            assertEquals(4294967296L, body.getJSONObject("search").getLong("maxScannedSeeds"));
            assertEquals(-1, body.getJSONArray("desiredCats").getInt(1));
        }
    }

    @Test public void sendsLicenseKeyOnlyForCalculation() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"status\":\"FOUND\",\"seed\":1}"));
            SeedApiClient.findSeed(server.url("").toString(), "tw", "test", "demo-key", List.of(448));
            JSONObject body = new JSONObject(server.takeRequest().getBody().readUtf8());
            assertEquals("demo-key", body.getString("licenseKey"));
        }
    }

    @Test public void separatesLimitNoMatchAndBusy() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (String status : List.of("LIMIT_REACHED", "NOT_FOUND", "BUSY")) {
                server.enqueue(new MockResponse().setResponseCode(status.equals("BUSY") ? 429 : 200)
                        .setBody(new JSONObject().put("status", status).put("message", "bounded search").toString()));
                try {
                    SeedApiClient.findSeed(server.url("").toString(), "tw", "test", List.of(448));
                    fail("Expected a typed failure");
                } catch (SeedApiClient.Failure failure) { assertEquals(status, failure.status); }
            }
        }
    }

    @Test public void unreachableCalculationDoesNotHangOrRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"status\":\"FOUND\",\"seed\":1}")
                    .setBodyDelay(10, TimeUnit.SECONDS));
            long start = System.nanoTime();
            try {
                SeedApiClient.findSeed(server.url("").toString(), "tw", "test", List.of(448));
                fail("Expected a bounded timeout");
            } catch (java.io.InterruptedIOException expected) {
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 7000);
                assertEquals(1, server.getRequestCount());
            }
        }
    }
}
