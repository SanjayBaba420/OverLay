package org.OverlayNotes;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.Window;

final class CapturePrivacy {
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;
    private static final int DWMWA_CLOAK = 0x0000000E;
    private static String lastResult = "Capture exclusion not yet applied";

    public interface DisplayAffinity extends StdCallLibrary {
        boolean SetWindowDisplayAffinity(Pointer window, int affinity);
        boolean GetWindowDisplayAffinity(Pointer window, IntByReference affinity);
    }

    public interface DwmApi extends StdCallLibrary {
        int DwmSetWindowAttribute(Pointer hwnd, int attribute, int[] pvAttribute, int cbAttribute);
        int DwmGetWindowAttribute(Pointer hwnd, int attribute, int[] pvAttribute, int cbAttribute);
    }

    static String lastResult() { return lastResult; }

    static boolean isExcluded(Window window) {
        if (!Platform.isWindows()) return false;
        try {
            DisplayAffinity api = Native.load("user32", DisplayAffinity.class);
            IntByReference affinity = new IntByReference();
            return api.GetWindowDisplayAffinity(Native.getComponentPointer(window), affinity)
                    && affinity.getValue() == WDA_EXCLUDEFROMCAPTURE;
        } catch (LinkageError | RuntimeException e) { return false; }
    }

    static boolean isCloaked(Window window) {
        if (!Platform.isWindows()) return false;
        try {
            DwmApi dwm = Native.load("dwmapi", DwmApi.class);
            int[] value = new int[1];
            return dwm.DwmGetWindowAttribute(Native.getComponentPointer(window),
                    DWMWA_CLOAK, value, 4) == 0 && value[0] == 1;
        } catch (LinkageError | RuntimeException e) { return false; }
    }

    static String enable(Window window) {
        lastResult = apply(window) ? "Capture exclusion active before window shown"
                : "Capture exclusion failed - window will not open";
        return lastResult;
    }

    static void cloak(Window window) {
        if (!Platform.isWindows()) return;
        try {
            DwmApi dwm = Native.load("dwmapi", DwmApi.class);
            int[] value = new int[]{1};
            dwm.DwmSetWindowAttribute(Native.getComponentPointer(window),
                    DWMWA_CLOAK, value, 4);
        } catch (LinkageError | RuntimeException e) {
            lastResult = "Cloak unavailable: " + e.getClass().getSimpleName();
        }
    }

    static void uncloak(Window window) {
        if (!Platform.isWindows()) return;
        try {
            DwmApi dwm = Native.load("dwmapi", DwmApi.class);
            int[] value = new int[]{0};
            dwm.DwmSetWindowAttribute(Native.getComponentPointer(window),
                    DWMWA_CLOAK, value, 4);
        } catch (LinkageError | RuntimeException e) { /* ignore */ }
    }

    private static boolean apply(Window window) {
        if (!Platform.isWindows()) {
            lastResult = "Capture exclusion unavailable: Windows required";
            return false;
        }
        try {
            DisplayAffinity api = Native.load("user32", DisplayAffinity.class);
            Pointer handle = Native.getComponentPointer(window);
            if (!api.SetWindowDisplayAffinity(handle, WDA_EXCLUDEFROMCAPTURE)) return false;
            IntByReference affinity = new IntByReference();
            return api.GetWindowDisplayAffinity(handle, affinity)
                    && affinity.getValue() == WDA_EXCLUDEFROMCAPTURE;
        } catch (LinkageError | RuntimeException e) {
            lastResult = "Capture exclusion unavailable: " + e.getClass().getSimpleName();
            return false;
        }
    }
}