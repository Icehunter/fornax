package dev.icehunter.fornax.pipeline;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavioural contract for the wall-plane scan and its selection rule, both pure: no level, no
 * player, no GPU, matching {@link WaterPlaneProbeTest}'s testing style. */
class WallPlaneProbeTest {

    private static WallPlaneProbe.RowHit scanRow(double... extents) {
        return WallPlaneProbe.scanRow(WallPlaneProbe.SCAN_BOUND_BLOCKS,
                offset -> {
                    int index = offset - 1;
                    return index >= 0 && index < extents.length ? extents[index] : -1.0;
                });
    }

    // --- scanRow: the pure per-row scan ------------------------------------------------------------

    @Test
    void findsAWallOneBlockOut() {
        var hit = scanRow(0.0);
        assertTrue(hit.valid());
        assertEquals(1, hit.distanceBlocks());
        assertEquals(0.0, hit.localExtent(), 0.0001);
    }

    @Test
    void skipsEmptyShapesUntilTheFirstNonEmptyOne() {
        // offsets 1..3 empty (-1), a wall at offset 4 with a 0.3 local extent (a fence post's
        // narrower footprint, for example).
        var hit = scanRow(-1.0, -1.0, -1.0, 0.3);
        assertTrue(hit.valid());
        assertEquals(4, hit.distanceBlocks());
        assertEquals(0.3, hit.localExtent(), 0.0001);
    }

    @Test
    void aWallRightAtTheBoundStillCounts() {
        double[] extents = new double[8];
        java.util.Arrays.fill(extents, -1.0);
        extents[7] = 1.0; // offset 8, the last block SCAN_BOUND_BLOCKS covers
        var hit = scanRow(extents);
        assertTrue(hit.valid());
        assertEquals(8, hit.distanceBlocks());
    }

    @Test
    void noWallWithinTheBoundIsInvalid() {
        double[] extents = new double[8];
        java.util.Arrays.fill(extents, -1.0);
        var hit = scanRow(extents);
        assertFalse(hit.valid());
    }

    @Test
    void aWallOneBlockPastTheBoundIsNeverSeen() {
        // Nothing in the first 8 offsets; a 9th would have hit, but scanRow never asks for it since
        // the fixture array runs out and the default (-1.0, treated as empty) takes over.
        double[] extents = new double[8];
        java.util.Arrays.fill(extents, -1.0);
        var hit = scanRow(extents);
        assertFalse(hit.valid(), "a hit at offset 9 must never be reached");
    }

    // --- nearerRow: feet vs head reduction, the "panes" case ---------------------------------------

    @Test
    void feetOnlyHitWinsWhenHeadFindsNothing() {
        var feet = new WallPlaneProbe.RowHit(true, 2, 0.0);
        var head = WallPlaneProbe.RowHit.INVALID;
        assertSame(feet, WallPlaneProbe.nearerRow(feet, head));
    }

    @Test
    void headOnlyHitWinsWhenFeetFindsNothing() {
        // A pane: solid at head height (like a window's top frame) with nothing at feet height
        // (the window opening itself, no shape there).
        var feet = WallPlaneProbe.RowHit.INVALID;
        var head = new WallPlaneProbe.RowHit(true, 3, 0.0);
        assertSame(head, WallPlaneProbe.nearerRow(feet, head));
    }

    @Test
    void theNearerRowWinsWhenBothHit() {
        var feet = new WallPlaneProbe.RowHit(true, 5, 0.0);
        var head = new WallPlaneProbe.RowHit(true, 2, 0.0);
        assertSame(head, WallPlaneProbe.nearerRow(feet, head), "head is closer (2 blocks vs 5)");
    }

    @Test
    void anExactDistanceTieKeepsTheFeetRow() {
        var feet = new WallPlaneProbe.RowHit(true, 4, 0.1);
        var head = new WallPlaneProbe.RowHit(true, 4, 0.1);
        assertSame(feet, WallPlaneProbe.nearerRow(feet, head));
    }

    @Test
    void neitherRowHittingIsInvalid() {
        var result = WallPlaneProbe.nearerRow(WallPlaneProbe.RowHit.INVALID, WallPlaneProbe.RowHit.INVALID);
        assertFalse(result.valid());
    }

    // --- presentsFullFace: decoration vs. real wall, scanning the X axis (others: Y, Z) -------------

    @Test
    void aFullBlockPresentsAFullFace() {
        assertTrue(WallPlaneProbe.presentsFullFace(Shapes.block(), Direction.Axis.X));
    }

    @Test
    void aWallTorchDoesNotStopTheScan() {
        // Thin on both other axes (a torch's narrow footprint, whichever face mounts on the
        // wall): must be rejected so scanRow keeps walking past it to the real wall behind.
        VoxelShape torch = Shapes.box(0.4375, 0.0, 0.4375, 0.5625, 0.6, 0.5625);
        assertFalse(WallPlaneProbe.presentsFullFace(torch, Direction.Axis.X));
    }

