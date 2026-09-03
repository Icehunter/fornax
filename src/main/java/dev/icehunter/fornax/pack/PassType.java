package dev.icehunter.fornax.pack;

/**
 * {@code TEMPORAL} is the one pass type whose SHADER is engine-owned: the pack declares where in
 * its graph temporal accumulation happens (one input target, one history-backed output target of
 * the same shape) and the engine runs its own motion-reprojected history blend there -- see
 * {@code TemporalPassRunner}. Everything else about it is an ordinary graph pass: declared order,
 * gate consistency, cycle detection and VRAM accounting all treat it like a FULLSCREEN pass with
 * a fixed, engine-supplied program.
 *
 * <p>{@code CONSOLIDATE} is shader-less like {@code COPY}, but N-to-1: it copies several
 * same-shaped declared targets into one layer each of a shared array texture (see {@code
 * ConsolidateRunner}), so a later fullscreen pass reads them through one {@code sampler2DArray}
 * instead of one {@code sampler2D} per input. Its output is never a {@code [targets.*]} entry
 * ({@link dev.icehunter.fornax.pack.graph.TargetKind} has no array kind); {@code
 * GraphInputResolver} resolves it against {@code GraphRunner.consolidateTargets()} instead, like
 * {@code MipchainRunner} owns its shape outside the registry. Declared order, gate consistency
 * (inputs only; the pass itself may not carry {@code enabled_if}) and cycle detection treat it
 * like any other pass; VRAM accounting does not, since its output is not a declared target (a
 * known gap; see docs/ARCHITECTURE.md §12).
 */
public enum PassType { GEOMETRY, FULLSCREEN, MIPCHAIN, COPY, COMPUTE, PARTICLES, TEMPORAL, CONSOLIDATE }
