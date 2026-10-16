package dev.windowscraft.ui;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Version;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a program calls itself, for buttons: the description in its own version info ("WhatsApp",
 * "Windows Terminal", "SteelSeries GG"), not its file name ("Whatsapp.root", "Windowsterminal",
 * "Steelseriesggclient"). Any program on any PC; the tidied file name when it has none.
 */
final class AppNames {
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> STORE = new ConcurrentHashMap<>();

    static String of(String exePath) {
        if (exePath == null || exePath.isEmpty()) return "app";
        return NAMES.computeIfAbsent(exePath, p -> {
            String own = described(p);
            return own != null ? own : tidy(p);
        });
    }

    /**
     * A Microsoft Store app (its folder holds an AppxManifest.xml: WindowsApps, XboxGames): it can't
     * run in a box, so it's never offered as a monitor's own copy.
     */
    static boolean storeApp(String exePath) {
        if (exePath == null || exePath.isEmpty()) return false;
        return STORE.computeIfAbsent(exePath, p -> {
            if (p.toLowerCase(Locale.ROOT).contains("\\windowsapps\\")) return true;
            try {
                Path dir = Path.of(p).getParent();
                for (int i = 0; dir != null && i < 3; i++, dir = dir.getParent()) {
                    if (Files.isRegularFile(dir.resolve("AppxManifest.xml"))) return true;
                }
            } catch (Exception ignored) {
            }
            return false;
        });
    }

    private static String described(String path) {
        try {
            IntByReference ignored = new IntByReference();
            int size = Version.INSTANCE.GetFileVersionInfoSize(path, ignored);
            if (size <= 0) return null;
            Memory info = new Memory(size);
            if (!Version.INSTANCE.GetFileVersionInfo(path, 0, size, info)) return null;
            PointerByReference at = new PointerByReference();
            IntByReference len = new IntByReference();
            String table = "040904b0"; // (US English, Unicode: when it doesn't say)
            if (Version.INSTANCE.VerQueryValue(info, "\\VarFileInfo\\Translation", at, len) && len.getValue() >= 4) {
                Pointer t = at.getValue();
                table = String.format(Locale.ROOT, "%04x%04x", t.getShort(0) & 0xFFFF, t.getShort(2) & 0xFFFF);
            }
            // the product's name first ("Windows Terminal", not "Windows Terminal Host"), then the
            // file's description (Windows' own programs: their product is "… Operating System")
            for (String key : new String[]{"ProductName", "FileDescription"}) {
                if (!Version.INSTANCE.VerQueryValue(info, "\\StringFileInfo\\" + table + "\\" + key, at, len) || len.getValue() <= 1) continue;
                String s = at.getValue().getWideString(0).trim();
                String low = s.toLowerCase(Locale.ROOT);
                if (s.isEmpty() || s.length() > 32 || low.endsWith(".exe") || low.contains("operating system")) continue;
                // "PowerToys.QuickAccess", "WhatsApp.Root": a name with a dot and no space is a file's
                if (s.indexOf(' ') < 0 && s.indexOf('.') > 0) s = s.substring(0, s.indexOf('.'));
                return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** "Brave", "Discord": the program's file name, tidied. */
    private static String tidy(String path) {
        String f = path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1);
        if (f.toLowerCase(Locale.ROOT).endsWith(".exe")) f = f.substring(0, f.length() - 4);
        if (f.isEmpty()) return "app";
        return Character.toUpperCase(f.charAt(0)) + f.substring(1);
    }

    private AppNames() {}
}
