package dev.icehunter.fornax.mixin.sodium;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import dev.icehunter.fornax.pipeline.PreviousFrameCameraTransform;
import dev.icehunter.fornax.util.SunDirection;
import net.caffeinemc.mods.sodium.client.gpu.device.context.GLDrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.mixin.core.GlRenderPassAccessor;
import net.caffeinemc.mods.sodium.mixin.core.RenderPassAccessor;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL46C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Appends the {@code u_SunDirection}/{@code u_PrevRegionOffset} uniforms {@code
 * GLDrawContext.setContext}/{@code updateData} write, alongside the official {@code
 * u_RegionOffset}/{@code u_CurrentTime}/{@code u_RegionID}.
 *
 * <p>Unlike the Vulkan side ({@code DrawContextVKMixin}), OpenGL uniforms are addressed by name via
 * {@code glGetUniformLocation}/{@code glUniform3f} -- there is no shared byte buffer, no push-constant
 * range, and no compile-time-inlined size constant standing in the way here. {@code setContext}
 * resolves exactly three uniform locations (region/time/id) and {@code updateData} sets exactly
 * those three, in that order, with nothing after the last one -- a pure tail-append of two more
 * location lookups and two more {@code glUniform3f} calls is a complete, lossless seam, so no
 * {@code @Overwrite} was needed.
 * Verified against Sodium mc26.2-0.9.0 (bf93ed83); no Sodium source is reproduced here.
 */
@Mixin(GLDrawContext.class)
public class DrawContextGLMixin {
    @Unique
    private int fornax$sunDirectionUniform;

    @Unique
    private int fornax$prevRegionUniform;

    @Inject(method = "setContext", at = @At("TAIL"))
    private void fornax$resolveExtraUniforms(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
        GlRenderPassAccessor passBackend = (GlRenderPassAccessor) ((RenderPassAccessor) pass).getBackend();
        int programId = passBackend.getPipeline().program().getProgramId();

        this.fornax$sunDirectionUniform = GL46C.glGetUniformLocation(programId, "u_SunDirection");
        this.fornax$prevRegionUniform = GL46C.glGetUniformLocation(programId, "u_PrevRegionOffset");
    }

    @Inject(method = "updateData", at = @At("TAIL"))
    private void fornax$writeExtraUniforms(RenderRegion region, CameraTransform camera, CallbackInfo ci) {
        Vector3f sunDirection = SunDirection.computeSunDirection();
        GL46C.glUniform3f(this.fornax$sunDirectionUniform, sunDirection.x(), sunDirection.y(), sunDirection.z());

        CameraTransform previousCamera = PreviousFrameCameraTransform.getCameraTransform();
        float prevX = DrawContextInvoker.fornax$getCameraTranslation(region.getOriginX(), previousCamera.intX, previousCamera.fracX);
        float prevY = DrawContextInvoker.fornax$getCameraTranslation(region.getOriginY(), previousCamera.intY, previousCamera.fracY);
        float prevZ = DrawContextInvoker.fornax$getCameraTranslation(region.getOriginZ(), previousCamera.intZ, previousCamera.fracZ);
        GL46C.glUniform3f(this.fornax$prevRegionUniform, prevX, prevY, prevZ);
    }

}
