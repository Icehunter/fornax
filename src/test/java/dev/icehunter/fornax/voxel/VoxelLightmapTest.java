package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelLightmapTest {
    @Test void nibblePairsRoundTripWithoutSharingLightBetweenPaletteCells() {
        for (int sky=0;sky<16;sky++) for (int block=0;block<16;block++) {
            int value=VoxelLightmap.pack(block,sky)&255;
            assertEquals(block,value&15);
            assertEquals(sky,value>>>4);
        }
    }
    @Test void sectionBytesUsePayloadOrderAndFourCellsPerGpuWord() {
        byte[] result=VoxelLightmap.sample((x,y,z)->VoxelLightmap.pack(x&15,(y+z)&15),-32,48,64);
        assertEquals(4096,result.length); // 16^3 cells, one pair of 4-bit levels each.
        assertEquals(1024,VoxelLightmap.WORDS_PER_SLOT);
        for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++)
            assertEquals((x|(((y+z)&15)<<4)),result[(y<<8)|(z<<4)|x]&255);
        assertEquals(0x03020100,ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }
    @Test void noWorldSampleIsExplicitlyDarkAndExistingResultConstructorStaysCompatible() {
        assertArrayEquals(new byte[4096],VoxelLightmap.capture(null,0,0,0));
        var result=new SectionHarvester.Result(new byte[4096],new SectionPalette(java.util.List.of()));
        assertArrayEquals(new byte[4096],result.lightmap());
        assertThrows(IllegalArgumentException.class,()->new SectionHarvester.Result(new byte[4096],
                new SectionPalette(java.util.List.of()),new byte[8]));
    }
    @Test void structurallyEmptySectionKeepsTheWorldSkyValue() throws Exception {
        byte[] sky=VoxelLightmap.sample((x,y,z)->VoxelLightmap.pack(0,15),0,320,0);
        var empty=DirectSectionReader.emptyResultWithLight(sky);
        assertEquals(VoxelShapeKind.EMPTY,empty.palette().entries().getFirst().shapeKind());
        for(byte light:empty.lightmap()) assertEquals(240,light&255);
        String source=Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/DirectSectionReader.java"));
        assertTrue(source.contains("emptyResultWithLight(VoxelLightmap.capture(level,"));
    }
    /** A headless test cannot run a meshing task, so pin the hook point instead: an old slice must
     * never be read before BlockRenderCache sets up the current section. */
    @Test void meshHarvestReadsTheCurrentInitializedSnapshot() throws Exception {
        String source=Files.readString(Path.of("src/main/java/dev/icehunter/fornax/mixin/sodium/ChunkBuilderMeshingTaskMixin.java"));
        assertTrue(source.contains("BlockRenderCache;init("));
        assertTrue(source.contains("shift = At.Shift.AFTER"));
    }
    @Test void optionalBufferUploadsBesideGeometryInSingleAndBatchPaths() throws Exception {
        String upload=Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/BrickGridUpload.java"));
        assertTrue(upload.contains("isEnabledBufferTarget(VoxelLightmap.TARGET)"));
        assertTrue(upload.contains("vkCmdUpdateBuffer(cmd, lightmapBuffer, lightmapOffset, lightmapBytes)"));
        assertTrue(upload.contains("vkCmdUpdateBuffer(cmd, lightmapBuffer, lightmapOffset, lightmapScratch)"));
        String validator=Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphValidator.java"));
        assertTrue(validator.contains("VoxelLightmap.TARGET"));
    }
}
