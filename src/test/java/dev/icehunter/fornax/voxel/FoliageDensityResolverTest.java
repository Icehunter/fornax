package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliageDensityResolverTest {
    private static FoliageDensityResolver.QuadSample quad(
            float area, float cov, float[] min, float[] max) {
        return new FoliageDensityResolver.QuadSample(area, cov, min, max);
    }

    @Test
    void extinctionAppliesCauchyQuarterProjectionWithinAUnitCell() {
        // One fully-opaque quad of area 1 exactly filling a unit cell. This is a pure arithmetic pin
        // of the constant, not a physically real single-sided plate -- see
        // extinctionPinsTheCauchyConstantAgainstATwoSidedPlate below for why the REAL constant's
        // meaning requires two quads (front+back). sigma = 1.0 area * 1.0 coverage * 0.25 / 1 volume.
        float sigma = FoliageDensityResolver.combineExtinction(List.of(
                quad(1.0f, 1.0f, new float[] {0, 0, 0}, new float[] {1, 1, 1})));
        assertEquals(0.25f, sigma, 1e-4f);
    }

    @Test
    void extinctionScalesWithOpaqueCoverage() {
        // Half the texels block light -> half the extinction.
        float sigma = FoliageDensityResolver.combineExtinction(List.of(
                quad(1.0f, 0.5f, new float[] {0, 0, 0}, new float[] {1, 1, 1})));
        assertEquals(0.125f, sigma, 1e-4f);
    }

    @Test
    void extinctionNormalisesByTheVolumeTheGeometryActuallyOccupies() {
        // Same total area, but spread across a 2x1x1 span: density per block HALVES. This is the
        // correction for leaf clouds bleeding outside their own cell -- without it a 20-plane leaf
        // bush reads several times too much optical depth in one voxel.
        float sigma = FoliageDensityResolver.combineExtinction(List.of(
                quad(2.0f, 1.0f, new float[] {0, 0, 0}, new float[] {2, 1, 1})));
        assertEquals(0.25f, sigma, 1e-4f);
    }

    @Test
    void extinctionNeverDividesByLessThanOneCell() {
        // A plane smaller than a block must not be amplified: occupied volume floors at 1 block^3.
        float sigma = FoliageDensityResolver.combineExtinction(List.of(
                quad(0.25f, 1.0f, new float[] {0.25f, 0.25f, 0.25f}, new float[] {0.75f, 0.75f, 0.75f})));
        assertEquals(0.0625f, sigma, 1e-4f);
    }

    @Test
    void extinctionFloorsTheVolumePerAxisNotOnTheProduct() {
        // An ordinary tall, thin plant: extents 4.0 x 0.5 x 0.5. The x axis is already >= 1 block; y
        // and z are sub-unit. Correct per-axis flooring floors ONLY the sub-unit axes:
        //   volume = max(4.0, 1) * max(0.5, 1) * max(0.5, 1) = 4.0 * 1.0 * 1.0 = 4.0
        // A mutant that instead floors the raw PRODUCT of the extents (4.0 * 0.5 * 0.5 = 1.0, already
        // >= 1, so that floor is a silent no-op) would pass every OTHER test in this file, because
        // they are all sub-unit on every axis simultaneously -- product-floor and per-axis-floor agree
        // there. This anisotropic shape is what tells the two apart.
        //   correct: sigma = (1.0 * 1.0) * 0.25 / 4.0 = 0.0625
        //   mutant:  sigma = (1.0 * 1.0) * 0.25 / 1.0 = 0.25
        float sigma = FoliageDensityResolver.combineExtinction(List.of(
                quad(1.0f, 1.0f, new float[] {0, 0, 0}, new float[] {4.0f, 0.5f, 0.5f})));
        assertEquals(0.0625f, sigma, 1e-4f);
    }

    @Test
    void extinctionUsesTheUnionOfEveryQuadsBoundsNotJustTheLast() {
        // Two quads with DIFFERENT bounds. A mutant that ASSIGNS bounds per quad (min[i] = q.min()[i])
        // instead of reducing with Math.min/Math.max would end up with whichever quad was processed
        // last, and would pass every OTHER test in this file because they all feed identical bounds on
        // every quad.
        //   union bounds: x 0..4 (extent 4), y 0..1 (extent 1), z 0..1 (extent 1) -> volume = 4.0
        //   weightedArea = 1.0*1.0 + 1.0*1.0 = 2.0
        //   correct: sigma = 2.0 * 0.25 / 4.0 = 0.125
        //   last-quad-wins mutant: bounds = quad 2 only (x 3..4, extent 1) -> volume 1,
        //                          sigma = 2.0 * 0.25 / 1.0 = 0.5
        float sigma = FoliageDensityResolver.combineExtinction(List.of(
                quad(1.0f, 1.0f, new float[] {0, 0, 0}, new float[] {1, 1, 1}),
                quad(1.0f, 1.0f, new float[] {3, 0, 0}, new float[] {4, 1, 1})));
        assertEquals(0.125f, sigma, 1e-4f);
    }

    @Test
    void extinctionPinsTheCauchyConstantAgainstATwoSidedPlate() {
        // THE test that would have caught the 2x bug: one fully-opaque, unit-area plate baked the way
        // the real model topology bakes it -- front AND back as two separate real quads (see the class
        // doc's derivation), each reporting the plate's one-sided area (1.0) and full coverage.
        //   S (sum of baked quad area, both sides)     = 1.0 + 1.0 = 2.0
        //   S/4 (Cauchy mean projection of a convex body) = 0.5
        //   volume (unit cell, zero-thickness axis floors to 1) = 1.0
        //   sigma = weightedArea(2.0) * 0.25 / 1.0 = 0.5
        // The old 0.5-factor bug read the same S=2.0 as if it were a one-sided A and multiplied by 0.5,
        // giving S*0.5 = 1.0 -- double the correct 0.5.
        float sigma = FoliageDensityResolver.combineExtinction(List.of(
                quad(1.0f, 1.0f, new float[] {0, 0, 0}, new float[] {1, 1, 0}),
                quad(1.0f, 1.0f, new float[] {0, 0, 0}, new float[] {1, 1, 0})));
        assertEquals(0.5f, sigma, 1e-4f);
    }

    @Test
    void fullyTransparentGeometryHasNoExtinction() {
        float sigma = FoliageDensityResolver.combineExtinction(List.of(
                quad(4.0f, 0.0f, new float[] {0, 0, 0}, new float[] {1, 1, 1})));
        assertEquals(0.0f, sigma, 1e-6f);
    }

    @Test
    void noQuadsMeansNoExtinction() {
        assertEquals(0.0f, FoliageDensityResolver.combineExtinction(List.of()), 1e-6f);
    }

    @Test
    void measuredAcaciaLeavesLandInThePlausibleCanopyRange() {
        // Regression pin using the real 2026-07-20 acacia_leaves.json harvest, fed the way the RESOLVER
        // actually sees it. This distinction is the whole point of the test and has already caused one
        // 2x error in each direction, so read carefully before changing the numbers:
        //
        //   acacia_leaves.json has 20 zero-thickness plate ELEMENTS whose one-sided areas total
        //   25.31 block^2. Minecraft bakes BOTH facing sides of a zero-thickness plate as separate
        //   quads, so resolveExtinction collects 40 quads totalling S = 50.62 block^2 -- the total
        //   SURFACE, which is exactly what Cauchy's S/4 expects. Feeding this test the 25.31
        //   one-sided figure would silently pin HALF the sigma the engine really produces.
        //
        //   S            = 2 * 25.3125                      = 50.625   (40 baked quads)
        //   coverage     = alpha>=128 fraction of the sprite = 0.2205810546875
        //   volume       = 2.23614 * 1.69725 * 2.25112       = 8.543616
        //   sigma        = S * coverage * 0.25 / volume      = 0.326762
        //
        // The extents are the ROTATED bounds. Every acacia element carries a rotation, and rotation
        // genuinely changes the occupied bounding volume (here it TIGHTENS it, 9.51 -> 8.54 block^3,
        // raising sigma). An earlier version of this test used unrotated from/to extents and pinned
        // 0.293 -- close enough to look right, wrong enough to matter. The resolver itself reads BAKED
        // quads, so Minecraft has already applied rotation by the time it sees them; only an offline
        // cross-check has to replicate it. See the plan doc's authoritative table.
        List<FoliageDensityResolver.QuadSample> bakedQuads = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            bakedQuads.add(quad(50.625f / 40f, 0.2205810546875f,
                    new float[] {0f, 0f, 0f}, new float[] {2.23614f, 1.69725f, 2.25112f}));
        }
        float sigma = FoliageDensityResolver.combineExtinction(bakedQuads);
        assertEquals(0.32676f, sigma, 1e-3f);
        // Physical sanity: real forest canopies run LAI ~5-6 over ~10 m (~0.5-0.6 LAI/m), giving
        // extinction ~0.25-0.30 per metre with Cauchy. Acacia landing in that band is the check that
        // the volume normalisation is doing its job -- without it this reads ~2.3, an absurd density.
        assertTrue(sigma > 0.15f && sigma < 0.7f, "outside the measured leaf-family band: " + sigma);
    }
}
