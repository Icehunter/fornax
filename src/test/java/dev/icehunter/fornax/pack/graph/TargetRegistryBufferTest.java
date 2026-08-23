package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Only the non-GPU-touching edges of {@link TargetRegistry}'s buffer path are unit-testable here --
 * actually allocating a {@code VkBuffer} needs a live Vulkan device, so that half is compile-checked
 * only (via {@code ./gradlew compileJava}) and build+deploy-verified in Task 11 (first real upload)
 * and Task 12 (first real bind), exactly like {@link TargetRegistryResizeClearTest}'s javadoc already
 * documents for the texture-clear side of this same class. What IS pure JVM behavior, and what this
 * test pins: {@link TargetRegistry#getBuffer} on a never-allocated name, and {@link
 * TargetRegistry#ensureBufferSize}'s graceful no-op when {@code VulkanComputeBackend.tryCreate()}
 * returns null (the case in this headless test JVM, with no GPU device ever bound) -- it must never
 * throw, and must leave the buffer unallocated for the next real attempt to pick up.
 */
class TargetRegistryBufferTest {
    private static TargetRegistry newRegistry() {
        GraphSpec graph = new GraphSpec(new LinkedHashMap<>(), List.of());
        return TargetRegistry.create(graph, Map.of());
    }

    @Test
    void getBufferReturnsNullForNeverAllocatedName() {
        TargetRegistry registry = newRegistry();
        assertNull(registry.getBuffer("voxelOccupancy"));
    }

    @Test
    void ensureBufferSizeNoOpsGracefullyWithNoComputeBackend() {
        TargetRegistry registry = newRegistry();
        assertDoesNotThrow(() -> registry.ensureBufferSize("voxelOccupancy", 4096L));
        assertNull(registry.getBuffer("voxelOccupancy"),
                "no compute backend in this headless test JVM -- the buffer must stay unallocated, not half-built");
    }

    @Test
    void zeroSizeIsRejectedInsteadOfReachingVulkan() {
        TargetRegistry registry = newRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.ensureBufferSize("voxelOccupancy", 0L));
    }

    @Test
    void releaseOfMissingBufferIsIdempotent() {
        TargetRegistry registry = newRegistry();
        assertDoesNotThrow(() -> registry.releaseBuffer("voxelOccupancy"));
    }

    @Test
    void ensureSizeWithPackSizedAndEngineOwnedBuffersDoesNotThrow() {
        // ensureSize runs from GraphRunner.prepare() EVERY frame; anything it throws takes the whole
        // frame with it, not just one target. The pack-sized-buffer reconcile added to it walks both
        // TargetPlan.bufferEntries() and the graph's own declared buffer targets, so this pins the
        // headless path (no compute backend, so ensureBufferSize itself no-ops) against a graph
        // carrying one of each kind -- a texture target, a pack-sized buffer, and an engine-owned
        // buffer whose name TargetPlan must not plan and this method must never free.
        Map<String, dev.icehunter.fornax.pack.TargetSpec> targets = new LinkedHashMap<>();
        targets.put("sceneColor", new dev.icehunter.fornax.pack.TargetSpec("sceneColor", "rgba16f", 1.0, false, null));
        targets.put("snowField", dev.icehunter.fornax.pack.TargetSpec.buffer("snowField", null, new BufferSize(4, 1024)));
        targets.put("voxelOccupancy", dev.icehunter.fornax.pack.TargetSpec.buffer("voxelOccupancy", null));
        TargetRegistry registry = TargetRegistry.create(new GraphSpec(targets, List.of()), Map.of());

        assertDoesNotThrow(() -> registry.ensureSize(1920, 1080, 1920, 1080));
        assertNull(registry.getBuffer("snowField"),
                "no compute backend here -- unallocated, never half-built");
        assertNull(registry.getBuffer("voxelOccupancy"));
    }
}
