package io.github.tuxkoh.bcsfe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Public, remotely maintained names. Never bundled as a fallback in the APK. */
final class SponsorDirectory {
    static final String URL = "https://raw.githubusercontent.com/tuxKOH/BCSFE-Android/main/sponsors.json";
    private SponsorDirectory() {}

    static List<String> fetch() throws Exception {
        return fetch(URL + "?t=" + System.currentTimeMillis());
    }

    static List<String> fetch(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Sponsor directory unavailable");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (out.size() + count > 131072) throw new IOException("Sponsor directory too large");
                    out.write(buffer, 0, count);
                }
                return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
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
