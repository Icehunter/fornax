package dev.icehunter.fornax.pack;

/**
 * {@code TEMPORAL} is the one pass type whose SHADER is engine-owned: the pack declares where in
 * its graph temporal accumulation happens (one input target, one history-backed output target of
 * the same shape) and the engine runs its own motion-reprojected history blend there -- see
 * {@code TemporalPassRunner}. Everything else about it is an ordinary graph pass: declared order,
 * gate consistency, cycle detection and VRAM accounting all treat it like a FULLSCREEN pass with
 * a fixed, engine-supplied program.
 */
public enum PassType { GEOMETRY, FULLSCREEN, MIPCHAIN, COPY, COMPUTE, PARTICLES, TEMPORAL }
