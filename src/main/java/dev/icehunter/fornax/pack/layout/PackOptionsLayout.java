package dev.icehunter.fornax.pack.layout;

import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Deterministic std140 layout of the runtime-option uniform block {@code u_PackOptions}. Members are laid
 * out in declaration order following std140 alignment; the block size is rounded up to a 16-byte multiple.
 *
 * <p>Declaration order is entirely the caller's responsibility: {@code OptionScanner} does not sort, it
 * merges options in first-encounter order across a caller-supplied, path-sorted {@code LinkedHashMap} of
 * shader sources (lexicographic file path, then line number within a file). This builder never reorders the
 * list it is given -- it is the single place that turns that order into byte offsets, which is what makes
 * the offsets deterministic build over build.
 *
 * <p>A scalar following a vec3 lands at the next 16-byte boundary, never in the vec3's trailing 4 bytes (the
 * engine's standing scalar-after-vec3 rule, made structural). This is deliberately stricter than the bare
 * GLSL std140 spec, which technically permits a scalar to pack into those trailing 4 bytes: the CPU-side
 * writer ({@code PackOptionsBuffer}) advances a vec3 member by a full 16 bytes, not 12, so treating a vec3's
 * layout footprint as 16 bytes here keeps the CPU write offsets and the GLSL block declaration in exact
 * agreement no matter what a given driver's std140 packing actually does with the spare 4 bytes.
 */
public final class PackOptionsLayout {
    private final Map<String, Integer> offsets;
    private final Map<String, String> glslTypes;
    private final int blockSize;

    private PackOptionsLayout(Map<String, Integer> offsets, Map<String, String> glslTypes, int blockSize) {
        this.offsets = offsets;
        this.glslTypes = glslTypes;
        this.blockSize = blockSize;
    }

    public Map<String, Integer> offsets() { return offsets; }
    public int blockSize() { return blockSize; }

    public static PackOptionsLayout build(List<PackOption> runtimeOptions) {
        return build(runtimeOptions, PackOptionsLayout::glslTypeOf);
    }

    /** Test/Plan-B seam: caller supplies the GLSL type per option. */
    static PackOptionsLayout build(List<PackOption> runtimeOptions, Function<PackOption, String> typeOf) {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        int cursor = 0;
        for (PackOption o : runtimeOptions) {
            if (o.type() != OptionType.RUNTIME) continue;
            String type = typeOf.apply(o);
            cursor = alignUp(cursor, alignmentOf(type));
            offsets.put(o.name(), cursor);
            types.put(o.name(), type);
            cursor += sizeOf(type);
        }
        return new PackOptionsLayout(offsets, types, Math.max(16, alignUp(cursor, 16)));
    }

    /**
     * GLSL text for the uniform block, prepended to every pack shader (after {@code #version}). Every
     * member carries an explicit {@code layout(offset = N)} taken straight from {@link #offsets()} so the
     * driver's std140 packing can never diverge from the CPU write offsets -- in particular the
     * scalar-after-vec3 case, where the spec would otherwise permit a driver to pack a scalar into a vec3's
     * trailing 4 bytes even though the CPU-side writer never puts anything there.
     *
     * <p>No explicit {@code set=}/{@code binding=} qualifier -- correct for a FULLSCREEN pass, whose
     * Blaze3D bind group resolves {@code u_PackOptions} by name, not by a positional descriptor index.
     * A COMPUTE pass's descriptor bindings ARE positional; use {@link #glslBlock(int)} for those.
     */
    public String glslBlock() {
        return glslBlock(null);
    }

    /**
     * Same block as {@link #glslBlock()}, but with an explicit {@code layout(std140, set = 0, binding =
     * binding)} qualifier -- required for a COMPUTE pass, whose descriptor set is built positionally
     * (binding N = the Nth entry in {@code ComputePassRunner.combinedBindingOrder}; set 0 is the single
     * descriptor set every compute pipeline binds at {@code firstSet = 0}), so the shader's binding
     * number must match exactly where {@code u_PackOptions}' reserved {@code "packOptions"} input
     * actually lands or the shader won't compile/bind correctly.
     */
    public String glslBlock(int binding) {
        return glslBlock(Integer.valueOf(binding));
    }

    private String glslBlock(Integer binding) {
        StringBuilder sb = new StringBuilder();
        sb.append("#extension GL_ARB_enhanced_layouts : require\n");
        if (binding == null) {
            sb.append("layout(std140) uniform u_PackOptions {\n");
        } else {
            sb.append("layout(std140, set = 0, binding = ").append(binding).append(") uniform u_PackOptions {\n");
        }
        for (Map.Entry<String, String> e : glslTypes.entrySet()) {
            sb.append("    layout(offset = ").append(offsets.get(e.getKey())).append(") ")
              .append(e.getValue()).append(' ').append(e.getKey()).append(";\n");
        }
        sb.append("};\n");
        return sb.toString();
    }

    // v0.1 runtime options are all floats; vec/int typed options are a possible future extension.
    private static String glslTypeOf(PackOption o) { return "float"; }

    private static int alignmentOf(String type) {
        return switch (type) {
            case "vec2" -> 8;
            case "vec3", "vec4" -> 16;
            default -> 4;
        };
    }

    private static int sizeOf(String type) {
        return switch (type) {
            case "vec2" -> 8;
            // Not the spec-minimal 12: see the class Javadoc. Treating a vec3's footprint as a full
            // 16 bytes guarantees the cursor is 16-aligned again immediately after it, so the very next
            // member's own alignUp() is a no-op and can never land in the vec3's last 4 bytes.
            case "vec3", "vec4" -> 16;
            default -> 4;
        };
    }

    private static int alignUp(int v, int a) { return (v + a - 1) / a * a; }
}
