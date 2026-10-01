package io.github.tuxkoh.bcsfe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Small client for the separately deployed seed-api service. */
final class SeedApiClient {
    static final int CALCULATION_TIMEOUT_MS = 3_000;
    // Calculation has a 3 s server deadline; allow transport a small delivery margin.
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build();

    static final class Failure extends IOException {
        final String status;
        Failure(String status, String message) { super(message); this.status = status; }
    }

    static final class Pool {
        final String id;
        final String name;
        final JSONObject data;
        final List<Integer> catIds;

        Pool(JSONObject data) {
            this.data = data;
            id = data.optString("id");
            name = data.optString("name", id);
            LinkedHashSet<Integer> ids = new LinkedHashSet<>();
            JSONObject slots = data.optJSONObject("slots");
            if (slots != null) {
                for (String key : new String[]{"rare", "superRare", "uber", "legend"}) {
                    JSONArray values = slots.optJSONArray(key);
                    if (values == null) continue;
                    for (int i = 0; i < values.length(); i++) ids.add(values.optInt(i));
                }
            }
            catIds = List.copyOf(ids);
        }
    }

    private SeedApiClient() { }

    static List<Pool> fetchPools(String baseUrl, String region) throws Exception {
        JSONObject response = request("GET", baseUrl + "/v1/pools?region="
                + java.net.URLEncoder.encode(region, StandardCharsets.UTF_8.name()), null);
        JSONArray values = response.getJSONArray("pools");
        if (response.optInt("catIdBase", -1) != 0) throw new IOException("Seed API cat ID numbering mismatch");
        ArrayList<Pool> result = new ArrayList<>(values.length());
        for (int i = 0; i < values.length(); i++) result.add(new Pool(values.getJSONObject(i)));
        if (result.isEmpty()) throw new IllegalStateException("No current gacha pool is available");
        return result;
    }

    static long findSeed(String baseUrl, String region, String poolId,
                         List<Integer> desiredCats) throws Exception {
        return findSeed(baseUrl, region, poolId, "", desiredCats);
    }

    static long findSeed(String baseUrl, String region, String poolId,
                         String licenseKey, List<Integer> desiredCats) throws Exception {
        JSONArray desired = new JSONArray();
        for (int cat : desiredCats) desired.put(cat);
        JSONObject body = new JSONObject()
                .put("catIdBase", 0)
                .put("region", region)
                .put("poolId", poolId)
                .put("desiredCats", desired)
                .put("seedKind", "rare")
                .put("search", new JSONObject()
                        .put("maxScannedSeeds", 4_294_967_296L)
                        .put("timeoutMillis", CALCULATION_TIMEOUT_MS)
                        .put("maxResults", 1));
        if (licenseKey != null && !licenseKey.isBlank()) body.put("licenseKey", licenseKey);
        JSONObject response = request("POST", baseUrl + "/v1/seed/find", body.toString());
        if (!"FOUND".equals(response.optString("status")) || !response.has("seed")) {
            throw new Failure(response.optString("status", "ERROR"),
                    response.optString("message", "No matching seed was found"));
        }
        long seed = response.getLong("seed");
        if (seed < 0 || seed > 0xffff_ffffL) throw new Failure("ERROR", "Invalid seed returned by API");
        return seed;
    }

    private static JSONObject request(String method, String urlText, String body) throws Exception {
        Request request = new Request.Builder().url(urlText).header("Accept", "application/json")
                .method(method, body == null ? null : RequestBody.create(body,
                        MediaType.get("application/json; charset=utf-8"))).build();
        try (Response result = HTTP.newCall(request).execute()) {
            if (result.body() == null) throw new IOException("Empty Seed API response");
            JSONObject response = new JSONObject(result.body().string());
            if (!result.isSuccessful()) {
                throw new Failure(response.optString("status", "ERROR"),
                        response.optString("error", "Seed API returned HTTP " + result.code()));
            }
            return response;
        }
    }
}
