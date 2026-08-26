package dev.icehunter.fornax.metalfx;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.metalfx.objc.Objc;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeCocoa;

/**
 * Apple's Metal Performance HUD ({@code CAMetalLayer.developerHUDProperties}, macOS 13+), applied
 * to the game window's layer in place of manually exporting {@code MTL_HUD_ENABLED} before launch.
 *
 * <p>Restart-to-apply in both directions under MoltenVK: its HUD compositor reads {@code
 * developerHUDProperties} once, at layer setup, and never again (confirmed live, including a
 * forced window resize). {@link #apply} still runs on a live settings save so the saved value is
 * correct, but only the {@code CLIENT_STARTED} call, when the persisted value is already true at
 * boot, ever shows the HUD. {@code MTL_HUD_ENABLED} has the same restriction, which is why {@link
 * MetalHudEnv} sets it natively at preLaunch, once per process.
 *
 * <p>Called from {@code SettingsApplyRouter}'s {@code METAL_HUD_APPLY} action and once at {@code
 * CLIENT_STARTED} for a persisted-on config; see {@code FornaxMod}.
 *
 * <p>Fail-closed like every other {@link Objc} caller: guarded by {@link Objc#isLoaded()} plus a
 * live {@code respondsToSelector:}/{@code isKindOfClass:} probe of the resolved layer. Every
 * failure is logged, never thrown.
 *
 * <p>{@link #apply} logs a success/failure verdict every call, including a read-back of the
 * property right after setting it: the only way to root-cause "the HUD never shows" from outside
 * this class.
 */
public final class MetalHudControl {
    private MetalHudControl() {
    }

    /**
     * Sets the HUD on/off on the game window's {@code CAMetalLayer}. Safe to call repeatedly, even
     * with the same value. Per this class's header: only a call from {@code CLIENT_STARTED} is ever
     * visible under MoltenVK; a live-save call still succeeds and logs, but has no effect until
     * restart.
     *
     * <p>Mutates a copy of the layer's CURRENT {@code developerHUDProperties} rather than replacing
     * it, matching Apple's sample code for this API: the setter appears to key off the dictionary
     * object it already tracks, so replacing it wholesale left a later disable call removing a key
     * from an object that never had it.
     *
     * <p>One autorelease pool brackets the whole call, popped in {@code finally} after the setter
     * runs. {@link #currentPropertiesMutableCopy} returns a RETAINED object; this method releases
     * it once the setter has consumed it.
     */
    public static void apply(boolean enabled) {
        if (!Objc.isLoaded()) {
            return;
        }
        long pool = Objc.autoreleasePoolPush();
        try {
            long layer = resolveMetalLayer();
            if (layer == 0) {
                logFailure("could not resolve the window's CAMetalLayer, no-op");
                return;
            }
            long responds = Objc.selector("respondsToSelector:");
            long setProperties = Objc.selector("setDeveloperHUDProperties:");
            if (!Objc.msgSendBool(layer, responds, setProperties)) {
                logFailure("CAMetalLayer has no setDeveloperHUDProperties: (macOS < 13?), no-op");
                return;
            }
            long properties = currentPropertiesMutableCopy(layer);
            if (properties == 0) {
                logFailure("could not build a mutable HUD properties dictionary, not touching the HUD");
                return;
            }
            try {
                long modeKey = Objc.nsString("mode");
                if (enabled) {
                    Objc.msgSendVoid(properties, Objc.selector("setObject:forKey:"),
                            Objc.nsString("default"), modeKey);
                } else {
                    Objc.msgSendVoid(properties, Objc.selector("removeObjectForKey:"), modeKey);
                }
                Objc.msgSendVoid(layer, setProperties, properties);
                long readBack = Objc.msgSendId(layer, Objc.selector("developerHUDProperties"));
                logSuccess("set developerHUDProperties(enabled=" + enabled + "), read back "
                        + describe(readBack));
            } finally {
                Objc.msgSendVoid(properties, Objc.selector("release"));
            }
        } catch (RuntimeException e) {
            logFailure("failed: " + e);
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * GLFW window handle -&gt; {@code NSWindow} -&gt; {@code contentView} -&gt; {@code layer},
     * verified to actually be a {@code CAMetalLayer} ({@code isKindOfClass:}) before returning it --
     * a layer-less or non-Metal content view (window not yet created, GL backend, unexpected macOS
     * view hierarchy) yields 0 rather than a garbage receiver for the next message send. Runs
     * entirely inside {@link #apply}'s own autorelease pool -- no pool of its own.
     */
    private static long resolveMetalLayer() {
        long glfwHandle = Minecraft.getInstance().getWindow().handle();
        long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(glfwHandle);
        if (nsWindow == 0) {
            return 0;
        }
        long contentView = Objc.msgSendId(nsWindow, Objc.selector("contentView"));
        if (contentView == 0) {
            return 0;
        }
        long layer = Objc.msgSendId(contentView, Objc.selector("layer"));
        if (layer == 0) {
            return 0;
        }
        long metalLayerClass = Objc.getClass("CAMetalLayer");
        if (metalLayerClass == 0) {
            return 0;
        }
        boolean isMetalLayer = Objc.msgSendBool(
                layer, Objc.selector("isKindOfClass:"), metalLayerClass);
        return isMetalLayer ? layer : 0;
    }

    /**
     * A RETAINED mutable copy of {@code [layer developerHUDProperties]} (never nil: an absent
     * property starts a fresh, retained empty dictionary instead), so {@link #apply} can remove
     * just the {@code mode} key rather than replace the whole object. Returns 0 on failure.
     */
    private static long currentPropertiesMutableCopy(long layer) {
        long current = Objc.msgSendId(layer, Objc.selector("developerHUDProperties"));
        if (current != 0) {
            return Objc.msgSendId(current, Objc.selector("mutableCopy"));
        }
        long mutableDictionaryClass = Objc.getClass("NSMutableDictionary");
        if (mutableDictionaryClass == 0) {
            return 0;
        }
        long fresh = Objc.msgSendId(mutableDictionaryClass, Objc.selector("dictionary"));
        if (fresh == 0) {
            return 0;
        }
        return Objc.msgSendId(fresh, Objc.selector("retain"));
    }

    /** {@code [nsObject description]} as a Java string, or the literal {@code "nil"} for a null
     * receiver -- used only to log what the OS actually holds after a set call, never to make any
     * behavioral decision. */
    private static String describe(long nsObject) {
        if (nsObject == 0) {
            return "nil";
        }
        long description = Objc.msgSendId(nsObject, Objc.selector("description"));
        return Objc.nsStringToJava(description);
    }

    private static void logSuccess(String message) {
        FornaxMod.LOGGER.info("[Fornax] Metal Performance HUD: {}", message);
    }

    private static void logFailure(String reason) {
        FornaxMod.LOGGER.warn("[Fornax] Metal Performance HUD: {}", reason);
    }
}
