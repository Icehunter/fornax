package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK13;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeGraphicsWaitsTest {
    private static PassSpec pass(String name, PassType type, List<String> reads, List<String> writes) {
        return new PassSpec(name, type, null, null, null, reads, writes, null, null,
                List.of(), null, null, null);
    }
    private static PassSpec compute(String name, List<String> reads, String output) {
        return pass(name, PassType.COMPUTE, reads, List.of(output));
    }
    private static PassSpec draw(String name, List<String> reads, String output) {
        return pass(name, PassType.FULLSCREEN, reads, List.of(output));
    }
    private record Wait(long semaphore, long stages) {}

    @Test void firstActualHandoffIsImmediateThenIndependentWorkRunsUntilAerialIsRead() {
        List<Wait> waits = new ArrayList<>();
        ComputeGraphicsWaits frame = new ComputeGraphicsWaits(ComputeGraphicsWaits::conflicts, (semaphore, stages) -> waits.add(new Wait(semaphore, stages)));
        frame.submitted(compute("sky", List.of("air"), "sky"), 10L, VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT);
        assertEquals(List.of(new Wait(10L, VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT)), waits);
        frame.submitted(compute("aerial", List.of("sky", "sunShadowMap"), "aerial"), 11L,
                VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT);
        frame.beforePass(draw("ao", List.of("builtin.depth"), "ao"));
        frame.beforePass(draw("ssr", List.of("builtin.depth", "sceneHistory.history"), "ssr"));
        assertEquals(1, waits.size(), "independent graphics must precede the later compute wait");
        frame.beforePass(draw("resolve", List.of("aerial"), "scene"));
        assertEquals(List.of(new Wait(10L, VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT),
                new Wait(11L, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)), waits);
        frame.close();
        assertEquals(2, waits.size(), "the binary signal is consumed exactly once");
    }

    @Test void writesToComputeInputsOrOutputsForceTheWaitBeforeGraphics() {
        PassSpec producer = compute("producer", List.of("seed"), "field");
        assertTrue(ComputeGraphicsWaits.conflicts(producer, draw("raw", List.of("field"), "out")));
        assertTrue(ComputeGraphicsWaits.conflicts(producer, draw("war", List.of(), "seed")));
        assertTrue(ComputeGraphicsWaits.conflicts(producer, draw("waw", List.of(), "field")));
        assertFalse(ComputeGraphicsWaits.conflicts(producer, draw("readRead", List.of("seed"), "other")));
    }

    @Test void historyAndShadowSamplerAliasesMatchPhysicalResourcesConservatively() {
        assertTrue(ComputeGraphicsWaits.conflicts(compute("p", List.of(), "field"),
                draw("history", List.of("field.history"), "out")));
        assertTrue(ComputeGraphicsWaits.conflicts(compute("p", List.of("field.history"), "out"),
                draw("historyWrite", List.of(), "field")));
        assertTrue(ComputeGraphicsWaits.conflicts(compute("p", List.of("sunShadowMapRaw"), "out"),
                draw("shadowWrite", List.of(), "sunShadowMap")));
    }

    @Test void mipchainTargetCountsAsAWriteEvenWithEmptyOutputs() {
        PassSpec mip = new PassSpec("mip", PassType.MIPCHAIN, null, null, null, List.of("seed"),
                List.of(), "field", null, List.of(), null, null, null);
        assertTrue(ComputeGraphicsWaits.conflicts(compute("p", List.of("field"), "out"), mip));
    }

    @Test void transferAndConsolidateReadsAreConsumers() {
        PassSpec producer = compute("p", List.of(), "field");
        assertTrue(ComputeGraphicsWaits.conflicts(producer,
                pass("copy", PassType.COPY, List.of("field"), List.of("copy"))));
        assertTrue(ComputeGraphicsWaits.conflicts(producer,
                pass("array", PassType.CONSOLIDATE, List.of("field"), List.of("array"))));
    }

    @Test void opaqueOrEngineSideEffectsDrainRatherThanAssumeOnlyDeclaredAttachments() {
        PassSpec producer = compute("p", List.of(), "field");
        for (PassType type : List.of(PassType.GEOMETRY, PassType.PARTICLES, PassType.TEMPORAL, PassType.COMPUTE)) {
            assertTrue(ComputeGraphicsWaits.conflicts(producer, pass("opaque", type, List.of(), List.of("other"))));
        }
        assertTrue(ComputeGraphicsWaits.conflicts(producer, draw("present", List.of(), "builtin.output")));
    }

    @Test void skippedConsumersStillConsumeTheirBinarySignalAtScopeExit() {
        List<Long> waits = new ArrayList<>();
        try (ComputeGraphicsWaits frame = new ComputeGraphicsWaits(ComputeGraphicsWaits::conflicts, (semaphore, stages) -> waits.add(semaphore))) {
            frame.submitted(compute("first", List.of(), "first"), 1L, 8L);
            frame.submitted(compute("later", List.of(), "later"), 2L, 8L);
            // Disabled or missing consumers do not call beforePass at all.
            assertEquals(List.of(1L), waits);
        }
        assertEquals(List.of(1L, 2L), waits);
    }

    @Test void onlyTheConflictingPendingHandoffWaitsNotEveryPendingOne() {
        List<Long> waits = new ArrayList<>();
        ComputeGraphicsWaits frame = new ComputeGraphicsWaits(ComputeGraphicsWaits::conflicts, (semaphore, stages) -> waits.add(semaphore));
        frame.submitted(compute("first", List.of(), "first"), 1L, 8L);
        frame.submitted(compute("water", List.of(), "water"), 2L, 8L);
        frame.submitted(compute("clouds", List.of(), "clouds"), 3L, 8L);
        assertEquals(List.of(1L), waits);
        frame.beforePass(draw("useWater", List.of("water"), "out"));
        assertEquals(List.of(1L, 2L), waits, "clouds does not conflict, so it must stay deferred");
        frame.close();
        assertEquals(List.of(1L, 2L, 3L), waits, "the deferred handoff still drains at scope exit");
    }

    @Test void laterComputeKeepsTheOriginalPreSubmitGraphicsBoundary() {
        List<Long> waits = new ArrayList<>();
        ComputeGraphicsWaits frame = new ComputeGraphicsWaits(ComputeGraphicsWaits::conflicts, (semaphore, stages) -> waits.add(semaphore));
        frame.submitted(compute("first", List.of(), "first"), 1L, 8L);
        frame.submitted(compute("later", List.of(), "later"), 2L, 8L);
        frame.beforePass(compute("next", List.of("later"), "next"));
        assertEquals(List.of(1L, 2L), waits);
        frame.close();
        assertEquals(List.of(1L, 2L), waits);
    }

    @Test void exceptionExitDrainsAndEachFrameKeepsItsOwnFirstBoundary() {
        List<Long> waits = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            try (ComputeGraphicsWaits frame = new ComputeGraphicsWaits(ComputeGraphicsWaits::conflicts, (semaphore, stages) -> waits.add(semaphore))) {
                frame.submitted(compute("first", List.of(), "first"), 1L, 8L);
                assertEquals(index * 2 + 1, waits.size());
                frame.submitted(compute("cached", List.of(), "cached"), 2L, 8L);
                throw new IllegalStateException("consumer failed");
            } catch (IllegalStateException expected) {
                assertEquals("consumer failed", expected.getMessage());
            }
        }
        assertEquals(List.of(1L, 2L, 1L, 2L), waits);
    }
    @Test void actualPackAerialWaitFollowsIndependentFiltersAndStopsAtComputeOrResolve() throws Exception {
        var path = java.nio.file.Path.of("../plague/graph.toml");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(path));
        dev.icehunter.fornax.pack.GraphSpec graph;
        try (var reader = java.nio.file.Files.newBufferedReader(path)) {
            graph = dev.icehunter.fornax.pack.PackTomlLoader.loadGraph(reader, "graph.toml");
        }
        var passes = graph.passes();
        PassSpec aerial = passes.stream().filter(p -> p.name().equals("atmo_aerial")).findFirst().orElseThrow();
        PassSpec resolve = passes.stream().filter(p -> p.name().equals("resolve")).findFirst().orElseThrow();
        int start = passes.indexOf(aerial), end = passes.indexOf(resolve);
        assertTrue(end > start);
        for (PassSpec intermediate : passes.subList(start + 1, end)) {
            // A newly inserted stage that does not draw keeps the existing conservative handoff
            // boundary; only independent graphics filters can defer the aerial semaphore further.
            // Ray query counts with compute here: both run off the graphics queue, so both hold the
            // boundary where a filter would move it.
            boolean offQueue = intermediate.type() == PassType.COMPUTE
                    || intermediate.type() == PassType.RAY_QUERY;
            assertEquals(offQueue, ComputeGraphicsWaits.conflicts(aerial, intermediate),
                    intermediate.name());
        }
        assertTrue(ComputeGraphicsWaits.conflicts(aerial, resolve));
    }

    @Test void compiledPlanPreservesHazardsAndRefusesUnplannedPasses() {
        PassSpec producer = compute("p", List.of("seed"), "field");
        PassSpec independent = draw("independent", List.of("seed"), "other");
        PassSpec reader = draw("reader", List.of("field.history"), "out");
        var plan = ComputeGraphicsWaits.compile(List.of(producer, independent, reader));
        assertFalse(plan.test(producer, independent));
        assertTrue(plan.test(producer, reader));
        assertTrue(plan.test(producer, draw("unplanned", List.of(), "new")));
        assertTrue(plan.test(compute("unplannedProducer", List.of(), "other"), independent));
        List<Long> waits = new ArrayList<>();
        try (ComputeGraphicsWaits frame = new ComputeGraphicsWaits(plan, (semaphore, stages) -> waits.add(semaphore))) {
            frame.submitted(producer, 1L, 8L);
            frame.submitted(producer, 2L, 8L);
            frame.beforePass(independent);
            assertEquals(List.of(1L), waits);
            frame.beforePass(reader);
            assertEquals(List.of(1L, 2L), waits);
        }
    }

}
