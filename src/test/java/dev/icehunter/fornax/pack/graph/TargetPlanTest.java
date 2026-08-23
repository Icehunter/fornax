package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TargetPlanTest {
    @Test
    void sizesEachTargetByItsOwnScale() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.full]
                format = "rgba8"
                scale = 1.0

                [targets.half]
                format = "r8"
                scale = 0.5
                """), "graph.toml");

        TargetPlan plan = TargetPlan.compute(g, Map.of(), 1920, 1080);

        assertEquals(2, plan.entries().size());
        TargetPlan.Entry full = plan.find("full").orElseThrow();
        assertEquals(1920, full.width());
        assertEquals(1080, full.height());
        assertEquals(1, full.mipLevels());
        assertFalse(full.history());

        TargetPlan.Entry half = plan.find("half").orElseThrow();
        assertEquals(960, half.width());
        assertEquals(540, half.height());
    }

    @Test
    void fixedExtentIgnoresRenderAndOutputResolution() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.simulation]
                format = "rg16f"
                width = 512
                height = 512
                history = true
                """), "graph.toml");

        TargetPlan plan = TargetPlan.compute(g, Map.of(), 1280, 720, 3840, 2160);
        TargetPlan.Entry simulation = plan.find("simulation").orElseThrow();
        assertEquals(512, simulation.width());
        assertEquals(512, simulation.height());
        assertTrue(simulation.history());
    }

    @Test
    void storageCapabilityIsCarriedByTheGraphTargetPlan() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.waveState]
                format = "rgba16f"
                width = 512
                height = 512
                storage = true
                """), "graph.toml");
        assertTrue(TargetPlan.compute(g, Map.of(), 1920, 1080)
                .find("waveState").orElseThrow().storage());
    }

    @Test
    void floorsSizeAtOnePixel() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.tiny]
                format = "r8"
                scale = 0.0001
                """), "graph.toml");

        TargetPlan plan = TargetPlan.compute(g, Map.of(), 100, 100);
        TargetPlan.Entry tiny = plan.find("tiny").orElseThrow();
        assertEquals(1, tiny.width());
        assertEquals(1, tiny.height());
    }

    @Test
    void disabledTargetIsNotPlanned() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.bloom]
                format = "rgba16f"
                scale = 1.0
                enabled_if = "BLOOM"
                """), "graph.toml");

        TargetPlan disabled = TargetPlan.compute(g, Map.of("BLOOM", 0), 1920, 1080);
        assertTrue(disabled.find("bloom").isEmpty());

        TargetPlan enabled = TargetPlan.compute(g, Map.of("BLOOM", 1), 1920, 1080);
        assertTrue(enabled.find("bloom").isPresent());
    }

    @Test
    void historyFlagIsCarriedThrough() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.taa]
                format = "rgba8"
                scale = 1.0
                history = true
                """), "graph.toml");

        TargetPlan plan = TargetPlan.compute(g, Map.of(), 1920, 1080);
        assertTrue(plan.find("taa").orElseThrow().history());
    }

    @Test
    void mipchainTargetGetsMultipleLevels() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.hiz]
                format = "r32f"
                scale = 1.0

                [targets.other]
                format = "r8"
                scale = 1.0

                [[pass]]
                name = "hiz_build"
                type = "mipchain"
                target = "hiz"
                inputs = ["builtin.depth"]
                """), "graph.toml");

        TargetPlan plan = TargetPlan.compute(g, Map.of(), 1920, 1080);
        assertTrue(plan.find("hiz").orElseThrow().mipLevels() > 1);
        assertEquals(1, plan.find("other").orElseThrow().mipLevels());
    }

    @Test
    void fixedExtentMipchainUsesItsDeclaredSizeForExtentAndLevels() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.pyramid]
                format = "r32f"
                width = 64
                height = 32

                [[pass]]
                name = "pyramid_build"
                type = "mipchain"
                target = "pyramid"
                inputs = ["builtin.depth"]
                """), "graph.toml");

        TargetPlan.Entry entry = TargetPlan.compute(g, Map.of(), 3840, 2160)
                .find("pyramid").orElseThrow();
        assertEquals(64, entry.width());
        assertEquals(32, entry.height());
        assertEquals(6, entry.mipLevels());
    }

    @Test
    void computeLevelCountMatchesHiZManagerFormulaAndCapsAtTen() {
        assertEquals(1, TargetPlan.computeLevelCount(1, 1));
        // minDim(1920,1080) = 1080; 1 + floor(log2(1080)) = 1 + 10 = 11, capped at MAX_MIP_LEVELS (10).
        assertEquals(TargetPlan.MAX_MIP_LEVELS, TargetPlan.computeLevelCount(1920, 1080));
        assertEquals(TargetPlan.MAX_MIP_LEVELS, TargetPlan.computeLevelCount(100000, 100000));
    }

    @Test
    void outputBasisTargetSizesOffOutputResolutionNotRender() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.native]
                format = "rgba8"
                scale = 1.0
                basis = "output"

                [targets.scaled]
                format = "rgba8"
                scale = 1.0
                """), "graph.toml");

        TargetPlan plan = TargetPlan.compute(g, Map.of(), 1280, 720, 1920, 1080);

        TargetPlan.Entry outputBasis = plan.find("native").orElseThrow();
        assertEquals(1920, outputBasis.width());
        assertEquals(1080, outputBasis.height());

        TargetPlan.Entry renderBasis = plan.find("scaled").orElseThrow();
        assertEquals(1280, renderBasis.width());
        assertEquals(720, renderBasis.height());
    }

    @Test
    void fourArgComputeDelegatesWithOutputEqualToRender() {
        // Compat: every caller that predates the render/output distinction only ever had one
        // resolution to give, and every one of its targets defaults to RENDER basis -- so this
        // overload must size byte-identically to the 6-arg form called with output == render.
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.full]
                format = "rgba8"
                scale = 1.0
                """), "graph.toml");

        TargetPlan viaFourArg = TargetPlan.compute(g, Map.of(), 1920, 1080);
        TargetPlan viaSixArg = TargetPlan.compute(g, Map.of(), 1920, 1080, 1920, 1080);

        assertEquals(viaSixArg.find("full").orElseThrow().width(), viaFourArg.find("full").orElseThrow().width());
        assertEquals(viaSixArg.find("full").orElseThrow().height(), viaFourArg.find("full").orElseThrow().height());
    }

    @Test
    void findReturnsEmptyForUnknownName() {
        GraphSpec g = PackTomlLoader.loadGraph(new java.io.StringReader(""), "graph.toml");
        TargetPlan plan = TargetPlan.compute(g, Map.of(), 1920, 1080);
        Optional<TargetPlan.Entry> missing = plan.find("nope");
        assertTrue(missing.isEmpty());
    }
}
