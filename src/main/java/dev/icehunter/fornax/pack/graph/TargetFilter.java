package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;

/**
 * How a graph target is filtered when a later pass samples it: {@code NEAREST} (the default, and
 * what every target got before this existed) or {@code LINEAR}.
 *
 * <p>This belongs on the TARGET rather than on the reading pass's input list because it is a
 * property of the resource, not of who happens to read it: a downsampled bloom level is a
 * bilinearly-sampled resource for every consumer, present and future, in the same way {@code
 * builtin.noise} is (see {@code FullscreenPassRunner}'s tileable-noise branch, which is the same
 * idea hard-coded for the one engine builtin that needed it first). Declaring it per-consumer would
 * let two passes disagree about the same texture, which is not a meaningful thing to express.
 *
 * <p>The case that motivated it: {@code bloom_combine} reads seven downsampled levels at FULL-res
 * {@code texCoord}, and the smallest is 1/256 scale -- roughly 7x5 texels for a 2000px frame. Under
 * NEAREST each of those texels paints a 256x256 pixel square instead of a smooth ramp. Measured
 * against LINEAR on a real frame the difference is small in absolute terms (0.03% mean, 3.1% peak,
 * 0.8% relative within the sky) because bloom strength is 0.12 and the coarse levels carry weights
 * of 0.13 and 0.026 -- so this is a correctness fix with a modest visual payoff, not the cause of
 * any large artifact. Recorded plainly so it is not later mistaken for one.
 *
 * <p>Wrap mode is deliberately NOT part of this. Every target here is CLAMP_TO_EDGE; only {@code
 * builtin.noise} and pack-declared texture assets wrap, and those are tileable IMAGES rather than
 * render targets. Upsampling a bloom level with REPEAT would bleed the opposite screen edge in.
 *
 * <p>Plain enum with a string token, off the blaze3d classpath and unit-testable, following {@link
 * TargetBasis}.
 */
public enum TargetFilter {
    NEAREST("nearest"),
    LINEAR("linear");

    private final String token;

    TargetFilter(String token) {
        this.token = token;
    }

    public static TargetFilter parse(String token, String targetName, String file) {
        for (TargetFilter f : values()) {
            if (f.token.equals(token)) return f;
        }
        throw new FornaxPackError(file, "targets." + targetName + ".filter",
                "unknown target filter '" + token + "' (expected nearest|linear)");
    }
}