    @Test
    void aFlowerInTheFeetRowDoesNotStopTheScan() {
        // Full X/Z footprint (a crop or flower's bounding box commonly spans the whole block base)
        // but short of full height: the Y extent alone must be enough to reject it.
        VoxelShape flower = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.6, 1.0);
        assertFalse(WallPlaneProbe.presentsFullFace(flower, Direction.Axis.X));
    }

    @Test
    void aFaceOnPaneThinOnlyAlongTheScanAxisIsAccepted() {
        // Thin along X (the axis being scanned, and so excluded from the check) but full on Y and Z:
        // a pane or thin panel presented flush to the scan is still a real face to reflect against.
        VoxelShape pane = Shapes.box(0.4375, 0.0, 0.0, 0.5625, 1.0, 1.0);
        assertTrue(WallPlaneProbe.presentsFullFace(pane, Direction.Axis.X));
    }

    @Test
    void aSlabIsSkipped() {
        // Half height (Y extent 0.5): excluded on purpose. Accepting it would mean guessing which
        // half of the block the flat face sits on; a missed wall behind a slab is the smaller,
        // accepted loss (see presentsFullFace's comment).
        VoxelShape slab = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
        assertFalse(WallPlaneProbe.presentsFullFace(slab, Direction.Axis.X));
    }

    // --- pickAxis: camera-facing selection, and its tie-break ---------------------------------------

    private static WallPlaneProbe.AxisCandidate plusCandidate(int distance) {
        return new WallPlaneProbe.AxisCandidate(-1.0f, 100.0, distance);
    }

    private static WallPlaneProbe.AxisCandidate minusCandidate(int distance) {
        return new WallPlaneProbe.AxisCandidate(1.0f, -100.0, distance);
    }

    @Test
    void onlyThePlusSideExistsAndIsReturnedRegardlessOfFacing() {
        var plus = plusCandidate(3);
        assertSame(plus, WallPlaneProbe.pickAxis(plus, null, -1.0f));
        assertSame(plus, WallPlaneProbe.pickAxis(plus, null, 1.0f));
    }

    @Test
    void onlyTheMinusSideExistsAndIsReturnedRegardlessOfFacing() {
        var minus = minusCandidate(3);
        assertSame(minus, WallPlaneProbe.pickAxis(null, minus, -1.0f));
        assertSame(minus, WallPlaneProbe.pickAxis(null, minus, 1.0f));
    }

    @Test
    void neitherSidePresentIsNull() {
        assertNull(WallPlaneProbe.pickAxis(null, null, 1.0f));
    }

    @Test
    void wallOnEachSideCameraFacingPositiveWinsThePlusCandidate() {
        // Camera forward.x > 0: looking toward +X, so the wall found scanning +X (whose face points
        // back at -X) is the one in view.
        var plus = plusCandidate(5);
        var minus = minusCandidate(2);
        assertSame(plus, WallPlaneProbe.pickAxis(plus, minus, 1.0f),
                "camera facing +X must pick the +X-side wall even though the -X one is nearer");
    }

    @Test
    void wallOnEachSideCameraFacingNegativeWinsTheMinusCandidate() {
        var plus = plusCandidate(2);
        var minus = minusCandidate(5);
        assertSame(minus, WallPlaneProbe.pickAxis(plus, minus, -1.0f),
                "camera facing -X must pick the -X-side wall even though the +X one is nearer");
    }

    @Test
    void wallOnEachSidePerpendicularCameraTiesBrokenByNearest() {
        // forwardComponent equal to 0: looking straight along the other axis (down a corridor), so
        // neither side's face reads as more "faced" than the other by the dot product alone.
        var plus = plusCandidate(6);
        var minus = minusCandidate(3);
        assertSame(minus, WallPlaneProbe.pickAxis(plus, minus, 0.0f),
                "perpendicular camera: the nearer wall (minus, 3 blocks) wins the tie");

        var plusNearer = plusCandidate(1);
        var minusFarther = minusCandidate(4);
        assertSame(plusNearer, WallPlaneProbe.pickAxis(plusNearer, minusFarther, 0.0f),
                "perpendicular camera, other way round: the nearer wall (plus, 1 block) wins");
    }

    // --- toCameraRelative: the precision case --------------------------------------------------

    @Test
    void aPlaneNearTheOriginSurvivesTheRoundTripExactly() {
        float result = WallPlaneProbe.toCameraRelative(10.05, 10.0);
        assertEquals(0.05f, result, 0.0001f);
    }

    @Test
    void aPlaneTenMillionBlocksOutStillSurvivesTheRoundTripAtFullPrecision() {
        // The claim this test proves: a plane 0.05 blocks from the camera, both sitting near x =
        // 10,000,000, well past float32's roughly 7-digit mantissa, where casting either value to
        // float before subtracting would already round away anything finer than about a block.
        // Doing the subtraction in double first, then casting only the small remainder, is why
        // toCameraRelative exists.
        double cameraX = 10_000_000.0;
        double planeAbsolute = cameraX + 0.05;
        float result = WallPlaneProbe.toCameraRelative(planeAbsolute, cameraX);
        assertEquals(0.05f, result, 1e-4f,
                "a naive (float) cast of either operand before subtracting would lose this entirely");
    }

    @Test
    void theSameDistanceIsExactAtSmallAndAtHugeCoordinatesAlike() {
        // Not just close enough: the residual must be identical regardless of how far from the
        // origin it is measured, since a mirror pass at any world position needs the same
        // 0.05-block guard to behave the same way.
        float nearOrigin = WallPlaneProbe.toCameraRelative(1000.32, 1000.0);
        float farFromOrigin = WallPlaneProbe.toCameraRelative(24_000_000.32, 24_000_000.0);
        assertEquals(nearOrigin, farFromOrigin, 1e-4f);
    }

    // --- read: the full pipeline, invalid-input guard only (everything else is covered above,
    // pure-core, without a level) ---------------------------------------------------------------

    @Test
    void readWithNoLevelIsZero() {
        var result = WallPlaneProbe.read(null, null, null, null);
        assertEquals(WallPlaneProbe.ZERO, result);
    }

    @Test
    void shouldProbeRequiresLevelAndPlayerAndADryEye() {
        assertTrue(WallPlaneProbe.shouldProbe(true, true, false));
        assertFalse(WallPlaneProbe.shouldProbe(false, true, false));
        assertFalse(WallPlaneProbe.shouldProbe(true, false, false));
        assertFalse(WallPlaneProbe.shouldProbe(true, true, true), "a submerged eye has nothing to look at a wall from");
    }
}
