package dev.icehunter.fornax.metalfx;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.metalfx.objc.Objc;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeCocoa;

/**
 * Apple's Metal Performance HUD ({@code CAMetalLayer.developerHUDProperties}, public API since
 * macOS 13) exposed as a live Fornax settings toggle -- replacing the session-only
 * {@code MTL_HUD_ENABLED} environment variable the user previously had to set before launch.
 * Setting a non-empty properties dictionary on the game window's {@code CAMetalLayer} (MoltenVK's
 * own layer, backing the GLFW-owned {@code NSWindow}'s content view -- the same layer every
 * presented Vulkan frame lands on) turns the overlay on; an empty dictionary turns it back off.
 * Both take effect on the next presented frame -- no relaunch required, unlike the env-var path.
 *
 * <p>Called from {@link dev.icehunter.fornax.config.SettingsApplyRouter}'s {@code
 * METAL_HUD_APPLY} action (either direction of the {@code metalHud} setting) and once at {@code
 * CLIENT_STARTED} when the persisted config already has it on -- see {@code FornaxMod}.
 *
 * <p>FAIL-CLOSED, EXACTLY LIKE EVERY OTHER {@link Objc} CALLER IN THIS PACKAGE: guarded by {@link
 * Objc#isLoaded()} (implies macOS/aarch64) plus a live {@code respondsToSelector:}/{@code
 * isKindOfClass:} probe of the resolved layer. Any failure anywhere in the chain (no window yet,
 * content view not layer-backed, layer isn't actually a {@code CAMetalLayer}, older macOS without
 * the property) is logged -- never thrown -- into the settings-save or client-start path.
 *
 * <p>EVERY {@link #apply} call logs an explicit success-or-failure verdict, including a read-back
 * of {@code [layer developerHUDProperties]} immediately after the set call. This is deliberate,
 * not chatty: {@link #apply} runs at most a handful of times per session (once at startup, once
 * per settings-screen toggle -- see the call sites above), so there is no hot-path spam risk, and
 * a silent outcome here was previously the reason "the HUD never shows" could not be root-caused
 * from outside this class at all -- see the mc-vulkan-realism architecture-audit follow-up.
 */
public final class MetalHudControl {
    private MetalHudControl() {
    }

    /**
     * Enables/disables the HUD on the game window's {@code CAMetalLayer}. Safe to call any number
     * of times, including redundantly with the same value -- each call independently resolves the
     * layer and sets its properties dictionary fresh.
     *
     * <p>ONE autorelease pool brackets the WHOLE operation -- layer resolution, dictionary
     * construction, AND the {@code setDeveloperHUDProperties:} call -- popped only in the
     * {@code finally} after the setter has run. This is load-bearing, not defensive style: {@code
     * dictionaryWithObject:forKey:} (and the {@code NSString} pieces that build it) hand back
     * AUTORELEASED objects, and Java evaluates a method's return expression before its {@code
     * finally} block runs -- an earlier revision popped a pool scoped to only the dictionary-build
     * helper, which freed the autoreleased dictionary before {@code setDeveloperHUDProperties:} ever
     * saw it, a live SIGSEGV on the enable path (reproduced 3/3). The disable path never caught this
     * because {@code [NSDictionary dictionary]} returns Apple's immortal empty singleton, not a
     * fresh autoreleased object.
     */
    public static void apply(boolean enabled) {
        if (!Objc.isLoaded()) {
            return;
        }
        long pool = Objc.autoreleasePoolPush();
        try {
            long layer = resolveMetalLayer();
            if (layer == 0) {
                logFailure("could not resolve the window's CAMetalLayer -- no-op");
                return;
            }
            long responds = Objc.selector("respondsToSelector:");
            long setProperties = Objc.selector("setDeveloperHUDProperties:");
            if (!Objc.msgSendBool(layer, responds, setProperties)) {
                logFailure("CAMetalLayer has no setDeveloperHUDProperties: (macOS < 13?) -- no-op");
                return;
            }
            long properties = enabled ? enabledProperties() : emptyDictionary();
            if (properties == 0) {
                // Never fall through and send nil here: on the enable path that would silently do
                // the SAME thing as disabling, with nothing anywhere to tell the two apart.
                logFailure("could not build the HUD " + (enabled ? "on" : "off")
                        + " properties dictionary (NSDictionary unavailable) -- not touching the HUD");
                return;
            }
            Objc.msgSendVoid(layer, setProperties, properties);
            long readBack = Objc.msgSendId(layer, Objc.selector("developerHUDProperties"));
            logSuccess("set developerHUDProperties(enabled=" + enabled + "), read back "
                    + describe(readBack));
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

    /** {@code {"mode": "default"}} -- Apple's documented minimal HUD-on properties dictionary.
     * Returns an AUTORELEASED object; the caller ({@link #apply}) must not pop its pool until AFTER
     * handing this to {@code setDeveloperHUDProperties:} -- see {@link #apply}'s own doc comment. */
    private static long enabledProperties() {
        long key = Objc.nsString("mode");
        long value = Objc.nsString("default");
        long dictionaryClass = Objc.getClass("NSDictionary");
        if (dictionaryClass == 0) {
            return 0;
        }
        return Objc.msgSendId(
                dictionaryClass, Objc.selector("dictionaryWithObject:forKey:"), value, key);
    }

    /** {@code [NSDictionary dictionary]} -- the documented HUD-off value (an empty dictionary). */
    private static long emptyDictionary() {
        long dictionaryClass = Objc.getClass("NSDictionary");
        if (dictionaryClass == 0) {
            return 0;
        }
        return Objc.msgSendId(dictionaryClass, Objc.selector("dictionary"));
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
