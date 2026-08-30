package io.github.tuxkoh.bcsfe;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.github.tuxkoh.bcsfe.core.SaveDocument;

/** Small, deliberately narrow bridge for rooted game-file access. */
final class RootAccess {
    private static final String TAG = "BCSFE-RootAccess";
    static final class Detection {
        final Map<SaveDocument.Region, Boolean> installed = new EnumMap<>(SaveDocument.Region.class);
        final Map<SaveDocument.Region, Boolean> saves = new EnumMap<>(SaveDocument.Region.class);

        List<SaveDocument.Region> installedRegions() {
            List<SaveDocument.Region> result = new ArrayList<>();
            for (SaveDocument.Region region : SaveDocument.Region.values()) {
                if (Boolean.TRUE.equals(installed.get(region))) result.add(region);
            }
            return result;
        }

        List<SaveDocument.Region> saveRegions() {
            List<SaveDocument.Region> result = new ArrayList<>();
            for (SaveDocument.Region region : SaveDocument.Region.values()) {
                if (Boolean.TRUE.equals(saves.get(region))) result.add(region);
            }
            return result;
        }
    }

    private RootAccess() {}

    static boolean isAvailable() {
        try {
            CommandResult result = rootCommand("id", null, 5);
            boolean uid0 = new String(result.stdout, StandardCharsets.UTF_8).contains("uid=0");
            boolean available = result.exitCode == 0 && uid0;
            Log.i(TAG, "isAvailable exit=" + result.exitCode + " uid0=" + uid0
                    + " stdoutBytes=" + result.stdout.length + " stderrBytes=" + result.stderr.length
                    + " available=" + available + diagnostic(result));
            return available;
        } catch (Exception error) {
            Log.e(TAG, "isAvailable exception=" + error.getClass().getSimpleName()
                    + " message=" + safeMessage(error));
            return false;
        }
    }

    static Detection detect() throws IOException {
        StringBuilder script = new StringBuilder();
        for (SaveDocument.Region region : SaveDocument.Region.values()) {
            String primary = shellQuote(gameHome(region));
            String alternate = shellQuote(alternateGameHome(region));
            String mirror = shellQuote(mirrorGameHome(region));
            // Android 11+ may expose app data through data_mirror depending on
            // the root manager's mount namespace. Probe all aliases.
            script.append("if [ -d ").append(primary).append(" ]; then p=1; else p=0; fi;"
                    + " if [ -d ").append(alternate).append(" ]; then a=1; else a=0; fi;"
                    + " if [ -d ").append(mirror).append(" ]; then m=1; else m=0; fi;"
                    + " echo D:").append(region.code()).append(":$p:$a:$m;");
            script.append("if [ $p = 1 ] || [ $a = 1 ] || [ $m = 1 ]; then echo I:").append(region.code()).append("; fi;");
            script.append("if [ -f ").append(primary).append("/files/SAVE_DATA ] || [ -f ").append(alternate).append("/files/SAVE_DATA ] || [ -f ").append(mirror).append("/files/SAVE_DATA ]; then echo S:").append(region.code()).append("; fi;");
        }
        CommandResult result = rootCommand(script.toString(), null, 8);
        Log.i(TAG, "detect exit=" + result.exitCode + " stdoutBytes=" + result.stdout.length
                + " stderrBytes=" + result.stderr.length + diagnostic(result));
        if (result.exitCode != 0) throw new IOException("root detection failed");
        Detection detection = new Detection();
        String text = new String(result.stdout, StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            if (line.startsWith("D:") && line.length() >= 8) {
                String[] probe = line.split(":");
                if (probe.length == 5) {
                    Log.i(TAG, "detect pathProbe region=" + probe[1]
                            + " dataData=" + ("1".equals(probe[2]))
                            + " user0=" + ("1".equals(probe[3]))
                            + " mirror=" + ("1".equals(probe[4])));
                }
                continue;
            }
            if (line.length() < 3 || line.charAt(1) != ':') continue;
            SaveDocument.Region region = SaveDocument.Region.fromCode(line.substring(2).trim());
            if (region == null) continue;
            if (line.charAt(0) == 'I') detection.installed.put(region, true);
            if (line.charAt(0) == 'S') detection.saves.put(region, true);
        }
        Log.i(TAG, "detect result installed=" + detection.installedRegions().size()
                + " saves=" + detection.saveRegions().size());
        return detection;
    }

    static byte[] readSave(SaveDocument.Region region) throws IOException {
        return readSave(packageName(region));
    }

    static byte[] readSave(String packageName) throws IOException {
        validatePackageName(packageName);
        Log.i(TAG, "readSave start package=" + packageName);
        String primary = shellQuote(savePath(packageName));
        String alternate = shellQuote(alternateGameHome(packageName) + "/files/SAVE_DATA");
        String mirror = shellQuote(mirrorGameHome(packageName) + "/files/SAVE_DATA");
        CommandResult result = rootCommand("if [ -f " + primary + " ]; then cat " + primary
                + "; elif [ -f " + alternate + " ]; then cat " + alternate
                + "; else cat " + mirror + "; fi", null, 15);
        Log.i(TAG, "readSave result package=" + packageName + " exit=" + result.exitCode
                + " bytes=" + result.stdout.length + " stderrBytes=" + result.stderr.length
                + diagnostic(result));
        if (result.exitCode != 0 || result.stdout.length == 0) throw new IOException("game save unavailable");
        return result.stdout;
    }

