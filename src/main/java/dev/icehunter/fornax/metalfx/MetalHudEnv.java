package dev.icehunter.fornax.metalfx;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Optional;

/**
 * Sets {@code MTL_HUD_ENABLED} natively from {@link dev.icehunter.fornax.FornaxPreLaunch}, before
 * Minecraft's {@code main()} and therefore before any Vulkan/MoltenVK/Metal initialization. Apple's
 * HUD subsystem must exist before {@code Metal.framework} loads, or {@link MetalHudControl}'s
 * per-layer property is a well-formed no-op (see that class's header for the restart-to-apply
 * picture).
 *
 * <p>Not routed through {@link dev.icehunter.fornax.metalfx.objc.Objc}: that class's static
 * initializer eagerly loads Metal, MetalFX and QuartzCore, which belongs after a window exists,
 * not this early. {@code setenv} is plain libc, so this links only {@link Linker#defaultLookup()}.
 */
public final class MetalHudEnv {
    private static final boolean PLATFORM_SUPPORTED;

    static {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        PLATFORM_SUPPORTED = os.contains("mac") && arch.equals("aarch64");
    }

    private MetalHudEnv() {
    }

    /**
     * No-ops off macOS/aarch64, and best-effort everywhere else: any FFM failure here is logged,
     * never thrown, since a missed HUD is cosmetic and must never block game boot. Requires {@link
     * FornaxConfig#load()} to have already run (the caller, {@code FornaxPreLaunch}, does this
     * immediately before calling here).
     */
    public static void enableIfConfigured() {
        if (!PLATFORM_SUPPORTED || !FornaxConfig.get().metalHud) {
            return;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup libc = linker.defaultLookup();
            Optional<MemorySegment> setenvSymbol = libc.find("setenv");
            if (setenvSymbol.isEmpty()) {
                FornaxMod.LOGGER.warn("[Fornax] Metal Performance HUD: libc setenv not found; "
                        + "the toggle will need the MTL_HUD_ENABLED env var set manually");
                return;
            }
            MethodHandle setenv = linker.downcallHandle(setenvSymbol.get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            try (Arena local = Arena.ofConfined()) {
                MemorySegment name = local.allocateFrom("MTL_HUD_ENABLED");
                MemorySegment value = local.allocateFrom("1");
                int result = (int) setenv.invokeExact(name, value, 1);
                if (result != 0) {
                    FornaxMod.LOGGER.warn(
                            "[Fornax] Metal Performance HUD: setenv(MTL_HUD_ENABLED) returned {}", result);
                } else {
                    FornaxMod.LOGGER.info(
                            "[Fornax] Metal Performance HUD: MTL_HUD_ENABLED set before Metal init");
                }
            }
        } catch (Throwable t) {
            FornaxMod.LOGGER.warn("[Fornax] Metal Performance HUD: failed to set MTL_HUD_ENABLED "
                    + "natively; the toggle will need the env var set manually", t);
        }
    }
}
