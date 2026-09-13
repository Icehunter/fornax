package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackTomlLoaderRayTracedShadowTest {
    private static final String DECLARATION = """
            [ray_traced_shadows]
            enabled_if = "TRACE_ENABLED"
            distance_option = "u_TraceRadius"
            """;

    @Test
    void acceptsAnExplicitRayTracedShadowDeclaration() {
        GraphSpec graph = assertDoesNotThrow(() -> load(DECLARATION + "blocks_per_unit = 16\n"));
        assertEquals(new RayTracedShadowSpec("TRACE_ENABLED", "u_TraceRadius", 16), graph.rayTracedShadows());
    }

    @Test
    void defaultsToAUnitScaleOfOneBlock() {
        assertEquals(1, load(DECLARATION).rayTracedShadows().blocksPerUnit());
    }

    @Test
    void absentDeclarationKeepsRayTracedShadowsOff() {
        assertNull(load("").rayTracedShadows());
    }

    @Test
    void existingConstructorsKeepRayTracedShadowsOff() {
        assertNull(new GraphSpec(Map.of(), List.of()).rayTracedShadows());
        assertNull(new GraphSpec(Map.of(), Map.of(), List.of()).rayTracedShadows());
    }

    @Test
    void rejectsNonTableDeclarations() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> load("ray_traced_shadows = true"));
        assertEquals("ray_traced_shadows", error.key());
    }

    @Test
    void rejectsUnknownDeclarationFields() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> load(DECLARATION + "radius = 64\n"));
        assertEquals("ray_traced_shadows.radius", error.key());
    }

    @Test
    void requiresNonemptyStringGateAndRadiusNames() {
        for (String field : List.of("enabled_if", "distance_option")) {
            String valid = field.equals("enabled_if") ? "\"TRACE_ENABLED\"" : "\"u_TraceRadius\"";
            for (String malformed : List.of("\"\"", "\"   \"", "true", "1")) {
                FornaxPackError error = assertThrows(FornaxPackError.class,
                        () -> load(DECLARATION.replace(field + " = " + valid, field + " = " + malformed)));
                assertEquals("ray_traced_shadows." + field, error.key());
            }
            FornaxPackError missing = assertThrows(FornaxPackError.class,
                    () -> load(DECLARATION.replace(field + " = " + valid + "\n", "")));
            assertEquals("ray_traced_shadows." + field, missing.key());
        }
    }

    @Test
    void rejectsNonpositiveNonintegerAndOverflowingUnitScales() {
        for (String malformed : List.of("0", "-1", "1.5", "\"1\"", "true", "2147483648")) {
            FornaxPackError error = assertThrows(FornaxPackError.class,
                    () -> load(DECLARATION + "blocks_per_unit = " + malformed + "\n"));
            assertEquals("ray_traced_shadows.blocks_per_unit", error.key());
        }
    }

    @Test
    void filterGuardMustBeFiniteAndNonnegative() {
        assertEquals(0.0f, load(DECLARATION).rayTracedShadows().filterGuardTexels());
        assertEquals(148.5f, load(DECLARATION + "filter_guard_texels = 148.5\n").rayTracedShadows().filterGuardTexels());
        for (String value : List.of("-1", "nan", "inf", "true", "\"4\"")) {
            FornaxPackError error = assertThrows(FornaxPackError.class,
                    () -> load(DECLARATION + "filter_guard_texels = " + value + "\n"));
            assertEquals("ray_traced_shadows.filter_guard_texels", error.key());
        }
    }

    private static GraphSpec load(String toml) {
        return PackTomlLoader.loadGraph(new StringReader(toml), "graph.toml");
    }
}
