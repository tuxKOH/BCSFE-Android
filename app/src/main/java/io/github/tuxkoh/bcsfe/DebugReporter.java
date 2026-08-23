package io.github.tuxkoh.bcsfe;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a small, opt-in diagnostic buffer for failures in the editor.
 * Nothing is copied or uploaded until the user explicitly presses Copy.
 */
final class DebugReporter {
    private static final String TAG = "BCSFE-Debug";
    private static final int MAX_ENTRIES = 24;
    private static final int MAX_ENTRY_CHARS = 6000;
    private static final int MAX_LOGCAT_CHARS = 12000;
    private static final ArrayDeque<String> ENTRIES = new ArrayDeque<>();
    private static final Pattern OFFSET = Pattern.compile("(?i)(?:offset|position|at)\\s*[=:]?\\s*(-?\\d+)");

    private DebugReporter() {}

    static void record(String operation, Throwable error, byte[] save) {
        StringBuilder report = new StringBuilder();
        report.append("operation=").append(safe(operation)).append('\n');
        if (error == null) {
            report.append("error=unknown\n");
        } else {
            report.append("error=").append(error.getClass().getName()).append(": ")
                    .append(safe(error.getMessage())).append('\n');
            report.append(redact(Log.getStackTraceString(error)));
        }
        if (save == null || save.length == 0) {
            report.append("save_context=unavailable\n");
        } else {
            report.append("save_length=").append(save.length)
                    .append(" sha256=").append(hash(save)).append('\n');
            int offset = findOffset(error);
            report.append("unparseable_bytes=")
                    .append(hexWindow(save, offset)).append('\n');
        }
        String value = trim(report.toString(), MAX_ENTRY_CHARS);
        Log.e(TAG, value);
        synchronized (ENTRIES) {
            ENTRIES.addLast(value);
            while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
        }
    }

    static String report() {
        StringBuilder out = new StringBuilder();
        out.append("BCSFE diagnostic report\n")
                .append("app_version=").append(BuildConfig.VERSION_NAME).append('\n')
                .append("warning=Contains bounded save-byte context and logcat diagnostics; share only with trusted people.\n\n");
        synchronized (ENTRIES) {
            if (ENTRIES.isEmpty()) out.append("No captured application errors.\n");
            else {
                for (String entry : ENTRIES) out.append(entry).append("\n\n");
            }
        }
        out.append("--- logcat (filtered) ---\n").append(captureLogcat());
        return trim(out.toString(), 30000);
    }

    private static String captureLogcat() {
        Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "250")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "capture timed out";
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0 && bytes.size() < MAX_LOGCAT_CHARS * 2) {
                    bytes.write(buffer, 0, Math.min(count, MAX_LOGCAT_CHARS * 2 - bytes.size()));
                }
            }
            StringBuilder filtered = new StringBuilder();
            for (String line : new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.contains("BCSFE-") || line.contains("AndroidRuntime") || line.contains("FATAL EXCEPTION")) {
                    filtered.append(line).append('\n');
                }
            }
            return trim(filtered.toString(), MAX_LOGCAT_CHARS);
        } catch (Exception error) {
            return "capture failed: " + error.getClass().getSimpleName();
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static int findOffset(Throwable error) {
        if (error == null) return -1;
        String text = Log.getStackTraceString(error);
        Matcher matcher = OFFSET.matcher(text);
        if (!matcher.find()) return -1;
        try { return Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static String hexWindow(byte[] save, int offset) {
        int center = offset >= 0 && offset < save.length ? offset : 0;
        int start = Math.max(0, center - 64);
        int end = Math.min(save.length, Math.max(start + 192, center + 128));
        StringBuilder out = new StringBuilder();
        out.append("offset=").append(offset).append(" range=").append(start).append('-').append(end).append(' ');
        for (int i = start; i < end; i++) {
            if ((i - start) % 16 == 0 && i != start) out.append('|');
            out.append(String.format(Locale.ROOT, "%02x", save[i] & 255));
        }
        return out.toString();
    }

    private static String hash(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest) out.append(String.format(Locale.ROOT, "%02x", item));
            return out.toString();
        } catch (Exception error) { return "unavailable"; }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return redact(value).replace('\n', ' ').replace('\r', ' ');
    }

    private static String redact(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)(transfer(?:code)?|password|pin|inquiry(?:code)?|refresh[_ ]?token|token)\\s*[:=]\\s*[A-Za-z0-9_+/=-]+", "$1=<redacted>");
    }

    private static String trim(String value, int max) {
        if (value.length() <= max) return value;
        return value.substring(0, max) + "\n[truncated]";
    }
}
