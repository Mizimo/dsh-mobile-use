/*
 * AgentVd — headless virtual display daemon for Android, runnable under plain
 * `adb shell` (uid 2000). No root, no KernelSU, no LSPosed.
 *
 * ── Why this exists instead of reusing the upstream dex ─────────────────────
 *
 * The upstream `agent_vd.dex` (AcidGr/agent-mobile-use) hard-codes the display's
 * package name as "AgentVirtualDisplay". Android 15's
 * DisplayManagerService.createVirtualDisplayInternal rejects that:
 *
 *     java.lang.SecurityException: packageName must match the calling uid
 *
 * because the name must equal the calling uid's own package. Running as
 * `com.android.shell` (uid 2000), the only accepted value is "com.android.shell".
 * The upstream dex therefore only works when launched by a root/system-owned
 * process; under adb shell it dies on exactly that check.
 *
 * This daemon takes the package name as a parameter and defaults it to the
 * caller's own package, which is what the platform demands.
 *
 * ── Reflection-only, on purpose ────────────────────────────────────────────
 *
 * No android.* import appears anywhere: the class is compiled by a desktop javac
 * and executed by `app_process`, so every platform symbol is resolved at runtime.
 *
 * ── Usage ──────────────────────────────────────────────────────────────────
 *
 *   CLASSPATH=/data/local/tmp/agent_vd2.dex \
 *     app_process /system/bin com.agent.AgentVd check
 *   CLASSPATH=/data/local/tmp/agent_vd2.dex \
 *     app_process /system/bin com.agent.AgentVd create <w> <h> <dpi> [pkg]
 *
 * `check` creates nothing. `create` mirrors the device into a new display, writes
 * the same status file the upstream CLI reads
 * (/data/local/tmp/vd_status.json), then blocks so the display survives; the
 * caller kills the pid to tear it down. A file at /data/local/tmp/vd_stop asks
 * the daemon to exit cleanly.
 */
