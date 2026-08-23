package dev.icehunter.fornax.compat;

import dev.icehunter.fornax.FornaxMod;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Detects other loaded mods that render their own sky/clouds, so Fornax can yield sky and cloud
 * ownership instead of colliding with them -- users routinely mix mods, and two competing
 * sky-pass cancellations (or two procedural cloud layers) fighting over the same frame is a worse
 * experience than Fornax quietly stepping back to plain vanilla sky/clouds for that session.
 *
 * <p>Queried lazily and cached for the whole session (mod loading is fixed at launch, so the
 * answer can never change mid-session outside tests) -- {@code GraphRunner.packOwnsSky()}/{@code
 * packOwnsClouds()} (in {@code dev.icehunter.fornax.pack.graph}) are the only production callers,
 * both per-frame hot paths that must not re-query {@link FabricLoader} every call.
 */
public final class SkyModCompat {
    /** Mod ids known to render their own sky and/or clouds. */
    private static final Set<String> COMPETING = Set.of("nuit", "fabricskyboxes");

    @Nullable
    private static Set<String> testOverride;
    @Nullable
    private static Boolean cached;

    private SkyModCompat() {
    }

    /**
     * True if any competing sky mod is loaded -- the sky/cloud-pass cancellation mixins and
     * {@code GraphRunner.packOwnsSky()}/{@code packOwnsClouds()} treat this as an unconditional
     * veto, regardless of what the active pack's own compile options resolve to.
     */
    public static boolean competingSkyModLoaded() {
        if (cached == null) {
            cached = computeCompetingSkyModLoaded();
        }
        return cached;
    }

    private static boolean computeCompetingSkyModLoaded() {
        for (String id : COMPETING) {
            boolean loaded = testOverride != null ? testOverride.contains(id) : FabricLoader.getInstance().isModLoaded(id);
            if (loaded) {
                FornaxMod.LOGGER.info("[Fornax] Detected competing sky mod '{}' -- yielding sky/cloud "
                        + "ownership to it for this session", id);
                return true;
            }
        }
        return false;
    }

    /**
     * Test-only seam: replaces the {@link FabricLoader#isModLoaded} query with a fixed set of mod
     * ids and clears the cache so the next {@link #competingSkyModLoaded()} call recomputes
     * against it. Pass {@code null} to restore the real {@link FabricLoader} query (also clearing
     * the cache) -- call this in an {@code @AfterEach} to avoid leaking state between tests.
     */
    public static void overrideLoadedModsForTest(@Nullable Set<String> mods) {
        testOverride = mods;
        cached = null;
    }
}
