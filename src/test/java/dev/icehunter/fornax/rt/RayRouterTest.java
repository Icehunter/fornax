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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cascade's own rules, with fake providers over an {@code int[]} standing in for the celestial
 * image. Every property here is the router's, not a provider's: what order the tiers run in, which
 * of them run at all, and what a failure does to the rest of the walk.
 *
 * <p>No Metal, no device, no graph: this runs in CI, which is the point. The tiers this arranges
 * are the real enum, so a reordered {@link RayTier} shows up here as a wrong walk order.
 */
class RayRouterTest {

    /** Cells a fake writes into. Zero is unanswered, exactly as the A channel is in the real image. */
    private int[] image;
    private List<String> order;
    private Map<String, Double> rows;

    @BeforeEach
    void setUp() {
        image = new int[8];
        order = new ArrayList<>();
        rows = new LinkedHashMap<>();
        RayRouter.profiler(rows::put);
        RayRouter.tierFloor(RayTier.NONE);
    }

    @AfterEach
    void tearDown() {
        RayRouter.close();
        RayRouter.tierFloor(RayTier.NONE);
        RayRouter.profiler(null);
    }

    @Test
    void installOrdersProvidersByDescendingTierWhateverOrderTheyArrivedIn() {
        RayRouter.install(List.of(fake(RayTier.SOFTWARE_VOXEL), fake(RayTier.HARDWARE_MESH),
                fake(RayTier.HARDWARE_VOXEL)));

        List<RayTier> installed = RayRouter.installed().stream().map(RayProvider::tier).toList();
        assertEquals(List.of(RayTier.HARDWARE_MESH, RayTier.HARDWARE_VOXEL, RayTier.SOFTWARE_VOXEL),
                installed, "the walk is best-first, so the list is sorted once at install");
    }

