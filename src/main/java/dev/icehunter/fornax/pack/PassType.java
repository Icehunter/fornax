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
 *
 * <p>{@code RAY_QUERY} is shader-less like {@code COPY} and {@code CONSOLIDATE}, and is the one pass
 * type whose work has no fixed implementation at all: the pack declares a buffer of ray requests and
 * a buffer for the hits, and the engine routes the batch to whichever traversal on this machine can
 * answer it (see {@code dev.icehunter.fornax.rt.RayRouter}). A pack therefore asks for rays without
 * naming hardware, and the same declaration is answered by exact mesh tracing on one machine and an
 * approximate voxel march on another. Its two targets are both {@code kind = "buffer"}; the request
 * buffer must be written by an earlier pass in the same frame, which the validator checks, because a
 * batch of uninitialised requests traces garbage directions rather than failing.
 */
public enum PassType { GEOMETRY, FULLSCREEN, MIPCHAIN, COPY, COMPUTE, PARTICLES, TEMPORAL, CONSOLIDATE, RAY_QUERY }
