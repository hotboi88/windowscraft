package dev.windowscraft.measure;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.win32.StdCallLibrary;

/** A helper program's memory (its working set), for the measurements. Windows only; 0 elsewhere or on any failure. */
final class WinMemory {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    private static volatile boolean broken;

    @Structure.FieldOrder({"cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
            "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage"})
    public static final class Counters extends Structure {
        public int cb;
        public int PageFaultCount;
        public SIZE_T PeakWorkingSetSize, WorkingSetSize, QuotaPeakPagedPoolUsage, QuotaPagedPoolUsage,
                QuotaPeakNonPagedPoolUsage, QuotaNonPagedPoolUsage, PagefileUsage, PeakPagefileUsage;
    }

    interface Kernel32 extends StdCallLibrary {
        Kernel32 I = Native.load("kernel32", Kernel32.class);

        Pointer OpenProcess(int access, boolean inherit, int pid);
        boolean CloseHandle(Pointer h);
        boolean K32GetProcessMemoryInfo(Pointer process, Counters counters, int cb);
    }

    static long workingSetMb(long pid) {
        if (!WINDOWS || broken) return 0;
        try {
            Pointer h = Kernel32.I.OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, (int) pid);
            if (h == null) return 0;
            try {
                Counters c = new Counters();
                c.cb = c.size();
                if (!Kernel32.I.K32GetProcessMemoryInfo(h, c, c.cb)) return 0;
                return c.WorkingSetSize.longValue() >> 20;
            } finally {
                Kernel32.I.CloseHandle(h);
            }
        } catch (Throwable t) {
            broken = true;
            return 0;
        }
    }

    private WinMemory() {}
}
