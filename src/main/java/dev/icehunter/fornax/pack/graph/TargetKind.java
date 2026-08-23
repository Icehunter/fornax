package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;

/**
 * Whether a graph target is a 2D texture (the original, still overwhelmingly common case) or a raw
 * storage buffer (SSBO). Buffer-kind targets are never sized by the render/output-resolution {@code
 * scale} every texture target uses -- their size is driven by something else entirely.
 *
 * <p>Two things can drive it. An ENGINE-owned buffer (the brick grid, the analytic light list) is
 * sized by its own engine call site via {@link TargetRegistry#ensureBufferSize}, and
 * {@link TargetPlan#compute} skips it. A PACK-owned one declares {@code stride_bytes} x
 * {@code count} in {@code graph.toml} (see {@link BufferSize}) and is planned, allocated and freed
 * by {@link TargetPlan}/{@link TargetRegistry} like any other target. Which of the two a given NAME
 * is allowed to be is decided by {@code GraphValidator}, not here.
 */
public enum TargetKind {
    TEXTURE("texture"),
    BUFFER("buffer");

    private final String token;

    TargetKind(String token) {
        this.token = token;
    }

    public static TargetKind parse(String token, String targetName, String file) {
        for (TargetKind k : values()) {
            if (k.token.equals(token)) return k;
        }
        throw new FornaxPackError(file, "targets." + targetName + ".kind",
                "unknown target kind '" + token + "' (expected texture|buffer)");
    }
}
