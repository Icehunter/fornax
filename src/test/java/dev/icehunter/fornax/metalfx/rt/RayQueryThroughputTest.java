package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures how many rays {@code rt_ray_query} traces per second on this machine.
 *
 * <p>This is a recorded measurement, not a threshold. The assertions only pin that tracing happened
 * and that the buffer came back with the mix of hits and misses the geometry demands; the number it
 * prints is the input to every "can we afford path-traced lighting" decision, and the engine
 * records no other absolute ray-tracing cost figure anywhere.
 *
 * <p>Budget arithmetic the figure feeds. One ray per pixel at 1920x1080 is 2.07 Mi rays. At 60fps
 * that is 124 Mrays/s for a single ray per pixel with the whole rest of the frame taking zero time.
 * A one-bounce path with one shadow ray per bounce is four times that.
 *
 * <p>The scene is one solid block, so this is a best case: a real world has orders of magnitude more
 * geometry in the structure and deeper traversal per ray. Treat the figure as an upper bound on
 * this hardware, not as what a world would sustain.
 */
class RayQueryThroughputTest {

    private static final int RAYS = 1 << 20;

    /** Timed runs. Odd so the median is an actual sample rather than a mean of two. */
    private static final int RUNS = 7;

    /** Dispatches per timed run, so one run's cost is well above timer and submission noise. */
    private static final int DISPATCHES_PER_RUN = 8;

    @Test
    void oneMillionRaysAgainstOneBlockRecordTheirThroughput() {
        // Just outside the block's -z face, so the fan sprays across it.
        measure("one block", RayQueryScene::open, 8.5f, 8.5f, 4.0f);
    }

    /**
     * The same measurement against 3072 triangles scattered through a section rather than 12. The
     * gap between the two figures is what BVH traversal depth costs, and it is the reason the
     * one-block number must not be quoted on its own.
     */
    @Test
    void oneMillionRaysAgainstAScatteredLatticeRecordTheirThroughput() {
        // Inside the lattice, so rays leave in every direction through varying amounts of it.
        measure("scattered lattice", RayQueryScene::openScatteredLattice, 7.0f, 3.0f, 7.0f);
    }

    private void measure(String label, java.util.function.LongFunction<RayQueryScene> opener,
            float originX, float originY, float originZ) {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        try (RayQueryScene scene = opener.apply(device)) {
            float[] requests = sphereFrom(RAYS, originX, originY, originZ);
            long requestBuffer = scene.newRequestBuffer(requests);
            long hitBuffer = scene.newHitBuffer(RAYS);

            // Discarded: first dispatch pays pipeline warm-up and first-touch page faults.
            scene.timeDispatches(requestBuffer, hitBuffer, RAYS, DISPATCHES_PER_RUN);

            long[] nanos = new long[RUNS];
            for (int run = 0; run < RUNS; run++) {
                nanos[run] = scene.timeDispatches(requestBuffer, hitBuffer, RAYS, DISPATCHES_PER_RUN);
            }
            Arrays.sort(nanos);
            long median = nanos[RUNS / 2];
            double raysPerSecond = (double) RAYS * DISPATCHES_PER_RUN / (median / 1e9);

            RayQueryScene.Hit[] hits = scene.trace(Arrays.copyOf(requests, 8 * RayQueryAbi.REQUEST_WORDS), 8);
            long hitCount = Arrays.stream(hits).filter(h -> !h.isMiss()).count();

            System.out.printf("%n  rt_ray_query throughput [%s, %d triangles]: %.1f Mrays/s"
                            + "  (%d rays x %d dispatches, median of %d runs, %.2f ms/run)%n"
                            + "  one ray per pixel at 1080p (2.07 Mi) costs %.2f ms%n%n",
                    label, scene.triangleCount(), raysPerSecond / 1e6,
                    RAYS, DISPATCHES_PER_RUN, RUNS, median / 1e6,
                    2_073_600.0 / raysPerSecond * 1e3);

            assertTrue(raysPerSecond > 0.0, "the benchmark traced no rays");
            // A run that reports every ray a miss traced nothing real, and its timing is
            // meaningless however fast it looks. This caught exactly that: a fan left aimed at the
            // single block measured the half-filled scene as empty air.
            assertTrue(hitCount > 0,
                    "a fan aimed at geometry must produce hits; all-miss means the ray set misses "
                            + "this scene or the structure was never resident, and the throughput "
                            + "figure is measuring an empty trace");
        } finally {
            Objc.msgSendVoid(device, Objc.selector("release"));
        }
    }

    /**
     * Rays from one point, spread evenly over the whole sphere. Deterministic (a fixed golden-angle
     * spiral, no RNG) so two runs trace identical work, and a full sphere rather than a cone so the
     * set carries the mix of hits and misses a bounce pass actually casts instead of the all-hit or
     * all-miss extremes a single direction gives.
     */
    private static float[] sphereFrom(int rays, float originX, float originY, float originZ) {
        float[] out = new float[rays * RayQueryAbi.REQUEST_WORDS];
        // Golden angle in radians: pi * (3 - sqrt(5)). Successive samples land far apart on the
        // sphere, so no two neighbouring threads walk an identical path through the structure.
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < rays; i++) {
            double z = 1.0 - 2.0 * (i + 0.5) / rays;
            double radius = Math.sqrt(Math.max(0.0, 1.0 - z * z));
            double theta = goldenAngle * i;
            int base = i * RayQueryAbi.REQUEST_WORDS;
            out[base] = originX;
            out[base + 1] = originY;
            out[base + 2] = originZ;
            out[base + 3] = 0.001f;
            out[base + 4] = (float) (Math.cos(theta) * radius);
            out[base + 5] = (float) (Math.sin(theta) * radius);
            out[base + 6] = (float) z;
            out[base + 7] = 1000.0f;
        }
        return out;
    }
}
