package dev.icehunter.fornax.mixin.vanilla;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.SkyFrameState;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla's sky pass when the active pack owns the sky ({@link GraphRunner#packOwnsSky()})
 * and commits {@link SkyFrameState} for the frame either way. Injected at HEAD of
 * {@code addSkyPass} -- pass REGISTRATION time, which runs before the frame graph executes and
 * therefore before any terrain draw writes u_Globals, so the committed values are current for
 * every consumer this frame.
 *
 * <p>Vanilla's own guards are replicated BEFORE deciding to cancel, and cancellation only
 * happens for the overworld skybox: powder-snow/lava fog and sky-blocking mob effects keep
 * vanilla's behavior (no sky at all -- we neither cancel nor paint, flag 0); the End keeps its
 * vanilla sky untouched; the Nether never registers a sky pass. The flag committed here is
 * literally "this mixin cancelled the pass": the resolve shader paints exactly on that flag, so
 * the cancel/paint pair cannot drift apart no matter how these guards evolve.
 *
 * <p>Water Round C Task 4 originally also committed {@link SkyFrameState}'s camera-eye-in-water
 * flag ({@code u_WaterState.x}) from this same call site, unconditionally. The fix-wave finding-1
 * review caught the resulting gap: the Nether never calls {@code addSkyPass} at all (see the
 * paragraph above), so a camera that went underwater in the Overworld and then portalled into the
 * Nether kept the flag frozen until the next dimension that registers a sky pass. That commit
 * moved to {@code GlobalUniformsWriteMixin}, which computes the flag live every frame instead
 * (see its water-tail comment) -- this mixin's {@code addSkyPass} hook has nothing to do with
 * camera fluid state anymore.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSkyPassMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
    private void fornax$maybeOwnSky(FrameGraphBuilder frameGraphBuilder,
            CameraRenderState cameraRenderState, GpuBufferSlice fogBuffer, CallbackInfo ci) {
        SkyRenderState sky = this.levelRenderState.skyRenderState;
        boolean vanillaWouldSkip = cameraRenderState.fogType == FogType.POWDER_SNOW
                || cameraRenderState.fogType == FogType.LAVA
                || cameraRenderState.entityRenderState.doesMobEffectBlockSky;
        boolean cancel = !vanillaWouldSkip
                && sky.skybox == DimensionType.Skybox.OVERWORLD
                && GraphRunner.packOwnsSky();
        // The flag is committed unconditionally; only the cancellation itself is conditional. The
        // sky's DATA no longer travels through here at all -- it used to ride the cancel branch of
        // this very if/else, which meant a pack that did not paint its own sky read zero for sky
        // colour, rain, sun angle and moon phase. It now comes from SkyProbe, read live every frame
        // in every dimension; see that class for why, and for the two engine-side consumers that
        // were quietly reading zeroes alongside the shader.
        SkyFrameState.commitSky(cancel);
        if (cancel) {
            ci.cancel();
        }
    }
}
