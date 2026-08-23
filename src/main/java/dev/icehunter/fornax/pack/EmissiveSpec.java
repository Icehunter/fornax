package dev.icehunter.fornax.pack;

import org.jspecify.annotations.Nullable;

/** A category's emissive synthesis parameters (blocks.toml inline table). {@code color} is an
 * optional authored RGB hue (see {@link EmissiveColor}); when absent the GPU-side emission word
 * packs zero and the shader falls back to deriving a tint from the block's own face colors.
 * {@code force} makes Tier-2 emissive synthesis run even when the texel already carries authored
 * LabPBR {@code _s} alpha emission (including an explicit zero) -- unlike smoothness/f0's shared
 * category-level {@code force_override}, this is scoped to emissive alone since a pack may want a
 * category's smoothness left gap-fill-only while still forcing its glow (e.g. a pack whose ore
 * flecks author {@code _s} alpha = 0, an explicit "no emission" that Tier-2's default
 * gap-fill-only gate would otherwise never override). Defaults false. */
public record EmissiveSpec(String source, double strength, @Nullable EmissiveColor color, boolean force) {}
