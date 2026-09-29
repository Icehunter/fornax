package dev.icehunter.fornax.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelPaletteCapacityTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void allSourcePolicyCoversTheLargestSelectablePalette() {
        assertTrue(VoxelSourcePolicy.ALL.allows(239), "240-entry palette requires source-policy bits above 127");
        assertFalse(VoxelSourcePolicy.ALL.allows(240));
    }

    @Test void sourceMembershipDoesNotAliasAcrossSixtyFourBitBoundaries() {
        for (int selected : new int[]{0, 63, 64, 95, 127, 128, 191, 192, 239}) {
            var builder = new VoxelSourcePolicy.Builder(240);
            for (int i = 0; i < 240; i++) builder.add(i == selected);
            var policy = builder.finish(false);
            for (int i = 0; i < 240; i++) assertEquals(i == selected, policy.allows(i), "entry " + i);
        }
        var builder = new VoxelSourcePolicy.Builder(240);
        for (int i = 0; i < 192; i++) builder.add(i == 128);
        builder.copy(128);
        assertTrue(builder.finish(false).allows(192));
    }

    @Test void captureFactIdsAndFailureSentinelsStayDistinctAtEveryCapacity() {
        for (int capacity : new int[]{96, 128, 192, 240}) {
            var scope = new VoxelBoundaryCapture.Scope(0, SectionPos.of(0, 0, 0), new Object(), 1, 1, 1, capacity);
            for (int i = 0; i < capacity; i++) scope.add(position(i), Block.BLOCK_STATE_REGISTRY.byId(i), null);
            int failed = capacity;
            scope.add(position(failed), Block.BLOCK_STATE_REGISTRY.byId(failed), null, VoxelFaceAppearance.UNAVAILABLE);
            scope.add(position(failed + 1), Block.BLOCK_STATE_REGISTRY.byId(failed + 1), null);
            var snapshot = new VoxelBoundaryCapture.Snapshot(scope);
            assertEquals(capacity, Byte.toUnsignedInt(scope.indices[capacity - 1]));
            assertSame(Block.BLOCK_STATE_REGISTRY.byId(capacity - 1), snapshot.cell(capacity - 1, Block.BLOCK_STATE_REGISTRY.byId(capacity - 1)).state());
            assertEquals(254, Byte.toUnsignedInt(scope.indices[failed]));
            assertEquals(255, Byte.toUnsignedInt(scope.indices[failed + 1]));
            assertSame(VoxelFaceAppearance.UNAVAILABLE, snapshot.cell(failed, Blocks.STONE.defaultBlockState()).appearance());
            assertNull(snapshot.cell(failed + 1, Blocks.STONE.defaultBlockState()).boundary());
        }
        assertThrows(IllegalArgumentException.class, () -> new VoxelBoundaryCapture.Scope(0,
                SectionPos.of(0, 0, 0), new Object(), 1, 1, 1, 254));
    }

    private static BlockPos position(int index) { return new BlockPos(index & 15, index >> 8, (index >> 4) & 15); }

    @Test void producerKeepsAllFortyTwoWordsAndPaletteColorsAtHighByteIndices() {
        var entries = new ArrayList<SectionPalette.Entry>();
        for (int index = 0; index < 240; index++) {
            int[] words = new int[42];
            for (int word = 0; word < words.length; word++) words[word] = (index << 16) | word;
            entries.add(new SectionPalette.Entry(VoxelShapeKind.FULL, List.of(),
                    new int[]{index, index, index, index, index, index}, 0, false, 0,
                    false, SectionPalette.NO_UV_RECT, 0, 63, words));
        }
        var faceBytes = ByteBuffer.wrap(BrickGridUpload.packFaceTextures(entries)).order(ByteOrder.LITTLE_ENDIAN);
        var paletteBytes = ByteBuffer.wrap(BrickGridUpload.packPaletteEntries(entries)).order(ByteOrder.LITTLE_ENDIAN);
        for (int index : new int[]{95, 127, 128, 191, 192, 239}) {
            int unsignedIndex = Byte.toUnsignedInt((byte) index);
            assertEquals(index, paletteBytes.getInt((unsignedIndex * 16 + 1) * 4));
            for (int word = 0; word < 42; word++)
                assertEquals((index << 16) | word, faceBytes.getInt((unsignedIndex * 42 + word) * 4));
        }
    }

    @Test void memoryIncludesFaceRecordsAndScalesWithSlotsAndCapacity() {
        for (int capacity : new int[]{96, 128, 192, 240}) {
            assertEquals(729L * capacity * 64, VoxelPaletteLayout.materialBytes(capacity, 729, false));
            assertEquals(729L * capacity * 232, VoxelPaletteLayout.materialBytes(capacity, 729, true));
            assertEquals(4913L * capacity * 64, VoxelPaletteLayout.materialBytes(capacity, 4913, false));
        }
    }
}
