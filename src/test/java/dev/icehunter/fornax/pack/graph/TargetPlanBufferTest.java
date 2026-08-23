package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.TargetSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetPlanBufferTest {
    @Test
    void bufferKindTargetIsSkippedByTargetPlanCompute() {
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("texTarget", new TargetSpec("texTarget", "rgba8", 1.0, false, null));
        targets.put("bufTarget", TargetSpec.buffer("bufTarget", null));
        GraphSpec graph = new GraphSpec(targets, List.of());

        TargetPlan plan = TargetPlan.compute(graph, Map.of(), 1920, 1080);

        assertEquals(1, plan.entries().size(), "only the texture target should produce a plan entry");
        assertEquals("texTarget", plan.entries().get(0).name());
    }

    // --- Pack-sized buffer targets --------------------------------------------------------------
    // A buffer target with a declared stride x count is the ONLY thing that makes TargetRegistry
    // allocate one. Every assertion below is the difference between a pack owning a persistent GPU
    // buffer and the buffer never existing, with nothing at load or run time saying which.

    @Test
    void packSizedBufferProducesABufferEntryWithItsExactByteCount() {
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("snowField", TargetSpec.buffer("snowField", null, new BufferSize(4, 65536)));
        GraphSpec graph = new GraphSpec(targets, List.of());

        TargetPlan plan = TargetPlan.compute(graph, Map.of(), 1920, 1080);

        assertEquals(List.of(new TargetPlan.BufferEntry("snowField", 262144L)), plan.bufferEntries());
        assertTrue(plan.entries().isEmpty(), "a buffer must never appear on the texture entry list");
    }

    @Test
    void packSizedBufferIgnoresRenderResolution() {
        // The whole point of stride x count rather than scale: a simulation field's capacity is a
        // property of the pack's own data layout, and must not move when the window resizes or a
        // TAAU render scale changes -- a reallocated buffer is a zero-cleared buffer, which would
        // wipe the accumulated state every resize.
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("snowField", TargetSpec.buffer("snowField", null, new BufferSize(8, 1024)));
        GraphSpec graph = new GraphSpec(targets, List.of());

        assertEquals(TargetPlan.compute(graph, Map.of(), 640, 480).bufferEntries(),
                TargetPlan.compute(graph, Map.of(), 3840, 2160).bufferEntries());
    }

    @Test
    void engineOwnedBufferProducesNoBufferEntry() {
        // No declared size = the engine's own ensureBufferSize call site owns it. If this ever
        // produced an entry, TargetRegistry would fight BrickGridUpload for the same allocation
        // every frame.
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("voxelOccupancy", TargetSpec.buffer("voxelOccupancy", null));
        GraphSpec graph = new GraphSpec(targets, List.of());

        assertTrue(TargetPlan.compute(graph, Map.of(), 1920, 1080).bufferEntries().isEmpty());
    }

    @Test
    void packSizedBufferHonoursItsEnabledIfGate() {
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("snowField", TargetSpec.buffer("snowField", "SNOW", new BufferSize(4, 16)));
        GraphSpec graph = new GraphSpec(targets, List.of());

        assertTrue(TargetPlan.compute(graph, Map.of("SNOW", 0), 1920, 1080).bufferEntries().isEmpty(),
                "gated off -> not planned, so TargetRegistry frees it instead of holding dead VRAM");
        assertEquals(1, TargetPlan.compute(graph, Map.of("SNOW", 1), 1920, 1080).bufferEntries().size());
    }

    @Test
    void bufferSizeIsComputedInLongArithmetic() {
        // 65536 x 65536 overflows int to exactly 0 -- which would sail through
        // ensureBufferSize's positivity check as a "release this buffer" request rather than an
        // allocation. PackTomlLoader refuses a product this large at load; this pins that the record
        // itself never reports a wrapped value regardless of who constructs it.
        assertEquals(4294967296L, new BufferSize(65536, 65536).sizeBytes());
    }

    @Test
    void textureTargetDefaultsToTextureKind() {
        TargetSpec spec = new TargetSpec("t", "rgba8", 1.0, false, null);
        assertEquals(TargetKind.TEXTURE, spec.kind());
    }

    @Test
    void bufferFactoryProducesBufferKind() {
        TargetSpec spec = TargetSpec.buffer("voxelOccupancy", null);
        assertEquals(TargetKind.BUFFER, spec.kind());
        assertTrue(spec.format() == null || spec.format().isEmpty(),
                "buffer-kind targets carry no pixel format");
    }
}
