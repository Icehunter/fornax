package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScreensMetaLoaderTest {
    private static ScreensSpec load(String toml) {
        return PackTomlLoader.loadScreens(new StringReader(toml), "screens.toml");
    }

    @Test
    void parsesMetaTableWithAssignTiers() {
        ScreensSpec s = load("""
                [metas.SHADOW_DETAIL]
                label = "Shadow Detail"
                description = "How crisp shadows look."
                values = ["Low", "High"]
                [metas.SHADOW_DETAIL.assign.Low]
                DETAIL_RESOLUTION = 1024
                u_DetailSoftness = 0.5
                [metas.SHADOW_DETAIL.assign.High]
                DETAIL_RESOLUTION = 4096
                u_DetailSoftness = 2.0
                """);
        MetaSpec m = s.metas().get("SHADOW_DETAIL");
        assertNotNull(m);
        assertEquals("Shadow Detail", m.label());
        assertEquals("How crisp shadows look.", m.description());
        assertEquals(List.of("Low", "High"), m.values());
        assertEquals(1024L, ((Number) m.assign().get("Low").get("DETAIL_RESOLUTION")).longValue());
        assertEquals(0.5, ((Number) m.assign().get("Low").get("u_DetailSoftness")).doubleValue(), 1e-9);
        assertEquals(2, m.assign().size());
    }

    @Test
    void parsesYaclPagesList() {
        ScreensSpec s = load("""
                [yacl]
                pages = ["quality", "lighting"]
                """);
        assertEquals(List.of("quality", "lighting"), s.yaclPages());
    }

    @Test
    void emptyFileHasEmptyMetasAndYaclPages() {
        ScreensSpec s = load("");
        assertTrue(s.metas().isEmpty());
        assertTrue(s.yaclPages().isEmpty());
    }

    @Test
    void assignTierNotInValuesFailsLoad() {
        FornaxPackError e = assertThrows(FornaxPackError.class, () -> load("""
                [metas.M]
                values = ["Low"]
                [metas.M.assign.High]
                X = 1
                """));
        assertEquals("screens.toml", e.file());
        assertTrue(e.reason().toLowerCase().contains("high"));
    }

    @Test
    void unknownKeyInMetaTableFailsLoad() {
        assertThrows(FornaxPackError.class, () -> load("""
                [metas.M]
                values = ["Low"]
                bogus = 3
                """));
    }

    @Test
    void metaMissingValuesFailsLoad() {
        assertThrows(FornaxPackError.class, () -> load("""
                [metas.M]
                label = "M"
                """));
    }

    @Test
    void metaWithDependsOnRoundTripsToMetaSpec() {
        ScreensSpec s = load("""
                [metas.LIGHT_REACH]
                values = ["Short", "Long"]
                dependsOn = "EMITTER_LIGHTS"
                [metas.LIGHT_REACH.assign.Short]
                u_LightReach = 48.0
                [metas.LIGHT_REACH.assign.Long]
                u_LightReach = 144.0
                """);
        MetaSpec m = s.metas().get("LIGHT_REACH");
        assertNotNull(m);
        assertEquals("EMITTER_LIGHTS", m.dependsOn());
    }

    @Test
    void metaWithoutDependsOnYieldsNullDependsOn() {
        ScreensSpec s = load("""
                [metas.SHADOW_DETAIL]
                values = ["Low", "High"]
                [metas.SHADOW_DETAIL.assign.Low]
                X = 1
                [metas.SHADOW_DETAIL.assign.High]
                X = 2
                """);
        MetaSpec m = s.metas().get("SHADOW_DETAIL");
        assertNotNull(m);
        assertNull(m.dependsOn());
    }

    @Test
    void unknownKeyInMetaTableStillFailsLoadWithDependsOnDeclared() {
        // dependsOn joining the allow-list must not accidentally widen it to accept arbitrary keys.
        assertThrows(FornaxPackError.class, () -> load("""
                [metas.M]
                values = ["Low"]
                dependsOn = "SOME_OPTION"
                bogus = 3
                """));
    }

    @Test
    void iterationOrderOfMetasFollowsSourceOrder() {
        ScreensSpec s = load("""
                [metas.B]
                values = ["x"]
                [metas.B.assign.x]
                O = 1
                [metas.A]
                values = ["y"]
                [metas.A.assign.y]
                O = 2
                """);
        assertEquals(List.of("B", "A"), List.copyOf(s.metas().keySet()));
    }
}
