package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises revision bookkeeping without allocating or submitting GPU resources. */
class TargetRegistryComputeRevisionTest {
    @Test void onlyNewKernelSubmissionsAdvanceAnUpstreamRevision() {
        TargetRegistry registry = TargetRegistry.create(new GraphSpec(Map.of(), List.of()), Map.of());
        assertEquals(0, registry.computeContentRevision("tableA"));
        registry.recordComputeWrite(List.of("tableA", "tableB"));
        long first = registry.computeContentRevision("tableA");
        assertTrue(first > 0);
        assertEquals(first, registry.computeContentRevision("tableB"));
        assertEquals(first, registry.computeContentRevision("tableA"));
        registry.recordComputeWrite(List.of("tableA"));
        assertTrue(registry.computeContentRevision("tableA") > first);
        assertEquals(first, registry.computeContentRevision("tableB"));
    }

    @Test void allocationReplacementAndRegistryReloadLosePriorContent() {
        TargetRegistry registry = TargetRegistry.create(new GraphSpec(Map.of(), List.of()), Map.of());
        registry.recordComputeWrite(List.of("tableA"));
        long first = registry.computeContentRevision("tableA");
        registry.invalidateComputeContent("tableA");
        assertEquals(0, registry.computeContentRevision("tableA"));
        registry.recordComputeWrite(List.of("tableA"));
        assertTrue(registry.computeContentRevision("tableA") > first);
        TargetRegistry replacement = TargetRegistry.create(new GraphSpec(Map.of(), List.of()), Map.of());
        assertEquals(0, replacement.computeContentRevision("tableA"));
    }
}
