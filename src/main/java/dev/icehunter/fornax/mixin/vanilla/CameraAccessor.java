package dev.icehunter.fornax.mixin.vanilla;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code Camera}'s private {@code depthFar} -- the per-frame far-plane distance {@code
 * Camera.update()} derives each frame as {@code max(effectiveRenderDistance * 16, cloudRange * 16 *
 * 4.0f)} and feeds into its own {@code Projection}'s {@code zFar} (always finite: this engine's
 * reversed-Z projection is NOT an infinite-far projection). {@link
 * dev.icehunter.fornax.metalfx.FrameGenPass} needs this exact engine-computed value, not a
 * recomputation of the formula, to feed {@code MTLFXFrameInterpolator}'s {@code farPlane} so it can
 * linearize our reversed-Z depth. {@code near} ({@code Camera.PROJECTION_Z_NEAR}) and {@code fov}
 * ({@code Camera.getFov()}) are already public and need no accessor.
 */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("depthFar")
    float fornax$depthFar();
}
