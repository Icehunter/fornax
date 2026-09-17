package dev.icehunter.fornax.metalfx.rt;

/**
 * The ray set every throughput benchmark here traces, in one place so two benchmarks cannot drift
 * into measuring different work and be compared anyway.
 */
final class RayFan {

    private RayFan() {
    }

    /**
     * Rays from one point, spread evenly over the whole sphere. Deterministic (a fixed golden-angle
     * spiral, no RNG) so two runs trace identical work, and a full sphere rather than a cone so the
     * set carries the mix of hits and misses a bounce pass actually casts instead of the all-hit or
     * all-miss extremes a single direction gives.
     */
    static float[] sphere(int rays, float originX, float originY, float originZ, float tMax) {
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
            out[base + 7] = tMax;
        }
        return out;
    }
}