    static void writeSave(SaveDocument.Region region, byte[] save) throws IOException {
        writeSave(packageName(region), save);
    }

    static void writeSave(String packageName, byte[] save) throws IOException {
        validatePackageName(packageName);
        if (save == null || save.length == 0) throw new IOException("empty save");
        Log.i(TAG, "writeSave start package=" + packageName + " bytes=" + save.length);
        String primaryHome = shellQuote(gameHome(packageName));
        String alternateHome = shellQuote(alternateGameHome(packageName));
        String mirrorHome = shellQuote(mirrorGameHome(packageName));
        String script = "if [ -d " + primaryHome + " ]; then home=" + primaryHome
                + "; elif [ -d " + alternateHome + " ]; then home=" + alternateHome
                + "; else home=" + mirrorHome + "; fi"
                + "; if [ ! -d \"$home/files\" ]; then exit 2; fi"
                + "; path=\"$home/files/SAVE_DATA\"; cache=\"$home/cache\""
                + "; uid=$(stat -c %u \"$home/files\" 2>/dev/null || echo -1); gid=$(stat -c %g \"$home/files\" 2>/dev/null || echo -1)"
                + "; rm -f \"$home/files/SAVE_DATA\" \"$home/files/SAVE_DATA.OLD\""
                + "; if [ -d \"$cache\" ]; then find \"$cache\" -maxdepth 1 -type f -name '*.json' -delete; fi"
                + "; cat > \"$path\"; chmod 600 \"$path\"; if [ \"$uid\" != -1 ] && [ \"$gid\" != -1 ]; then chown \"$uid:$gid\" \"$path\"; fi";
        CommandResult result = rootCommand(script, save, 20);
        Log.i(TAG, "writeSave result package=" + packageName + " exit=" + result.exitCode
                + " stdoutBytes=" + result.stdout.length + " stderrBytes=" + result.stderr.length
                + diagnostic(result));
        if (result.exitCode != 0) throw new IOException("game save write failed");
    }

    static String gameHome(SaveDocument.Region region) {
        return gameHome(packageName(region));
    }

    private static String savePath(SaveDocument.Region region) {
        return savePath(packageName(region));
    }

    private static String alternateGameHome(SaveDocument.Region region) {
        return alternateGameHome(packageName(region));
    }

    private static String mirrorGameHome(SaveDocument.Region region) {
        return mirrorGameHome(packageName(region));
    }

    private static String packageName(SaveDocument.Region region) { return "jp.co.ponos." + region.packageSuffix(); }
    private static String gameHome(String packageName) { return "/data/data/" + packageName; }
    private static String savePath(String packageName) { return gameHome(packageName) + "/files/SAVE_DATA"; }
    private static String alternateGameHome(String packageName) { return "/data/user/0/" + packageName; }
    private static String mirrorGameHome(String packageName) { return "/data_mirror/data_ce/null/0/" + packageName; }
    private static void validatePackageName(String packageName) throws IOException {
        if (packageName == null || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) throw new IOException("invalid package name");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static CommandResult command(String[] command, byte[] input, long timeoutSeconds) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        if (input != null) {
            try (OutputStream output = process.getOutputStream()) {
                output.write(input);
            }
        } else {
            process.getOutputStream().close();
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(process.getInputStream(), stdout), "root-access-reader");
        Thread errorReader = new Thread(() -> copy(process.getErrorStream(), stderr), "root-access-error-reader");
        reader.start();
        errorReader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("root command timed out");
            }
            reader.join(1000);
            errorReader.join(1000);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("root command interrupted", interrupted);
        }
        return new CommandResult(process.exitValue(), stdout.toByteArray(), stderr.toByteArray());
    }

    /** Supports both common Android su forms: `su -c command` and
     * `su 0 sh -c command` (used by some KernelSU builds). */
    private static CommandResult rootCommand(String script, byte[] input, long timeoutSeconds) throws IOException {
        CommandResult first = command(new String[]{"su", "-c", script}, input, timeoutSeconds);
        Log.i(TAG, "su form=su-c exit=" + first.exitCode + " stdoutBytes=" + first.stdout.length
                + " stderrBytes=" + first.stderr.length + diagnostic(first));
        if (first.exitCode == 0) return first;
        CommandResult fallback = command(new String[]{"su", "0", "sh", "-c", script}, input, timeoutSeconds);
        Log.i(TAG, "su form=su-0-sh-c exit=" + fallback.exitCode + " stdoutBytes=" + fallback.stdout.length
                + " stderrBytes=" + fallback.stderr.length + diagnostic(fallback));
        return fallback.exitCode == 0 ? fallback : first;
    }

    private static String diagnostic(CommandResult result) {
        if (result.stderr.length == 0) return "";
        String text = new String(result.stderr, StandardCharsets.UTF_8)
                .replace('\n', ' ').replace('\r', ' ').trim();
        if (text.length() > 160) text = text.substring(0, 160) + "…";
        return text.isEmpty() ? "" : " stderr=\"" + text + "\"";
    }

    private static String safeMessage(Exception error) {
        String text = error.getMessage();
        if (text == null) return "";
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        try (InputStream stream = input) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) >= 0) output.write(buffer, 0, count);
        } catch (IOException ignored) {
            // The process exit code is the authoritative result.
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final byte[] stdout;
        final byte[] stderr;
        CommandResult(int exitCode, byte[] stdout, byte[] stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
