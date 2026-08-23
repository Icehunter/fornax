package dev.icehunter.fornax.pack;
import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * {@code localSize}, when present, is a compute pass's {@code layout(local_size_x=X, local_size_y=Y)}
 * declared in its own {@code .comp} source, restated here so {@code ComputePassRunner} can derive
 * real dispatch group counts ({@code ceil(outputWidth/X), ceil(outputHeight/Y)}) from the pass's
 * first output target's ACTUAL resolved pixel size every dispatch, instead of a static literal --
 * required for any compute pass whose output target scales with render resolution (most of them).
 * {@code null} means the pass dispatches with {@code dispatch}'s x/y literally, unchanged from before
 * this field existed -- for a compute pass whose group count is genuinely fixed, not resolution-derived.
 *
 * <p>{@code blend}, when present, is one of {@code "translucent"}, {@code "additive"}, or {@code
 * "multiply"} and is only
 * ever legal on a {@link PassType#FULLSCREEN} pass (see {@code GraphValidator}) -- it selects the
 * hardware blend preset {@code FullscreenPassRunner.build} passes to the pipeline's {@code
 * ColorTargetState} instead of the default opaque overwrite, letting a pass composite over its
 * output attachment's LOAD-preserved contents rather than read it back as a sampler input (which
 * would be a same-frame read-write hazard on {@code builtin.output}). {@code null} means opaque,
 * unchanged from before this field existed.
 *
 * <p>{@code slot} is non-null exactly on a {@link PassType#GEOMETRY} pass, where it names which kind
 * of geometry this pass's {@code program} shades (see {@link GeometrySlot}); a geometry pass omitting
 * {@code slot} in TOML gets {@link GeometrySlot#DEFAULT}. Every other pass type rejects the key at
 * load rather than carrying a null that means "not applicable" and one that means "unset".
 *
 * <p>{@code particles} is non-null exactly on a {@link PassType#PARTICLES} pass and carries the two
 * fields only that pass type has -- see {@link ParticleSpec}. Same rule as {@code slot}: every other
 * pass type rejects {@code vertex_shader}/{@code instances} at load.
 */
public record PassSpec(String name, PassType type, @Nullable GeometrySlot slot, @Nullable String program,
                       @Nullable String shader, List<String> inputs, List<String> outputs,
                       @Nullable String target, @Nullable String enabledIf, List<Integer> dispatch,
                       @Nullable List<Integer> localSize, @Nullable String blend,
                       @Nullable ParticleSpec particles) {}
