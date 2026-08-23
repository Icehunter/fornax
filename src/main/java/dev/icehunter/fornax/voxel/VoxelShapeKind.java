package dev.icehunter.fornax.voxel;

/** How much of a voxel cell a block's REAL shape (from vanilla's own {@code getOcclusionShape()})
 * actually fills -- derived from the game's own collision/occlusion geometry, never a hand-authored
 * per-block guess. {@code CROSS} (plant-style billboard geometry) is assigned later, in per-face
 * color resolution, which already has to walk the block's real model quads and can tell a billboard
 * model from a cube model directly -- this classifier only has shape data, not model data. */
public enum VoxelShapeKind { EMPTY, FULL, PARTIAL, CROSS }
