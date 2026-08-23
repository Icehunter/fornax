package dev.icehunter.fornax.voxel;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelWindowTest {
    @Test
    void slotIsMinusOneOutsideTheCurrentWindow() {
        VoxelWindow.recenter(0, 4, 0, 2);
        assertEquals(-1, VoxelWindow.slotFor(100, 4, 100), "far outside the window radius");
    }

    @Test
    void slotIsValidInsideTheCurrentWindow() {
        VoxelWindow.recenter(0, 4, 0, 2);
        assertNotEquals(-1, VoxelWindow.slotFor(0, 4, 0), "the center section must always be in-window");
    }

    @Test
    void recenteringInvalidatesSlotsThatFallOutOfRange() {
        VoxelWindow.recenter(0, 4, 0, 2);
        int slotBefore = VoxelWindow.slotFor(0, 4, 0);
        VoxelWindow.recenter(50, 4, 50, 2);
        assertEquals(-1, VoxelWindow.slotFor(0, 4, 0), "old center should fall outside the new window");
        assertNotEquals(-1, VoxelWindow.slotFor(50, 4, 50), "new center must be in-window");
    }

    @Test
    void toroidalWraparoundMapsDistinctSectionsToDistinctSlotsWithinOneWindow() {
        VoxelWindow.recenter(0, 4, 0, 2);
        int slotA = VoxelWindow.slotFor(-1, 4, -1);
        int slotB = VoxelWindow.slotFor(1, 4, 1);
        assertNotEquals(slotA, slotB, "two distinct in-window sections must not collide onto the same slot");
    }

    // --- shell-delta enumeration (the resyncPending -> recenterAndResync rescan fix) ---------------
    //
    // enumerateResyncShell is the pure, Level-free seam that decides WHICH section positions a move
    // reharvests. Its contract is exact: visit every position in (new cube \ old cube) once and only
    // once on an incremental move, and the whole new cube once on a large-jump / radius-change / first
    // -enable fallback. Every assertion below compares against a brute-force set so a missed or
    // double-visited slot (which would silently corrupt what the shader renders) fails the test.

    /** Runs the production enumeration and records every visited position as an "x,y,z" key. */
    private static List<String> collect(int ocx, int ocy, int ocz, int or,
                                        int ncx, int ncy, int ncz, int nr) {
        List<String> visited = new ArrayList<>();
        VoxelWindow.enumerateResyncShell(ocx, ocy, ocz, or, ncx, ncy, ncz, nr,
                (x, y, z) -> visited.add(x + "," + y + "," + z));
        return visited;
    }

    /** Every section position inside the axis-aligned cube of the given center and radius. */
    private static Set<String> cube(int cx, int cy, int cz, int r) {
        Set<String> out = new HashSet<>();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = cy - r; y <= cy + r; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    out.add(x + "," + y + "," + z);
                }
            }
        }
        return out;
    }

    /** Brute-force set-difference (new cube minus old cube) -- the exact shell a move should reharvest. */
    private static Set<String> shellDifference(int ocx, int ocy, int ocz, int oc,
                                               int ncx, int ncy, int ncz, int nr) {
        Set<String> diff = cube(ncx, ncy, ncz, nr);
        diff.removeAll(cube(ocx, ocy, ocz, oc));
        return diff;
    }

    private static void assertNoDuplicates(List<String> visited) {
        assertEquals(visited.size(), new HashSet<>(visited).size(),
                "no section position may be visited (harvested) twice");
    }

    @Test
    void singleAxisMoveVisitsExactlyTheOneNewlyExposedFace() {
        // radius 2 -> diameter 5; moving center +1 on X exposes exactly one 5x5 face (25 sections).
        List<String> visited = collect(0, 0, 0, 2, 1, 0, 0, 2);
        assertNoDuplicates(visited);
        assertEquals(shellDifference(0, 0, 0, 2, 1, 0, 0, 2), new HashSet<>(visited),
                "a one-section X move must reharvest exactly the new +X face, nothing interior");
        assertEquals(25, visited.size(), "one 5x5 face for a diameter-5 window");
    }

    @Test
    void diagonalMoveVisitsTheUnionOfExposedFacesWithoutDoubleCountingTheEdge() {
        // Moving (0,0,0)->(1,1,1) exposes three faces that share edges/a corner; the decomposition
        // must not double-visit the shared cells.
        List<String> visited = collect(0, 0, 0, 2, 1, 1, 1, 2);
        assertNoDuplicates(visited);
        assertEquals(shellDifference(0, 0, 0, 2, 1, 1, 1, 2), new HashSet<>(visited),
                "diagonal move must cover the exact set difference with no overlap between slabs");
    }

    @Test
    void interiorSectionsThatStayInWindowAreNeverRevisited() {
        List<String> visited = collect(0, 0, 0, 2, 1, 0, 0, 2);
        Set<String> stillInWindow = cube(0, 0, 0, 2);
        stillInWindow.retainAll(cube(1, 0, 0, 2)); // sections in BOTH old and new window
        for (String kept : stillInWindow) {
            assertTrue(!visited.contains(kept),
                    "section " + kept + " stayed in-window; its data is still valid and must not be reharvested");
        }
    }

    @Test
    void moveOfExactlyOneDiameterFallsBackToFullCube() {
        // diameter 5: a move of 5 on any axis leaves no overlap, so the whole new cube is stale.
        List<String> visited = collect(0, 0, 0, 2, 5, 0, 0, 2);
        assertNoDuplicates(visited);
        assertEquals(cube(5, 0, 0, 2), new HashSet<>(visited), "diameter-sized move must full-scan the new cube");
        assertEquals(125, visited.size(), "5^3 positions");
    }

    @Test
    void largeTeleportFallsBackToFullCube() {
        List<String> visited = collect(0, 0, 0, 2, 100, 100, 100, 2);
        assertNoDuplicates(visited);
        assertEquals(cube(100, 100, 100, 2), new HashSet<>(visited), "a teleport must full-scan the new cube");
    }

    @Test
    void radiusChangeFallsBackToFullCubeEvenWhenCentersMatch() {
        // Radius change reshuffles every toroidal slot index, so old data is meaningless -> full scan.
        List<String> visited = collect(0, 0, 0, 2, 0, 0, 0, 3);
        assertNoDuplicates(visited);
        assertEquals(cube(0, 0, 0, 3), new HashSet<>(visited), "radius change must full-scan the new (larger) cube");
        assertEquals(7 * 7 * 7, visited.size(), "diameter 7 cubed");
    }

    @Test
    void neverCenteredSentinelFullScansTheWholeInitialWindow() {
        // First enable: old radius 0 (sentinel) differs from the real radius -> full scan populates all.
        List<String> visited = collect(0, 0, 0, 0, 8, 64, 8, 2);
        assertNoDuplicates(visited);
        assertEquals(cube(8, 64, 8, 2), new HashSet<>(visited), "first enable must harvest the entire initial window");
    }

    // --- nearest-camera-first full-scan ordering (the debug-view "build from the bottom up" fix) ----
    //
    // The full-scan fallback (first-enable, radius change, large jump) must now visit the camera's own
    // section first, then each Chebyshev (L-infinity) shell outward. These tests assert BOTH the new
    // ordering property AND that the total visited set is still exactly the full cube, once -- so the
    // reorder cannot silently drop or duplicate a section relative to the old bottom-up sweep.

    /** Chebyshev (L-infinity) distance of an "x,y,z" key from a center. */
    private static int chebyshev(int cx, int cy, int cz, String key) {
        String[] parts = key.split(",");
        int dx = Math.abs(Integer.parseInt(parts[0]) - cx);
        int dy = Math.abs(Integer.parseInt(parts[1]) - cy);
        int dz = Math.abs(Integer.parseInt(parts[2]) - cz);
        return Math.max(dx, Math.max(dy, dz));
    }

    /** Asserts a full-scan visit sequence is center-first, shell-monotonic, and covers the exact cube. */
    private static void assertNearestFirstFullCube(List<String> visited, int cx, int cy, int cz, int r) {
        assertNoDuplicates(visited);
        assertEquals(cube(cx, cy, cz, r), new HashSet<>(visited),
                "full scan must still visit the entire cube exactly once");
        assertEquals(cx + "," + cy + "," + cz, visited.get(0),
                "the camera's own section must be visited first");
        int prev = -1;
        for (String key : visited) {
            int d = chebyshev(cx, cy, cz, key);
            assertTrue(d >= prev,
                    "Chebyshev distance must be non-decreasing (shell " + d + " appeared after shell " + prev + ")");
            prev = d;
        }
    }

    @Test
    void firstEnableFullScanVisitsCameraSectionFirstThenExpandsOutward() {
        // Sentinel oldRadius 0 != real radius -> full scan. Player at a realistic height.
        List<String> visited = collect(0, 0, 0, 0, 8, 64, 8, 3);
        assertNearestFirstFullCube(visited, 8, 64, 8, 3);
        assertEquals(7 * 7 * 7, visited.size(), "diameter-7 cube");
    }

    @Test
    void radiusChangeFullScanIsAlsoNearestFirst() {
        // Radius change is a full-scan trigger too -- the fix applies to it, not just first-enable.
        List<String> visited = collect(0, 0, 0, 2, 0, 0, 0, 4);
        assertNearestFirstFullCube(visited, 0, 0, 0, 4);
    }

    @Test
    void largeTeleportFullScanIsAlsoNearestFirst() {
        // A jump >= one diameter is the third full-scan trigger; same nearest-first rationale applies.
        List<String> visited = collect(0, 0, 0, 2, 500, 70, -300, 3);
        assertNearestFirstFullCube(visited, 500, 70, -300, 3);
    }

    @Test
    void nearestFirstShellsPartitionTheFullCubeAcrossManyRadii() {
        // Property sweep: for every radius, the center-first + concentric-shell decomposition must be a
        // gap-free, duplicate-free partition of the full cube, and strictly shell-monotonic. This is the
        // r=1 edge case (oldRadius 0 = single-point cube for the first shell) verified up through r=6.
        for (int r = 0; r <= 6; r++) {
            // A teleport of 1000 on X (>= any diameter here) forces the full-scan branch for every r,
            // including r=0 where oldRadius==newRadius would otherwise take the incremental (empty) path.
            List<String> visited = collect(0, 0, 0, r, 1000, 0, 0, r);
            assertNearestFirstFullCube(visited, 1000, 0, 0, r);
            assertEquals((2 * r + 1) * (2 * r + 1) * (2 * r + 1), visited.size(),
                    "r=" + r + " full cube size");
        }
    }

    // --- occupancy-clear bookkeeping (livefix7: synchronous slot-staleness fix) ---------------------
    //
    // exposedSlots is the pure seam VoxelWindow.recenterAndResync uses to decide which toroidal slots
    // to occupancy-clear synchronously on recenter. It must equal the same shell
    // enumerateResyncShell visits, mapped through the NEW window's own slotFor -- these tests assert
    // that equality directly against a brute-force recomputation via the public slotFor API, so a
    // mismatch between what gets cleared and what the DDA actually reads would fail here.

    @Test
    void exposedSlotsEqualsTheShellDifferenceMappedThroughTheNewWindow() {
        VoxelWindow.WindowState previous = VoxelWindow.WindowState.of(0, 0, 0, 2);
        VoxelWindow.WindowState next = VoxelWindow.WindowState.of(1, 0, 0, 2);

        Set<Integer> exposed = VoxelWindow.exposedSlots(previous, next);

        VoxelWindow.recenter(next.centerX(), next.centerY(), next.centerZ(), next.radius());
        Set<Integer> expected = new HashSet<>();
        for (String key : shellDifference(0, 0, 0, 2, 1, 0, 0, 2)) {
            String[] parts = key.split(",");
            int slot = VoxelWindow.slotFor(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (slot >= 0) {
                expected.add(slot);
            }
        }
        assertEquals(expected, exposed, "occupancy-clear slot set must equal the harvested-shell slot set");
        assertEquals(25, exposed.size(), "one 5x5 face for a diameter-5 window, all distinct slots");
    }

    @Test
    void exposedSlotsIsEmptyWhenTheWindowDoesNotMove() {
        VoxelWindow.WindowState state = VoxelWindow.WindowState.of(5, 60, 5, 2);
        assertTrue(VoxelWindow.exposedSlots(state, state).isEmpty(), "no move -> nothing newly exposed, nothing to clear");
    }

    @Test
    void exposedSlotsOnFullScanFallbackCoversEveryDistinctSlotInTheNewWindow() {
        // Radius change forces the full-scan fallback; every position in the new cube maps to a slot,
        // and the toroidal invariant guarantees a bijection within one full window cube -- so the set
        // size must equal the cube's volume exactly, not merely be non-empty.
        VoxelWindow.WindowState previous = VoxelWindow.WindowState.of(0, 0, 0, 2);
        VoxelWindow.WindowState next = VoxelWindow.WindowState.of(0, 0, 0, 3);
        Set<Integer> exposed = VoxelWindow.exposedSlots(previous, next);
        assertEquals(7 * 7 * 7, exposed.size(), "diameter 7 cubed, all distinct slots");
    }

    @Test
    void everySmallMoveExactlyEqualsTheBruteForceDifference() {
        // Property sweep across radii and move vectors within one diameter: the enumeration must always
        // equal the mathematical set difference, with no duplicates. Deltas are radius-relative (-2r..2r,
        // not a fixed range) so the sweep reaches the thin-overlap moves near the diameter boundary for
        // every radius tested, not just r=1.
        int[] radii = {1, 2, 3};
        for (int r : radii) {
            int bound = 2 * r;
            for (int dx = -bound; dx <= bound; dx++) {
                for (int dy = -bound; dy <= bound; dy++) {
                    for (int dz = -bound; dz <= bound; dz++) {
                        List<String> visited = collect(0, 0, 0, r, dx, dy, dz, r);
                        assertNoDuplicates(visited);
                        assertEquals(shellDifference(0, 0, 0, r, dx, dy, dz, r), new HashSet<>(visited),
                                "r=" + r + " move=(" + dx + "," + dy + "," + dz + ") must equal the exact shell");
                    }
                }
            }
        }
    }

    // --- harvest-throughput fix: priority ordering (isFront / squaredDistance / priorityComparator) --
    //
    // Pure, GPU-free/Level-free math that decides which slots recenterAndResync harvests SYNCHRONOUSLY
    // (the SYNC_BUDGET-highest-priority ones) vs. defers to RESYNC_EXECUTOR. These tests exercise the
    // exact same package-private methods recenterAndResync itself calls -- not a parallel reimplementation.

    @Test
    void isFrontIsTrueForTheCamerasOwnSectionRegardlessOfForwardDirection() {
        // Zero delta -> dot product 0 -> classified front (>= 0), so the camera's own section is never
        // deprioritized behind anything else, whatever direction the player happens to be looking.
        assertTrue(VoxelWindow.isFront(SectionPos.of(5, 6, 7), 5, 6, 7, 1.0f, 0.0f, 0.0f));
        assertTrue(VoxelWindow.isFront(SectionPos.of(5, 6, 7), 5, 6, 7, 0.0f, 0.0f, 0.0f));
    }

    @Test
    void isFrontIsTrueDirectlyAheadOfTheCamera() {
        // Camera looking down +X; a section at +X from center is directly ahead.
        assertTrue(VoxelWindow.isFront(SectionPos.of(10, 0, 0), 0, 0, 0, 1.0f, 0.0f, 0.0f));
    }

    @Test
    void isFrontIsFalseDirectlyBehindTheCamera() {
        // Camera looking down +X; a section at -X from center is directly behind.
        assertFalse(VoxelWindow.isFront(SectionPos.of(-10, 0, 0), 0, 0, 0, 1.0f, 0.0f, 0.0f));
    }

    @Test
    void isFrontIsTrueForAnExactlyPerpendicularSection() {
        // Dot product exactly 0 (perpendicular to the look direction) counts as front -- the coarse
        // hemisphere test is deliberately a SUPERSET of the real view frustum, never a subset.
        assertTrue(VoxelWindow.isFront(SectionPos.of(0, 10, 0), 0, 0, 0, 1.0f, 0.0f, 0.0f));
    }

    @Test
    void isFrontDegradesGracefullyForAZeroForwardVector() {
        // A zero-length forward vector (e.g. before the camera ever supplies a real one) makes every
        // dot product 0 -> every slot "front" -> priorityComparator falls back to nearest-first only.
        assertTrue(VoxelWindow.isFront(SectionPos.of(50, -30, 12), 0, 0, 0, 0.0f, 0.0f, 0.0f));
    }

    @Test
    void squaredDistanceMatchesPlainEuclideanMath() {
        assertEquals(0L, VoxelWindow.squaredDistance(SectionPos.of(3, 4, 5), 3, 4, 5));
        assertEquals(1L, VoxelWindow.squaredDistance(SectionPos.of(4, 4, 5), 3, 4, 5));
        // 3-4-5 right triangle in the XZ plane -> squared distance 25.
        assertEquals(25L, VoxelWindow.squaredDistance(SectionPos.of(3 + 3, 4, 5 + 4), 3, 4, 5));
    }

    @Test
    void priorityComparatorSortsFrontSlotsBeforeBackSlotsEvenWhenFartherAway() {
        SectionPos farFront = SectionPos.of(20, 0, 0);   // ahead, far
        SectionPos nearBack = SectionPos.of(-1, 0, 0);   // behind, near
        List<SectionPos> shell = new ArrayList<>(List.of(nearBack, farFront));
        shell.sort(VoxelWindow.priorityComparator(0, 0, 0, 1.0f, 0.0f, 0.0f));
        assertEquals(List.of(farFront, nearBack), shell,
                "a front slot must sort before a back slot even though it is farther away");
    }

    @Test
    void priorityComparatorSortsNearestFirstWithinTheSameFrontOrBackGroup() {
        SectionPos near = SectionPos.of(2, 0, 0);
        SectionPos far = SectionPos.of(10, 0, 0);
        List<SectionPos> shell = new ArrayList<>(List.of(far, near));
        shell.sort(VoxelWindow.priorityComparator(0, 0, 0, 1.0f, 0.0f, 0.0f));
        assertEquals(List.of(near, far), shell, "within the same front/back group, nearer sorts first");
    }

    @Test
    void priorityComparatorOnARealShellIsALosslessReorderingThatIsFrontThenDistanceMonotonic() {
        // Sort a real enumerateResyncShell output (the diagonal-move shell used elsewhere in this
        // suite) and assert the two structural guarantees the harvest-throughput fix depends on:
        // (1) nothing is lost or duplicated by sorting (same multiset), and (2) no back-facing slot
        // ever precedes a front-facing one, with squared distance non-decreasing inside each group.
        List<SectionPos> shell = new ArrayList<>();
        VoxelWindow.enumerateResyncShell(0, 0, 0, 2, 1, 1, 1, 2,
                (x, y, z) -> shell.add(SectionPos.of(x, y, z)));
        Set<SectionPos> beforeSort = new HashSet<>(shell);

        float fx = 1.0f, fy = 0.3f, fz = -0.5f;
        int cx = 1, cy = 1, cz = 1;
        shell.sort(VoxelWindow.priorityComparator(cx, cy, cz, fx, fy, fz));

        assertEquals(beforeSort, new HashSet<>(shell), "sorting must not lose or duplicate any position");
        assertEquals(beforeSort.size(), shell.size(), "sorting must not lose or duplicate any position (size check)");

        boolean sawBack = false;
        long prevDistInGroup = -1;
        Boolean prevFront = null;
        for (SectionPos p : shell) {
            boolean front = VoxelWindow.isFront(p, cx, cy, cz, fx, fy, fz);
            if (!front) {
                sawBack = true;
            }
            assertFalse(front && sawBack, "a front slot must never appear after a back slot has been seen");
            long dist = VoxelWindow.squaredDistance(p, cx, cy, cz);
            if (prevFront != null && prevFront == front) {
                assertTrue(dist >= prevDistInGroup,
                        "squared distance must be non-decreasing within the same front/back group");
            }
            prevDistInGroup = dist;
            prevFront = front;
        }
    }

    // --- populationFraction (voxel-window streaming telemetry) -------------------------------------
    //
    // Pure math pulled out of the live populatedSlots/state counters specifically so it can be tested
    // without touching this class's global static state (see the method's own doc) -- these tests
    // exercise ONLY the arithmetic, not the counter wiring in onSectionHarvested/recenterAndResync.

    @Test
    void populationFractionIsPopulatedOverDiameterCubed() {
        int diameter = 5; // 125 total slots
        assertEquals(40.0 / 125.0, VoxelWindow.populationFraction(40, diameter), 1e-9);
    }

    @Test
    void populationFractionIsZeroWhenNothingIsPopulatedYet() {
        assertEquals(0.0, VoxelWindow.populationFraction(0, 7), 1e-9);
    }

    @Test
    void populationFractionIsOneWhenFullyPopulated() {
        int diameter = 3; // 27 total slots
        assertEquals(1.0, VoxelWindow.populationFraction(27, diameter), 1e-9);
    }

    @Test
    void populationFractionIsZeroForTheNeverCenteredSentinelDiameter() {
        // WindowState.of's diameter is always >= 1 in practice (2*radius+1, radius >= 0), but a
        // degenerate/zero diameter must degrade to 0.0 rather than divide-by-zero (NaN/Infinity would
        // render as garbage on the HUD).
        assertEquals(0.0, VoxelWindow.populationFraction(0, 0), 1e-9);
        assertEquals(0.0, VoxelWindow.populationFraction(5, 0), 1e-9, "even a nonsensical positive count over a zero diameter must not divide by zero");
    }

    @Test
    void populationFractionHandlesASinglePointWindow() {
        // diameter 1 (radius 0): 1 total slot.
        assertEquals(1.0, VoxelWindow.populationFraction(1, 1), 1e-9);
        assertEquals(0.0, VoxelWindow.populationFraction(0, 1), 1e-9);
    }

    // --- BatchSizeController (batch-upload-throughput fix: batched harvest uploads) ----------------
    //
    // Pure arithmetic (no GPU, no static VoxelWindow state) that decides how large the NEXT batch
    // harvestAndUploadBatch accumulates before flushing, from the MEASURED wall-clock cost of the
    // batch just flushed -- "budget-bounded by measured milliseconds, not slot count" per the fix's
    // own brief. Exercises the exact same package-private class recenterAndResync's harvest paths use.

    @Test
    void batchSizeControllerStartsAtTheConservativeInitialTarget() {
        VoxelWindow.BatchSizeController controller = new VoxelWindow.BatchSizeController();
        assertEquals(VoxelWindow.BatchSizeController.INITIAL_TARGET, controller.currentTargetSize(),
                "before any real measurement, the target must be the conservative initial guess");
    }

    @Test
    void recordBatchProjectsTheNextTargetFromMeasuredNanosPerSlot() {
        VoxelWindow.BatchSizeController controller = new VoxelWindow.BatchSizeController();
        // 100 slots measured at 1,000,000ns total -> 10,000ns/slot -> 4,000,000 / 10,000 = 400.
        controller.recordBatch(100, 1_000_000L);
        assertEquals(400, controller.currentTargetSize(),
                "projected target = time budget / measured per-slot cost");
    }

    @Test
    void recordBatchClampsAnExpensiveMeasurementToTheFloor() {
        VoxelWindow.BatchSizeController controller = new VoxelWindow.BatchSizeController();
        // 1 slot measured at 1 whole second -> a projected target far below MIN_TARGET must clamp up,
        // not flush a batch of zero or a negative size.
        controller.recordBatch(1, 1_000_000_000L);
        assertEquals(VoxelWindow.BatchSizeController.MIN_TARGET, controller.currentTargetSize(),
                "an extremely expensive measured batch must still clamp to the floor, never below it");
    }

    @Test
    void recordBatchClampsACheapMeasurementToTheCeiling() {
        VoxelWindow.BatchSizeController controller = new VoxelWindow.BatchSizeController();
        // 1000 slots measured at 1ns total -> a projected target far above MAX_TARGET must clamp down,
        // bounding one command buffer's embedded byte count regardless of how favorable the measured
        // cost looks.
        controller.recordBatch(1000, 1L);
        assertEquals(VoxelWindow.BatchSizeController.MAX_TARGET, controller.currentTargetSize(),
                "an extremely cheap measured batch must still clamp to the ceiling, never above it");
    }

    @Test
    void recordBatchIgnoresNonPositiveInputsAndLeavesTheTargetUnchanged() {
        VoxelWindow.BatchSizeController controller = new VoxelWindow.BatchSizeController();
        controller.recordBatch(0, 1_000_000L);
        controller.recordBatch(10, 0L);
        controller.recordBatch(-5, 1_000_000L);
        controller.recordBatch(10, -1_000_000L);
        assertEquals(VoxelWindow.BatchSizeController.INITIAL_TARGET, controller.currentTargetSize(),
                "a degenerate measurement (zero/negative size or elapsed time) must not perturb the target");
    }

    @Test
    void batchSizeControllerFastRampsFromTheFixedFenceOverheadAcrossSuccessiveFlushes() {
        // Simulates the real scenario this controller exists for: an initial-fill queue of thousands
        // of slots, where each flush's cost is dominated by a ~0.2ms FIXED per-submit overhead (see
        // SYNC_BUDGET's own derivation) with a small, roughly constant per-slot marginal cost. As the
        // controller discovers a larger batch barely costs more than a small one (the fixed cost is
        // amortized), the target should ramp UP monotonically flush over flush until it saturates at
        // MAX_TARGET -- never oscillate back down while the measured per-slot cost keeps improving.
        VoxelWindow.BatchSizeController controller = new VoxelWindow.BatchSizeController();
        long fixedOverheadNanos = 200_000L; // ~0.2ms
        long perSlotMarginalNanos = 200L;   // small, per-slot recording cost

        int previousTarget = controller.currentTargetSize();
        boolean reachedCeiling = false;
        for (int i = 0; i < 8; i++) {
            int batchSize = previousTarget;
            long elapsed = fixedOverheadNanos + (long) batchSize * perSlotMarginalNanos;
            controller.recordBatch(batchSize, elapsed);
            int nextTarget = controller.currentTargetSize();
            assertTrue(nextTarget >= previousTarget,
                    "target must never shrink while the fixed overhead keeps getting amortized over more slots");
            assertTrue(nextTarget >= VoxelWindow.BatchSizeController.MIN_TARGET
                            && nextTarget <= VoxelWindow.BatchSizeController.MAX_TARGET,
                    "target must always stay within [MIN_TARGET, MAX_TARGET]");
            if (nextTarget == VoxelWindow.BatchSizeController.MAX_TARGET) {
                reachedCeiling = true;
            }
            previousTarget = nextTarget;
        }
        assertTrue(reachedCeiling, "a fixed-overhead-dominated cost model must ramp all the way to MAX_TARGET "
                + "within a handful of flushes -- this is the 'fast ramp during initial fill' property");
    }
}
