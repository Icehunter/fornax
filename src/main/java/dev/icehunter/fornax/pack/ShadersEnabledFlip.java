package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.FornaxSettings;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pack.layout.RuntimeShaderPack;
import dev.icehunter.fornax.util.RendererReload;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;

/**
 * The master shaders-enabled toggle flipped with the active pack SELECTION unchanged -- extracted
 * from {@code screen.ShaderPacksScreen#applyChanges} so both that screen's master-toggle-only
 * branch and the YACL-hosted {@code screen.FornaxSettingsScreen}'s save callback share exactly one
 * implementation, never two independently-maintained copies of the render-state latch law.
 *
 * <p>Neither the {@code packChanged} branch of {@code ShaderPacksScreen.applyChanges} nor {@code
 * GraphRunner.rebuild}/{@code unload} runs on this path, so nothing else would ever touch {@link
 * RuntimeShaderPack}'s published vanilla-shader override here -- a pack disabled this way used to
 * keep serving a vanilla core-shader override (e.g. the curved lightmap) until restart, until this
 * clear/republish pairing was added (live-caught).
 *
 * <p>THE RENDER-STATE LATCH LAW: the terrain pipeline's shader redirect, {@code USE_DEFERRED}
 * constant, and 5-attachment G-buffer color-target state are all baked in at compile time, keyed
 * off {@code GraphRunner.isActive()}, while the per-frame render-pass attachment count follows the
 * CURRENT value of that same flag -- flipping {@code shadersEnabled} without a renderer reload
 * crashes {@code RenderPass.setPipeline} on the very next chunk draw with an attachment-count
 * mismatch (live-caught; see {@link RendererReload}'s own doc comment). The renderer reload below
 * is therefore CHAINED on the vanilla-override republish/clear future, never requested directly:
 * that future resolves asynchronously, and reloading the renderer before it lands would resync the
 * terrain pipelines against a resource snapshot that doesn't yet reflect the new override state.
 */
public final class ShadersEnabledFlip {
    private ShadersEnabledFlip() {
    }

    /**
     * Applies {@code newEnabled} to {@link FornaxSettings#shadersEnabled}, republishes or clears
     * the active pack's vanilla-shader override to match, persists the config, and chains a
     * renderer reload onto the override future's completion -- exactly the sequence {@code
     * ShaderPacksScreen.applyChanges}'s master-toggle-only branch used to run inline.
     */
    public static void apply(boolean newEnabled) {
        FornaxSettings settings = FornaxConfig.get();
        settings.shadersEnabled = newEnabled;

        CompletableFuture<Void> sourcesVisible;
        if (!newEnabled) {
            sourcesVisible = GraphRunner.currentPack() != null
                    ? RuntimeShaderPack.getInstance().clearVanillaOverrides()
                    : CompletableFuture.completedFuture(null);
        } else {
            // A no-op (already-completed future) whenever no pack is loaded -- see
            // GraphRunner.republishVanillaOverride()'s own self-contained safety check.
            sourcesVisible = GraphRunner.republishVanillaOverride();
        }

        FornaxConfig.save();
        // Completed-future paths (no pack loaded, or turning off with nothing to clear) run this
        // synchronously right here; a real override change defers it to the render thread once the
        // republished/cleared sources are visible.
        sourcesVisible.thenRunAsync(RendererReload::request, Minecraft.getInstance())
                .exceptionally(t -> {
                    // The toggle is saved but the terrain pipelines were never resynced; rendering
                    // degrades consistently (previous latch state) but silently past this log line.
                    FornaxMod.LOGGER.error(
                            "[Fornax] Resource reload failed after shaders-enabled flip; renderer reload skipped", t);
                    return null;
                });
    }
}
