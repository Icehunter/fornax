package dev.icehunter.fornax.voxel;

import java.util.function.Function;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Resolves an emitted quad's sprite while the caller owns an atlas read lease. */
final class VoxelAtlasLookup {
    private VoxelAtlasLookup() { }

    static @Nullable TextureAtlasSprite sprite(QuadView quad) {
        if (quad.atlas() == null) return null;
        var atlas = atlas(quad.atlas(), Minecraft.getInstance().getAtlasManager()::getAtlasOrThrow);
        return ((FabricTextureAtlas) atlas).spriteFinder().find(quad);
    }

    /** Lookup seam retains the public QuadAtlas keys without constructing a live client. */
    static <T> T atlas(QuadAtlas atlas, Function<Identifier, T> lookup) {
        // AtlasManager is keyed by atlas IDs. Texture paths identify the GPU texture and fail
        // this lookup even after stitching, silently disabling callers that reject exceptions.
        return lookup.apply(atlas.getId());
    }
}
