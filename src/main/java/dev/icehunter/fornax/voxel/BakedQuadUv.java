package dev.icehunter.fornax.voxel;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/** Shared sprite-LOCAL UV span derivation for a baked quad -- used by both {@link FaceColorResolver}
 * and {@link FoliageDensityResolver} so this exact bit of math cannot drift between them again (it
 * already had once: one caller grew a null-image guard the other lacked, while the UV derivation
 * itself stayed a verbatim copy-paste). Callers own everything downstream of the span (image
 * null-checks, degenerate-span handling, what to do with the result) -- this only answers "what
 * fraction of the sprite does this quad's own UVs cover". */
final class BakedQuadUv {
    private BakedQuadUv() {
    }

    /** The sprite-local UV span ({@code u0, v0, u1, v1}, each normally in [0,1]) that {@code quad}'s
     * own packed UVs cover within {@code sprite}'s U0..U1 / V0..V1 rect. */
    static float[] localSpan(BakedQuad quad, TextureAtlasSprite sprite) {
        float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            long packed = quad.packedUV(i);
            float u = UVPair.unpackU(packed);
            float v = UVPair.unpackV(packed);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        return new float[] {
                (minU - u0) / (u1 - u0),
                (minV - v0) / (v1 - v0),
                (maxU - u0) / (u1 - u0),
                (maxV - v0) / (v1 - v0)
        };
    }
}
