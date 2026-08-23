package dev.icehunter.fornax.pack;

/**
 * A pack-shipped static texture asset declared under {@code [textures.NAME]} in {@code graph.toml}
 * (e.g. {@code [textures.waterWaveNormal] file = "textures/water_wave_normal.png"}) -- the
 * texture-kind sibling of {@link TargetSpec}. Unlike a target, this is never a render output: it
 * names an image file shipped inside the pack directory, decoded and uploaded once per pack session
 * by {@code PackTextureRegistry}, and referenced by passes using its bare {@code name} (no
 * {@code builtin.} prefix -- it is not engine-generated, and no {@code .history} suffix -- it has no
 * ping-pong slot, just one static GPU texture for the pack's lifetime).
 *
 * @param name the declared table key ({@code [textures.NAME]}), also the input-ref string passes use
 * @param file the image file path, relative to the pack root (mirrors {@code TargetSpec}'s own
 *             pack-root-relative conventions for {@code blocks.toml}'s {@code categories.*.glsl})
 */
public record PackTextureSpec(String name, String file) {}
