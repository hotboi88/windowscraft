package dev.windowscraft.capture;

import java.util.List;
import java.util.Locale;

/**
 * Persistent identity of a source, used to pre-select "what you shared last time" on a display
 * after you rejoin. Window handles change between sessions, so windows are matched by exe + title.
 */
public record SourceDescriptor(SourceInfo.Kind kind, String title, String exeName, String screenKey) {

    /** Best match among the currently available sources, or null. */
    public SourceInfo findMatch(List<SourceInfo> candidates) {
        SourceInfo exeOnly = null;
        for (SourceInfo s : candidates) {
            if (s.kind() != kind) continue;
            switch (kind) {
                case TEST -> { return s; }
                case SCREEN -> {
                    if (screenKey.endsWith("|" + s.id())) return s;
                }
                case WINDOW -> {
                    if (!exeName.equalsIgnoreCase(s.exeName())) continue;
                    if (s.title().equals(title)) return s;
                    if (exeOnly == null) exeOnly = s;
                }
            }
        }
        return exeOnly;
    }

    public String shortLabel() {
        return switch (kind) {
            case TEST -> "Test pattern";
            case SCREEN -> title;
            case WINDOW -> exeName.isEmpty() ? title : exeName.toLowerCase(Locale.ROOT).replace(".exe", "");
        };
    }
}
