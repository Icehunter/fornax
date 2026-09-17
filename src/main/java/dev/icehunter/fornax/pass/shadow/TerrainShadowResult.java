package dev.icehunter.fornax.pass.shadow;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.joml.Vector4f;

/** Current accepted-terrain RT depth, separate from the complete raster map and voxel RT outputs.
 * R is forward light depth (miss=1); G is the {@code RayTier} ordinal of the traversal that wrote the
 * texel; B is reserved; A certifies a traced texel this frame. A provider writes value, tier and
 * validity in one store, so G is never nonzero with A zero. Zero A always falls back to raster, and
 * nothing else in the texel means anything then: the alloc-time clear leaves it all zeros.
 * Descriptors exist on unsupported devices too; declaring a sampler does not dispatch RT. */
public final class TerrainShadowResult {
    public static final String TARGET = "rtTerrainShadowDepth";

    /**
     * Whether a pass input names this engine-owned target. Kept as a predicate rather than a bare
     * equals, so every classifier that has to know it names one thing: the validator, the
     * raw-compute and particle runners, and the geometry-finality check. A rename cannot leave
     * one of them turning away a good pack.
     */
    public static boolean isRef(String ref) {
        return TARGET.equals(ref);
    }
    private static GpuTexture texture;
    private static GpuTextureView view;
    private static boolean requested, valid;

    private TerrainShadowResult() {}

    public static void setRequested(boolean value) { requested = value; }
    public static boolean requested() { return requested; }
    public static GpuTexture texture() { return texture; }
    public static GpuTextureView view() { return view; }

    public static void ensureSize(int resolution) {
        if (!requested || (texture != null && texture.getWidth(0) == resolution)) return;
        var device = RenderSystem.tryGetDevice();
        if (device == null) return;
        GpuTexture next = null;
        GpuTextureView nextView = null;
        try {
            next = device.createTexture("Fornax terrain RT shadow depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA32_FLOAT, resolution, resolution, 1, 1);
            nextView = device.createTextureView(next);
            device.createCommandEncoder().clearColorTexture(next, new Vector4f());
        } catch (RuntimeException error) {
            if (nextView != null) nextView.close();
            if (next != null) next.close();
            throw error;
        }
        if (texture != null) VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        if (view != null) view.close();
        if (texture != null) texture.close();
        texture = next;
        view = nextView;
        valid = false;
    }

    /** A transition clear is sufficient: a successful trace overwrites every texel. */
    public static void invalidate() {
        if (valid && texture != null) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, new Vector4f());
            valid = false;
        }
    }

    public static void published() { valid = true; }

    /**
     * Clears this frame's trusted radius, which each answering tier then widens to its own reach.
     * Separate from {@link #invalidate()}: the image's contents and the radius a pack compares
     * against have different lifetimes, and clearing the radius costs no GPU work.
     */
    public static void invalidateTrustedRadius() {
        ShadowFrameState.setRtDistance(0);
    }

    public static void close() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        texture = null;
        view = null;
        requested = valid = false;
    }
}
