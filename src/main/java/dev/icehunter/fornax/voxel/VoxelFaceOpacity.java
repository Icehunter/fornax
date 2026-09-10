package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

/** Conservative rendered coverage, independent of a face's texture mapping or emission.
 * Callers hold a harvest lease while inspecting the atlas-owned native pixels. */
final class VoxelFaceOpacity {
    private static final ConcurrentHashMap<SpriteContents, Boolean> OPAQUE = new ConcurrentHashMap<>();
    private VoxelFaceOpacity() { }

    static void clear() { OPAQUE.clear(); }

    static boolean covers(BakedQuad quad, Direction face) {
        var layer = quad.materialInfo().layer();
        if (layer != ChunkSectionLayer.SOLID && layer != ChunkSectionLayer.CUTOUT) return false;
        int axis = switch (face.getAxis()) { case X -> 0; case Y -> 1; case Z -> 2; };
        float plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        int corners = 0;
        int[] order = new int[4];
        for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
            var p = quad.position(i);
            float n = axis == 0 ? p.x() : axis == 1 ? p.y() : p.z();
            float s = axis == 0 ? p.y() : p.x(), t = axis == 2 ? p.y() : p.z();
            if (n != plane || (s != 0 && s != 1) || (t != 0 && t != 1)) return false;
            order[i] = (int) s | ((int) t << 1);
            corners |= 1 << order[i];
        }
        // Four square corners in perimeter order; a bow-tie has the same bounds but leaves gaps.
        if (corners != 15 || (order[0] ^ order[2]) != 3 || (order[1] ^ order[3]) != 3) return false;
        return opaque(quad);
    }

    static boolean opaque(BakedQuad quad) {
        var layer = quad.materialInfo().layer();
        if (layer == ChunkSectionLayer.SOLID) return true;
        if (layer != ChunkSectionLayer.CUTOUT) return false;
        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        if (sprite == null || !(sprite.getU0() < sprite.getU1()) || !(sprite.getV0() < sprite.getV1())) return false;
        for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
            float u = UVPair.unpackU(quad.packedUV(i)), v = UVPair.unpackV(quad.packedUV(i));
            if (!(u >= sprite.getU0() && u <= sprite.getU1()
                    && v >= sprite.getV0() && v <= sprite.getV1())) return false;
        }
        return OPAQUE.computeIfAbsent(sprite.contents(), VoxelFaceOpacity::opaquePixels);
    }

    static boolean opaque(QuadView quad, Function<QuadView, @Nullable TextureAtlasSprite> sprites) {
        var layer = quad.chunkLayer();
        if (layer == ChunkSectionLayer.SOLID) return true;
        if (layer != ChunkSectionLayer.CUTOUT) return false;
        TextureAtlasSprite sprite = sprites.apply(quad);
        if (sprite == null || !(sprite.getU0() < sprite.getU1()) || !(sprite.getV0() < sprite.getV1())) return false;
        for (int v = 0; v < 4; v++) {
            // Final vertex alpha can open a cutout even when its atlas pixels are fully opaque.
            if ((quad.color(v) >>> 24) != 255
                    || !(quad.u(v) >= sprite.getU0() && quad.u(v) <= sprite.getU1()
                    && quad.v(v) >= sprite.getV0() && quad.v(v) <= sprite.getV1())) return false;
        }
        return OPAQUE.computeIfAbsent(sprite.contents(), VoxelFaceOpacity::opaquePixels);
    }

    private static boolean opaquePixels(SpriteContents contents) {
        if (!((Object) contents instanceof SpriteContentsAccessor pixels)) return false;
        NativeImage original = pixels.fornax$originalImage();
        if (!opaqueImage(original)) return false;
        NativeImage[] mips = pixels.fornax$byMipLevel();
        if (mips == null || mips.length == 0) return false;
        // Every animation frame and uploaded mip must be fully opaque; no sampling/filter guess.
        for (NativeImage mip : mips) if (mip != original && !opaqueImage(mip)) return false;
        return true;
    }

    private static boolean opaqueImage(NativeImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return false;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getPixel(x, y) >>> 24) != 255) return false; // Exact full alpha, not an alpha-test threshold.
        }
        return true;
    }
}
