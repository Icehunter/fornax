package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.TargetSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-JVM computation of what {@link TargetRegistry} must allocate for a given graph at a given
 * render resolution: which declared targets are actually enabled ({@code enabled_if} evaluated
 * against compile-option values, mirroring {@link GraphValidator}'s own VRAM-accounting pass),
 * their pixel size ({@code round(renderSize * scale)}, floored at 1), and their mip-level count
 * (more than 1 only for a target named as some {@code mipchain} pass's {@code target}).
 *
 * <p>Buffer-kind targets are planned here too, but on their own list ({@link #bufferEntries()}) and
 * only when the PACK declared their size -- see {@link BufferSize}. Before that syntax existed this
 * class skipped every buffer target unconditionally, which meant a pack literally could not own a
 * persistent GPU buffer: nothing anywhere would ever allocate it, and the first pass to bind it
 * threw instead.
 *
 * <p>Deliberately touches no {@code com.mojang.blaze3d} type -- this is the "graph compilation,
 * target planning" half that stays unit-testable; {@link TargetRegistry}
 * consumes a computed {@link TargetPlan} to do the actual (GPU-coupled, build+deploy-verified only)
 * texture allocation.
 */
public final class TargetPlan {
    /** Mirrors {@code HiZManager#MAX_LEVELS} from the old hardcoded pipeline, for parity with the pyramid it replaces. */
    public static final int MAX_MIP_LEVELS = 10;

    private final List<Entry> entries;
    private final List<BufferEntry> bufferEntries;

    private TargetPlan(List<Entry> entries, List<BufferEntry> bufferEntries) {
        this.entries = List.copyOf(entries);
        this.bufferEntries = List.copyOf(bufferEntries);
    }

    /** The TEXTURE targets to allocate. Buffer targets are {@link #bufferEntries()} instead: they
     * have no pixel format, extent or mip count, so folding them into this list would mean an
     * {@link Entry} whose fields are meaningless for half its instances. */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * The PACK-SIZED buffer targets to allocate, and their exact byte counts. An ENGINE-owned
     * buffer target ({@code TargetSpec.bufferSize() == null}) never appears here -- its bytes come
     * from an engine call site's own {@link TargetRegistry#ensureBufferSize}, which this plan has no
     * way to compute and must not second-guess.
     */
    public List<BufferEntry> bufferEntries() {
        return bufferEntries;
    }

    public Optional<Entry> find(String name) {
        return entries.stream().filter(e -> e.name().equals(name)).findFirst();
    }

    /**
     * Compat overload for callers that only ever dealt with one resolution (every existing caller,
     * pre-basis) -- delegates with {@code output == render}, so every target (all default
     * RENDER-basis until a pack opts a target into {@code basis = "output"}) sizes byte-identically
     * to before this overload existed.
     */
    public static TargetPlan compute(GraphSpec graph, Map<String, Integer> compileValues,
                                     int renderWidth, int renderHeight) {
        return compute(graph, compileValues, renderWidth, renderHeight, renderWidth, renderHeight);
    }

    public static TargetPlan compute(GraphSpec graph, Map<String, Integer> compileValues,
                                     int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        Set<String> mipchainTargets = new HashSet<>();
        for (PassSpec p : graph.passes()) {
            if (p.type() == PassType.MIPCHAIN && p.target() != null) {
                mipchainTargets.add(p.target());
            }
        }

        List<Entry> entries = new ArrayList<>();
        List<BufferEntry> bufferEntries = new ArrayList<>();
        for (TargetSpec t : graph.targets().values()) {
            if (t.kind() == TargetKind.BUFFER) {
                // An ENGINE-owned buffer (no declared size) is still skipped entirely -- it is sized
                // by its own owner via TargetRegistry.ensureBufferSize, never here. A PACK-sized one
                // is planned like any other target, gated by the same enabled_if, so a pack buffer
                // behind a disabled compile option costs no VRAM (and, dropping out of the plan,
                // gets freed -- see TargetRegistry.ensureSize).
                BufferSize size = t.bufferSize();
                if (size != null
                        && (t.enabledIf() == null || EnabledIfExpr.parse(t.enabledIf()).evaluate(compileValues))) {
                    bufferEntries.add(new BufferEntry(t.name(), size.sizeBytes()));
                }
                continue;
            }
            if (t.enabledIf() != null && !EnabledIfExpr.parse(t.enabledIf()).evaluate(compileValues)) {
                continue;
            }
            TargetFormat format = TargetFormat.parse(t.format(), t.name(), "graph.toml");
            int width = textureWidth(t, renderWidth, outputWidth);
            int height = textureHeight(t, renderHeight, outputHeight);
            int mipLevels = mipchainTargets.contains(t.name()) ? computeLevelCount(width, height) : 1;
            entries.add(new Entry(t.name(), format, width, height, t.history(), mipLevels, t.storage()));
        }
        return new TargetPlan(entries, bufferEntries);
    }

    /** Same max-reduce pyramid depth formula as {@code HiZManager.computeLevelCount}. */
    public static int computeLevelCount(int width, int height) {
        int minDim = Math.max(1, Math.min(width, height));
        int levels = 1 + (31 - Integer.numberOfLeadingZeros(minDim)); // 1 + floor(log2(minDim))
        return Math.min(levels, MAX_MIP_LEVELS);
    }

    /** Resolves one graph texture's width. Pack texture assets/atlases never pass through here. */
    static int textureWidth(TargetSpec target, int renderWidth, int outputWidth) {
        TextureSize fixed = target.fixedSize();
        if (fixed != null) return fixed.width();
        int base = target.basis() == TargetBasis.OUTPUT ? outputWidth : renderWidth;
        return Math.max(1, (int) Math.round(base * target.scale()));
    }

    /** Resolves one graph texture's height. Pack texture assets/atlases never pass through here. */
    static int textureHeight(TargetSpec target, int renderHeight, int outputHeight) {
        TextureSize fixed = target.fixedSize();
        if (fixed != null) return fixed.height();
        int base = target.basis() == TargetBasis.OUTPUT ? outputHeight : renderHeight;
        return Math.max(1, (int) Math.round(base * target.scale()));
    }

    public record Entry(String name, TargetFormat format, int width, int height, boolean history,
                        int mipLevels, boolean storage) {
    }

    /** One pack-sized buffer target and the exact byte count {@link TargetRegistry#ensureBufferSize}
     * must be called with. Resolution-independent by construction: a buffer's size comes from the
     * pack's own {@code stride_bytes} x {@code count}, never from {@code renderSize * scale}. */
    public record BufferEntry(String name, long sizeBytes) {
    }
}
