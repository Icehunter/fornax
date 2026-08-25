package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.FornaxSettings;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.screen.PackSettingsSupport;
import dev.icehunter.fornax.util.RendererReload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Commits an active-pack SELECTION change (activating a named pack, or unloading back to "None") --
 * mirroring {@link ShadersEnabledFlip}'s own shared-implementation shape, so every caller that needs
 * to change the active pack -- currently {@code screen.FornaxPacksTab}'s Apply flow, the Shader
 * Packs tab hosted inside the YACL-based {@code screen.FornaxSettingsScreen} -- shares exactly one
 * implementation, never independently-maintained copies of this apply sequence.
 *
 * <p>Does its OWN fresh {@code PackDiscovery.discover()} pass rather than reusing a caller-held scan
 * -- the {@code PackReload.reload()} precedent -- and closes every NON-picked pack's filesystem from
 * that scan immediately; the one pack actually activated keeps its own freshly-opened filesystem
 * alive indefinitely, tracked from then on via {@code GraphRunner.currentPack().root()} (never by
 * the caller). A caller that holds its own separate discovery scan purely to populate a pick list
 * (e.g. {@code screen.FornaxPacksTab}'s row scan) can therefore always close every handle IT
 * opened, unconditionally -- none of them are ever the one this method goes on to activate.
 * Concurrently open, independent {@link java.nio.file.FileSystem} views of the same zip pack are
 * routine in this codebase (every tab build re-scans over an already-active zip pack), so a second
 * scan here opening its own handles alongside a caller's is not a new hazard.
 *
 * <p><b>Ordering law for a caller that ALSO flips {@code shadersEnabled} in the SAME apply step:</b>
 * that field must already hold its NEW value in {@link FornaxConfig#get()} before calling {@link
 * #apply} -- never written afterward. {@code GraphRunner.rebuild}/{@code #unload} both read {@code
 * FornaxConfig.get().shadersEnabled} live to decide whether the just-(de)activated pack's vanilla
 * overrides publish or stay suppressed (the "invisible when off" invariant), so this method must see
 * the correct final value already in place. This mirrors {@code ShaderPacksScreen.applyChanges}'s
 * original ordering exactly: its both-changed case wrote {@code settings.shadersEnabled =
 * this.stagedEnabled} as the very first statement of the {@code packChanged} branch, before touching
 * the pack selection at all. {@code screen.FornaxPacksTab}'s {@code apply()} satisfies this same law
 * by construction today: {@code screen.PackListState#applyPlan()} orders {@code
 * Action.WRITE_ENABLED} before {@code Action.PACK_SWITCH} whenever both are pending, and never
 * returns {@code Action.SHADERS_FLIP} alongside {@code Action.PACK_SWITCH} -- so a combined
 * toggle-and-switch Apply always writes {@code shadersEnabled} into {@link FornaxConfig#get()}
 * before this method runs, and never also invokes {@link ShadersEnabledFlip#apply} for that same
 * Apply.
 *
 * <p>THE RENDER-STATE LATCH LAW: the terrain pipeline's shader redirect, {@code USE_DEFERRED}
 * constant, and 5-attachment G-buffer color-target state are all baked in at compile time, keyed off
 * {@code GraphRunner.isActive()}, while the per-frame render-pass attachment count follows the
 * CURRENT value of that same flag -- flipping the active pack without a renderer reload crashes
 * {@code RenderPass.setPipeline} on the very next chunk draw with an attachment-count mismatch
 * (live-caught; see {@link RendererReload}'s own doc comment). The renderer reload below is
 * therefore CHAINED on the rebuild/unload's resource-reload future, never requested directly: that
 * future resolves asynchronously, and reloading the renderer before it lands would resync the
 * terrain pipelines against a resource snapshot that doesn't yet reflect the new pack's (or no
 * pack's) content.
 */
public final class PackSwitch {
    private PackSwitch() {
    }

    /**
     * Activates {@code targetPackName} as the new active pack -- empty unloads back to vanilla
     * Sodium: saves and closes the OLD pack's values/filesystem, then loads and rebuilds the new one
     * (or unloads, for empty), persists the config, and chains a renderer reload onto the resulting
     * resource-reload future -- exactly the sequence {@code ShaderPacksScreen.applyChanges}'s {@code
     * packChanged} branch used to run inline.
     *
     * <p>On failure (a {@link FornaxPackError}, or a target that no longer discovers), {@code
     * activePack} is explicitly REVERTED to {@code previousPackName}. The legacy screen only ever
     * wrote that field after a successful load, but the YACL path's binding writes the pending value
     * into the live config BEFORE this method runs (YACL's apply-before-save semantics) -- without
     * the revert, the broken pack's name would be persisted by the {@code FornaxConfig.save()} in
     * the catch below and greet the next boot with the fallback log. Callers must therefore pass the
     * pre-change value: the legacy screen passes the still-unwritten live field, the YACL screen its
     * pre-binding snapshot.
     *
     * @param previousPackName the {@code activePack} value from BEFORE the caller (or its binding)
     *                     staged this switch -- restored on any failure so only a successful rebuild
     *                     ever leaves a new name persisted.
     * @param alertParent the screen an {@link AlertScreen} returns to on a {@link FornaxPackError}
     *                     (the {@code shadersEnabled = false} fallback) -- must be a screen safe to
     *                     redisplay as-is, never a YACL instance with stale pending option state (see
     *                     {@code FornaxSettingsScreen}'s fresh-parent law, which this same hazard
     *                     class applies to).
     * @return {@code false} only when activating the named pack failed (an alert screen is already
     *         up); {@code true} otherwise, including every unload-to-empty call.
     */
    public static boolean apply(String targetPackName, String previousPackName, Screen alertParent) {
        FornaxSettings settings = FornaxConfig.get();
        CompletableFuture<Void> sourcesVisible = CompletableFuture.completedFuture(null);

        // The old pack's filesystem is only closed once the switch is known to proceed, never up
        // front. Closing it unconditionally left GraphRunner.currentPack() pointing at a model backed
        // by an already-closed zip FileSystem on the picked-pack-not-found path below (nothing else in
        // that path touches GraphRunner, so the old pack is still meant to be active), throwing
        // ClosedFileSystemException the next time anything re-read from it.
        PackModel oldModel = GraphRunner.currentPack();
        if (oldModel != null) {
            PackValuesFile.save(PackSettingsSupport.valuesPath(oldModel), PackSettingsSupport.mergedValues(oldModel));
        }

        if (targetPackName.isEmpty()) {
            if (oldModel != null) {
                closeIfCustomFileSystem(oldModel.root());
            }
            sourcesVisible = GraphRunner.unload();
            settings.activePack = "";
        } else {
            List<DiscoveredPack> discovered = PackDiscovery.discover();
            DiscoveredPack picked = discovered.stream()
                    .filter(p -> p.name().equals(targetPackName)).findFirst().orElse(null);
            for (DiscoveredPack pack : discovered) {
                if (pack != picked) {
                    closeQuietly(pack);
                }
            }
            if (picked == null) {
                settings.activePack = previousPackName;
                FornaxMod.LOGGER.warn("[Fornax] Pack '{}' no longer discovers under shaderpacks/;"
                        + " keeping '{}' active", targetPackName, previousPackName);
                FornaxConfig.save();
                return false;
            }
            try {
                var window = Minecraft.getInstance().getWindow();
                PackModel model = PackDiscovery.load(picked, window.getWidth(), window.getHeight());
                Map<String, String> values = PackSettingsSupport.mergedValues(model);
                sourcesVisible = GraphRunner.rebuild(model, PackDiscovery.loadShaderSources(model.root()),
                        PackSettingsSupport.compileIntMap(model, values),
                        PackSettingsSupport.runtimeFloatMap(model, values));
                settings.activePack = picked.name();
                if (oldModel != null) {
                    closeIfCustomFileSystem(oldModel.root());
                }
            } catch (FornaxPackError e) {
                // GraphRunner.rebuild mutates its statics before its final resolve step, so a failure
                // partway through can leave currentPack/registry/compileValues pointing at the broken
                // new pack. unload() rolls GraphRunner back to inactive, matching PackReload.reload()'s
                // identical catch; shadersEnabled is forced off below too, so closing the old pack's
                // filesystem here is safe.
                //
                // The renderer reload must chain on unload()'s own future, never fire directly (RENDER-
                // STATE LATCH LAW, this class's own doc): calling it directly resynced terrain pipelines
                // against vanilla-override state that hadn't finished clearing, producing stale/ghosted
                // geometry (live-caught testing this fix).
                CompletableFuture<Void> unloaded = GraphRunner.unload();
                if (oldModel != null) {
                    closeIfCustomFileSystem(oldModel.root());
                }
                settings.activePack = previousPackName;
                settings.shadersEnabled = false;
                FornaxConfig.save();
                unloaded.thenRunAsync(RendererReload::request, Minecraft.getInstance())
                        .exceptionally(t -> {
                            FornaxMod.LOGGER.error("[Fornax] Resource reload failed after a pack"
                                    + " error fallback; renderer reload skipped", t);
                            return null;
                        });
                closeQuietly(picked);
                Minecraft.getInstance().gui.setScreen(new AlertScreen(
                        () -> Minecraft.getInstance().gui.setScreen(alertParent),
                        Component.literal("Shaderpack Error"), Component.literal(e.getMessage())));
                return false;
            }
        }

        FornaxConfig.save();
        // The unload path's completed-future runs this synchronously right here; a real pack
        // activation defers it to the render thread once the republished sources are visible.
        sourcesVisible.thenRunAsync(RendererReload::request, Minecraft.getInstance())
                .exceptionally(t -> {
                    // The pack is installed but the terrain pipelines were never resynced; the latch
                    // stays in its previous state, so rendering degrades consistently -- but silently
                    // enough that the log line is the only breadcrumb.
                    FornaxMod.LOGGER.error(
                            "[Fornax] Resource reload failed after pack apply; renderer reload skipped", t);
                    return null;
                });
        return true;
    }

    private static void closeQuietly(DiscoveredPack pack) {
        try {
            pack.close();
        } catch (IOException e) {
            // Best-effort cleanup only -- a failed zip-handle close never blocks activation.
        }
    }

    private static void closeIfCustomFileSystem(Path root) {
        FileSystem fs = root.getFileSystem();
        if (fs != FileSystems.getDefault()) {
            try {
                fs.close();
            } catch (IOException ignored) {
            }
        }
    }
}