    /**
     * The tier is what a pack compares and what a record carries. Two providers at one tier would
     * make the answer depend on list order, which is not something a pack can see or reason about.
     */
    @Test
    void twoProvidersAtTheSameTierAreRefusedAtInstallAndBothAreNamed() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RayRouter.install(List.of(fake(RayTier.HARDWARE_VOXEL), fake(RayTier.HARDWARE_VOXEL))));
        assertTrue(error.getMessage().contains("HARDWARE_VOXEL"), error.getMessage());
        assertTrue(error.getMessage().contains(FakeProvider.class.getName()),
                "the message must name the classes involved: " + error.getMessage());
    }

    /** Tier NONE is the value an unanswered record carries; a provider claiming it is a bug. */
    @Test
    void aProviderClaimingTierNoneIsRefused() {
        assertThrows(IllegalStateException.class, () -> RayRouter.install(List.of(fake(RayTier.NONE))));
    }

    @Test
    void anEmptyInstallAnswersNothingAndThrowsNothing() {
        RayRouter.install(List.of());
        RayRouter.beginFrame();
        RayRouter.phaseOne(fill());
        RayRouter.phaseOne(fill());
        RayRouter.answer(new BufferQuery(RayQueryKind.VISIBILITY, 1L, 2L, 4));

        assertEquals(0, image[0], "nothing installed writes nothing");
    }

    /**
     * Phase one is the exact-geometry tier alone, at the frame point where the terrain draw can
     * overlap it. Phase two is the approximate tiers, after this frame's grid is current. Running
     * a voxel tier in phase one would trace last frame's grid.
     */
    /**
     * Every tier traces in one phase, best first. They were split across two frame points to give
     * the approximate tiers a grid updated later in the frame, but that update runs after the pass
     * loop: they traced the previous frame's grid either way, and the split cost a frame of camera
     * lag in the published image for nothing.
     */
    @Test
    void oneFillRunsEveryTierBestFirst() {
        RayRouter.install(List.of(fake(RayTier.SOFTWARE_VOXEL), fake(RayTier.HARDWARE_VOXEL),
                fake(RayTier.HARDWARE_MESH)));
        RayRouter.beginFrame();

        RayRouter.phaseOne(fill());

        assertEquals(List.of("fill:HARDWARE_MESH", "fill:HARDWARE_VOXEL", "fill:SOFTWARE_VOXEL"), order);
    }

    /**
     * The cascade's whole point: a lower tier fills what the tiers above left alone and never
     * touches what they answered. The fakes enforce it the way the real providers do, by testing
     * the cell before writing, so what this pins is that the router runs them in the order that
     * makes the discipline meaningful.
     */
    @Test
    void aLowerTierFillsOnlyTheCellsTheTiersAboveLeftUnanswered() {
        FakeProvider mesh = fake(RayTier.HARDWARE_MESH);
        mesh.covers = cell -> cell < 2;
        FakeProvider voxel = fake(RayTier.HARDWARE_VOXEL);
        voxel.covers = cell -> cell < 5;
        RayRouter.install(List.of(voxel, mesh));
        RayRouter.beginFrame();

        RayRouter.phaseOne(fill());

        assertEquals(RayTier.HARDWARE_MESH.ordinal(), image[0]);
        assertEquals(RayTier.HARDWARE_MESH.ordinal(), image[1]);
        assertEquals(RayTier.HARDWARE_VOXEL.ordinal(), image[2]);
        assertEquals(RayTier.HARDWARE_VOXEL.ordinal(), image[4]);
        assertEquals(RayTier.NONE.ordinal(), image[5], "beyond every tier's coverage stays raster");
    }

    /**
     * Readiness is per frame, not permanent. A provider whose acceleration structure is still
     * building says so, sits out, and is asked again next frame.
     */
    @Test
    void aProviderThatIsNotReadyIsSkippedForTheFrameAndAskedAgainTheNext() {
        FakeProvider provider = fake(RayTier.HARDWARE_MESH);
        provider.readiness = RayReadiness.notReady("no acceleration structure");
        RayRouter.install(List.of(provider));

        RayRouter.beginFrame();
        RayRouter.phaseOne(fill());
        assertEquals(List.of(), order, "a provider that is not ready does not run");
        assertEquals(0.0, rows.get("rt_tier3_ready"));

        provider.readiness = RayReadiness.answering();
        RayRouter.beginFrame();
        RayRouter.phaseOne(fill());
        assertEquals(List.of("fill:HARDWARE_MESH"), order, "and is asked again the next frame");
        assertEquals(1.0, rows.get("rt_tier3_ready"));
    }

    /**
     * Readiness is asked once and not asked again, so a provider that only becomes ready later in
     * the frame is skipped for the whole of it. This is not a bug in the router, it is the rule
     * providers have to be written to: a tier reporting on state it receives mid-frame reports
     * "not ready" every frame and is never called, silently, while the tier below answers
     * everything. That defect shipped once and cost a session to find, so it is pinned here.
     */
    @Test
    void aProviderThatBecomesReadyAfterTheFrameBeganIsStillSkippedForThatFrame() {
        FakeProvider provider = fake(RayTier.HARDWARE_VOXEL);
        provider.readiness = RayReadiness.notReady("its input has not arrived yet");
        RayRouter.install(List.of(provider));

        RayRouter.beginFrame();
        provider.readiness = RayReadiness.answering();
        RayRouter.phaseOne(fill());

        assertEquals(List.of(), order,
                "the router asks once; turning ready afterwards does not take effect until the "
                        + "next frame");
    }

    /** A query before beginFrame has no readiness to go on, so no provider may run on it. */
    @Test
    void noProviderRunsBeforeTheFrameHasBegun() {
        RayRouter.install(List.of(fake(RayTier.HARDWARE_MESH)));
        RayRouter.phaseOne(fill());
        assertEquals(List.of(), order);
    }

    /**
     * The floor is how a pack says it would rather have raster than an approximation. A provider
     * below it is not asked for readiness either, so it costs nothing to have installed.
     */
    @Test
    void aProviderBelowTheTierFloorIsNeverAskedAndNeverRuns() {
        RayRouter.install(List.of(fake(RayTier.HARDWARE_MESH), fake(RayTier.HARDWARE_VOXEL),
                fake(RayTier.SOFTWARE_VOXEL)));
        RayRouter.tierFloor(RayTier.HARDWARE_VOXEL);

        RayRouter.beginFrame();
        RayRouter.phaseOne(fill());

        assertEquals(List.of("fill:HARDWARE_MESH", "fill:HARDWARE_VOXEL"), order);
        assertEquals(0.0, rows.get("rt_tier1_ready"), "a floored-out tier reports itself not ready");
    }

    /**
     * A traversal that threw has no state a later frame can trust, so the latch is for the pack
     * load rather than the frame. The walk continues: the tiers below it are unaffected by its
     * failure and are exactly the fallback the cascade exists to provide.
     */
    @Test
    void aThrowingProviderIsLatchedOutAndTheWalkContinuesToTheTierBelow() {
        FakeProvider mesh = fake(RayTier.HARDWARE_MESH);
        mesh.throwOnFill = true;
        FakeProvider voxel = fake(RayTier.HARDWARE_VOXEL);
        RayRouter.install(List.of(mesh, voxel));

        RayRouter.beginFrame();
        RayRouter.phaseOne(fill());

        assertTrue(RayRouter.hasFailed(RayTier.HARDWARE_MESH));
        assertFalse(RayRouter.hasFailed(RayTier.HARDWARE_VOXEL));
        assertEquals(RayTier.HARDWARE_VOXEL.ordinal(), image[0],
                "the tier below still answers in the same frame");

        order.clear();
        RayRouter.beginFrame();
        RayRouter.phaseOne(fill());
        assertEquals(List.of("fill:HARDWARE_VOXEL"), order,
                "the failed tier is not tried again; the tier below still runs");
        assertEquals(0.0, rows.get("rt_tier3_ready"));
    }

    /** Reinstalling is what a pack reload does, and it clears the latch a failure set. */
    @Test
    void installingAgainClearsAFailureLatchAndClosesWhatWasInstalledBefore() {
        FakeProvider first = fake(RayTier.HARDWARE_MESH);
        first.throwOnFill = true;
        RayRouter.install(List.of(first));
        RayRouter.beginFrame();
        RayRouter.phaseOne(fill());
        assertTrue(RayRouter.hasFailed(RayTier.HARDWARE_MESH));

        RayRouter.install(List.of(fake(RayTier.HARDWARE_MESH)));
        assertTrue(first.closed, "the old provider is closed, not leaked");
        assertFalse(RayRouter.hasFailed(RayTier.HARDWARE_MESH));
    }

    /** A provider that does not serve the kind sits the query out without being a failure. */
    @Test
    void aBufferQueryReachesOnlyTheProvidersThatAnswerItsKind() {
        FakeProvider visibilityOnly = fake(RayTier.HARDWARE_MESH);
        visibilityOnly.kinds = List.of(RayQueryKind.VISIBILITY);
        FakeProvider both = fake(RayTier.HARDWARE_VOXEL);
        RayRouter.install(List.of(visibilityOnly, both));
        RayRouter.beginFrame();

        RayRouter.answer(new BufferQuery(RayQueryKind.CLOSEST_HIT, 1L, 2L, 16));

        assertEquals(List.of("answer:HARDWARE_VOXEL"), order);
        assertFalse(RayRouter.hasFailed(RayTier.HARDWARE_MESH), "sitting out is not failing");
    }

    /** An empty batch is not an error and is not worth a dispatch. */
    @Test
    void aQueryWithNoRaysReachesNoProvider() {
        RayRouter.install(List.of(fake(RayTier.HARDWARE_MESH)));
        RayRouter.beginFrame();

        RayRouter.answer(new BufferQuery(RayQueryKind.VISIBILITY, 0L, 0L, 0));

        assertEquals(List.of(), order);
    }

    private FakeProvider fake(RayTier tier) {
        return new FakeProvider(tier);
    }

    private static CelestialFill fill() {
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        return new CelestialFill(identity, identity, 0, 0, 0, 8, 64f, 0f, 0f);
    }

    /** Writes its tier into any cell it covers that is still zero, which is the provider contract. */
    private final class FakeProvider implements RayProvider {
        private final RayTier tier;
        RayReadiness readiness = RayReadiness.answering();
        List<RayQueryKind> kinds = List.of(RayQueryKind.VISIBILITY, RayQueryKind.CLOSEST_HIT);
        java.util.function.IntPredicate covers = cell -> true;
        boolean throwOnFill;
        boolean closed;

        FakeProvider(RayTier tier) {
            this.tier = tier;
        }

        @Override
        public RayTier tier() {
            return tier;
        }

        @Override
        public boolean answers(RayQueryKind kind) {
            return kinds.contains(kind);
        }

        @Override
        public RayReadiness readiness() {
            return readiness;
        }

        @Override
        public void fillCelestialVisibility(CelestialFill request) {
            if (throwOnFill) {
                throw new IllegalStateException("fake traversal failure");
            }
            order.add("fill:" + tier);
            for (int cell = 0; cell < image.length; cell++) {
                if (image[cell] == 0 && covers.test(cell)) {
                    image[cell] = tier.ordinal();
                }
            }
        }

        @Override
        public void answer(BufferQuery query) {
            order.add("answer:" + tier);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
