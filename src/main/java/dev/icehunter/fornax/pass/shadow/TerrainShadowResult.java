package dev.icehunter.fornax.pass.shadow;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.joml.Vector4f;

/** Current accepted-terrain RT depth, separate from the complete raster map and voxel RT outputs.
 * R is forward light depth (miss=1); A certifies a traced texel this frame. Zero A always falls back
 * to raster. Descriptors exist on unsupported devices too; declaring a sampler does not dispatch RT. */
public final class TerrainShadowResult {
    public static final String TARGET = "rtTerrainShadowDepth";
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

    public static void close() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        texture = null;
        view = null;
        requested = valid = false;
    }
}
