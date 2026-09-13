package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/**
 * Per-session holder for the vanilla block atlas ({@link
 * net.minecraft.client.renderer.texture.TextureAtlas#LOCATION_BLOCKS}) GPU texture/view, so a
 * generic engine pass (a fullscreen/compute pass, not the terrain draw itself) can sample the real
 * block atlas -- e.g. for a per-texel alpha test against a cutout-flagged voxel's real texture.
 * Mirrors {@link dev.icehunter.fornax.pipeline.CelestialSprites}'s exact holder shape (see that
 * class's own doc comment): committed by {@code TextureAtlasBlockHookMixin} at the block atlas's own
 * {@code TextureAtlas#upload} RETURN hook, idempotently overwritten on every resource reload, and
 * never owning the GPU texture/view it stores (vanilla's {@code TextureAtlas} does), so there is
 * nothing to close on replacement -- only the reference is swapped.
 *
 * <p>NOTE: unlike {@link NormalMapAtlas}/{@link MaterialMapAtlas}, which hold a SECOND, DERIVED
 * texture built at the same UV layout as the block atlas (a normal map / material map), this class
 * holds the RAW block atlas itself -- confirmed neither of those two classes (nor any other existing
 * holder) already expose a queryable {@link GpuTextureView} of the raw atlas outside Sodium's own
 * {@code TerrainRenderPass.getAtlas()} (a Sodium-internal accessor, not reusable from a generic
 * engine pass) before adding this class.
 *
 * <p>Garbage-VRAM law: before the first capture (no pack active, or a resource reload has not yet
 * completed), {@link #texture()}/{@link #view()} return {@code null} rather than uninitialized data
 * -- every consumer (see {@code GraphInputResolver}'s {@code builtin.blockAtlas} case) must already
 * null-check the same way it null-checks {@code builtin.celestials}. Render-thread only, like every
 * sibling frame-state/atlas holder in this package.
 */
public final class BlockAtlasView {
    @Nullable
    private static GpuTexture texture;
    @Nullable
    private static GpuTextureView textureView;
    private static int generation;

    private BlockAtlasView() {
    }

    /** Installs {@code texture}/{@code view}, captured by {@code TextureAtlasBlockHookMixin} at the
     * block atlas's upload hook. Overwrites any previous capture in place (see class doc), and bumps
     * {@link #generation()} so a consumer holding a copy made from the previous texture reference
     * (e.g. a Metal-visible export) knows to refresh it. */
    public static void capture(@Nullable GpuTexture texture, @Nullable GpuTextureView view) {
        BlockAtlasView.texture = texture;
        BlockAtlasView.textureView = view;
        generation++;
    }

    /** Clears both references, per the class doc's garbage-VRAM law. Also bumps {@link
     * #generation()}: replacing the reference with {@code null} counts the same as a real
     * capture. */
    public static void clear() {
        texture = null;
        textureView = null;
        generation++;
    }

    /** Increments every time {@link #capture} or {@link #clear} replaces the texture reference,
     * starting at 0 before either has ever run this session. A consumer that copies the atlas
     * elsewhere (rather than sampling it directly) compares this against the value it last copied
     * and only re-copies when it has moved. Atlas reloads are rare, so this saves a copy on most
     * frames. */
    public static int generation() {
        return generation;
    }

    /** The block atlas GPU texture, or {@code null} if never captured this session. */
    @Nullable
    public static GpuTexture texture() {
        return texture;
    }

    /** The block atlas GPU texture view, or {@code null} if never captured this session. */
    @Nullable
    public static GpuTextureView view() {
        return textureView;
    }
}
