package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Holds the LabPBR material (specular/reflectance) map atlas: a second GPU texture laid out at the
 * <em>exact</em> same UV coordinates as vanilla's block atlas, mirroring {@link NormalMapAtlas}'s own
 * structure exactly. Encodes LabPBR's {@code _s} channel data: R=smoothness, G=F0/reflectance,
 * B=porosity/subsurface-scattering, A=emissive strength.
 *
 * <p>The atlas is (re)built on every resource reload via
 * {@link dev.icehunter.fornax.mixin.vanilla.TextureAtlasMaterialHookMixin}, which builds both
 * LabPBR lanes at one hook point and publishes them as one generation.
 *
 * <p>For each sprite the builder looks up its {@code _s} sidecar texture (via
 * {@link LabPbrSidecarLocator}); if present it is blitted into the matching UV rectangle, and if
 * absent that rectangle is filled with {@code (0, 0, 0, 255)} — zero smoothness, zero F0, zero
 * porosity/SSS, and LabPBR's unprovided-emission sentinel — so "no material map" is a semantic
 * no-op rather than borrowed albedo data.
 */
public final class MaterialMapAtlas implements AutoCloseable {
    private final GpuTexture texture;
    private final GpuTextureView textureView;
    private final LabPbrAnimationSet animations;
    private final ArrayTextures.@Nullable Allocation pages;
    private final @Nullable String fingerprint;

    MaterialMapAtlas(GpuTexture texture, GpuTextureView textureView) {
        this(texture, textureView, LabPbrAnimationSet.EMPTY, null, null);
    }

    MaterialMapAtlas(GpuTexture texture, GpuTextureView textureView,
                     LabPbrAnimationSet animations) {
        this(texture, textureView, animations, null, null);
    }

    MaterialMapAtlas(GpuTexture texture, GpuTextureView textureView,
                     LabPbrAnimationSet animations, ArrayTextures.@Nullable Allocation pages,
                     @Nullable String fingerprint) {
        this.texture = texture;
        this.textureView = textureView;
        this.animations = animations;
        this.pages = pages;
        this.fingerprint = fingerprint;
    }

    /**
     * The {@link LabPbrAtlasFingerprint} this atlas was built from, or {@code null} for an atlas
     * built through a test-only constructor. {@link LabPbrAtlasPair#rebuild} compares this against a
     * freshly-computed fingerprint to decide whether a rebuild can be skipped entirely.
     */
    @Nullable
    public String fingerprint() {
        return this.fingerprint;
    }

    /** The paged-atlas overflow layers' view ({@code u_MaterialPagesTex}), or {@code null} when
     * the current atlas generation is unpaged or the layers could not be built -- callers bind
     * the neutral array fallback then. */
    @Nullable
    public GpuTextureView pagesView() {
        return this.pages == null ? null : this.pages.view();
    }

    /**
     * @return the current material-map atlas, or {@code null} if none has been built yet (e.g.
     * before the first resource reload completes).
     */
    @Nullable
    public static synchronized MaterialMapAtlas getInstance() {
        return getInstance(TextureAtlas.LOCATION_BLOCKS);
    }

    /** Exact mirrored material atlas for the original vanilla atlas resource owner. */
    @Nullable
    public static synchronized MaterialMapAtlas getInstance(Identifier atlasLocation) {
        LabPbrAtlasPair pair = LabPbrAtlasPair.get(
                Objects.requireNonNull(atlasLocation, "atlasLocation"));
        return pair == null ? null : pair.material();
    }

    /**
     * The GPU texture backing this atlas. Matches the block atlas's normalised UV layout exactly, but
     * NOT its pixel dimensions since {@link PbrSidecarAtlasScale} started sizing this atlas from the
     * sidecars' own resolution: the actual width/height are the block atlas's scaled by
     * {@code 2^log2Scale}, which can be larger (packs with higher-resolution maps than colour) or
     * smaller (the device/budget degradation path) than the block atlas itself.
     */
    public GpuTexture getTexture() {
        return this.texture;
    }

    /**
     * The view bound as {@code u_MaterialTex}, mirroring how {@link NormalMapAtlas} exposes
     * {@code u_NormalTex}'s view.
     */
    public GpuTextureView getTextureView() {
        return this.textureView;
    }

    /** Advances sidecars on the same tick boundary as the owning albedo atlas. */
    public void tickAnimations() {
        this.animations.tick(this.texture);
    }

    /**
     * GPU lifetime is owned by {@link LabPbrAtlasPair}; neither lane can be installed separately.
     *
     * <p>Document-safe: unlike OpaqueDepth/GBufferManager/ShadowMapManager/WaterSurfaceManager/
     * MipchainRunner/TargetRegistry's texture-teardown paths (see VulkanComputeBackend
     * .waitForGpuIdleBeforeDestroy's own doc), this atlas texture is never bound as an input to any
     * COMPUTE-queue submission (ComputePassRunner/VoxelDebugRaymarchPass only ever bind pack-declared
     * TargetRegistry/OpaqueDepth/ShadowMapManager/WaterSurfaceManager targets, never this atlas) --
     * only ever sampled by the terrain fragment shader, a graphics-queue draw. Blaze3D's own
     * per-GRAPHICS-submission destruction ring already fully
     * covers that case; the hazard the other classes above guard against is specifically a destroy
     * racing a COMPUTE-queue submission the ring never rotates for. No wait-idle needed here.
     */
    @Override
    public void close() {
        this.animations.close();
        this.textureView.close();
        this.texture.close();
        if (this.pages != null) {
            this.pages.close();
        }
    }
}
