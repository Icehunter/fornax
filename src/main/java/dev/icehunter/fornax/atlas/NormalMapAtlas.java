package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Holds the LabPBR normal-map atlas: a second GPU texture laid out at the <em>exact</em> same UV
 * coordinates as vanilla's {@link net.minecraft.client.renderer.texture.TextureAtlas#LOCATION_BLOCKS}
 * block atlas. Because the UV rectangles match sprite-for-sprite, the terrain vertex shader's
 * {@code v_TexCoord} (computed from the block atlas) can sample this texture directly with zero
 * coordinate transform.
 *
 * <p>The atlas is (re)built on every resource reload via
 * {@link dev.icehunter.fornax.mixin.vanilla.TextureAtlasMaterialHookMixin}, which builds both
 * LabPBR lanes when the vanilla atlas finishes uploading and publishes them as one pair.
 *
 * <p>The built texture is not level-0-only: {@link NormalMapAtlasReloadListener} uploads a full mip
 * chain above level 0, box-filtered per sprite, and the sampler this atlas is bound with reads it --
 * a lookup here can be blended across several levels, not just the sprite's own authored texels.
 *
 * <p>For each sprite the builder looks up its {@code _n} sidecar texture (via
 * {@link LabPbrSidecarLocator}); if present it is blitted into the matching UV rectangle, and if
 * absent that rectangle is filled with LabPBR's neutral {@code _n} value {@code (128, 128, 255, 255)}:
 * R=G=128 decode to X=Y=0 (flat, no lean) through LabPBR's {@code (value/255)*2-1} formula, B=255 is
 * fully-unoccluded on LabPBR's inverted ambient-occlusion channel (not a stored Z component), and
 * A=255 is 0% depth on LabPBR's POM-height channel (not RGBA opacity) -- so "no normal map" decodes
 * as a visual no-op under the actual LabPBR channel semantics, not the OpenGL normal-map convention
 * the {@code * 2.0 - 1.0} shorthand might suggest.
 */
public final class NormalMapAtlas implements AutoCloseable {
    private final GpuTexture texture;
    private final GpuTextureView textureView;
    private final LabPbrAnimationSet animations;
    private final ArrayTextures.@Nullable Allocation pages;
    private final @Nullable String fingerprint;

    NormalMapAtlas(GpuTexture texture, GpuTextureView textureView) {
        this(texture, textureView, LabPbrAnimationSet.EMPTY, null, null);
    }

    NormalMapAtlas(GpuTexture texture, GpuTextureView textureView,
                   LabPbrAnimationSet animations) {
        this(texture, textureView, animations, null, null);
    }

    NormalMapAtlas(GpuTexture texture, GpuTextureView textureView,
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

    /** The paged-atlas overflow layers' view ({@code u_NormalPagesTex}), or {@code null} when the
     * current atlas generation is unpaged or the layers could not be built -- callers bind the
     * neutral array fallback then. */
    @Nullable
    public GpuTextureView pagesView() {
        return this.pages == null ? null : this.pages.view();
    }

    /**
     * @return the current normal-map atlas, or {@code null} if none has been built yet (e.g. before
     * the first resource reload completes).
     */
    @Nullable
    public static synchronized NormalMapAtlas getInstance() {
        return getInstance(TextureAtlas.LOCATION_BLOCKS);
    }

    /** Exact mirrored normal atlas for the original vanilla atlas resource owner. */
    @Nullable
    public static synchronized NormalMapAtlas getInstance(Identifier atlasLocation) {
        LabPbrAtlasPair pair = LabPbrAtlasPair.get(
                Objects.requireNonNull(atlasLocation, "atlasLocation"));
        return pair == null ? null : pair.normal();
    }

    /**
     * The GPU texture backing this atlas. Matches the block atlas's pixel dimensions and UV layout.
     */
    public GpuTexture getTexture() {
        return this.texture;
    }

    /**
     * The view bound as {@code u_NormalTex}, mirroring how
     * {@code TerrainRenderPass.getAtlas()} returns the block atlas's {@link GpuTextureView}.
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
