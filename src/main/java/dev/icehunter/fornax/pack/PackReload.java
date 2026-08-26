package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.FornaxSettings;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.PersistentPipelineCache;
import dev.icehunter.fornax.util.RendererReload;
import dev.icehunter.fornax.screen.PackSettingsSupport;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Re-discovers and rebuilds whichever pack {@code FornaxConfig.get().activePack} currently names --
 * the reusable core behind both {@code FornaxMod}'s boot-time pack activation and a live settings
 * change that isn't itself a pack option (the engine's own {@code aaMethod} selector), so both
 * activation paths share exactly one discover/load/rebuild implementation rather than drifting apart
 * as two copies. {@code GraphRunner.rebuild} always overlays fresh {@code EngineDefines} for the
 * CURRENT {@code aaMethod} on every call, so simply re-running {@link #reload} picks up whatever
 * setting just changed.
 *
 * <p>Deliberately does NOT call {@code RendererReload.request()}, unlike {@code
 * PackEditSession.apply()}'s compile-option path: engine defines only change which pack GRAPH
 * shader text compiles (fullscreen post passes, via {@code GraphRunner.rebuild}'s own resource-pack
 * republish), never the terrain pipeline shape {@code RendererReload} exists to resync -- see its
 * javadoc. Recompiling the pack graph alone is sufficient here. This method itself doesn't need to
 * touch the rebuild's returned resource-reload future at all: {@code GraphRunner.rebuild} now gates
 * its OWN pass-runner (re)build on that future landing (see its doc comment), so a fullscreen pass
 * gated on an {@code FX_*} define genuinely self-heals a few frames later -- it simply has no
 * runner (a normal, logged-once, non-fatal skip) until then, never a pipeline built against a stale
 * shader snapshot.
 */
public final class PackReload {
    private PackReload() {
    }

    /**
     * Reapplies the active pack from the settings screen (or any other live call site): a device
     * and window are guaranteed to exist here, so the real window size is used for the graph
     * validator's VRAM estimate, unlike {@code FornaxMod}'s boot-time call (see {@link #reload}'s own
     * doc on why boot can't do the same).
     */
    public static void reapplyActivePack() {
        var window = Minecraft.getInstance().getWindow();
        reload(window.getWidth(), window.getHeight());
    }

    /**
     * Discovers, loads, and rebuilds {@code FornaxConfig.get().activePack} against the graph
     * interpreter. {@code renderWidth}/{@code renderHeight} feed only the boot-time-safe
     * {@code GraphValidator} VRAM estimate baked into {@link PackModel} -- real target allocation is
     * always lazy (see {@code GraphRunner.prepare()}), so a caller with no live window yet (mod init,
     * before {@code Minecraft}'s constructor finishes) can pass a placeholder size and a caller with
     * a real window (this settings screen) can pass its actual size; neither affects correctness.
     * No-op if no pack is configured active. Any discovery/load/rebuild failure is logged and leaves
     * {@link GraphRunner} unloaded (vanilla Sodium) rather than thrown -- a broken or missing pack
     * must never crash the caller, whether that's mod init or a settings-screen click.
     */
    public static void reload(int renderWidth, int renderHeight) {
        // Flushes whatever this process has compiled SO FAR -- i.e. the previous activation's
        // pipelines, which have had every frame since that reload to finish their lazy Blaze3D
        // compiles (see FullscreenPassRunner's first-bind timing) -- to disk, before starting this
        // one. Deliberately at the START of a reload rather than chained onto this call's own
        // completion: this reload's own pipelines won't exist yet for several frames (compute/
        // particle compile synchronously inside the NEXT ensureRunnersBuilt() pass; fullscreen
        // pipelines compile lazily on first bind, later still), so persisting at the end would
        // capture little of what this reload is about to build. Placing it here instead means an
        // iterative F8 tuning session's gains are never more than one reload old on disk, without
        // waiting for a clean quit (CLIENT_STOPPING's own persist() covers that). No-op, safely, on
        // the very first reload of a process (nothing compiled yet) or if no cache exists.
        PersistentPipelineCache.persist();

        FornaxSettings settings = FornaxConfig.get();
        if (settings.activePack.isEmpty()) {
            return;
        }

        List<DiscoveredPack> discovered = PackDiscovery.discover();
        DiscoveredPack picked = discovered.stream()
                .filter(p -> p.name().equals(settings.activePack))
                .findFirst().orElse(null);

        for (DiscoveredPack pack : discovered) {
            if (pack != picked) {
                closeQuietly(pack);
            }
        }

        if (picked == null) {
            FornaxMod.LOGGER.warn("[Fornax] Configured active pack '{}' not found under shaderpacks/; falling back to vanilla Sodium", settings.activePack);
            return;
        }

        try {
            PackModel model = PackDiscovery.load(picked, renderWidth, renderHeight);
            Map<String, String> values = PackSettingsSupport.mergedValues(model);
            Map<String, String> shaderSources = PackDiscovery.loadShaderSources(model.root());
            logShaderFingerprints(shaderSources);
            long rebuildStart = System.nanoTime();
            var sourcesVisible = GraphRunner.rebuild(model, shaderSources,
                    PackSettingsSupport.compileIntMap(model, values),
                    PackSettingsSupport.runtimeFloatMap(model, values));
            // A pack reapply republishes the pack's terrain shader sources, not just the pack
            // graph, and Sodium's terrain pipelines only recompile on a renderer resync. Chained
            // on the resource future, never requested directly -- same latch-law idiom as
            // PackSwitch.
            //
            // The timing log here spans vanilla's FULL Minecraft.reloadResourcePacks() (every mod's
            // resources, not just this pack's) plus every reload listener it drives, including the
            // labPBR normal/material atlas builds (each separately timed at their own log line) --
            // this is the number to compare those against to see whether vanilla's own reload or
            // Fornax's atlas compositing is the larger share of an F8's wall-clock time.
            sourcesVisible.thenRunAsync(() -> {
                FornaxMod.LOGGER.info("[Fornax] Pack sources visible (vanilla resource reload + "
                                + "atlas builds) in {} ms",
                        (System.nanoTime() - rebuildStart) / 1_000_000L);
                RendererReload.request();
            }, Minecraft.getInstance()).exceptionally(t -> {
                // Matches PackSwitch.apply and PackEditSession.apply's identical chains.
                FornaxMod.LOGGER.error(
                        "[Fornax] Resource reload failed after pack reload; renderer reload skipped", t);
                return null;
            });
            FornaxMod.LOGGER.info("[Fornax] Loaded active pack '{}' ({} targets, {} passes)", picked.name(),
                    model.graph().targets().size(), model.graph().passes().size());
        } catch (FornaxPackError e) {
            // Pack-authoring error: the interactive settings screens surface these on an error
            // screen, but neither mod init nor this reload path has one -- log and stay vanilla.
            FornaxMod.LOGGER.error("[Fornax] Failed to load configured pack '{}'; falling back to vanilla Sodium", settings.activePack, e);
            GraphRunner.unload();
            closeQuietly(picked);
        } catch (Throwable t) {
            // A broken pack (or a bug of ours) must never kill the caller -- live-caught at boot: MC
            // 26.2's registry throws IllegalStateException on tag access before the first datapack
            // tag bind, which sailed straight through the FornaxPackError-only catch above and took
            // the whole boot down. GraphRunner.rebuild mutates its statics before its final resolve
            // step, so unload() here rolls a half-installed pack back to the same "GraphRunner stays
            // inactive, vanilla rendering" state a missing pack leaves behind.
            FornaxMod.LOGGER.error("[Fornax] Unexpected failure loading configured pack '{}'; falling back to vanilla Sodium", settings.activePack, t);
            GraphRunner.unload();
            closeQuietly(picked);
        }
    }

    /** One INFO line per reload naming the content fingerprint of the shaders most edited during
     * live tuning -- the ground-truth answer to "is the game running the file I just edited?". */
    private static void logShaderFingerprints(Map<String, String> sources) {
        for (String key : new String[] {"shaders/blocks/terrain.fsh", "shaders/blocks/terrain.vsh", "shaders/post/gbuffer_resolve.fsh"}) {
            String src = sources.get(key);
            FornaxMod.LOGGER.info("[Fornax] shader fingerprint {} = {}", key,
                    src == null ? "ABSENT" : Integer.toHexString(src.hashCode()) + " (" + src.length() + " chars)");
        }
    }

    private static void closeQuietly(DiscoveredPack pack) {
        try {
            pack.close();
        } catch (IOException e) {
            // Best-effort cleanup only -- a failed zip-handle close never blocks activation.
        }
    }
}
