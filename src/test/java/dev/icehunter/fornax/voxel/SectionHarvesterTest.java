package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.material.MaterialScalars;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionHarvesterTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void effectiveEmissionCombinesCategoryStrengthWithVanillaLevel() {
        // Tagged block: category strength scaled by the vanilla level (torch: 1.0 x 14/15).
        assertEquals(1.0 * 14.0 / 15.0, SectionHarvester.effectiveEmission(1.0, 14), 1e-9);
        // Tagged but vanilla-dark (an OFF redstone lamp): emits nothing.
        assertEquals(0.0, SectionHarvester.effectiveEmission(0.8, 0), 1e-9);
        // UNTAGGED emitter floor (lava has no blocks.toml category): the vanilla level alone.
        assertEquals(1.0, SectionHarvester.effectiveEmission(0.0, 15), 1e-9);
        assertEquals(7.0 / 15.0, SectionHarvester.effectiveEmission(0.0, 7), 1e-9);
        // Plain stone: nothing.
        assertEquals(0.0, SectionHarvester.effectiveEmission(0.0, 0), 1e-9);
    }

    @Test
    void allAirSectionHasATrivialPalette() {
        PalettedContainerRO<BlockState> allAir = uniformSection(Blocks.AIR.defaultBlockState());
        SectionHarvester.Result result = SectionHarvester.harvest(allAir, MaterialScalars.build(List.of()));
        assertEquals(4096, result.paletteIndices().length);
        assertEquals(1, result.palette().entries().size(), "an all-air section needs exactly one palette entry");
        assertEquals(VoxelShapeKind.EMPTY, result.palette().entries().get(0).shapeKind());
        assertEquals(0, result.palette().entries().get(0).emissionColor(),
                "uncategorized (categoryId 0) always reports no authored emission color");
    }

    @Test
    @Disabled("needs a live Minecraft client instance (Minecraft.getInstance().getModelManager()) "
            + "for real model/sprite resolution via FaceColorResolver -- verified live in this task's "
            + "Step 7 instead, matching FaceColorResolverTest's own precedent")
    void allStoneSectionHasOneFullEntry() {
        PalettedContainerRO<BlockState> allStone = uniformSection(Blocks.STONE.defaultBlockState());
        SectionHarvester.Result result = SectionHarvester.harvest(allStone, MaterialScalars.build(List.of()));
        assertEquals(1, result.palette().entries().size());
        assertEquals(VoxelShapeKind.FULL, result.palette().entries().get(0).shapeKind());
        assertFalse(result.palette().entries().get(0).lightTransmissive(), "stone must not be transmissive");
        for (byte index : result.paletteIndices()) {
            assertEquals(0, index, "every voxel should point at the single stone palette entry");
        }
    }

    @Test
    @Disabled("needs a live Minecraft client instance (Minecraft.getInstance().getModelManager()) "
            + "for real model/sprite resolution via FaceColorResolver -- verified live in this task's "
            + "Step 7 instead, matching FaceColorResolverTest's own precedent")
    void glassIsFullButLightTransmissiveWhileTintedGlassIsNot() {
        PalettedContainerRO<BlockState> allGlass = uniformSection(Blocks.GLASS.defaultBlockState());
        SectionHarvester.Result glassResult = SectionHarvester.harvest(allGlass, MaterialScalars.build(List.of()));
        assertEquals(VoxelShapeKind.FULL, glassResult.palette().entries().get(0).shapeKind());
        assertTrue(glassResult.palette().entries().get(0).lightTransmissive(),
                "regular glass dampens light by 0, not fully sealed");

        PalettedContainerRO<BlockState> allTintedGlass = uniformSection(Blocks.TINTED_GLASS.defaultBlockState());
        SectionHarvester.Result tintedResult = SectionHarvester.harvest(allTintedGlass, MaterialScalars.build(List.of()));
        assertFalse(tintedResult.palette().entries().get(0).lightTransmissive(),
                "tinted glass fully blocks light despite looking like glass -- confirmed vanilla behavior");
    }

    @Test
    @Disabled("needs a live Minecraft client instance (Minecraft.getInstance().getModelManager()) "
            + "for real model/sprite resolution via FaceColorResolver -- verified live in this task's "
            + "Step 7 instead, matching FaceColorResolverTest's own precedent")
    void moreThanMaxPaletteEntriesDistinctStatesCapsThePaletteInsteadOfAliasing() {
        // Grab enough real, distinct block states (>MAX_PALETTE_ENTRIES, currently 96 -- well under the
        // byte-addressable limit of 256 a palette index can ever encode) to reproduce the real "section
        // palette overflowed to vanilla's global palette" scenario, rather than hand-listing 100+
        // Blocks.* constants. Iterating the real block registry guarantees these are genuinely distinct
        // BlockState identities, exactly like forEachInPalette would see. 300 keeps comfortable headroom
        // above MAX_PALETTE_ENTRIES regardless of which candidate value it currently holds.
        List<BlockState> distinctStates = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            distinctStates.add(block.defaultBlockState());
            if (distinctStates.size() >= 300) {
                break;
            }
        }
        assertTrue(distinctStates.size() > SectionHarvester.MAX_PALETTE_ENTRIES,
                "test setup needs more distinct states than the palette cap to actually exercise the fix");

        Strategy<BlockState> strategy = Strategy.createForBlockStates(
                net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY);
        PalettedContainer<BlockState> container =
                new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
        int placed = 0;
        outer:
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (placed >= distinctStates.size()) {
                        break outer;
                    }
                    container.set(x, y, z, distinctStates.get(placed));
                    placed++;
                }
            }
        }

        SectionHarvester.Result result = assertDoesNotThrow(
                () -> SectionHarvester.harvest(container, MaterialScalars.build(List.of())),
                "harvesting a section with more than MAX_PALETTE_ENTRIES distinct states must not throw");

        assertEquals(SectionHarvester.MAX_PALETTE_ENTRIES, result.palette().entries().size(),
                "palette must be capped at MAX_PALETTE_ENTRIES instead of growing past it");
        for (byte rawIndex : result.paletteIndices()) {
            int index = rawIndex & 0xFF;
            assertTrue(index >= 0 && index < SectionHarvester.MAX_PALETTE_ENTRIES,
                    "every voxel's palette index must land inside the real, capped palette -- "
                            + "no aliasing/wraparound past the byte range");
        }
    }

    @Test
    @Disabled("needs a live Minecraft client instance (Minecraft.getInstance().getModelManager()) "
            + "for real model/sprite resolution via FaceColorResolver, same gap as every other "
            + "PARTIAL/FULL-shape harvest test above -- VoxelShapeClassifierTest's own "
            + "hopperShapeExceedsMaxBoxesAndMergesRatherThanDrops verifies the merge behaviour itself "
            + "live without needing this fixture; this test documents that buildEntry's box list "
            + "(shape.boxes() -> Entry.boxes()) is wired through end to end unmodified.")
    void hopperEntryCarriesTheClassifiersMergedBoxes() {
        PalettedContainerRO<BlockState> allHoppers = uniformSection(Blocks.HOPPER.defaultBlockState());
        SectionHarvester.Result result = SectionHarvester.harvest(allHoppers, MaterialScalars.build(List.of()));
        assertEquals(VoxelShapeClassifier.MAX_BOXES, result.palette().entries().get(0).boxes().size(),
                "a hopper's harvested entry must carry the classifier's own merged (never dropped) box list "
                        + "through to the palette");
    }

    /** Minimal in-memory PalettedContainer construction mirroring how vanilla's own
     * {@code PalettedContainerFactory.createForBlockStates()} actually builds one, for a section
     * uniformly filled with a single block state. Verified against the real jar via javap: in this
     * version {@code PalettedContainer.Strategy} is no longer a nested enum -- {@code Strategy<T>} is
     * its own top-level class with a {@code createForBlockStates(IdMap<T>)} factory method, and the
     * public {@code PalettedContainer} constructor is {@code (T defaultValue, Strategy<T> strategy)}
     * (two args, no registry parameter) -- not the three-arg
     * {@code (Registry, T, Strategy.SECTION_STATES)} guess this test started from. */
    private static PalettedContainerRO<BlockState> uniformSection(BlockState state) {
        Strategy<BlockState> strategy = Strategy.createForBlockStates(
                net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY);
        PalettedContainer<BlockState> container = new PalettedContainer<>(state, strategy);
        return container;
    }
}