package com.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class AgentVd {

    /** VirtualDisplay flags: PUBLIC keeps it usable by other processes. */
    private static final int FLAG_PUBLIC = 1;
    private static final int FLAG_OWN_CONTENT_ONLY = 8;
    private static final int FLAG_AUTO_MIRROR = 16;

    private static final String DISPLAY_NAME = "AgentVirtualDisplay";
    private static final String STATUS_FILE = "/data/local/tmp/vd_status.json";
    private static final String STOP_SIGNAL = "/data/local/tmp/vd_stop";
    private static final String CAPTURE_TRIGGER = "/data/local/tmp/vd_capture";
    private static final String SCREENSHOT_FILE = "/data/local/tmp/vd_screenshot.png";

    private static final int IME_POLICY_NEVER = 2;

    private static Object displayManager;
    private static Object virtualDisplay;
    private static Object surface;

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "check";
        try {
            if ("check".equals(mode)) {
                check();
                return;
            }
            if ("create".equals(mode)) {
                if (args.length < 4) {
                    System.err.println("usage: AgentVd create <width> <height> <dpi> [packageName]");
                    System.exit(2);
                }
                String pkg = args.length >= 5 ? args[4] : defaultPackageName();
                String flagsWord = args.length >= 6 ? args[5] : "own";
                create(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), pkg, flagsWord);
                return;
            }
            if ("probe".equals(mode)) {
                probe();
                return;
            }
            System.err.println("usage: AgentVd <check|probe|create> [w h dpi pkg]");
            System.exit(2);
        } catch (Throwable t) {
            System.err.println("[AgentVd] FAILED: " + t);
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * The package name the platform will accept from this process.
     *
     * DisplayManagerService compares the requested name against the calling uid's
     * package. `com.android.shell` is uid 2000, which is the uid of every
     * `adb shell` process, so it is the correct default whenever we are not told
     * otherwise.
     */
    private static String defaultPackageName() {
        return "com.android.shell";
    }

    // ── check ───────────────────────────────────────────────────────────────

    private static void check() throws Exception {
        System.out.println("[AgentVd] platform check");
        System.out.println("  pid                     = " + currentPid());
        System.out.println("  default package         = " + defaultPackageName());
        System.out.println("  prepareMainLooper       = " + prepareLooper());
        for (String name : new String[] {
                "android.hardware.display.DisplayManager",
                "android.media.ImageReader",
                "android.view.WindowManagerGlobal",
                "android.view.IWindowManager$Stub",
                "android.app.ActivityThread",
                "android.view.Surface",
        }) {
            System.out.println("  " + pad(name) + " = " + (classOrNull(name) != null ? "present" : "MISSING"));
        }
        Class<?> dm = classOrNull("android.hardware.display.DisplayManager");
        if (dm != null) {
            int six = 0;
            int eight = 0;
            for (Method m : dm.getMethods()) {
                if (!"createVirtualDisplay".equals(m.getName())) {
                    continue;
                }
                if (m.getParameterTypes().length == 6) {
                    six++;
                }
                if (m.getParameterTypes().length == 8) {
                    eight++;
                }
            }
            System.out.println("  createVirtualDisplay 6-arg = " + six);
            System.out.println("  createVirtualDisplay 8-arg = " + eight);
        }
        Class<?> reader = classOrNull("android.media.ImageReader");
        if (reader != null) {
            System.out.println("  ImageReader.newInstance   = " + (hasMethod(reader, "newInstance") ? "present" : "absent"));
        }
        System.out.println("[AgentVd] check done (nothing created)");
    }

    // ── probe ───────────────────────────────────────────────────────────────

    /**
     * Print the live signatures that decide how a package name reaches
     * DisplayManagerService.
     *
     * The legacy DisplayManager.createVirtualDisplay overloads carry no package
     * name at all — the framework derives it from the caller's Context — yet
     * DisplayManagerService validates it against the calling uid. This probe
     * lists every overload on device so the correct call path can be chosen from
     * facts instead of from a remembered ABI.
     */
    private static void probe() throws Exception {
        // ActivityThread.systemMain() installs the main Looper; without it the
        // call throws and every context experiment dies before it starts.
        prepareLooper();
        String[] owners = {
                "android.hardware.display.DisplayManager",
                "android.hardware.display.DisplayManagerGlobal",
                "android.hardware.display.VirtualDisplayConfig",
                "android.hardware.display.VirtualDisplayConfig$Builder",
                "android.hardware.display.IDisplayManager",
                "android.hardware.display.IDisplayManager$Stub",
                "android.hardware.display.VirtualDisplay",
                "android.app.ActivityThread",
                "android.app.ContextImpl",
        };
        for (String owner : owners) {
            Class<?> type = classOrNull(owner);
            System.out.println("=== " + owner + (type == null ? " (MISSING)" : ""));
            if (type == null) {
                continue;
            }
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                System.out.println("  <init> " + describe(ctor.getParameterTypes()));
            }
            for (Method m : type.getDeclaredMethods()) {
                String n = m.getName();
                if (n.startsWith("createVirtualDisplay") || n.startsWith("validatePackageName")
                        || n.startsWith("setPackageName") || n.startsWith("getPackageName")
                        || n.startsWith("getSystemContext") || n.startsWith("currentPackageName")
                        || n.startsWith("build") || n.startsWith("getService") || n.startsWith("asInterface")
                        || n.startsWith("createSystemContext") || n.startsWith("systemMain")) {
                    System.out.println("  " + n + describe(m.getParameterTypes()) + " -> " + m.getReturnType().getName());
                }
            }
        }
        Class<?> process = classOrNull("android.os.Process");
        if (process != null) {
            System.out.println("=== my uid=" + invokeStatic(process, "myUid") + " pid=" + invokeStatic(process, "myPid"));
        }

        // ── can we obtain a Context that reports the package name the platform
        //    will accept (com.android.shell, the only package owned by uid 2000)?
        System.out.println("=== context package-name experiment");
        try {
            Object thread = invokeStatic(classOrNull("android.app.ActivityThread"), "systemMain");
            Object systemContext = invoke(thread, "getSystemContext");
            System.out.println("  systemContext.getPackageName() = " + invoke(systemContext, "getPackageName"));
            try {
                Object pkgContext = invoke(systemContext, "createPackageContext", "com.android.shell", 0);
                System.out.println("  createPackageContext OK -> " + pkgContext);
                if (pkgContext != null) {
                    System.out.println("  pkgContext.getPackageName() = " + invoke(pkgContext, "getPackageName"));
                }
            } catch (Throwable t) {
                System.out.println("  createPackageContext FAILED: " + t);
            }
        } catch (Throwable t) {
            System.out.println("  context experiment failed: " + t);
        }

        // ── VirtualDisplayConfig.Builder surface, needed to build the config
        //    argument of the IDisplayManager call.
        Class<?> builder = classOrNull("android.hardware.display.VirtualDisplayConfig$Builder");
        System.out.println("=== VirtualDisplayConfig$Builder methods");
        if (builder != null) {
            for (Method m : builder.getDeclaredMethods()) {
                System.out.println("  " + m.getName() + describe(m.getParameterTypes()));
            }
        }
    }

    private static String describe(Class<?>[] types) {
        StringBuilder builder = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(types[i].getName());
        }
        return builder.append(')').toString();
    }

    // ── create ──────────────────────────────────────────────────────────────

    private static void create(int width, int height, int dpi, String pkg, String flagsWord) throws Exception {
        prepareLooper();
        System.out.println("[AgentVd] starting virtual display " + width + "x" + height + " @" + dpi
                + "dpi as " + pkg + " flags=" + flagsWord);

        Object reader = makeImageReader(width, height);
        surface = invoke(reader, "getSurface");
        if (surface == null) {
            throw new IllegalStateException("ImageReader.getSurface() returned null");
        }

        // own   — the display renders its own content (a real second desktop)
        // mirror— the display mirrors the default display, which is the cheapest
        //         way to prove whether frames reach our ImageReader at all
        int flags = "mirror".equals(flagsWord)
                ? FLAG_PUBLIC | FLAG_AUTO_MIRROR
                : FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY;
        Object handler = makeHandler();

        // ── Preferred path ──────────────────────────────────────────────────
        //
        // DisplayManagerService.validatePackageName(callingUid, packageName) only
        // accepts a package owned by the calling uid, and uid 2000 owns exactly
        // one: com.android.shell. The legacy DisplayManager overloads carry no
        // package name at all (the framework passes a null Context and the
        // validation then fails), so the package name has to arrive through a
        // Context: createPackageContext() gives one that reports
        // com.android.shell.
        //
        // DisplayManagerGlobal.createVirtualDisplay(Context, MediaProjection,
        // VirtualDisplayConfig, Callback, Executor) is the overload that takes it.
        String packageFailure = null;
        try {
            Object context = packageContext(pkg);
            Object config = buildConfig(width, height, dpi, flags);
            Object global = displayManagerGlobal();
            System.out.println("[AgentVd] using package context: " + invoke(context, "getPackageName"));
            virtualDisplay = createViaGlobal(global, context, config);
        } catch (Throwable t) {
            packageFailure = String.valueOf(t);
        }

        if (virtualDisplay == null) {
            System.out.println("[AgentVd] package-context path failed (" + packageFailure + ")");
            displayManager = getDisplayManager();
            if (displayManager == null) {
                throw new IllegalStateException("could not obtain DisplayManager for the fallback path");
            }
            String lastError = null;
            try {
                virtualDisplay = createWithCallback(pkg, width, height, dpi, flags, null, handler);
            } catch (Throwable t) {
                lastError = String.valueOf(t);
            }
            if (virtualDisplay == null) {
                System.out.println("[AgentVd] 8-arg fallback failed (" + lastError + "); trying 6-arg");
                virtualDisplay = createWithoutCallback(pkg, width, height, dpi, flags);
            }
        }
        if (virtualDisplay == null) {
            throw new IllegalStateException("createVirtualDisplay returned null for every path");
        }

        int displayId = resolveDisplayId(virtualDisplay);
        System.out.println("[AgentVd] Virtual Display created successfully! ID: " + displayId);
        writeStatus("running", displayId, width, height, dpi);

        suppressImeOn(displayId);
        drainLoop(reader, displayId);
    }

    /**
     * Read a display id off the returned VirtualDisplay.
     *
     * getDisplayId() is public API, but the object handed back by
     * DisplayManagerGlobal came through a hidden creation path, so a couple of
     * accessors are tried in order and the available method names are printed
     * when none of them work — a silent zero here would look like success.
     */
    private static int resolveDisplayId(Object display) throws Exception {
        try {
            return (Integer) invoke(display, "getDisplayId");
        } catch (Throwable first) {
            try {
                Object inner = invoke(display, "getDisplay");
                if (inner != null) {
                    return (Integer) invoke(inner, "getDisplayId");
                }
            } catch (Throwable second) {
                // fall through to diagnostics
            }
            StringBuilder names = new StringBuilder();
            for (Method m : display.getClass().getMethods()) {
                if (m.getParameterTypes().length == 0) {
                    names.append(m.getName()).append(' ');
                }
            }
            throw new IllegalStateException("cannot read display id from " + display.getClass().getName()
                    + "; zero-arg methods: " + names, first);
        }
    }

    /**
     * A Context whose getPackageName() is the name DisplayManagerService will
     * accept.
     *
     * createPackageContext on the system context is allowed here because this
     * process already runs as uid 2000, which owns com.android.shell.
     */
    private static Object packageContext(String pkg) throws Exception {
        Object thread = invokeStatic(classOrNull("android.app.ActivityThread"), "systemMain");
        Object systemContext = invoke(thread, "getSystemContext");
        Object context = invoke(systemContext, "createPackageContext", pkg, 0);
        if (context == null) {
            throw new IllegalStateException("createPackageContext returned null for " + pkg);
        }
        return context;
    }

    /** Build a VirtualDisplayConfig through its public Builder. */
    private static Object buildConfig(int width, int height, int dpi, int flags) throws Exception {
        Class<?> builderClass = classOrNull("android.hardware.display.VirtualDisplayConfig$Builder");
        if (builderClass == null) {
            throw new ClassNotFoundException("VirtualDisplayConfig$Builder");
        }
        Constructor<?> ctor = builderClass.getConstructor(String.class, int.class, int.class, int.class);
        Object builder = ctor.newInstance(DISPLAY_NAME, width, height, dpi);
        invoke(builder, "setSurface", surface);
        invoke(builder, "setFlags", flags);
        return invoke(builder, "build");
    }

    private static Object displayManagerGlobal() throws Exception {
        Class<?> globalClass = classOrNull("android.hardware.display.DisplayManagerGlobal");
        if (globalClass == null) {
            throw new ClassNotFoundException("DisplayManagerGlobal");
        }
        Object global = invokeStatic(globalClass, "getInstance");
        if (global == null) {
            throw new IllegalStateException("DisplayManagerGlobal.getInstance() returned null");
        }
        return global;
    }

    /**
     * Call DisplayManagerGlobal.createVirtualDisplay with the 5-arg Context form,
     * falling back to the 4-arg form on releases that predate the Executor
     * parameter.
     */
    private static Object createViaGlobal(Object global, Object context, Object config) throws Exception {
        // The Executor is fetched from the platform rather than declared here:
        // naming java.util.concurrent.Executor would add a library type that d8
        // has to resolve against a --lib it is not given, and the whole point of
        // this class is to depend on nothing but java.lang + reflection.
        Object executor = null;
        try {
            executor = invoke(context, "getMainExecutor");
        } catch (Throwable ignored) {
            // older releases: fall through with a null executor
        }
        for (Method m : global.getClass().getMethods()) {
            if (!"createVirtualDisplay".equals(m.getName())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 5 && p[0].isInstance(context) && p[2].isInstance(config)) {
                Object executorArg = executor != null && p[4].isInstance(executor) ? executor : null;
                return m.invoke(global, context, null, config, null, executorArg);
            }
            if (p.length == 4 && p[0].isInstance(context) && p[2].isInstance(config)) {
                return m.invoke(global, context, null, config, null);
            }
        }
        throw new NoSuchMethodException("no usable DisplayManagerGlobal.createVirtualDisplay");
    }

    private static Object createWithCallback(String pkg, int w, int h, int dpi, int flags,
                                             Object callback, Object handler) throws Exception {
        for (Method m : displayManager.getClass().getMethods()) {
            if (!"createVirtualDisplay".equals(m.getName()) || m.getParameterTypes().length != 8) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (callback != null && !p[6].isInstance(callback)) {
                continue;
            }
            Object handlerArg = handler != null && p[7].isInstance(handler) ? handler : null;
            return m.invoke(displayManager, DISPLAY_NAME, w, h, dpi, surface, flags, callback, handlerArg);
        }
        throw new NoSuchMethodException("no compatible 8-arg createVirtualDisplay");
    }

    private static Object createWithoutCallback(String pkg, int w, int h, int dpi, int flags) throws Exception {
        for (Method m : displayManager.getClass().getMethods()) {
            if (!"createVirtualDisplay".equals(m.getName()) || m.getParameterTypes().length != 6) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p[4].isInstance(surface)) {
                return m.invoke(displayManager, DISPLAY_NAME, w, h, dpi, surface, flags);
            }
        }
        return null;
    }

    /**
     * Consume frames from the ImageReader and watch for the stop file.
     *
     * A headless display backed by an ImageReader produces frames nobody consumes
     * unless the acquisition listener keeps pulling; without this the producer
     * eventually blocks. The loop also gives the daemon a place to notice
     * /data/local/tmp/vd_stop.
     */
    private static void drainLoop(Object reader, int displayId) throws Exception {
        while (true) {
            if (new File(STOP_SIGNAL).exists()) {
                System.out.println("[AgentVd] stop signal detected; releasing display");
                break;
            }
            try {
                Object image = invoke(reader, "acquireLatestImage");
                if (image != null) {
                    if (new File(CAPTURE_TRIGGER).exists()) {
                        // The display renders into this ImageReader, so a frame here
                        // IS the display content. screencap -d refuses a virtual
                        // display owned by another process (status -2), so capture
                        // belongs here rather than in an external command.
                        if (saveFrame(image)) {
                            new File(CAPTURE_TRIGGER).delete();
                        }
                    }
                    invoke(image, "close");
                } else {
                    Thread.sleep(16);
                }
            } catch (Throwable t) {
                Thread.sleep(50);
            }
        }
        releaseQuietly();
        writeStatus("stopped", displayId, 0, 0, 0);
        System.out.println("[AgentVd] daemon terminated");
        System.exit(0);
    }

    /**
     * Write the current frame to {@link #SCREENSHOT_FILE} as PNG.
     *
     * Reflection-only, like everything else here: Image -> Plane buffer -> Bitmap
     * -> PNG. Row stride is honoured because a display surface almost always pads
     * rows, and ignoring the padding skews the image.
     */
    private static boolean saveFrame(Object image) {
        try {
            int width = (Integer) invoke(image, "getWidth");
            int height = (Integer) invoke(image, "getHeight");
            Object planes = invoke(image, "getPlanes");
            int planeCount = (Integer) invoke(planes, "length");
            if (planeCount < 1) {
                return false;
            }
            Object plane = java.lang.reflect.Array.get(planes, 0);
            Object buffer = invoke(plane, "getBuffer");
            int pixelStride = (Integer) invoke(plane, "getPixelStride");
            int rowStride = (Integer) invoke(plane, "getRowStride");

            Class<?> bitmapClass = classOrNull("android.graphics.Bitmap");
            Class<?> configClass = classOrNull("android.graphics.Bitmap$Config");
            if (bitmapClass == null || configClass == null) {
                System.out.println("[AgentVd] capture: Bitmap unavailable");
                return false;
            }
            Object argb = null;
            for (Object constant : configClass.getEnumConstants()) {
                if ("ARGB_8888".equals(String.valueOf(constant))) {
                    argb = constant;
                }
            }
            int rowPadding = rowStride - pixelStride * width;
            int paddedWidth = width + (pixelStride > 0 ? rowPadding / pixelStride : 0);
            Object padded = invokeStatic(bitmapClass, "createBitmap", paddedWidth, height, argb);
            invoke(padded, "copyPixelsFromBuffer", buffer);
            Object cropped = invokeStatic(bitmapClass, "createBitmap", padded, 0, 0, width, height);

            Class<?> formatClass = classOrNull("android.graphics.Bitmap$CompressFormat");
            Object png = null;
            if (formatClass != null) {
                for (Object constant : formatClass.getEnumConstants()) {
                    if ("PNG".equals(String.valueOf(constant))) {
                        png = constant;
                    }
                }
            }
            FileOutputStream out = new FileOutputStream(SCREENSHOT_FILE);
            try {
                invoke(cropped, "compress", png, 100, out);
            } finally {
                out.close();
            }
            System.out.println("[AgentVd] captured frame " + width + "x" + height + " -> " + SCREENSHOT_FILE);
            return true;
        } catch (Throwable t) {
            System.out.println("[AgentVd] capture failed: " + t);
            return false;
        }
    }

    private static void releaseQuietly() {
        try {
            if (virtualDisplay != null) {
                invoke(virtualDisplay, "release");
            }
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** WMS.setDisplayImePolicy(id, NEVER): no soft keyboard on the virtual display. */
    private static void suppressImeOn(int displayId) {
        try {
            Object wms = invokeStatic(classOrNull("android.view.WindowManagerGlobal"), "getWindowManagerService");
            if (wms == null) {
                System.out.println("[AgentVd] IME policy skipped: no WindowManagerService");
                return;
            }
            // getWindowManagerService() already hands back the IWindowManager
            // interface, so call the method on it directly. Wrapping it again with
            // Stub.asInterface() is wrong twice over: the argument is an IBinder,
            // and the value is not one.
            if (callSetDisplayImePolicy(wms, displayId)) {
                return;
            }
            Class<?> stub = classOrNull("android.view.IWindowManager$Stub");
            if (stub != null && isBinder(wms)) {
                Object iface = invokeStatic(stub, "asInterface", wms);
                if (iface != null && callSetDisplayImePolicy(iface, displayId)) {
                    return;
                }
            }
            System.out.println("[AgentVd] setDisplayImePolicy not found on " + wms.getClass().getName());
        } catch (Throwable t) {
            System.out.println("[AgentVd] setDisplayImePolicy failed (non-fatal): " + t);
        }
    }

    private static boolean callSetDisplayImePolicy(Object target, int displayId) {
        for (Method m : target.getClass().getMethods()) {
            if ("setDisplayImePolicy".equals(m.getName()) && m.getParameterTypes().length == 2) {
                try {
                    m.invoke(target, displayId, IME_POLICY_NEVER);
                    System.out.println("[AgentVd] display " + displayId + " IME policy = NEVER");
                    return true;
                } catch (Throwable t) {
                    Throwable cause = t.getCause() == null ? t : t.getCause();
                    System.out.println("[AgentVd] setDisplayImePolicy rejected (non-fatal): " + cause);
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean isBinder(Object value) {
        Class<?> binder = classOrNull("android.os.IBinder");
        return binder != null && binder.isInstance(value);
    }

    // ── platform plumbing ───────────────────────────────────────────────────

    private static Object getDisplayManager() throws Exception {
        Class<?> activityThread = classOrNull("android.app.ActivityThread");
        if (activityThread == null) {
            return null;
        }
        Object thread = invokeStatic(activityThread, "systemMain");
        if (thread == null) {
            return null;
        }
        Object context = invoke(thread, "getSystemContext");
        if (context != null) {
            Object dm = invoke(context, "getSystemService", "display");
            if (dm != null) {
                return dm;
            }
        }
        return invoke(thread, "getSystemService", "display");
    }

    private static Object makeImageReader(int w, int h) throws Exception {
        Class<?> reader = classOrNull("android.media.ImageReader");
        if (reader == null) {
            throw new ClassNotFoundException("android.media.ImageReader");
        }
        // PixelFormat.RGBA_8888 == 1, maxImages == 2
        return invokeStatic(reader, "newInstance", w, h, 1, 2);
    }

    private static Object makeDisplayCallback() {
        try {
            Class<?> callback = classOrNull("android.hardware.display.VirtualDisplay$Callback");
            if (callback == null) {
                return null;
            }
            return Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[] { callback },
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            return null;
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object makeHandler() {
        try {
            Class<?> handlerClass = classOrNull("android.os.Handler");
            Class<?> looperClass = classOrNull("android.os.Looper");
            if (handlerClass == null || looperClass == null) {
                return null;
            }
            Object looper = invokeStatic(looperClass, "myLooper");
            if (looper == null) {
                looper = invokeStatic(looperClass, "getMainLooper");
            }
            Constructor<?> ctor = handlerClass.getConstructor(new Class<?>[] { looperClass });
            return ctor.newInstance(looper);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean prepareLooper() {
        try {
            Class<?> looperClass = classOrNull("android.os.Looper");
            if (looperClass == null) {
                return false;
            }
            invokeStatic(looperClass, "prepareMainLooper");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeStatus(String status, int displayId, int w, int h, int dpi) {
        try {
            String json = "{\"status\":\"" + status + "\",\"pid\":" + currentPid()
                    + ",\"display_id\":" + displayId + ",\"width\":" + w
                    + ",\"height\":" + h + ",\"dpi\":" + dpi + "}";
            FileOutputStream out = new FileOutputStream(STATUS_FILE);
            try {
                out.write(json.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            System.out.println("[AgentVd] status file written: " + json);
        } catch (Throwable t) {
            System.out.println("[AgentVd] failed to write status file: " + t);
        }
    }

    private static int currentPid() {
        try {
            Class<?> process = classOrNull("android.os.Process");
            if (process != null) {
                return (Integer) invokeStatic(process, "myPid");
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return -1;
    }

    private static String pad(String value) {
        StringBuilder builder = new StringBuilder(value);
        while (builder.length() < 34) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static boolean hasMethod(Class<?> owner, String name) {
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        if (target == null) {
            throw new NullPointerException("invoke target is null for " + name);
        }
        Method fallback = null;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) {
                continue;
            }
            if (fallback == null) {
                fallback = m;
            }
            if (accepts(m.getParameterTypes(), args)) {
                return m.invoke(target, args);
            }
        }
        if (fallback != null) {
            // Signature matched by arity but not by type; let the JVM produce the
            // precise mismatch error rather than reporting "no such method".
            return fallback.invoke(target, args);
        }
        throw new NoSuchMethodException(name + " on " + target.getClass());
    }

    /**
     * Whether a declared parameter list can accept these runtime arguments.
     *
     * This is what stops the overload resolution that broke once already:
     * `Context.getSystemService` has both a `(String)` and a `(Class)` overload,
     * so matching on arity alone picked the Class form and threw
     * IllegalArgumentException at invoke time.
     */
    private static boolean accepts(Class<?>[] params, Object[] args) {
        for (int i = 0; i < params.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                continue;
            }
            if (!params[i].isInstance(arg)) {
                return false;
            }
        }
        return true;
    }

    private static Object invokeStatic(Class<?> owner, String name, Object... args) throws Exception {
        if (owner == null) {
            return null;
        }
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                return m.invoke(null, args);
            }
        }
        throw new NoSuchMethodException(name + " on " + owner);
    }
}
