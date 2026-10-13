package dev.windowscraft.capture.win;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.util.Locale;

/**
 * The few Direct3D 11 / DXGI calls direct GPU sharing needs, through JNA COM vtables: pick the
 * graphics card Minecraft draws with, make a D3D11 device on it, and open the capture helper's
 * shared textures. All pointers are raw COM interface pointers; release with {@link #release}.
 */
public final class D3D {
    private static final int S_OK = 0, DXGI_ERROR_NOT_FOUND = 0x887A0002;
    // vtable slots
    private static final int RELEASE = 2, FACTORY1_ENUM_ADAPTERS1 = 12, ADAPTER1_GET_DESC1 = 10, DEVICE_OPEN_SHARED_RESOURCE = 28, TEXTURE2D_GET_DESC = 10;

    /** A chosen graphics card: its LUID (for the helper) and a D3D11 device on it (for us). */
    public record Adapter(int luidLow, int luidHigh, String name, Pointer device) {}

    /**
     * Makes a D3D11 device on the graphics card whose vendor matches OpenGL's (so OpenGL can map
     * its textures), preferring the one with the most video memory. Null if anything fails.
     */
    public static Adapter open(String glVendor) { return open(glVendor, true); }

    /** Just finds the graphics card (LUID and name), without making a device on it. */
    public static Adapter find(String glVendor) { return open(glVendor, false); }

    private static Adapter open(String glVendor, boolean makeDevice) {
        int wantVendor = vendorId(glVendor);
        Pointer factory = null, best = null;
        long bestMem = -1;
        int bestLow = 0, bestHigh = 0;
        String bestName = "";
        try {
            PointerByReference pf = new PointerByReference();
            NativeLibrary dxgi = NativeLibrary.getInstance("dxgi");
            int hr = dxgi.getFunction("CreateDXGIFactory1").invokeInt(new Object[]{guid("770aae78-f26f-4dba-a829-253c83d1b387"), pf});
            if (hr != S_OK) return null;
            factory = pf.getValue();
            for (int i = 0; ; i++) {
                PointerByReference pa = new PointerByReference();
                hr = call(factory, FACTORY1_ENUM_ADAPTERS1, i, pa);
                if (hr == DXGI_ERROR_NOT_FOUND || hr != S_OK) break;
                Pointer adapter = pa.getValue();
                Memory desc = new Memory(312);
                desc.clear();
                if (call(adapter, ADAPTER1_GET_DESC1, desc) == S_OK) {
                    int vendor = desc.getInt(256);
                    long mem = desc.getLong(272);
                    int flags = desc.getInt(304);
                    boolean software = (flags & 2) != 0;
                    if (!software && (wantVendor == 0 || vendor == wantVendor) && mem > bestMem) {
                        if (best != null) release(best);
                        best = adapter;
                        bestMem = mem;
                        bestLow = desc.getInt(296);
                        bestHigh = desc.getInt(300);
                        bestName = desc.getWideString(0);
                        continue;
                    }
                }
                release(adapter);
            }
            if (!makeDevice) return best == null ? null : new Adapter(bestLow, bestHigh, bestName, null);
            PointerByReference dev = new PointerByReference();
            NativeLibrary d3d11 = NativeLibrary.getInstance("d3d11");
            // D3D_DRIVER_TYPE_UNKNOWN with an adapter, HARDWARE without; BGRA support; SDK version 7
            hr = d3d11.getFunction("D3D11CreateDevice").invokeInt(new Object[]{best, best != null ? 0 : 1, null, 0x20, null, 0, 7,
                    dev, null, null});
            if (hr != S_OK) return null;
            return new Adapter(bestLow, bestHigh, bestName, dev.getValue());
        } catch (Throwable t) {
            return null;
        } finally {
            if (best != null) release(best);
            if (factory != null) release(factory);
        }
    }

    /** Opens one of the helper's shared textures (legacy shared handle) on our device; null on failure. */
    public static Pointer openShared(Pointer device, long handle) {
        PointerByReference tex = new PointerByReference();
        int hr = call(device, DEVICE_OPEN_SHARED_RESOURCE, new Pointer(handle), guid("6f15aaf2-d208-4e89-9ab4-489535d34f9c"), tex);
        return hr == S_OK ? tex.getValue() : null;
    }

    /** A shared texture's size and DXGI format: {width, height, format}; null if it can't be read. */
    public static int[] textureInfo(Pointer texture) {
        try {
            Memory desc = new Memory(44); // D3D11_TEXTURE2D_DESC
            desc.clear();
            Pointer vtbl = texture.getPointer(0);
            Function f = Function.getFunction(vtbl.getPointer((long) TEXTURE2D_GET_DESC * Native.POINTER_SIZE));
            f.invokeVoid(new Object[]{texture, desc});
            return new int[]{desc.getInt(0), desc.getInt(4), desc.getInt(16)};
        } catch (Throwable t) {
            return null;
        }
    }

    public static final int DXGI_FORMAT_B8G8R8A8_UNORM = 87;

    public static void release(Pointer com) {
        if (com != null) call(com, RELEASE);
    }

    private static int call(Pointer com, int slot, Object... args) {
        Pointer vtbl = com.getPointer(0);
        Function f = Function.getFunction(vtbl.getPointer((long) slot * Native.POINTER_SIZE));
        Object[] all = new Object[args.length + 1];
        all[0] = com;
        System.arraycopy(args, 0, all, 1, args.length);
        return f.invokeInt(all);
    }

    private static Memory guid(String s) {
        String h = s.replace("-", "");
        Memory m = new Memory(16);
        m.setInt(0, (int) Long.parseLong(h.substring(0, 8), 16));
        m.setShort(4, (short) Integer.parseInt(h.substring(8, 12), 16));
        m.setShort(6, (short) Integer.parseInt(h.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) m.setByte(8 + i, (byte) Integer.parseInt(h.substring(16 + i * 2, 18 + i * 2), 16));
        return m;
    }

    static int vendorId(String glVendor) {
        String v = glVendor == null ? "" : glVendor.toLowerCase(Locale.ROOT);
        if (v.contains("nvidia")) return 0x10DE;
        if (v.contains("intel")) return 0x8086;        // before "ati": "corporATIon"
        if (v.contains("amd") || v.contains("ati technologies")) return 0x1002;
        return 0;
    }

    private D3D() {}
}
