package dev.icehunter.fornax.atlas;

/**
 * Derives how many overflow atlas pages a VRAM budget can support, and reports whether a resolved
 * stack's natural page requirement fits inside it.
 *
 * <p>Kept GPU-free on purpose: this class takes a byte budget as a plain {@code long}, never a
 * queried device. Whichever later, live phase asks the GPU how much VRAM is available hands the
 * number in here; nothing in this class does the asking. That split is what keeps page-count
 * derivation itself in the {@code CameraJitter}/{@code ShadowCamera} pattern -- pure, static,
 * fully unit-testable -- while the impure query stays at the call site, same as
 * {@code ShadowCamera.compute} takes a light direction and camera position rather than reading
 * {@code Minecraft.getInstance()} itself.
 *
 * <p>Target hardware for the paged atlas spans roughly a 10x VRAM range, from a high-end desktop
 * GPU down to a modest laptop's shared memory; a constant page ceiling either wastes headroom on
 * the former or crashes the latter. {@code maxPages} is instead computed from what a page actually
 * costs resident: its own albedo mip chain, plus a normal and a material sidecar atlas at the SAME
 * resolution ({@code NormalMapAtlasReloadListener}/{@code MaterialMapAtlasReloadListener} allocate
 * every overflow layer at page 0's own {@code atlasWidth}x{@code atlasHeight}; neither builder
 * downsamples an overflow page's sidecars).
 *
 * <p>Public since Phase 2 (package-private through Phase 1): {@code
 * dev.icehunter.fornax.mixin.vanilla.SpriteLoaderPagedStitchMixin} is the first live caller,
 * feeding it a budget derived from {@link dev.icehunter.fornax.util.GpuMemoryEstimator}'s real
 * device-local VRAM reading (a system-memory fraction only as a fallback); see that mixin.
 */
public final class BlockAtlasPageBudget {
    private BlockAtlasPageBudget() {
    }

    /**
     * A full mip chain's levels below the base sum to 1/3 of the base's own resident cost (matches
     * {@code MaterialMapAtlasReloadListener.MIP_CHAIN_FACTOR} and
     * {@code NormalMapAtlasReloadListener}'s own chain shape).
     */
    static final double MIP_CHAIN_FACTOR = 4.0 / 3.0;

    private static final int BYTES_PER_TEXEL = 4; // RGBA8, every atlas this engine builds.

    /**
     * Resident bytes one page costs across all three atlases it needs: its own albedo mip chain,
     * plus a normal and a material sidecar chain, each at the SAME resolution as the albedo (see
     * this class's own doc for why: neither atlas builder downsamples an overflow page's sidecars).
     */
    public static long bytesPerPage(int pageWidth, int pageHeight) {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException("page dimensions must be positive");
        }
        long albedoTexels = (long) pageWidth * pageHeight;
        long totalTexels = 3 * albedoTexels; // albedo + normal + material, all full resolution
        return Math.round(totalTexels * BYTES_PER_TEXEL * MIP_CHAIN_FACTOR);
    }

    /**
     * The most pages {@code availableVramBytes} can hold at {@code pageWidth}x{@code pageHeight},
     * after reserving {@code (1 - budgetFraction)} of it for everything else the engine and game
     * already keep resident. Never returns less than 1 (a device that cannot afford even one page
     * is a later phase's degrade-or-refuse decision, not this method's), and never more than
     * {@code hardCeiling}.
     *
     * @param budgetFraction the fraction of {@code availableVramBytes} this atlas may claim, in
     *                        (0, 1]
     * @param hardCeiling    an upper bound independent of VRAM (for example, a page-index encoding
     *                       width decided elsewhere); must be at least 1
     */
    public static int maxPages(long availableVramBytes, double budgetFraction, int pageWidth, int pageHeight,
                                int hardCeiling) {
        if (availableVramBytes < 0) {
            throw new IllegalArgumentException("availableVramBytes must not be negative");
        }
        if (!(budgetFraction > 0.0) || budgetFraction > 1.0) {
            throw new IllegalArgumentException("budgetFraction must be in (0, 1]");
        }
        if (hardCeiling < 1) {
            throw new IllegalArgumentException("hardCeiling must be at least 1");
        }
        long budgetBytes = (long) (availableVramBytes * budgetFraction);
        long pages = budgetBytes / bytesPerPage(pageWidth, pageHeight);
        return (int) Math.max(1, Math.min(hardCeiling, pages));
    }

    /**
     * @param pageCount   how many pages the reload should actually build
     * @param vramLimited {@code true} when the resolved stack needed more pages than {@code
     *                     maxPages} allowed, so {@code pageCount} is the VRAM ceiling, not the
     *                     stack's true requirement -- a later phase's signal to degrade (for
     *                     example, dropping PBR resolution further) rather than a value this class
     *                     acts on itself
     */
    // pageCount is deliberately a plain int with no bit-width assumption baked in. A page index
    // eventually has to fit somewhere in a_Position.w's packed code alongside light emission and
    // BlockClasses' flags (11 of 16 bits currently spare -- see FornaxChunkVertex.packBlockFacts),
    // and how many of those spare bits get committed to paging versus reserved for BlockClasses to
    // keep growing is an open owner decision, needed before the phase that wires page indices into
    // the vertex encoder, not this one. Nothing here assumes an answer either way.
    public record Decision(int pageCount, boolean vramLimited) {
    }

    /** Caps a resolved stack's natural page requirement at a VRAM-derived ceiling. */
    public static Decision decide(int pagesNeeded, int maxPages) {
        if (pagesNeeded < 0) {
            throw new IllegalArgumentException("pagesNeeded must not be negative");
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be at least 1");
        }
        return pagesNeeded <= maxPages
                ? new Decision(pagesNeeded, false)
                : new Decision(maxPages, true);
    }
}
