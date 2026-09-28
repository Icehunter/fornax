package dev.icehunter.fornax.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VoxelOpticalFactsTest {
    @Test void translucentMappingKeepsLegacyOpaqueUvValidityClear() {
        var quad = new BakedQuad(new Vector3f(0,0,0), new Vector3f(1,0,0),
                new Vector3f(1,1,0), new Vector3f(0,1,0),
                UVPair.pack(.25f,.5f), UVPair.pack(.5f,.5f),
                UVPair.pack(.5f,.75f), UVPair.pack(.25f,.75f), Direction.NORTH,
                new BakedQuad.MaterialInfo(null, ChunkSectionLayer.TRANSLUCENT, null, 0, true, 0));
        int[] words = VoxelFaceTexture.mapping(quad, Direction.NORTH, 0xff123456);
        // Bits 29 and 31 carry optical mapping and layer facts; bit 24 stays clear.
        assertEquals(0xa0123456, words[0]);
        assertEquals(.25f, Float.intBitsToFloat(words[1]));
        assertEquals(.25f, Float.intBitsToFloat(words[3]));
        assertEquals(.25f, Float.intBitsToFloat(words[6]));
    }

    @Test void paletteMirrorsRawVanillaTransmissionWithoutChangingEmissionColour() {
        var entry = new SectionPalette.Entry(VoxelShapeKind.FULL, List.of(), new int[6],
                1, true, 0x123456);
        var bytes = ByteBuffer.wrap(BrickGridUpload.packPaletteEntries(List.of(entry)))
                .order(ByteOrder.LITTLE_ENDIAN);
        // Word zero bit 12 is independent of extinction, box count and cutout flags.
        assertEquals(0x1000, bytes.getInt(0));
        assertEquals(0x123456ff, bytes.getInt(15 * Integer.BYTES));
    }
}
