package dev.windowscraft.monitor;

import dev.windowscraft.WindowsCraftClient;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A monitor's own copy of your browser starts out as your browser: your sign-ins, saved
 * passwords, bookmarks, extensions and settings are copied over from the one on your desk (caches
 * aren't). Same Windows user, so the browser can read them as its own.
 *
 * <p>While your desk browser is open it keeps its sign-in cookies locked; those come over the next
 * time neither copy is running (checked now and then), everything else right away.
 */
public final class ProfileSeed {
    /** Cache folders: big, and rebuilt by the browser on its own. */
    private static final Set<String> SKIP = Set.of("cache", "code cache", "gpucache", "dawncache", "dawngraphitecache", "dawnwebgpucache",
            "grshadercache", "shadercache", "graphitedawncache", "media cache", "application cache", "crashpad", "blob_storage",
            "cachestorage", "scriptcache", "jumplisticons", "jumplisticonsold", "component_crx_cache", "download_cache",
            "optimization_guide_model_store", "optimization_guide_prediction_model_downloads", "safe browsing", "ondeviceheadsuggestmodel");
    private static final Set<String> SKIP_FILES = Set.of("lockfile", "singletonlock", "singletoncookie", "singletonsocket", "lock");

    public record Result(int copied, List<Path> locked) {}

    /** Where that browser keeps its things for you (null if unknown / not there). */
    public static Path userData(String exeFile) {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null) return null;
        Path p = switch (exeFile.toLowerCase(Locale.ROOT)) {
            case "brave.exe" -> Path.of(local, "BraveSoftware", "Brave-Browser", "User Data");
            case "chrome.exe" -> Path.of(local, "Google", "Chrome", "User Data");
            case "msedge.exe" -> Path.of(local, "Microsoft", "Edge", "User Data");
            case "vivaldi.exe" -> Path.of(local, "Vivaldi", "User Data");
            default -> null;
        };
        return p != null && Files.isRegularFile(p.resolve("Local State")) ? p : null;
    }

    /** The profile you use ("Default", or the last one you had open). */
    static String lastProfile(Path userData) {
        try {
            String s = Files.readString(userData.resolve("Local State"));
            var o = com.google.gson.JsonParser.parseString(s).getAsJsonObject();
            var prof = o.getAsJsonObject("profile");
            if (prof != null && prof.has("last_used")) {
                String n = prof.get("last_used").getAsString();
                if (!n.isBlank() && Files.isDirectory(userData.resolve(n))) return n;
            }
        } catch (Exception ignored) {
        }
        return "Default";
    }

    /**
     * Copies your profile from {@code from} (a browser's "User Data") into {@code to} (the
     * monitor copy's own). Files that are locked right now are listed in the result; nothing
     * already in {@code to} is removed.
     */
    public static Result seed(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        List<Path> locked = new ArrayList<>();
        int[] n = {0};
        copy(from.resolve("Local State"), to.resolve("Local State"), locked, n);
        String profile = lastProfile(from);
        Path src = from.resolve(profile), dst = to.resolve(profile);
        if (Files.isDirectory(src)) {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                    if (!dir.equals(src) && SKIP.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) return FileVisitResult.SKIP_SUBTREE;
                    Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                    if (SKIP_FILES.contains(file.getFileName().toString().toLowerCase(Locale.ROOT))) return FileVisitResult.CONTINUE;
                    copy(file, dst.resolve(src.relativize(file).toString()), locked, n);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    locked.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        // the copy opens straight into that profile
        return new Result(n[0], locked);
    }

    /** The files that were locked last time, again (your browser's closed now). True if all came over. */
    public static boolean retry(Path from, Path to, List<Path> lockedBefore) {
        List<Path> still = new ArrayList<>();
        int[] n = {0};
        for (Path f : lockedBefore) {
            Path rel = from.relativize(f);
            copy(f, to.resolve(rel.toString()), still, n);
        }
        lockedBefore.clear();
        lockedBefore.addAll(still);
        return still.isEmpty();
    }

    private static void copy(Path a, Path b, List<Path> locked, int[] n) {
        try {
            if (!Files.exists(a)) return;
            Files.createDirectories(b.getParent());
            Files.copy(a, b, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            n[0]++;
        } catch (IOException e) {
            locked.add(a); // (in use right now)
        }
    }

    /** For the log: "Network/Cookies, Login Data". */
    static String names(Path base, List<Path> files) {
        StringBuilder sb = new StringBuilder();
        for (Path f : files) {
            if (sb.length() > 200) { sb.append("…"); break; }
            if (sb.length() > 0) sb.append(", ");
            try { sb.append(base.relativize(f)); } catch (Exception e) { sb.append(f.getFileName()); }
        }
        return sb.toString();
    }

    static void log(String fmt, Object... args) { WindowsCraftClient.LOGGER.info(fmt, args); }

    private ProfileSeed() {}
}
