package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pack.layout.RuntimeShaderPack;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the GLSL a pack wants compiled in place of a vanilla pipeline's own shader.
 *
 * <p>Substitution happens at the <em>shader source</em> level rather than by replacing the compiled
 * pipeline. Three reasons, in order of how badly the alternative fails:
 *
 * <ol>
 *   <li>The backend pipeline caches ({@code VulkanDevice.pipelineCache}, {@code GlDevice.pipelineCache})
 *       are identity maps filled by {@code computeIfAbsent}. Substituting a compiled pipeline pins
 *       whatever the first lookup returned, so a later pack switch keeps serving the old program with
 *       no error -- it just silently renders wrong.
 *   <li>{@code ShaderManager.apply} already calls {@code clearPipelineCache()} and then recompiles
 *       every static pipeline through a {@code ShaderSource}, so source substitution rides a path
 *       vanilla re-runs on every resource reload -- which is exactly when a pack change needs to take
 *       effect.
 *   <li>It is backend-agnostic: one hook covers both the GL and Vulkan devices, instead of a mixin
 *       per backend.
 * </ol>
 *
 * <p>Returning {@code null} means "compile vanilla's own source", which is the answer for every
 * pipeline Fornax does not map and every slot the active pack does not claim.
 */
public final class GeometryProgramSource {
    private GeometryProgramSource() {}

    /**
     * The {@link Identifier} whose source should be compiled in place of {@code pipeline}'s own for
     * the {@code type} stage, or {@code null} to leave vanilla's alone.
     *
     * <p><b>An identifier, not source text.</b> {@code ShaderManager}'s {@code getShaderSource}
     * returns GLSL that has already had its {@code #moj_import} directives resolved and inlined;
     * handing back raw file text instead makes the compiler reject the directive it never expects to
     * survive preprocessing ({@code Invalid Directive: moj_import}). Returning an identifier lets
     * vanilla's own loader do the resolution, which is also how the terrain program and every
     * fullscreen pass already reach {@code fornax_runtime:} sources.
     *
     * <p>Only returns an identifier when the pack genuinely ships that stage's file. A pack overriding
     * just the fragment stage is the normal case -- vanilla's vertex shader then still supplies the
     * varyings, so the vertex format and bind groups match with nothing to keep in sync.
     */
    @Nullable
    public static Identifier replacementIdentifierFor(@Nullable RenderPipeline pipeline, ShaderType type) {
        return replacementIdentifierFor(pipeline, type, GeometryPipelineMap.slotOf(pipeline));
    }

    /**
     * As above, but against an explicit slot rather than the one this pipeline normally maps to.
     * Used by the shadow-casting path, where the same vanilla entity pipeline needs the pack's
     * shadow-entities program instead of its entities program.
     */
    @Nullable
    public static Identifier replacementIdentifierFor(@Nullable RenderPipeline pipeline, ShaderType type,
                                                      @Nullable GeometrySlot slot) {
        if (pipeline == null || slot == null) {
            return null; // not a pipeline Fornax claims
        }
        String programPath = GraphRunner.geometryProgramPath(slot);
        if (programPath == null) {
            return null; // no pack active, or this pack does not claim the slot
        }
        RuntimeShaderPack pack = RuntimeShaderPack.getInstance();
        if (pack == null || pack.sourceOrNull("shaders/" + programPath + extensionOf(type)) == null) {
            // The pack does not ship this stage -- keep vanilla's.
            return null;
        }
        return Identifier.fromNamespaceAndPath(RuntimeShaderPack.NAMESPACE, programPath);
    }

    /**
     * Mirrors {@code ShaderType}'s own extensions. Kept as an explicit switch rather than reading
     * {@code ShaderType#extension} so an unexpected stage (a future compute/geometry stage arriving
     * through this path) fails loudly here rather than resolving to a plausible-looking wrong file.
     */
    private static String extensionOf(ShaderType type) {
        return switch (type) {
            case VERTEX -> ".vsh";
            case FRAGMENT -> ".fsh";
            default -> throw new IllegalArgumentException(
                    "Fornax: no geometry-program source mapping for shader stage " + type);
        };
    }
}
