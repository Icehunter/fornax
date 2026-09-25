package dev.icehunter.fornax.rt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rows the cascade publishes, pinned by name because a profiler row is read by a person
 * comparing two sessions: renaming one silently breaks that comparison, and adding one that is
 * never recorded leaves a reader concluding a tier did nothing.
 */
class RayRouterProfilerContractTest {

    private Map<String, Double> rows;
    private List<String> order;

    @BeforeEach
    void setUp() {
        rows = new LinkedHashMap<>();
        order = new ArrayList<>();
        RayRouter.profiler((label, value) -> {
            rows.put(label, value);
            order.add(label);
        });
        RayRouter.tierFloor(RayTier.NONE);
    }

    @AfterEach
    void tearDown() {
        RayRouter.close();
        RayRouter.tierFloor(RayTier.NONE);
        RayRouter.profiler(null);
    }

    @Test
    void everyInstalledTierPublishesItsReadinessAndTheFrameItsTotalCost() {
        RayRouter.install(List.of(new StubProvider(RayTier.HARDWARE_MESH),
                new StubProvider(RayTier.HARDWARE_VOXEL)));
        RayRouter.beginFrame();

        assertTrue(rows.containsKey("RT cascade CPU"), "the frame total must be published");
        assertEquals(1.0, rows.get("rt_tier3_ready"));
        assertEquals(1.0, rows.get("rt_tier2_ready"));
        assertFalse(rows.containsKey("rt_tier1_ready"),
                "a tier that is not installed publishes nothing rather than a zero that reads as "
                        + "'present but idle'");
    }

    /** One row per tier that actually ran, so a slow tier can be told from a slow frame. */
    @Test
    void eachTierThatRunsPublishesItsOwnCost() {
        RayRouter.install(List.of(new StubProvider(RayTier.HARDWARE_MESH),
                new StubProvider(RayTier.HARDWARE_VOXEL)));
        RayRouter.beginFrame();
        rows.clear();

        RayRouter.phaseOne(fill());
        assertTrue(rows.containsKey("RT tier3 CPU"));
        assertTrue(rows.containsKey("RT tier2 CPU"), "every tier traces in one phase");

        RayRouter.answer(new BufferQuery(RayQueryKind.VISIBILITY, 1L, 2L, 8, "test_pass"));
        assertTrue(rows.containsKey("RT tier3 query CPU"));
        assertTrue(rows.containsKey("RT tier2 query CPU"),
                "buffer queries are timed separately: they trace a pack's own rays, not the "
                        + "engine's, and their cost belongs to the pack's pass");
    }

    /** A tier that declined publishes no cost row, so a zero never means "ran and was free". */
    @Test
    void aTierThatWasNotReadyPublishesNoCostRow() {
        StubProvider provider = new StubProvider(RayTier.HARDWARE_MESH);
        provider.readiness = RayReadiness.notReady("nothing built this frame");
        RayRouter.install(List.of(provider));
        RayRouter.beginFrame();
        rows.clear();

        RayRouter.phaseOne(fill());

        assertFalse(rows.containsKey("RT tier3 CPU"));
    }

    private static CelestialFill fill() {
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        return new CelestialFill(identity, identity, 0, 0, 0, 8, 32f, 0f, 0f);
    }

    private static final class StubProvider implements RayProvider {
        private final RayTier tier;
        RayReadiness readiness = RayReadiness.answering();

        StubProvider(RayTier tier) {
            this.tier = tier;
        }

        @Override
        public RayTier tier() {
            return tier;
        }

        @Override
        public boolean answers(RayQueryKind kind) {
            return true;
        }

        @Override
        public RayReadiness readiness() {
            return readiness;
        }

        @Override
        public void fillCelestialVisibility(CelestialFill request) {
        }

        @Override
        public void answer(BufferQuery query) {
        }

        @Override
        public void close() {
        }
    }
}
