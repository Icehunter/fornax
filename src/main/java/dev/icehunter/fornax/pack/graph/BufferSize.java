package dev.icehunter.fornax.pack.graph;

/**
 * The declared size of a PACK-OWNED buffer-kind target: {@code count} elements of
 * {@code strideBytes} bytes each, parsed from {@code [targets.NAME]}'s own {@code stride_bytes}
 * and {@code count} keys (see {@code PackTomlLoader}). {@code null} on a {@link TargetSpec}
 * means the target is ENGINE-owned -- some engine call site drives its
 * {@link TargetRegistry#ensureBufferSize} instead, and {@link TargetPlan} must not size it.
 *
 * <p><b>Two numbers rather than one {@code size_bytes}.</b> A single byte count would have been
 * less TOML, and it was rejected for a specific reason: the element count is the number the engine
 * needs in order to CHECK anything. A particles pass declaring {@code instances = 40000} against a
 * buffer holding 10000 elements is an out-of-bounds std430 read -- garbage flakes, no validation
 * error, nothing in the log -- and {@code GraphValidator} can only refuse that at load if the
 * buffer's capacity is expressed in the same unit the pass's {@code instances} is. A lone
 * {@code size_bytes} hides that unit inside a product the engine cannot factor. The stride is also
 * exactly what a pack's own {@code layout(std430) buffer { Element data[]; }} declaration commits
 * to, so putting it in {@code graph.toml} keeps the two sides of that contract visible together.
 *
 * <p>Deriving the size from a particles pass's {@code instances} instead was considered and
 * rejected: it cannot size a buffer no particles pass names (an accumulation field, a footprint
 * trail -- the very cases this exists for), and it would still need the pack to state the stride,
 * since nothing engine-side knows {@code sizeof} the pack's own element struct.
 */
public record BufferSize(int strideBytes, int count) {
    /**
     * Hard ceiling on one pack-declared buffer, refused at load rather than passed to
     * {@code vmaCreateBuffer}. 1 GiB is far past any plausible pack structure (the engine's own
     * largest buffer, the brick-grid payload at a 17-section window, is ~20 MB) and comfortably
     * under {@code VkPhysicalDeviceLimits::maxStorageBufferRange} on every device this mod runs on,
     * so a graph that trips this is a typo -- a misplaced zero -- not a legitimate allocation. The
     * alternative is a driver-side allocation failure at first frame, which {@code ensureBufferSize}
     * only logs before returning, leaving the pass permanently unbuildable with no load-time error.
     */
    public static final long MAX_SIZE_BYTES = 1L << 30;

    /** Computed in {@code long} deliberately: {@code strideBytes * count} in {@code int} arithmetic
     * silently wraps, and a wrapped-negative size would reach
     * {@link TargetRegistry#ensureBufferSize}'s positivity check as a confusing runtime failure
     * rather than the load-time refusal {@code PackTomlLoader} gives it here. */
    public long sizeBytes() {
        return (long) strideBytes * (long) count;
    }
}
