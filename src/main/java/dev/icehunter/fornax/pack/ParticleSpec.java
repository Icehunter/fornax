package dev.icehunter.fornax.pack;

/**
 * The two fields only a {@link PassType#PARTICLES} pass carries, grouped into their own record
 * rather than added as two more nullable {@link PassSpec} components -- the same reason
 * {@code PassSpec.slot} is documented as "non-null exactly on a GEOMETRY pass": a field that is
 * meaningless on five of the six pass types reads as "unset" at every one of them, and the loader
 * would then have to distinguish "absent because not applicable" from "absent because the author
 * forgot". One nullable group answers both questions at once -- non-null iff the pass is
 * {@code PARTICLES}, and complete when it is.
 *
 * <p>{@code vertexShader} is a pack-root-relative path to the billboard vertex stage. A particles
 * pass is the only pass type that names BOTH stages: {@code PassSpec.shader()} keeps its existing
 * meaning (the fragment shader, exactly as on a fullscreen pass) and this names its vertex partner.
 * Both are compiled to SPIR-V directly by {@code ParticlePipelineBuilder} via shaderc, NOT through
 * Blaze3D's shader manager -- so both files carry the same authoring contract a {@code .comp}
 * already does: Vulkan GLSL ({@code #version 450}), explicit {@code layout(binding = N)} on every
 * uniform/buffer, and no {@code #moj_import} (nothing resolves those on this path -- {@code
 * RuntimeShaderPack.sourceOrNull} hands back the file's own text).
 *
 * <p>{@code instances} is how many quads the draw issues: {@code vkCmdDraw(6, instances, 0, 0)}.
 * It is a literal rather than a count read back from the GPU because the pack's own simulation
 * compute pass allocates a fixed-size flake buffer and dispatches over the same fixed count -- the
 * two numbers are one authored constant, and an indirect draw would add a second source of truth
 * (plus a buffer round-trip) for a value that never varies at runtime. A flake the simulation
 * considers inactive is expected to collapse to a degenerate quad in the vertex shader, which costs
 * no fragments.
 */
public record ParticleSpec(String vertexShader, int instances) {}
