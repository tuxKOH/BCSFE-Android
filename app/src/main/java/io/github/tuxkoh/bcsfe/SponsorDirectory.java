package io.github.tuxkoh.bcsfe;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Public, remotely maintained names. Never bundled as a fallback in the APK. */
final class SponsorDirectory {
    static final String URL = "https://raw.githubusercontent.com/tuxKOH/BCSFE-Android/main/sponsors.json";
    private static final List<String> SOURCES = List.of(URL,
            "https://ghfast.top/" + URL,
            "https://gh-proxy.com/" + URL);
    private static final int MAX_BYTES = 131072;
    private static final long TIMEOUT_MS = 5000;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followSslRedirects(false).retryOnConnectionFailure(false).build();
    private SponsorDirectory() {}

    static List<String> fetch() throws Exception {
        // Use the same cache-busting query for the original and both mirrors.
        String query = "?t=" + System.currentTimeMillis();
        List<String> endpoints = new ArrayList<>();
        for (String source : SOURCES) endpoints.add(source + query);
        return fetch(endpoints, TIMEOUT_MS);
    }

    static List<String> fetch(String endpoint) throws Exception {
        return fetch(List.of(endpoint), TIMEOUT_MS);
    }

    /** Race complete, validated responses, rather than just connection latency. */
    static List<String> fetch(List<String> endpoints, long timeoutMillis) throws Exception {
        if (endpoints.isEmpty() || endpoints.size() > 3 || timeoutMillis <= 0)
            throw new IllegalArgumentException("Invalid sponsor sources");
        ArrayBlockingQueue<Attempt> results = new ArrayBlockingQueue<>(endpoints.size());
        List<Call> calls = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            for (String endpoint : endpoints) {
                Request request = new Request.Builder().url(endpoint)
                        .header("Cache-Control", "no-cache").header("Pragma", "no-cache")
                        .header("Accept", "application/json").build();
                Call call = CLIENT.newCall(request);
                calls.add(call);
                call.enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException error) {
                        results.offer(new Attempt(null, error));
                    }
                    @Override public void onResponse(Call call, Response response) {
                        Attempt result;
                        try (response) {
                            if (response.code() != 200 || response.body() == null)
                                throw new IOException("Sponsor directory unavailable");
                            if (response.body().contentLength() > MAX_BYTES)
                                throw new IOException("Sponsor directory too large");
                            try (InputStream input = response.body().byteStream()) {
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                byte[] buffer = new byte[4096];
                                int count;
                                while ((count = input.read(buffer)) != -1) {
                                    if (out.size() + count > MAX_BYTES)
                                        throw new IOException("Sponsor directory too large");
                                    out.write(buffer, 0, count);
                                }
                                result = new Attempt(parse(new String(out.toByteArray(), StandardCharsets.UTF_8)), null);
                            }
                        } catch (Exception invalid) { result = new Attempt(null, invalid); }
                        results.offer(result);
                    }
                });
            }
            Exception failure = new IOException("Sponsor directory unavailable");
            for (int completed = 0; completed < calls.size(); completed++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Sponsor directory timed out");
                Attempt result = results.poll(remaining, TimeUnit.NANOSECONDS);
                if (result == null) throw new IOException("Sponsor directory timed out");
                if (result.names != null) return result.names;
                failure = result.error;
            }
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            // Cancel slow/blocked losers, including their response-body reads.
            for (Call call : calls) call.cancel();
        }
    }

    private static final class Attempt {
        final List<String> names;
        final Exception error;
        Attempt(List<String> names, Exception error) { this.names = names; this.error = error; }
    }

    static List<String> parse(String json) throws Exception {
        JSONArray names = new JSONObject(json).getJSONArray("sponsors");
        if (names.length() > 2000) throw new IOException("Too many sponsor names");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < names.length(); i++) {
            Object value = names.get(i);
            if (!(value instanceof String)) throw new IOException("Invalid sponsor name");
            String name = ((String) value).trim();
            if (name.isEmpty() || name.length() > 200) throw new IOException("Invalid sponsor name");
            result.add(name);
        }
        return result;
    }
}
