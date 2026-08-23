package dev.icehunter.fornax.pack.material;

import dev.icehunter.fornax.pack.BlocksSpec;
import dev.icehunter.fornax.pack.CategorySpec;
import dev.icehunter.fornax.pack.FornaxPackError;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BlockMaterialResolverTest {
    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BlocksSpec spec(Map<String, List<String>> categoryBlocks) {
        Map<String, CategorySpec> cats = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : categoryBlocks.entrySet()) {
            cats.put(e.getKey(), new CategorySpec(e.getKey(), e.getValue(), false, null, null, null, null, false, false));
        }
        return new BlocksSpec(cats);
    }

    private static BlocksSpec oneCategory(String name, List<String> blocks) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(name, blocks);
        return spec(m);
    }

    @Test void directBlocksAndTagsResolveToDeclarationOrderIds() {
        Map<String, List<String>> cats = new LinkedHashMap<>();
        cats.put("polished_metal", List.of("minecraft:iron_block", "#c:demo"));
        cats.put("glass", List.of("minecraft:glass"));
        BlocksSpec blocksSpec = spec(cats);
        MaterialCategories materialCategories = MaterialCategories.from(blocksSpec);

        var lookup = new BlockMaterialResolver.Lookup() {
            public Optional<Block> block(String id) {
                return id.equals("minecraft:iron_block") ? Optional.of(Blocks.IRON_BLOCK)
                        : id.equals("minecraft:glass") ? Optional.of(Blocks.GLASS) : Optional.empty();
            }

            public List<Block> tagMembers(String id) {
                return id.equals("c:demo") ? List.of(Blocks.GOLD_BLOCK) : List.of();
            }
        };

        Map<Block, Integer> m = BlockMaterialResolver.resolve(blocksSpec, materialCategories, lookup);
        assertEquals(1, m.get(Blocks.IRON_BLOCK));
        assertEquals(1, m.get(Blocks.GOLD_BLOCK)); // via #c:demo tag
        assertEquals(2, m.get(Blocks.GLASS));
    }

    @Test void leadingHashForcesTagLookupEvenWhenBlockOfThatIdExists() {
        BlocksSpec blocksSpec = oneCategory("weird", List.of("#minecraft:iron_block"));
        MaterialCategories cats = MaterialCategories.from(blocksSpec);

        var lookup = new BlockMaterialResolver.Lookup() {
            public Optional<Block> block(String id) {
                fail("block() must not be consulted for a leading '#' entry: " + id);
                return Optional.empty();
            }

            public List<Block> tagMembers(String id) {
                assertEquals("minecraft:iron_block", id); // '#' stripped before lookup
                return List.of(Blocks.STONE);
            }
        };

        Map<Block, Integer> m = BlockMaterialResolver.resolve(blocksSpec, cats, lookup);
        assertEquals(1, m.get(Blocks.STONE));
    }

    @Test void bareEntryTriesBlockFirstThenRetriesAsTag() {
        // "c:storage_blocks/iron" names no block (the c namespace has no blocks), so it must fall
        // back to a tag lookup even without a leading '#'.
        BlocksSpec blocksSpec = oneCategory("polished_metal", List.of("c:storage_blocks/iron"));
        MaterialCategories cats = MaterialCategories.from(blocksSpec);

        var lookup = new BlockMaterialResolver.Lookup() {
            public Optional<Block> block(String id) { return Optional.empty(); }

            public List<Block> tagMembers(String id) {
                return id.equals("c:storage_blocks/iron") ? List.of(Blocks.IRON_BLOCK) : List.of();
            }
        };

        Map<Block, Integer> m = BlockMaterialResolver.resolve(blocksSpec, cats, lookup);
        assertEquals(1, m.get(Blocks.IRON_BLOCK));
    }

    @Test void unresolvableButWellFormedEntryIsSkippedNotAnError() {
        // Well-formed id/tag that simply isn't present (e.g. a modded block absent from this
        // install) must not fail pack load -- it's just missing from the resulting map.
        BlocksSpec blocksSpec = oneCategory("modded", List.of("somemod:nonexistent_block"));
        MaterialCategories cats = MaterialCategories.from(blocksSpec);

        var lookup = new BlockMaterialResolver.Lookup() {
            public Optional<Block> block(String id) { return Optional.empty(); }

            public List<Block> tagMembers(String id) { return List.of(); }
        };

        Map<Block, Integer> m = BlockMaterialResolver.resolve(blocksSpec, cats, lookup);
        assertTrue(m.isEmpty());
    }

    @Test void malformedEntryThrowsFornaxPackError() {
        BlocksSpec blocksSpec = oneCategory("polished_metal", List.of("minecraft:"));
        MaterialCategories cats = MaterialCategories.from(blocksSpec);

        var lookup = new BlockMaterialResolver.Lookup() {
            public Optional<Block> block(String id) {
                fail("a malformed entry must be rejected before any lookup is attempted");
                return Optional.empty();
            }

            public List<Block> tagMembers(String id) {
                fail("a malformed entry must be rejected before any lookup is attempted");
                return List.of();
            }
        };

        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> BlockMaterialResolver.resolve(blocksSpec, cats, lookup));
        assertEquals("blocks.toml", e.file());
        assertEquals("categories.polished_metal.blocks", e.key());
        assertTrue(e.reason().contains("minecraft:"));
    }

    @Test void malformedEntryWithHashPrefixIsAlsoRejected() {
        BlocksSpec blocksSpec = oneCategory("polished_metal", List.of("#Bad Name"));
        MaterialCategories cats = MaterialCategories.from(blocksSpec);

        var lookup = new BlockMaterialResolver.Lookup() {
            public Optional<Block> block(String id) { return Optional.empty(); }

            public List<Block> tagMembers(String id) {
                fail("a malformed entry must be rejected before any lookup is attempted");
                return List.of();
            }
        };

        assertThrows(FornaxPackError.class, () -> BlockMaterialResolver.resolve(blocksSpec, cats, lookup));
    }

    @Test void overlappingCategoriesKeepFirstDeclaredId() {
        Map<String, List<String>> cats = new LinkedHashMap<>();
        cats.put("polished_metal", List.of("minecraft:iron_block"));
        cats.put("shiny", List.of("minecraft:iron_block")); // same block claimed twice
        BlocksSpec blocksSpec = spec(cats);
        MaterialCategories materialCategories = MaterialCategories.from(blocksSpec);

        var lookup = new BlockMaterialResolver.Lookup() {
            public Optional<Block> block(String id) {
                return id.equals("minecraft:iron_block") ? Optional.of(Blocks.IRON_BLOCK) : Optional.empty();
            }

            public List<Block> tagMembers(String id) { return List.of(); }
        };

        Map<Block, Integer> m = BlockMaterialResolver.resolve(blocksSpec, materialCategories, lookup);
        assertEquals(1, m.get(Blocks.IRON_BLOCK)); // "polished_metal" (declared first) wins
        assertFalse(m.containsValue(2));
    }
}
