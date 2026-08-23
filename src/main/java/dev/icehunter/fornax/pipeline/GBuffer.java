package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/**
 * The G-buffer: the set of off-screen render targets opaque terrain draws into instead of
 * vanilla's shared mainRenderTarget, allowing lighting to be resolved once per pixel in a later
 * deferred pass instead of forward-shaded per-draw.
 */
public final class GBuffer implements AutoCloseable {
    private final int width;
    private final int height;

    @Nullable
    private final GpuTexture normalTexture;
    @Nullable
    private final GpuTextureView normalView;
    @Nullable
    private final GpuTexture albedoTexture;
    @Nullable
    private final GpuTextureView albedoView;
    @Nullable
    private final GpuTexture materialTexture;
    @Nullable
    private final GpuTextureView materialView;
    @Nullable
    private final GpuTexture aoTexture;
    @Nullable
    private final GpuTextureView aoView;
    @Nullable
    private final GpuTexture motionTexture;
    @Nullable
    private final GpuTextureView motionView;
    @Nullable
    private final GpuTexture depthTexture;
    @Nullable
    private final GpuTextureView depthView;

    GBuffer(int width, int height,
            GpuTexture normalTexture, GpuTextureView normalView,
            GpuTexture albedoTexture, GpuTextureView albedoView,
            GpuTexture materialTexture, GpuTextureView materialView,
            GpuTexture aoTexture, GpuTextureView aoView,
            GpuTexture motionTexture, GpuTextureView motionView,
            GpuTexture depthTexture, GpuTextureView depthView) {
        this.width = width;
        this.height = height;
        this.normalTexture = normalTexture;
        this.normalView = normalView;
        this.albedoTexture = albedoTexture;
        this.albedoView = albedoView;
        this.materialTexture = materialTexture;
        this.materialView = materialView;
        this.aoTexture = aoTexture;
        this.aoView = aoView;
        this.motionTexture = motionTexture;
        this.motionView = motionView;
        this.depthTexture = depthTexture;
        this.depthView = depthView;
    }

    /** Test-only constructor: bookkeeping alone, no GPU resources. See GBufferTest. */
    static GBuffer createForTesting(int width, int height) {
        return new GBuffer(width, height, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public GpuTextureView getNormalView() {
        return this.normalView;
    }

    public GpuTextureView getAlbedoView() {
        return this.albedoView;
    }


    public GpuTextureView getMaterialView() {
        return this.materialView;
    }

    public GpuTextureView getAoView() {
        return this.aoView;
    }

    public GpuTextureView getMotionView() {
        return this.motionView;
    }

    public GpuTextureView getDepthView() {
        return this.depthView;
    }

    public GpuTexture getNormalTexture() {
        return this.normalTexture;
    }

    public GpuTexture getAlbedoTexture() {
        return this.albedoTexture;
    }


    public GpuTexture getMaterialTexture() {
        return this.materialTexture;
    }

    public GpuTexture getAoTexture() {
        return this.aoTexture;
    }

    public GpuTexture getMotionTexture() {
        return this.motionTexture;
    }

    public GpuTexture getDepthTexture() {
        return this.depthTexture;
    }

    @Override
    public void close() {
        // Test instances have every field null; guard each close individually.
        if (this.normalView != null) this.normalView.close();
        if (this.normalTexture != null) this.normalTexture.close();
        if (this.albedoView != null) this.albedoView.close();
        if (this.albedoTexture != null) this.albedoTexture.close();
        if (this.materialView != null) this.materialView.close();
        if (this.materialTexture != null) this.materialTexture.close();
        if (this.aoView != null) this.aoView.close();
        if (this.aoTexture != null) this.aoTexture.close();
        if (this.motionView != null) this.motionView.close();
        if (this.motionTexture != null) this.motionTexture.close();
        if (this.depthView != null) this.depthView.close();
        if (this.depthTexture != null) this.depthTexture.close();
    }
}
