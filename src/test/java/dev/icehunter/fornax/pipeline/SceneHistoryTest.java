package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.pack.graph.TargetBasis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-JVM bookkeeping for {@link SceneHistory} -- no GPU device touched, since {@link
 * SceneHistory#copyFinalColor} is the only device-coupled member and is build+deploy-verified
 * only, exactly like every other {@code com.mojang.blaze3d}-touching call in this package.
 */
class SceneHistoryTest {
    @Test
    void specDeclaresTheEngineOwnedHistoryTarget() {
        TargetSpec spec = SceneHistory.spec();
        assertEquals("sceneHistory", spec.name());
        assertEquals("sceneHistory", SceneHistory.TARGET);
        assertEquals("rgba8", spec.format());
        assertEquals(1.0, spec.scale());
        assertTrue(spec.history());
        assertNull(spec.enabledIf(), "sceneHistory must exist under every method, never enabled_if-gated");
        assertEquals(TargetBasis.OUTPUT, spec.basis(),
                "sceneHistory must always hold native detail regardless of render resolution");
    }

    @Test
    void injectIntoAddsTheTargetToAnEmptyGraph() {
        GraphSpec graph = new GraphSpec(Map.of(), List.of());
        GraphSpec injected = SceneHistory.injectInto(graph);
        assertEquals(1, injected.targets().size());
        assertTrue(injected.targets().containsKey(SceneHistory.TARGET));
    }

    @Test
    void injectIntoPreservesExistingTargetsAndPasses() {
        TargetSpec ssr = new TargetSpec("ssr", "rgba16f", 1.0, true, null);
        PassSpec resolve = new PassSpec("resolve", PassType.FULLSCREEN, null, null, "shaders/post/resolve.fsh",
                List.of("builtin.depth"), List.of("builtin.output"), null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(Map.of("ssr", ssr), List.of(resolve));

        GraphSpec injected = SceneHistory.injectInto(graph);

        assertEquals(2, injected.targets().size());
        assertSame(ssr, injected.targets().get("ssr"));
        assertEquals(List.of(resolve), injected.passes());
    }

    @Test
    void injectIntoNeverDuplicatesAcrossRepeatedCalls() {
        // Mirrors production: GraphRunner.rebuild calls this on every (re)build, so a graph that
        // already carries the entry (every rebuild after the pack's first) must come back
        // unchanged rather than growing a second sceneHistory entry.
        GraphSpec original = new GraphSpec(Map.of(), List.of());
        GraphSpec once = SceneHistory.injectInto(original);
        GraphSpec twice = SceneHistory.injectInto(once);

        assertEquals(1, twice.targets().size());
        assertTrue(twice.targets().containsKey(SceneHistory.TARGET));
    }

    @Test
    void injectIntoIsANoOpOnceAlreadyPresent() {
        GraphSpec original = new GraphSpec(Map.of(), List.of());
        GraphSpec once = SceneHistory.injectInto(original);
        GraphSpec twice = SceneHistory.injectInto(once);

        // Not just "still one entry" (the test above) -- the second call must not even allocate a
        // new GraphSpec/TargetSpec when there's nothing to add.
        assertSame(once, twice);
    }
}
