package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VoxelOpaqueFaceTest {
    // Header high byte: independent coverage bit 2, alongside mapping bits 0 and 1.
    private static final int OPAQUE_FACE = 1 << 26;

    @Test void aSolidBackingSurvivesStackedCutoutAndUnmappedTintLayers() {
        var base = quad(null, ChunkSectionLayer.SOLID, 2, 0, 1);
        var overlay = quad(null, ChunkSectionLayer.CUTOUT, 0, 0, 1);
        for (var layers : List.of(List.of(base, overlay), List.of(overlay, base))) {
            int[] words = VoxelFaceTexture.pack(List.of(part(layers)), -1);
            assertEquals(OPAQUE_FACE, words[14]);
            for (int i = 15; i < 21; i++) assertEquals(0, words[i], "stacked UV mapping stays absent");
        }
    }

    @Test void anOpaqueCutoutBackingIsIndependentOfItsTransparentOverlay() {
        try (var opaque = contents(-1); var holes = contents(0)) {
            var base = quad(new Sprite(opaque), ChunkSectionLayer.CUTOUT, -1, 0, 1);
            var overlay = quad(new Sprite(holes), ChunkSectionLayer.CUTOUT, 0, 0, 1);
            assertEquals(OPAQUE_FACE, header(List.of(base, overlay)));
            assertEquals(0, header(List.of(overlay, overlay)));
        }
    }

    @Test void oneNonopaqueTexelOrMipNeverCertifiesCutoutCoverage() {
        for (int alpha : new int[]{0, 127, 254}) {
            try (var pixels = contents(-1)) {
                pixels.image.setPixel(1, 1, alpha << 24);
                assertEquals(0, header(List.of(quad(new Sprite(pixels), ChunkSectionLayer.CUTOUT, -1, 0, 1))) & OPAQUE_FACE);
            }
        }
        try (var pixels = contents(-1); var mip = new NativeImage(1, 1, false)) {
            mip.setPixel(0, 0, 0);
            pixels.mips = new NativeImage[]{pixels.image, mip};
            assertEquals(0, header(List.of(quad(new Sprite(pixels), ChunkSectionLayer.CUTOUT, -1, 0, 1))) & OPAQUE_FACE);
        }
    }

    @Test void translucencyPartialCoverageWrongPlaneAndCrossedCornersStayUncertified() {
        var full = quad(null, ChunkSectionLayer.SOLID, -1, 0, 1);
        var crossed = new BakedQuad(full.position0(), full.position2(), full.position1(), full.position3(),
                full.packedUV0(), full.packedUV2(), full.packedUV1(), full.packedUV3(),
                Direction.NORTH, full.materialInfo());
        for (var q : List.of(quad(null, ChunkSectionLayer.TRANSLUCENT, -1, 0, 1),
                quad(null, ChunkSectionLayer.SOLID, -1, 0, .5f),
                quad(null, ChunkSectionLayer.SOLID, -1, .5f, 1), crossed,
                quad(null, ChunkSectionLayer.CUTOUT, -1, 0, 1))) {
            assertEquals(0, header(List.of(q)) & OPAQUE_FACE);
        }
        assertEquals(OPAQUE_FACE, header(List.of(full)) & OPAQUE_FACE);
    }

    @Test void fullyOpaqueCutoutRetainsItsExactUvFlagsAndTint() {
        try (var pixels = contents(-1)) {
            var q = quad(new Sprite(pixels), ChunkSectionLayer.CUTOUT, 0, 0, 1);
            int[] mapping = VoxelFaceTexture.mapping(q, Direction.NORTH, 0xff123456);
            int[] packed = VoxelFaceTexture.pack(List.of(part(List.of(q))), 0xff123456);
            assertEquals(mapping[0] | OPAQUE_FACE, packed[14]);
            for (int i = 1; i < 7; i++) assertEquals(mapping[i], packed[14 + i]);
        }
    }

    @Test void everyCardinalFaceRequiresItsOwnRenderedBoundary() {
        for (Direction face : Direction.values()) {
            Vector3f[] p = new Vector3f[4];
            int[] perimeter = {0, 1, 3, 2};
            for (int i = 0; i < 4; i++) {
                float s = perimeter[i] & 1, t = perimeter[i] >> 1;
                float n = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
                p[i] = switch (face.getAxis()) {
                    case X -> new Vector3f(n,s,t); case Y -> new Vector3f(s,n,t); case Z -> new Vector3f(s,t,n);
                };
            }
            var q = new BakedQuad(p[0], p[1], p[2], p[3], UVPair.pack(0,0), UVPair.pack(1,0),
                    UVPair.pack(1,1), UVPair.pack(0,1), face,
                    new BakedQuad.MaterialInfo(null, ChunkSectionLayer.SOLID, null, -1, true, 0));
            var unculled = new BlockStateModelPart() {
                public List<BakedQuad> getQuads(Direction direction) { return direction == null ? List.of(q) : List.of(); }
                public boolean useAmbientOcclusion() { return true; }
                public Material.Baked particleMaterial() { return null; }
                public int materialFlags() { return 0; }
            };
            int[] packed = VoxelFaceTexture.pack(List.of(unculled), -1);
            for (Direction direction : Direction.values()) {
                assertEquals(direction == face ? OPAQUE_FACE : 0,
                        packed[direction.get3DDataValue() * 7] & OPAQUE_FACE);
            }
        }
    }

    @Test void atlasRetirementRevokesCachedAlphaEvidenceBeforeNewPixelsAreRead() {
        try (var pixels = contents(-1)) {
            var q = quad(new Sprite(pixels), ChunkSectionLayer.CUTOUT, -1, 0, 1);
            assertEquals(OPAQUE_FACE, header(List.of(q)) & OPAQUE_FACE);
            VoxelHarvestLifecycle.onBlockAtlasRetired();
            pixels.image.setPixel(0, 0, 0);
            VoxelHarvestLifecycle.onModelsPublished();
            assertEquals(0, header(List.of(q)) & OPAQUE_FACE);
        } finally {
            VoxelHarvestLifecycle.onModelsPublished();
        }
    }

    private static int header(List<BakedQuad> quads) { return VoxelFaceTexture.pack(List.of(part(quads)), -1)[14]; }
    private static Pixels contents(int argb) {
        NativeImage image = new NativeImage(2, 2, false);
        image.fillRect(0, 0, 2, 2, argb);
        return new Pixels(image);
    }
    private static final class Pixels extends SpriteContents implements SpriteContentsAccessor {
        final NativeImage image;
        NativeImage[] mips;
        Pixels(NativeImage image) {
            super(Identifier.fromNamespaceAndPath("test", "coverage"), new FrameSize(2, 2), image);
            this.image = image;
            this.mips = new NativeImage[]{image};
        }
        public NativeImage fornax$originalImage() { return this.image; }
        public NativeImage[] fornax$byMipLevel() { return this.mips; }
    }
    private static final class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 2, 2, 0, 0, 0); }
    }
    private static BakedQuad quad(TextureAtlasSprite sprite, ChunkSectionLayer layer, int tint, float z, float width) {
        return new BakedQuad(new Vector3f(0,0,z), new Vector3f(width,0,z),
                new Vector3f(width,1,z), new Vector3f(0,1,z),
                UVPair.pack(0,0), UVPair.pack(1,0), UVPair.pack(1,1), UVPair.pack(0,1), Direction.NORTH,
                new BakedQuad.MaterialInfo(sprite, layer, null, tint, true, 0));
    }
    private static BlockStateModelPart part(List<BakedQuad> quads) {
        return new BlockStateModelPart() {
            public List<BakedQuad> getQuads(Direction face) { return face == Direction.NORTH ? quads : List.of(); }
            public boolean useAmbientOcclusion() { return true; }
            public Material.Baked particleMaterial() { return null; }
            public int materialFlags() { return 0; }
        };
    }
}
