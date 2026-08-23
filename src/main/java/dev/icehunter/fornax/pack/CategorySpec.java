package dev.icehunter.fornax.pack;

import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * One named material category from blocks.toml. {@code blocks} entries are block registry ids
 * ("minecraft:iron_block") or block tags; an entry that names no existing block is retried as a tag,
 * and a leading '#' forces tag interpretation (see BlockMaterialResolver). {@code forceOverride}
 * makes tier-2 synthesis override present labPBR data instead of only filling gaps. {@code glsl} is
 * an optional pack-relative path to a per-category tier-3 snippet.
 *
 * <p>{@code cutout}/{@code cross} are id-only voxel-harvest flags (mirroring the id-only {@code
 * [categories.foliage]}/{@code [categories.water]} pattern -- no synthesis fields, just a
 * classification the harvest path reads via {@code MaterialScalars.isCutout}/{@code isCross}).
 * {@code cutout} marks a block whose real appearance is alpha-tested (leaves, cross plants) rather
 * than fully opaque, so voxel harvesting should capture its atlas UV rect for a per-texel shadow
 * alpha test instead of treating every occupied voxel as a hard occluder. {@code cross} additionally
 * marks a block as cross/billboard-shaped (two diagonal quads, not a cube) -- always paired with
 * {@code cutout} in practice, but kept as its own flag since a hypothetical modded block could be
 * cross-shaped without alpha-cutout texture (or vice versa, an alpha-cutout cube like a leaf block).
 */
public record CategorySpec(String name, List<String> blocks, boolean forceOverride,
                           @Nullable String glsl, @Nullable SmoothnessSpec smoothness,
                           @Nullable String f0, @Nullable EmissiveSpec emissive,
                           boolean cutout, boolean cross) {}
