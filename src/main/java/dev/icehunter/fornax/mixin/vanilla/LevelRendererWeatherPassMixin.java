package dev.icehunter.fornax.mixin.vanilla;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla's precipitation pass for a pack that declares {@code WEATHER_PROCEDURAL} and draws
 * rain and snow itself. Directly parallel to {@link LevelRendererCloudsPassMixin}, which does the
 * same for clouds and is the working precedent this follows.
 *
 * <p><b>Why a mixin here at all, rather than a geometry slot.</b> Weather is the one piece of world
 * geometry Fornax's deferral cannot touch: {@code WeatherEffectRenderer.render} builds its own
 * {@code BufferBuilder}, uploads its own {@code GpuBuffer}, and calls {@code
 * CommandEncoder.createRenderPass} / {@code RenderPass.setPipeline} / {@code drawIndexed} directly,
 * so it never reaches {@code PreparedRenderType.drawFromBuffer} -- the sole place {@link
 * dev.icehunter.fornax.pipeline.GeometryPipelineMap} is consulted. Claiming {@code GeometrySlot
 * .WEATHER} therefore substitutes no program and defers no draw. A pack shipped exactly that, it was
 * inert for its entire life, and three engine-side "fixes" were spent on the same dead axis before
 * anyone disassembled the renderer. Cancelling the pass outright and letting the pack draw its own
 * precipitation is the only mechanism that works.
 *
 * <p><b>Registration is unconditional</b> -- {@code LevelRenderer.render} calls {@code
 * addWeatherPass} on every frame with no guard of its own (bytecode-verified at offset 510 against
 * the 26.2 deobf jar), and the pass then {@code readsAndWrites} the MAIN target handle, since
 * {@code LevelTargetBundle.weather} is null outside Fabulous. So this injection fires every frame and
 * the gate below is the only thing deciding. {@code WeatherEffectRenderer.render}'s own early-out on
 * an empty column list runs later still, inside the pass.
 *
 * <p><b>Absent counts as off.</b> A pack that says nothing keeps vanilla's rain, which is what makes
 * this safe to ship before any pack can replace it: with no declaration there is no behaviour change
 * at all. Enabling it without a replacement removes precipitation entirely and puts nothing back.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererWeatherPassMixin {

    @Inject(method = "addWeatherPass", at = @At("HEAD"), cancellable = true)
    private void fornax$maybeOwnWeather(FrameGraphBuilder frameGraphBuilder, GpuBufferSlice fogBuffer,
            CallbackInfo ci) {
        if (GraphRunner.isActive() && GraphRunner.isCompileOptionEnabled("WEATHER_PROCEDURAL")) {
            ci.cancel();
        }
    }
}
