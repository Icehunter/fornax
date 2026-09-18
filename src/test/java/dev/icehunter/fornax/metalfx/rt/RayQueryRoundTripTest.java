package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.rt.RayTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fires caller-built rays at a real acceleration structure and checks the distances that come back.
 *
 * <p>This is the test that establishes the capability the engine did not previously have: before
 * {@code rt_ray_query}, a caller could read the sun-shadow depth texture and nothing else, because
 * every trace in the engine had its direction fixed to the light. Every ray below points somewhere
 * of the caller's choosing.
 *
 * <p>Scene, from {@link RayQueryScene}: one solid block occupying [8,9] on every axis. Every
 * expected distance below is read off that box, not off a previous run.
 */
class RayQueryRoundTripTest {

    /** Rays start just off their origin so a surface at t=0 cannot self-intersect. */
    private static final float T_MIN = 0.001f;

    private static final float FAR = 1000.0f;

    /** The block spans [8,9]; 8.5 is its centre line on every axis. */
    private static final float CENTRE = 8.5f;

    private static float[] ray(float ox, float oy, float oz, float dx, float dy, float dz, float tMax) {
        return new float[]{ox, oy, oz, T_MIN, dx, dy, dz, tMax};
    }

    private static float[] rays(float[]... each) {
        float[] out = new float[each.length * RayQueryAbi.REQUEST_WORDS];
        for (int i = 0; i < each.length; i++) {
            System.arraycopy(each[i], 0, out, i * RayQueryAbi.REQUEST_WORDS, RayQueryAbi.REQUEST_WORDS);
        }
        return out;
    }

