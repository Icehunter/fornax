package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomesSpecTest {
    @Test
    void explicitIdsAndDeclarationOrderSurviveParsing() {
        BiomesSpec spec = parse("""
                [biomes]
                "minecraft:plains" = 9
                "example.mod:climate/cold.valley" = 2
                "minecraft:lush_caves" = 17
                """);

        assertEquals(List.of("minecraft:plains", "example.mod:climate/cold.valley",
                "minecraft:lush_caves"), List.copyOf(spec.ids().keySet()));
        // Deliberately sparse, unsorted IDs prove declaration order never assigns or renumbers IDs.
        assertEquals(9, spec.id("minecraft:plains"));
        assertEquals(2, spec.id("example.mod:climate/cold.valley"));
        assertEquals(17, spec.id("minecraft:lush_caves"));
    }

    @Test
    void absentOrEmptyMappingResolvesEveryBiomeToUnknown() {
        for (BiomesSpec spec : List.of(BiomesSpec.empty(), parse(""), parse("[biomes]\n"))) {
            assertTrue(spec.ids().isEmpty());
            assertEquals(0, spec.id("minecraft:plains"));
            assertEquals(0, spec.id(null));
        }
    }

    @Test
    void unmappedAndNullBiomeNamesResolveToReservedZero() {
        BiomesSpec spec = parse("[biomes]\n\"minecraft:plains\" = 1\n");

        assertEquals(0, spec.id("minecraft:desert"));
        assertEquals(0, spec.id(null));
    }

    @Test
    void constructionDefensivelyCopiesAnInsertionOrderedMap() {
        Map<String, Integer> ids = new LinkedHashMap<>();
        ids.put("minecraft:plains", 9);
        ids.put("minecraft:lush_caves", 2);
        BiomesSpec spec = new BiomesSpec(ids);

        ids.clear();
        ids.put("minecraft:desert", 17);

        assertEquals(List.of("minecraft:plains", "minecraft:lush_caves"),
                List.copyOf(spec.ids().keySet()));
        assertEquals(9, spec.id("minecraft:plains"));
        assertEquals(0, spec.id("minecraft:desert"));
        assertThrows(UnsupportedOperationException.class,
                () -> spec.ids().put("minecraft:desert", 17));
        assertThrows(UnsupportedOperationException.class,
                () -> spec.ids().entrySet().iterator().next().setValue(17));
    }

    @Test
    void parsedMappingCannotBeMutated() {
        BiomesSpec spec = parse("[biomes]\n\"minecraft:plains\" = 1\n");
        assertThrows(UnsupportedOperationException.class, () -> spec.ids().clear());
    }

    @Test
    void largestConsecutivelyRepresentableFloatIntegerIsAcceptedExactly() {
        // IEEE 754 binary32 has 24 significant bits, so integers through 2^24 remain exact.
        int maximumId = 1 << 24;
        BiomesSpec spec = parse("[biomes]\n\"minecraft:plains\" = " + maximumId + "\n");

        assertEquals(maximumId, spec.id("minecraft:plains"));
        assertEquals(maximumId, (int) (float) spec.id("minecraft:plains"));
    }

    @Test
    void reservedZeroNegativeAndInexactOrOverflowingIdsAreRejected() throws IOException {
        for (String fixture : List.of("zero_id", "negative_id", "above_float_limit", "integer_overflow")) {
            assertInvalidFixture(fixture, "minecraft:plains");
        }
    }

    @Test
    void idsMustBeIntegersRatherThanFloatsStringsBooleansArraysOrTables() throws IOException {
        for (String fixture : List.of("float_id", "string_id", "boolean_id", "array_id", "table_id")) {
            assertInvalidFixture(fixture, "minecraft:plains");
        }
    }

    @Test
    void biomeKeysMustBeCanonicalNamespacedResourceLocations() throws IOException {
        assertInvalidFixture("missing_namespace", "plains");
        assertInvalidFixture("empty_namespace", ":plains");
        assertInvalidFixture("empty_path", "minecraft:");
        assertInvalidFixture("uppercase_key", "minecraft:Plains");
        assertInvalidFixture("whitespace_key", "minecraft:cold plains");
        assertInvalidFixture("extra_colon", "minecraft:plains:other");
        assertInvalidFixture("invalid_namespace", "mine/craft:plains");
    }

    @Test
    void duplicateNumericIdsCannotAliasDifferentBiomeKeys() throws IOException {
        assertInvalidFixture("duplicate_id", "minecraft:desert");
    }

    @Test
    void unknownManifestKeysAndANonTableMappingAreRejected() throws IOException {
        assertInvalidFixture("unknown_root_key", "unexpected");
        assertInvalidFixture("mapping_not_table", "biomes");
    }

    @Test
    void directConstructionEnforcesTheSameKeyRangeAndUniquenessRules() {
        for (int invalidId : List.of(-1, 0, (1 << 24) + 1)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BiomesSpec(Map.of("minecraft:plains", invalidId)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new BiomesSpec(Map.of("plains", 1)));

        Map<String, Integer> duplicateIds = new LinkedHashMap<>();
        duplicateIds.put("minecraft:plains", 1);
        duplicateIds.put("minecraft:desert", 1);
        assertThrows(IllegalArgumentException.class, () -> new BiomesSpec(duplicateIds));
    }

    private static BiomesSpec parse(String source) {
        return BiomesTomlLoader.load(new StringReader(source), "biomes.toml");
    }

    private static void assertInvalidFixture(String name, String offendingKey) throws IOException {
        String resource = "/packs/bad_biomes/" + name + ".toml";
        InputStream stream = BiomesSpecTest.class.getResourceAsStream(resource);
        assertNotNull(stream, "missing test fixture: " + resource);
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            FornaxPackError error = assertThrows(FornaxPackError.class,
                    () -> BiomesTomlLoader.load(reader, "biomes.toml"));
            assertEquals("biomes.toml", error.file());
            assertTrue(error.getMessage().contains(offendingKey), error.getMessage());
            assertTrue(!error.reason().isBlank(), "load failure must explain the invalid declaration");
        }
    }

}
