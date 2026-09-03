package dev.icehunter.fornax.debug;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.icehunter.fornax.FornaxKeybind;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.GBufferDebugView;
import dev.icehunter.fornax.pack.PackReload;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.EnvSpecularRatioReadback;
import dev.icehunter.fornax.pipeline.GBufferReadbackDiagnostic;
import dev.icehunter.fornax.util.RendererReload;
import dev.icehunter.fornax.voxel.PaletteSizeHistogram;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Live iteration keybinds for shader-pack/pipeline development, so shader-file and {@code
 * blocks.toml}/vertex-format edits become testable in-game without a full relaunch (see the ecv2
 * debugging saga in {@code .superpowers/sdd/progress.md} -- dozens of relaunch cycles burned
 * chasing changes that a live reload path would have shown instantly):
 *
 * <ul>
 *   <li>Reload Pack (default F8) -- re-discovers and rebuilds the active pack via {@link
 *       PackReload#reapplyActivePack()}, the exact same discover/load/{@link GraphRunner#rebuild}
 *       path the Sodium-hosted settings screen's Apply button uses. That path already clears
 *       Sodium's terrain-pipeline cache at the {@code sourcesReady} boundary and stamps a fresh
 *       {@code FORNAX_PACK_GEN} cache-busting define (see {@code GraphRunner.rebuild}'s own doc
 *       comment) -- both ecv2 fixes -- so a shader-file edit is guaranteed to actually recompile,
 *       not silently bind a stale cached pipeline. Shift+F8 additionally calls {@link
 *       RendererReload#request()} (Sodium's full chunk remesh + renderer recreation), needed on
 *       top of a pack reload only when a vertex-format or {@code blocks.toml} category edit
 *       changes what terrain itself uploads, not just what the post chain does with it.</li>
 *   <li>Debug View Cycle (unbound by default) -- steps {@link FornaxConfig#get()}{@code
 *       .debugView} forward through {@link GBufferDebugView#values()} (wrapping), or backward
 *       while Shift is held, replacing the ~16-click Sodium-dropdown path with one keypress.
 *       Persisted the same way the dropdown itself persists a change ({@link FornaxConfig#save()}
 *       right after the field write).</li>
 *   <li>Readback Dump (unbound by default) -- one-shot {@link
 *       GBufferReadbackDiagnostic#requestDump()}, serviced by the very next {@code
 *       GraphRunner.finish} call regardless of whether the profiler overlay (the automatic
 *       per-30-frame log's own gate) is on.</li>
 *   <li>Palette Histogram Dump (unbound by default) -- one-shot {@link
 *       PaletteSizeHistogram#dumpToLog()}, logging the real per-section palette-size distribution
 *       (histogram, max/mean/p50/p95/p99, cap-hit count) plus what each candidate {@code
 *       SectionHarvester.MAX_PALETTE_ENTRIES} value would cost in VRAM -- the evidence that already
 *       took the constant from 256 to 96 and, via the cap-hits counter, the early warning if a
 *       future world ever needs it moved again.</li>
 *   <li>Measure Env Specular (unbound by default) -- one-shot {@link
 *       EnvSpecularRatioReadback#requestMeasure()}: a VRAM-to-CPU readback of the crosshair pixel,
 *       valid only while one of {@link GBufferDebugView#ENV_SPEC_RATIO}/{@link
 *       GBufferDebugView#ENV_DECOMP_SKY}/{@link GBufferDebugView#ENV_DECOMP_MIX}/{@link
 *       GBufferDebugView#ENV_DECOMP_MAT}/{@link GBufferDebugView#ENV_DECOMP_LOCAL}/{@link
 *       GBufferDebugView#ENV_DECOMP_AO}/{@link GBufferDebugView#ENV_DECOMP_RESIDUAL} is the
 *       active debug view (select one with Debug View Cycle first, press again after cycling to
 *       read the next). Prints actual numbers rather than asking a false-colour ramp to
 *       distinguish 5% from 15% by eye -- see that class's own
 *       doc comment for what each ordinal reports.</li>
 *   <li>Array Layer Probe (unbound by default) -- one-shot {@link
 *       ArrayTextureLayerProbe#run()}: a throwaway GPU smoke test that writes and shader-samples both
 *       layers of a real 2-layer array {@code GpuTexture}, de-risking the paged block atlas's future
 *       phase-3 array-texture holders before anything is built on that assumption. Touches no live
 *       render target and has zero visible effect; see that class's own doc comment for why both a
 *       write and a real sampling round-trip are needed to prove a layer actually works.</li>
 *   <li>Array Copy-Layer Probe (unbound by default): one-shot {@link
 *       ArrayTextureCopyLayerProbe#run()}. GPU-copies two ordinary 2D textures into an array
 *       texture's two layers, then shader-samples both back, proving {@link
 *       dev.icehunter.fornax.atlas.ArrayTextures#copyLayer} reaches the sampler, not just the
 *       destination image. Zero rendering-visible effect, same shape as Array Layer Probe.</li>
 * </ul>
 *
 * <p>Registered separately from {@link FornaxKeybind} (development/debugging aids, not
 * player-facing UI shortcuts) but sharing its {@link FornaxKeybind#CATEGORY} "Fornax" bucket, so
 * both groups list together on the Controls screen. Every bind here except Reload Pack defaults
 * UNBOUND -- see this class's own tick handler for how Shift is read live off the physical key
 * state at click time (one physical key driving two logical actions) rather than a second,
 * separately-bindable {@link KeyMapping} that could conflict with the first.
 */
public final class FornaxDebugKeys {
    private FornaxDebugKeys() {
    }

    public static void register() {
        KeyMapping reloadPack = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.reload_pack", InputConstants.Type.KEYSYM, InputConstants.KEY_F8, FornaxKeybind.CATEGORY));
        KeyMapping debugViewCycle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.debug_view_cycle", InputConstants.Type.KEYSYM, InputConstants.KEY_F9, FornaxKeybind.CATEGORY));
        KeyMapping readbackDump = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.readback_dump", InputConstants.Type.KEYSYM, InputConstants.KEY_F10, FornaxKeybind.CATEGORY));
        // Unbound by default (matches FornaxKeybind's own three player-facing binds): every real
        // function key in this class's own F8-F10 sequence is already claimed, and F11 collides with
        // vanilla's own Toggle Fullscreen bind -- a shared physical key fires BOTH KeyMappings on
        // press, not a quiet override. The player picks a free key from the Controls screen instead.
        KeyMapping paletteHistogramDump = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.palette_histogram_dump", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), FornaxKeybind.CATEGORY));
        // Unbound by default, same rationale as Palette Histogram Dump above -- a rare, on-demand
        // verification aid (analytic-lights milestone, M1) for a feature with no rendering output yet.
        KeyMapping analyticLightListDump = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.analytic_light_list_dump", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), FornaxKeybind.CATEGORY));
        // Unbound by default, same rationale as the two dumps above -- the High-tier cell-flicker
        // instrument (2026-07-22): arms an 8-tick capture+diff of the camera slot's light-volume
        // words. See LightVolumeDebug's own doc for the producer-vs-relaxation question it answers.
        KeyMapping lightVolumeDump = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.light_volume_dump", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), FornaxKeybind.CATEGORY));
        // Unbound by default, same rationale as the three dumps above -- LabPBR decode audit
        // (2026-08-09): a one-shot numeric readback, only meaningful with the ratio or one of the
        // three decomposition ordinals selected (EnvSpecularRatioReadback knows all four).
        KeyMapping measureEnvSpecular = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.measure_env_specular", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), FornaxKeybind.CATEGORY));
        // Unbound by default, same rationale as the four dumps above -- paged-block-atlas phase-3
        // de-risk tool (2026-08-19): a one-shot GPU write+sample smoke test with no rendering output
        // and no live GPU-state dependency of its own, so unlike the dumps above it needs no arming
        // step and reports its verdict synchronously (see ArrayTextureLayerProbe's own doc).
        KeyMapping arrayLayerProbe = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.array_layer_probe", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), FornaxKeybind.CATEGORY));
        // Unbound by default, same rationale as Array Layer Probe above.
        KeyMapping arrayCopyLayerProbe = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.array_copy_layer_probe", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), FornaxKeybind.CATEGORY));
        // Unbound by default -- paged-atlas sample-provenance tint (2026-08-21): live toggle, no
        // reload; recompiles terrain with the tint define so every overflow-layer sample shows red
        // and every ghost sample yellow (see BlockAtlasOverflow.toggleDebugTint).
        KeyMapping atlasDebugTint = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.atlas_debug_tint", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), FornaxKeybind.CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            LightVolumeDebug.tick();
            while (reloadPack.consumeClick()) {
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] reload_pack pressed");
                reloadPack(isShiftDown());
            }
            while (debugViewCycle.consumeClick()) {
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] debug_view_cycle pressed");
                cycleDebugView(isShiftDown() ? -1 : 1);
            }
            while (readbackDump.consumeClick()) {
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] readback_dump pressed");
                GBufferReadbackDiagnostic.requestDump();
                actionbar("[Fornax] G-buffer readback dump requested (see log)");
            }
            while (paletteHistogramDump.consumeClick()) {
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] palette_histogram_dump pressed");
                PaletteSizeHistogram.dumpToLog();
                actionbar("[Fornax] Voxel palette-size histogram dumped (see log)");
            }
            while (analyticLightListDump.consumeClick()) {
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] analytic_light_list_dump pressed");
                int count = AnalyticLightListDebug.readLightCount();
                String message = switch (count) {
                    case AnalyticLightListDebug.NOT_ALLOCATED ->
                            "[Fornax] Analytic light list: not allocated (ANALYTIC_LIGHTS off or no active voxel window)";
                    case AnalyticLightListDebug.READBACK_FAILED -> "[Fornax] Analytic light list: readback FAILED (see log)";
                    default -> "[Fornax] Analytic light list: " + count + " lights";
                };
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] {}", message);
                actionbar(message);
            }
            while (lightVolumeDump.consumeClick()) {
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] light_volume_dump pressed");
                LightVolumeDebug.requestCapture();
                actionbar("[Fornax] Light-volume capture armed: hold still ~half a second (see log)");
            }
            while (measureEnvSpecular.consumeClick()) {
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] measure_env_specular pressed");
                EnvSpecularRatioReadback.requestMeasure();
                actionbar("[Fornax] Env-specular crosshair measurement requested (see log)");
            }
            while (arrayLayerProbe.consumeClick()) {
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] array_layer_probe pressed");
                String verdict = ArrayTextureLayerProbe.run();
                actionbar("[Fornax] Array layer probe: " + verdict + " (see log for details)");
            }
            while (arrayCopyLayerProbe.consumeClick()) {
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] array_copy_layer_probe pressed");
                String verdict = ArrayTextureCopyLayerProbe.run();
                actionbar("[Fornax] Array copy-layer probe: " + verdict + " (see log for details)");
            }
            while (atlasDebugTint.consumeClick()) {
                dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][key] atlas_debug_tint pressed");
                dev.icehunter.fornax.atlas.BlockAtlasOverflow.toggleDebugTint();
                actionbar("[Fornax] Atlas provenance tint toggled (red = overflow layer, yellow = ghost)");
            }
        });
    }

    /**
     * F8/Shift+F8: full pack republish, mirroring the settings-apply path exactly (see this
     * class's own doc comment). {@code alsoRendererReload} additionally triggers Sodium's chunk
     * remesh/renderer recreation for vertex-format or {@code blocks.toml} edits that a pack
     * republish alone can't pick up (those only affect terrain's own upload, not the post chain
     * {@link GraphRunner#rebuild} recompiles). Generation is read AFTER the reload: {@code
     * GraphRunner.rebuild} bumps its counter synchronously (before the async resource-reload
     * future it returns lands), so the freshly-stamped generation is already current by the time
     * {@link PackReload#reapplyActivePack()} returns.
     */
    private static void reloadPack(boolean alsoRendererReload) {
        PackReload.reapplyActivePack();
        if (alsoRendererReload) {
            RendererReload.request();
        }
        long generation = GraphRunner.shaderCacheGeneration();
        actionbar("[Fornax] Pack reloaded (generation " + generation + ")"
                + (alsoRendererReload ? " + renderer reload" : ""));
    }

    /** F9 ({@code direction} 1) / Shift+F9 ({@code direction} -1): steps {@link
     * FornaxConfig#get()}{@code .debugView} through {@link GBufferDebugView#values()}, wrapping
     * both directions via {@link Math#floorMod}, persists it the same way the Sodium dropdown
     * binding does, and shows the new view's name on the actionbar for vanilla's normal overlay
     * duration (~2s) -- no custom HUD needed. */
    private static void cycleDebugView(int direction) {
        GBufferDebugView[] views = GBufferDebugView.values();
        int current = FornaxConfig.get().debugView.ordinal();
        GBufferDebugView next;
        // Step until a SELECTABLE view is reached rather than taking a single step, so a retired view is
        // skipped instead of landed on -- merely cycling PAST VOXEL_RAYMARCH was enough to wedge the GPU
        // back when its presentation path still ran. Shares GBufferDebugView#isSelectable with the settings
        // dropdown so the two routes can never disagree. Bounded by views.length so an enum with nothing
        // selectable can never spin forever.
        int steps = 0;
        do {
            current += direction;
            next = views[Math.floorMod(current, views.length)];
        } while (!next.isSelectable() && ++steps < views.length);
        FornaxConfig.get().debugView = next;
        FornaxConfig.save();
        actionbar("[Fornax] Debug view: " + next.label());
    }

    /** Live physical Shift state (either hand), read off the current GLFW window -- not tied to
     * whichever KeyMapping was clicked, so the same check works no matter which physical key a
     * player rebinds reload-pack/debug-view-cycle to. */
    private static boolean isShiftDown() {
        Window window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
    }

    private static void actionbar(String message) {
        Minecraft.getInstance().gui.hud.setOverlayMessage(Component.literal(message), false);
    }
}