    /**
     * A caller's origin is camera-relative, and the kernel puts it in the structure's own frame.
     *
     * <ul>
     *   <li>The mesh tier builds on a coarse grid origin and the voxel tier on its window's first
     *       section. A pack has no way to know either, and nothing in a request says which frame
     *       it is in.
     *   <li>Without the offset a ray traces against geometry displaced by up to the grid step, so
     *       every hit lands somewhere real and wrong, with no error.
     *   <li>Shifting the origin by an amount and passing that amount back must return the same
     *       distance as the unshifted ray.
     * </ul>
     */
    @Test
    void aCameraRelativeOriginIsRebasedIntoTheStructureFrame() {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        try (RayQueryScene scene = RayQueryScene.open(device)) {
            // Straight at the near face from z=0: the block spans [8,9], so t=8.
            float[] direct = rays(ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, 1.0f, FAR));
            RayQueryScene.Hit[] plain = scene.trace(direct, 1);
            assertEquals(8.0f, plain[0].distance(), 1e-3f, "the unshifted ray reaches the near face");

            // The same ray written by a caller whose camera sits at (256, 64, -128) in this frame.
            float camX = 256.0f, camY = 64.0f, camZ = -128.0f;
            float[] relative = rays(ray(CENTRE - camX, CENTRE - camY, 0.0f - camZ,
                    0.0f, 0.0f, 1.0f, FAR));
            RayQueryScene.Hit[] rebased = scene.traceRebased(relative, 1, camX, camY, camZ);
            assertEquals(plain[0].distance(), rebased[0].distance(), 1e-3f,
                    "a camera-relative ray plus its frame offset is the same ray");

            // The same relative origins with no offset: a real hit somewhere else, or a miss.
            RayQueryScene.Hit[] unrebased = scene.trace(relative, 1);
            assertNotEquals(plain[0].distance(), unrebased[0].distance(),
                    "without the offset the ray traces a displaced scene, which is the silent case");
        }
    }

    @Test
    void raysAimedAtTheBlockReportTheExactFaceDistanceAndRaysAimedAwayReportMisses() {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        try (RayQueryScene scene = RayQueryScene.open(device)) {
            float[] requests = rays(
                    // 0: from z=0 toward +z along the block's centre line. The near face is z=8, so t=8.
                    ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, 1.0f, FAR),
                    // 1: same origin, fired away from the block. Nothing behind it: miss.
                    ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, -1.0f, FAR),
                    // 2: from z=20 toward -z. The near face from that side is z=9, so t = 20-9 = 11.
                    ray(CENTRE, CENTRE, 20.0f, 0.0f, 0.0f, -1.0f, FAR),
                    // 3: from x=0 toward +x. Near face x=8, so t=8. Proves the axis is not hardcoded.
                    ray(0.0f, CENTRE, CENTRE, 1.0f, 0.0f, 0.0f, FAR),
                    // 4: from above, toward -y. Near face y=9, so t = 20-9 = 11.
                    ray(CENTRE, 20.0f, CENTRE, 0.0f, -1.0f, 0.0f, FAR),
                    // 5: parallel to the block but offset past its x extent (x=20 vs max 9): miss.
                    ray(20.0f, CENTRE, 0.0f, 0.0f, 0.0f, 1.0f, FAR),
                    // 6: aimed at the block but tMax cut short of it. The face is at t=8, so a tMax
                    //    of 5 must report a miss rather than the hit beyond it.
                    ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, 1.0f, 5.0f),
                    // 7: an unnormalized direction of length 2. Distance is reported along the
                    //    normalized ray, so this must match ray 0 exactly, not half or double it.
                    ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, 2.0f, FAR));

            RayQueryScene.Hit[] hits = scene.trace(requests, 8);

            assertEquals(8.0f, hits[0].distance(), 1e-4f, "+z ray meets the z=8 face at t=8");
            assertTrue(hits[1].isMiss(), "the ray fired away from the block meets nothing");
            assertEquals(11.0f, hits[2].distance(), 1e-4f, "-z ray from z=20 meets the z=9 face at t=11");
            assertEquals(8.0f, hits[3].distance(), 1e-4f, "+x ray meets the x=8 face at t=8");
            assertEquals(11.0f, hits[4].distance(), 1e-4f, "-y ray from y=20 meets the y=9 face at t=11");
            assertTrue(hits[5].isMiss(), "a ray offset past the block's x extent meets nothing");
            assertTrue(hits[6].isMiss(), "tMax of 5 must not report the face at t=8");
            assertEquals(8.0f, hits[7].distance(), 1e-4f,
                    "distance is measured along the normalized direction, so length-2 matches length-1");
        } finally {
            Objc.msgSendVoid(device, Objc.selector("release"));
        }
    }

    @Test
    void aHitCarriesTheSurfaceWordItLandedOnAndReadsAsFrontFacingAtTheTracingTier() {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        try (RayQueryScene scene = RayQueryScene.open(device)) {
            RayQueryScene.Hit[] hits = scene.trace(
                    rays(ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, 1.0f, FAR)), 1);

            assertTrue(hits[0].answered(), "a dispatched ray always carries its tracing tier");
            // 2 is RayTier.HARDWARE_VOXEL.ordinal(): this scene is the rt_expand brick-grid
            // structure, and RayQueryScene declares that tier in its constants block.
            assertEquals(RayTier.HARDWARE_VOXEL.ordinal(), hits[0].tier(),
                    "a hit reports the tier that traced it");
            assertFalse(hits[0].isMiss(), "the centre-line ray hits");
            // voxelIndex | face << 12 | paletteIndex << 16, the word rt_expand writes per triangle.
            // The scene holds one block at (8, 8, 8), so the low 12 bits are 2184.
            assertEquals(2184, hits[0].surface() & 0xFFF,
                    "the surface word must carry the voxel index rt_expand stored, got "
                            + Integer.toHexString(hits[0].surface()));
            // Face 2 is north (-Z) in Minecraft's Direction order. A ray travelling +Z enters
            // through the block's -Z side, so the outward normal of the face it meets is -Z.
            assertEquals(2, hits[0].face(),
                    "the flag word's face field names the face the ray entered through");
            assertEquals(1, hits[0].frontFacing(),
                    "a ray entering the block from outside meets a front face under the clockwise "
                            + "winding rt_trace's own facing probe established");
            // A box triangle's record is one word long. Reporting a UV here would mean the kernel
            // read the next triangle's surface word as a texture coordinate, which samples a real
            // texel and reads as a shading bug rather than as a decode one.
            assertFalse(hits[0].uvKnown(),
                    "an rt_expand box triangle carries no UVs, so the record must say so");
            assertEquals(0, hits[0].atlasUv(), "and must leave the UV word zero");
        } finally {
            Objc.msgSendVoid(device, Objc.selector("release"));
        }
    }

    /**
     * The zero-fill case, measured rather than argued. A hit buffer that nothing ever dispatched
     * against is readable on any device, and every word of it reads zero, including the distance.
     * Tier zero is the only thing in the record that separates it from a real answer.
     */
    @Test
    void aBufferNothingEverTracedReadsAsUnansweredRatherThanAsHitsAtDistanceZero() {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        try (RayQueryScene scene = RayQueryScene.open(device)) {
            RayQueryScene.Hit[] hits = scene.readUntracedBuffer(64);

            for (RayQueryScene.Hit hit : hits) {
                assertFalse(hit.answered(), "an untraced record must read as unanswered");
                assertEquals(RayTier.NONE.ordinal(), hit.tier());
                assertFalse(hit.isMiss(),
                        "distance zero reads as a hit, which is exactly why the tier word exists "
                                + "and why a reader must test it first");
            }
        } finally {
            Objc.msgSendVoid(device, Objc.selector("release"));
        }
    }

    /**
     * Every one of the block's six faces, struck head-on from outside, reports the outward normal
     * of exactly that face. Exact, not approximate: a voxel face is axis aligned and its normal is
     * decoded from the face index rt_expand stored per triangle, so any component off by more than
     * float noise means the face-to-axis mapping is wrong, not that the sampling is coarse.
     *
     * <p>Face order is Minecraft's Direction order, which rt_expand's face_corners switch follows:
     * 0 down (-Y), 1 up (+Y), 2 north (-Z), 3 south (+Z), 4 west (-X), 5 east (+X).
     */
    @Test
    void eachFaceOfTheBlockReportsItsOwnOutwardNormal() {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        try (RayQueryScene scene = RayQueryScene.open(device)) {
            float[] requests = rays(
                    // 0: from -Z toward +Z, meets the north face at z=8. Outward normal is -Z.
                    ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, 1.0f, FAR),
                    // 1: from +Z toward -Z, meets the south face at z=9. Outward normal is +Z.
                    ray(CENTRE, CENTRE, 20.0f, 0.0f, 0.0f, -1.0f, FAR),
                    // 2: from -X toward +X, meets the west face at x=8. Outward normal is -X.
                    ray(0.0f, CENTRE, CENTRE, 1.0f, 0.0f, 0.0f, FAR),
                    // 3: from +X toward -X, meets the east face at x=9. Outward normal is +X.
                    ray(20.0f, CENTRE, CENTRE, -1.0f, 0.0f, 0.0f, FAR),
                    // 4: from below toward +Y, meets the down face at y=8. Outward normal is -Y.
                    ray(CENTRE, 0.0f, CENTRE, 0.0f, 1.0f, 0.0f, FAR),
                    // 5: from above toward -Y, meets the up face at y=9. Outward normal is +Y.
                    ray(CENTRE, 20.0f, CENTRE, 0.0f, -1.0f, 0.0f, FAR));

            RayQueryScene.Hit[] hits = scene.trace(requests, 6);

            float[][] expected = {
                    {0.0f, 0.0f, -1.0f},
                    {0.0f, 0.0f, 1.0f},
                    {-1.0f, 0.0f, 0.0f},
                    {1.0f, 0.0f, 0.0f},
                    {0.0f, -1.0f, 0.0f},
                    {0.0f, 1.0f, 0.0f}};
            String[] face = {"north", "south", "west", "east", "down", "up"};

            for (int i = 0; i < 6; i++) {
                assertFalse(hits[i].isMiss(), face[i] + " ray must hit the block");
                assertFalse(hits[i].normalUnknown(),
                        face[i] + " face must resolve to a named axis, got " + hits[i].normalText());
                assertEquals(expected[i][0], hits[i].normalX(), 1e-6f, face[i] + " normal x");
                assertEquals(expected[i][1], hits[i].normalY(), 1e-6f, face[i] + " normal y");
                assertEquals(expected[i][2], hits[i].normalZ(), 1e-6f, face[i] + " normal z");
            }
        } finally {
            Objc.msgSendVoid(device, Objc.selector("release"));
        }
    }

    /**
     * Every returned normal points back along the ray that found it. A face-to-axis mapping with
     * two entries swapped can still give six distinct unit vectors and pass a per-face check if the
     * expectations were copied from the same wrong table; this cannot be satisfied that way.
     */
    @Test
    void everyHitNormalFacesBackAlongTheRayThatFoundIt() {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        try (RayQueryScene scene = RayQueryScene.open(device)) {
            float[] requests = rays(
                    ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, 1.0f, FAR),
                    ray(CENTRE, CENTRE, 20.0f, 0.0f, 0.0f, -1.0f, FAR),
                    ray(0.0f, CENTRE, CENTRE, 1.0f, 0.0f, 0.0f, FAR),
                    ray(20.0f, CENTRE, CENTRE, -1.0f, 0.0f, 0.0f, FAR),
                    ray(CENTRE, 0.0f, CENTRE, 0.0f, 1.0f, 0.0f, FAR),
                    ray(CENTRE, 20.0f, CENTRE, 0.0f, -1.0f, 0.0f, FAR));

            RayQueryScene.Hit[] hits = scene.trace(requests, 6);

            for (int i = 0; i < 6; i++) {
                float dx = requests[i * RayQueryAbi.REQUEST_WORDS + 4];
                float dy = requests[i * RayQueryAbi.REQUEST_WORDS + 5];
                float dz = requests[i * RayQueryAbi.REQUEST_WORDS + 6];
                float facing = dx * hits[i].normalX() + dy * hits[i].normalY() + dz * hits[i].normalZ();
                assertEquals(-1.0f, facing, 1e-6f,
                        "ray " + i + " struck its face head-on, so direction dot normal must be -1, "
                                + "got " + facing + " with normal " + hits[i].normalText());
            }
        } finally {
            Objc.msgSendVoid(device, Objc.selector("release"));
        }
    }

    /**
     * The cascade discipline, in buffer form: a record another tier already answered survives a
     * lower tier's fill untouched, and one still at tier 0 gets traced. Without this a tier-1 march
     * overwrites a tier-3 exact hit and nothing in the record says it happened.
     */
    @Test
    void aFillLeavesAnsweredRecordsAloneAndTracesOnlyTheUnansweredOnes() {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        try (RayQueryScene scene = RayQueryScene.open(device)) {
            // Two identical rays that both hit the block. The first is staged as already answered
            // by the mesh tier, the second left unanswered.
            float[] rays = rays(
                    ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, 1.0f, FAR),
                    ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, 1.0f, FAR));
            RayQueryScene.Hit[] hits = scene.fill(rays, 2,
                    new int[]{RayTier.HARDWARE_MESH.ordinal(), RayTier.NONE.ordinal()});

            assertEquals(RayTier.HARDWARE_MESH.ordinal(), hits[0].tier(),
                    "an answered record keeps the tier that answered it");
            assertEquals(0.0f, hits[0].distance(), 0f,
                    "and keeps every other word: this one was staged with a zero distance, so a "
                            + "distance of 8 here would mean the fill overwrote a better answer");

            assertEquals(RayTier.HARDWARE_VOXEL.ordinal(), hits[1].tier(),
                    "an unanswered record is traced and carries the filling tier");
            assertFalse(hits[1].isMiss(), "and the identical ray does hit the block");
        } finally {
            Objc.msgSendVoid(device, Objc.selector("release"));
        }
    }

    /**
     * The kernel refuses a buffer it was not built to read rather than reading it at the wrong
     * stride. Exercised by lying about the version in the constants block. It answers with an
     * all-zero record rather than with a miss: the caller's own layout may keep the tier somewhere
     * else, and all-zero reads as unanswered under every layout this kernel has had.
     */
    @Test
    void aMismatchedAbiVersionYieldsUnansweredRecordsRatherThanGarbage() {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        try (RayQueryScene scene = RayQueryScene.open(device)) {
            RayQueryScene.Hit[] hits = scene.traceWithAbiVersion(
                    rays(ray(CENTRE, CENTRE, 0.0f, 0.0f, 0.0f, 1.0f, FAR)), 1,
                    RayQueryAbi.ABI_VERSION + 1);

            assertFalse(hits[0].answered(),
                    "a ray that would otherwise hit at t=8 must come back unanswered under a "
                            + "version the kernel does not implement; a miss would read as a real "
                            + "answer from a tier that never ran");
            assertEquals(RayTier.NONE.ordinal(), hits[0].tier(),
                    "a version mismatch writes an all-zero record, which reads as unanswered under "
                            + "every layout this kernel has had");
        } finally {
            Objc.msgSendVoid(device, Objc.selector("release"));
        }
    }
}
