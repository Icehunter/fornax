package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;

/**
 * The sizing basis a graph target's {@code scale} multiplies against: {@code RENDER} (the
 * default) scales off the current render resolution -- a later task lets this run below the
 * native output resolution under TAAU/upscaling; {@code OUTPUT} scales off the native output
 * resolution regardless of render scale, for targets that must always hold full native detail
 * even while the graph itself runs at a lower render resolution (the engine-guaranteed {@code
 * sceneHistory} target, and the future TAAU reconstruct destination). Today render size and
 * output size are the same value everywhere this is consumed, so the distinction is inert until
 * that later task threads a genuinely different output size through.
 *
 * <p>Kept as a plain enum with a string token so this layer stays off the blaze3d classpath and
 * unit-testable, the same convention {@link TargetFormat} already follows.
 */
public enum TargetBasis {
    RENDER("render"),
    OUTPUT("output");

    private final String token;

    TargetBasis(String token) {
        this.token = token;
    }

    public static TargetBasis parse(String token, String targetName, String file) {
        for (TargetBasis b : values()) {
            if (b.token.equals(token)) return b;
        }
        throw new FornaxPackError(file, "targets." + targetName + ".basis",
                "unknown target basis '" + token + "' (expected render|output)");
    }
}
