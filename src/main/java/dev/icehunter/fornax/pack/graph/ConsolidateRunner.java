package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.atlas.ArrayTextures;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.TargetSpec;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A {@link dev.icehunter.fornax.pack.PassType#CONSOLIDATE} pass: copies N same-shaped declared
 * targets into one layer each of a shared array texture via {@link ArrayTextures#copyLayer}, so a
 * later pass reads them through one {@code sampler2DArray} instead of N {@code sampler2D}
 * bindings (see docs/ARCHITECTURE.md §12).
 *
 * <p>Owns its array texture outside {@link TargetRegistry}, like {@link MipchainRunner}: {@link
 * TargetKind} has no array kind, so this pass's output name is never a {@code [targets.*]} entry.
 * {@link GraphInputResolver} resolves it against {@link GraphRunner#consolidateTargets()} instead.
 *
 * <p>Sized one of two ways, never mixed in one pass: from the first input's own {@link
 * TargetSpec} via the usual {@link TargetPlan#textureWidth}/{@code textureHeight} formula, or, for
 * the allowlisted G-buffer builtins ({@code GraphValidator#CONSOLIDATE_BUILTIN_FORMATS}), directly
 * off render resolution via {@code GBufferManager.ensureSize}.
 *
 * <p>Not VRAM-budgeted: its cost doesn't appear in {@code GraphValidator}'s VRAM report, since
 * that walk is per-declared-target and this pass's output is not one (docs/ARCHITECTURE.md §12).
 */
public final class ConsolidateRunner implements AutoCloseable {
    private final PassSpec spec;
    @Nullable
    private final TargetSpec firstInputSpec;
    private final TargetFormat format;

    private int width;
    private int height;
    private ArrayTextures.@Nullable Allocation array;

    private ConsolidateRunner(PassSpec spec, @Nullable TargetSpec firstInputSpec, TargetFormat format) {
        this.spec = spec;
        this.firstInputSpec = firstInputSpec;
        this.format = format;
    }

    /** {@code firstInputSpec} is any one of {@code spec.inputs()}'s declared targets: every input
     * is already proven to share format and shape, so any one sizes the whole array. */
    public static ConsolidateRunner build(PassSpec spec, TargetSpec firstInputSpec) {
        TargetFormat format = TargetFormat.parse(firstInputSpec.format(), firstInputSpec.name(), "graph.toml");
        return new ConsolidateRunner(spec, firstInputSpec, format);
    }

    /** For a pass whose inputs are all allowlisted G-buffer builtins: sized directly off render
     * resolution in {@link #ensureSize}, with no {@link TargetSpec}. */
    public static ConsolidateRunner buildForBuiltins(PassSpec spec, String builtinFormat) {
        TargetFormat format = TargetFormat.parse(builtinFormat, spec.name() + " (builtin inputs)", "graph.toml");
        return new ConsolidateRunner(spec, null, format);
    }

    /** The array texture's all-layer view, for {@link GraphInputResolver} to hand back to a
     * {@code sampler2DArray} reader. {@code null} before the first successful {@link #ensureSize}. */
    @Nullable
    public GpuTextureView fullArrayView() {
        return array == null ? null : array.view();
    }

    public void ensureSize(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        int newWidth = firstInputSpec == null ? renderWidth
                : TargetPlan.textureWidth(firstInputSpec, renderWidth, outputWidth);
        int newHeight = firstInputSpec == null ? renderHeight
                : TargetPlan.textureHeight(firstInputSpec, renderHeight, outputHeight);
        if (array != null && width == newWidth && height == newHeight) {
            return;
        }

        ArrayTextures.Allocation newArray = ArrayTextures.create("Fornax Consolidate " + spec.outputs().get(0),
                TargetRegistry.gpuFormat(format), newWidth, newHeight, spec.inputs().size(), 1);
        if (newArray == null) {
            // No GPU device yet, or a non-Vulkan backend; fullArrayView() stays as it was.
            return;
        }

        ArrayTextures.Allocation old = array;
        array = newArray;
        width = newWidth;
        height = newHeight;
        if (old != null) {
            old.close();
        }
    }

    /** Copies each declared input into its own layer, in declaration order: input {@code i} lands
     * in layer {@code i}. A no-op before the first successful {@link #ensureSize}. */
    public void run(TargetRegistry registry, Map<String, MipchainRunner> mipchainTargets) {
        ArrayTextures.Allocation destination = array;
        if (destination == null) {
            return;
        }
        List<String> inputs = spec.inputs();
        for (int layer = 0; layer < inputs.size(); layer++) {
            GpuTextureView source = GraphInputResolver.resolveView(inputs.get(layer), registry, mipchainTargets);
            ArrayTextures.copyLayer(source, destination, layer);
        }
    }

    @Override
    public void close() {
        if (array != null) {
            array.close();
        }
    }
}
