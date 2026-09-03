package dev.icehunter.fornax.pack;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.icehunter.fornax.pack.graph.BufferSize;
import dev.icehunter.fornax.pack.graph.TargetBasis;
import dev.icehunter.fornax.pack.graph.TargetFilter;
import dev.icehunter.fornax.pack.graph.TargetKind;
import dev.icehunter.fornax.pack.graph.TextureSize;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses the pack manifests into immutable records. Pure over a {@link Reader} for testability. */
public final class PackTomlLoader {
    private static final Pattern CATEGORY_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    static {
        // night-config's in-memory tables are HashMap-backed by default (arbitrary iteration
        // order); GraphSpec.targets() is documented as insertion-ordered, so every table this
        // loader produces needs to preserve TOML source order.
        Config.setInsertionOrderPreserved(true);
    }

    private PackTomlLoader() {}

    public static PackMeta loadMeta(Reader reader, String file) {
        Config root = parse(reader, file);
        TomlSupport.rejectUnknownKeys(root, Set.of("pack"), file);
        if (!root.contains("pack")) {
            throw new FornaxPackError(file, "pack", "missing required [pack] table");
        }
        Config pack = requireTable(root.get("pack"), "pack", file);
        TomlSupport.rejectUnknownKeys(pack, Set.of("name", "version", "authors", "license", "format"), file);
        return new PackMeta(
                TomlSupport.requireString(pack, "name", file),
                TomlSupport.requireString(pack, "version", file),
                TomlSupport.getStringList(pack, "authors", file),
                TomlSupport.requireString(pack, "license", file),
                TomlSupport.requireInt(pack, "format", file));
    }

    public static GraphSpec loadGraph(Reader reader, String file) {
        Config root = parse(reader, file);
        TomlSupport.rejectUnknownKeys(root, Set.of("targets", "textures", "pass"), file);
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        if (root.contains("targets")) {
            Config t = requireTable(root.get("targets"), "targets", file);
            for (Config.Entry e : t.entrySet()) {
                String name = e.getKey();
                Object raw = e.getValue();
                if (!(raw instanceof Config spec)) {
                    throw new FornaxPackError(file, "targets." + name, "target must be a table");
                }
                String kindToken = TomlSupport.getStringOrNull(spec, "kind", file);
                TargetKind kind = kindToken == null ? TargetKind.TEXTURE : TargetKind.parse(kindToken, name, file);
                if (kind == TargetKind.BUFFER) {
                    // stride_bytes/count are buffer-ONLY, and the texture branch below deliberately
                    // does not list them either -- so a texture target naming one gets
                    // rejectUnknownKeys' own "unknown key" error, exactly the way a buffer target
                    // naming `format` already does (see ComputeTargetBindingTest's
                    // bufferKindTargetRejectsTextureOnlyKeys). One symmetric mechanism, not two.
                    TomlSupport.rejectUnknownKeys(spec, Set.of("kind", "enabled_if", "stride_bytes", "count"), file);
                    targets.put(name, TargetSpec.buffer(name,
                            TomlSupport.getStringOrNull(spec, "enabled_if", file),
                            parseBufferSize(spec, name, file)));
                } else {
                    TomlSupport.rejectUnknownKeys(spec,
                            Set.of("kind", "format", "scale", "width", "height", "history",
                                    "enabled_if", "basis", "filter", "storage"), file);
                    boolean hasWidth = spec.contains("width");
                    boolean hasHeight = spec.contains("height");
                    if (hasWidth != hasHeight) {
                        throw new FornaxPackError(file, "targets." + name,
                                "fixed texture targets must declare both width and height");
                    }
                    if (hasWidth && (spec.contains("scale") || spec.contains("basis"))) {
                        throw new FornaxPackError(file, "targets." + name,
                                "fixed width/height are mutually exclusive with scale and basis");
                    }
                    TextureSize fixedSize = null;
                    if (hasWidth) {
                        int width = TomlSupport.requireInt(spec, "width", file);
                        int height = TomlSupport.requireInt(spec, "height", file);
                        if (width <= 0 || height <= 0) {
                            throw new FornaxPackError(file, "targets." + name,
                                    "fixed texture width and height must be positive");
                        }
                        fixedSize = new TextureSize(width, height);
                    }
                    String basisToken = TomlSupport.getStringOrNull(spec, "basis", file);
                    TargetBasis basis = basisToken == null ? TargetBasis.RENDER : TargetBasis.parse(basisToken, name, file);
                    String filterToken = TomlSupport.getStringOrNull(spec, "filter", file);
                    TargetFilter filter = filterToken == null
                            ? TargetFilter.NEAREST : TargetFilter.parse(filterToken, name, file);
                    targets.put(name, new TargetSpec(
                            name,
                            TomlSupport.requireString(spec, "format", file),
                            TomlSupport.getDouble(spec, "scale", 1.0, file),
                            TomlSupport.getBoolean(spec, "history", false, file),
                            TomlSupport.getStringOrNull(spec, "enabled_if", file),
                            basis,
                            TargetKind.TEXTURE,
                            filter,
                            null,
                            fixedSize,
                            TomlSupport.getBoolean(spec, "storage", false, file)));
                }
            }
        }

        // [textures.NAME]: a pack-shipped static image asset (e.g. [textures.waterWaveNormal]
        // file = "textures/water_wave_normal.png"), the texture-kind sibling of [targets.NAME] --
        // see PackTextureSpec's own doc. A separate top-level table from [targets.*] (not a
        // TargetKind variant) since it carries none of a target's render-output machinery (no
        // format/scale/history/enabled_if/basis -- just a name and a file path).
        Map<String, PackTextureSpec> textures = new LinkedHashMap<>();
        if (root.contains("textures")) {
            Config tx = requireTable(root.get("textures"), "textures", file);
            for (Config.Entry e : tx.entrySet()) {
                String name = e.getKey();
                Object raw = e.getValue();
                if (!(raw instanceof Config spec)) {
                    throw new FornaxPackError(file, "textures." + name, "texture must be a table");
                }
                TomlSupport.rejectUnknownKeys(spec, Set.of("file", "depth", "width", "height", "format"), file);
                String texFile = TomlSupport.requireString(spec, "file", file);
                Integer depth = spec.contains("depth") ? TomlSupport.requireInt(spec, "depth", file) : null;
                if (depth == null) {
                    if (spec.contains("width") || spec.contains("height") || spec.contains("format")) {
                        throw new FornaxPackError(file, "textures." + name,
                                "width/height/format are only valid alongside depth (a volume texture); "
                                        + "a 2D [textures." + name + "] takes its dimensions from the "
                                        + "decoded image file");
                    }
                    textures.put(name, PackTextureSpec.texture2D(name, texFile));
                } else {
                    if (depth <= 0) {
                        throw new FornaxPackError(file, "textures." + name + ".depth",
                                "volume texture depth must be positive, got " + depth);
                    }
                    if (!spec.contains("width") || !spec.contains("height")) {
                        throw new FornaxPackError(file, "textures." + name,
                                "a volume texture (depth declared) must also declare width and height");
                    }
                    int width = TomlSupport.requireInt(spec, "width", file);
                    int height = TomlSupport.requireInt(spec, "height", file);
                    if (width <= 0 || height <= 0) {
                        throw new FornaxPackError(file, "textures." + name,
                                "volume texture width and height must be positive");
                    }
                    String format = TomlSupport.requireString(spec, "format", file);
                    textures.put(name, new PackTextureSpec(name, texFile, depth, width, height, format));
                }
            }
        }

        List<PassSpec> passes = new ArrayList<>();
        Object passList = root.get("pass");
        if (passList instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Config p)) {
                    throw new FornaxPackError(file, "pass", "each [[pass]] must be a table");
                }
                String name = TomlSupport.requireString(p, "name", file);
                TomlSupport.rejectUnknownKeys(p, Set.of("name", "type", "slot", "program",
                        "shader", "inputs", "outputs", "target", "enabled_if", "dispatch", "local_size",
                        "blend", "vertex_shader", "instances"), file);
                PassType type = parsePassType(TomlSupport.requireString(p, "type", file), name, file);
                List<Integer> dispatch = TomlSupport.getIntList(p, "dispatch", file);
                if (type == PassType.COMPUTE && dispatch.size() != 3) {
                    throw new FornaxPackError(file, "pass." + name + ".dispatch",
                            "a compute pass must declare dispatch = [x, y, z] (3 positive group counts)");
                }
                if (type != PassType.COMPUTE && !dispatch.isEmpty()) {
                    throw new FornaxPackError(file, "pass." + name + ".dispatch",
                            "'dispatch' is only valid on a compute pass");
                }
                for (int d : dispatch) {
                    if (d <= 0) {
                        throw new FornaxPackError(file, "pass." + name + ".dispatch",
                                "dispatch group counts must be positive, got " + d);
                    }
                }
                List<Integer> localSizeRaw = TomlSupport.getIntList(p, "local_size", file);
                if (type != PassType.COMPUTE && !localSizeRaw.isEmpty()) {
                    throw new FornaxPackError(file, "pass." + name + ".local_size",
                            "'local_size' is only valid on a compute pass");
                }
                if (!localSizeRaw.isEmpty() && localSizeRaw.size() != 2) {
                    throw new FornaxPackError(file, "pass." + name + ".local_size",
                            "local_size must be exactly [x, y] (2 positive group sizes)");
                }
                for (int s : localSizeRaw) {
                    if (s <= 0) {
                        throw new FornaxPackError(file, "pass." + name + ".local_size",
                                "local_size values must be positive, got " + s);
                    }
                }
                @Nullable List<Integer> localSize = localSizeRaw.isEmpty() ? null : localSizeRaw;
                // 'slot' names the geometry program slot (see GeometrySlot) and is meaningless on
                // every other pass type -- rejected there rather than ignored, so a misplaced key is
                // a load error instead of a silently inert one. Absent on a geometry pass means
                // GeometrySlot.DEFAULT (terrain), which is what a single-program pack wants.
                String slotToken = TomlSupport.getStringOrNull(p, "slot", file);
                if (type != PassType.GEOMETRY && slotToken != null) {
                    throw new FornaxPackError(file, "pass." + name + ".slot",
                            "'slot' is only valid on a geometry pass");
                }
                GeometrySlot slot = type == PassType.GEOMETRY
                        ? (slotToken == null ? GeometrySlot.DEFAULT : GeometrySlot.parse(slotToken, name, file))
                        : null;
                ParticleSpec particles = parseParticleSpec(p, type, name, file);
                passes.add(new PassSpec(
                        name,
                        type,
                        slot,
                        TomlSupport.getStringOrNull(p, "program", file),
                        TomlSupport.getStringOrNull(p, "shader", file),
                        TomlSupport.getStringList(p, "inputs", file),
                        TomlSupport.getStringList(p, "outputs", file),
                        TomlSupport.getStringOrNull(p, "target", file),
                        TomlSupport.getStringOrNull(p, "enabled_if", file),
                        dispatch,
                        localSize,
                        TomlSupport.getStringOrNull(p, "blend", file),
                        particles));
            }
        }
        return new GraphSpec(targets, textures, passes);
    }

    public static ScreensSpec loadScreens(Reader reader, String file) {
        Config root = parse(reader, file);
        TomlSupport.rejectUnknownKeys(root, Set.of("main", "screens", "profiles", "sliders", "metas", "yacl", "descriptions"), file);

        Object mainRaw = root.get("main");
        MainScreenSpec mainSpec;
        if (mainRaw == null) {
            mainSpec = new MainScreenSpec(List.of(), 1);
        } else if (!(mainRaw instanceof Config main)) {
            throw new FornaxPackError(file, "main", "[main] must be a table");
        } else {
            TomlSupport.rejectUnknownKeys(main, Set.of("elements", "columns"), file);
            mainSpec = new MainScreenSpec(TomlSupport.getStringList(main, "elements", file),
                    (int) TomlSupport.getDouble(main, "columns", 1, file));
        }

        Map<String, ScreenSpec> screens = new LinkedHashMap<>();
        if (root.contains("screens")) {
            Config s = requireTable(root.get("screens"), "screens", file);
            for (Config.Entry e : s.entrySet()) {
                if (!(e.getValue() instanceof Config spec)) {
                    throw new FornaxPackError(file, "screens." + e.getKey(), "screen must be a table");
                }
                TomlSupport.rejectUnknownKeys(spec, Set.of("title", "elements"), file);
                String title = TomlSupport.getStringOrNull(spec, "title", file);
                screens.put(e.getKey(), new ScreenSpec(
                        title == null ? e.getKey() : title,
                        TomlSupport.getStringList(spec, "elements", file)));
            }
        }

        Map<String, ProfileSpec> profiles = new LinkedHashMap<>();
        if (root.contains("profiles")) {
            Config pr = requireTable(root.get("profiles"), "profiles", file);
            for (Config.Entry e : pr.entrySet()) {
                if (!(e.getValue() instanceof Config spec)) {
                    throw new FornaxPackError(file, "profiles." + e.getKey(), "profile must be a table");
                }
                TomlSupport.rejectUnknownKeys(spec, Set.of("values"), file);
                Object rawValues = spec.get("values");
                Map<String, Object> valueMap = new LinkedHashMap<>();
                if (rawValues != null) {
                    if (!(rawValues instanceof Config values)) {
                        throw new FornaxPackError(file, "profiles." + e.getKey() + ".values",
                                "values must be a table of option assignments");
                    }
                    for (Config.Entry ve : values.entrySet()) {
                        valueMap.put(ve.getKey(), ve.getValue());
                    }
                }
                profiles.put(e.getKey(), new ProfileSpec(valueMap));
            }
        }

        Map<String, MetaSpec> metas = new LinkedHashMap<>();
        if (root.contains("metas")) {
            Config ms = requireTable(root.get("metas"), "metas", file);
            for (Config.Entry e : ms.entrySet()) {
                if (!(e.getValue() instanceof Config spec)) {
                    throw new FornaxPackError(file, "metas." + e.getKey(), "meta must be a table");
                }
                TomlSupport.rejectUnknownKeys(spec,
                        Set.of("label", "description", "values", "assign", "dependsOn"), file);
                String label = TomlSupport.getStringOrNull(spec, "label", file);
                String description = TomlSupport.getStringOrNull(spec, "description", file);
                String dependsOn = TomlSupport.getStringOrNull(spec, "dependsOn", file);
                List<String> values = TomlSupport.getStringList(spec, "values", file);
                if (values.isEmpty()) {
                    throw new FornaxPackError(file, "metas." + e.getKey() + ".values",
                            "meta must declare a non-empty values list");
                }
                Map<String, Map<String, Object>> assign = new LinkedHashMap<>();
                Object rawAssign = spec.get("assign");
                if (rawAssign != null) {
                    if (!(rawAssign instanceof Config assignTable)) {
                        throw new FornaxPackError(file, "metas." + e.getKey() + ".assign",
                                "assign must be a table of per-tier assignment tables");
                    }
                    for (Config.Entry tier : assignTable.entrySet()) {
                        if (!values.contains(tier.getKey())) {
                            throw new FornaxPackError(file, "metas." + e.getKey() + ".assign." + tier.getKey(),
                                    "assign tier '" + tier.getKey() + "' is not one of this meta's values");
                        }
                        if (!(tier.getValue() instanceof Config tierTable)) {
                            throw new FornaxPackError(file, "metas." + e.getKey() + ".assign." + tier.getKey(),
                                    "assign tier must be a table of option assignments");
                        }
                        Map<String, Object> tierMap = new LinkedHashMap<>();
                        for (Config.Entry a : tierTable.entrySet()) {
                            tierMap.put(a.getKey(), a.getValue());
                        }
                        assign.put(tier.getKey(), tierMap);
                    }
                }
                metas.put(e.getKey(), new MetaSpec(
                        label == null ? e.getKey() : label,
                        description == null ? "" : description,
                        values, assign, dependsOn));
            }
        }

        List<String> yaclPages = List.of();
        if (root.contains("yacl")) {
            Config yacl = requireTable(root.get("yacl"), "yacl", file);
            TomlSupport.rejectUnknownKeys(yacl, Set.of("pages"), file);
            yaclPages = TomlSupport.getStringList(yacl, "pages", file);
        }

        // [descriptions] is a flat option-name -> prose table. Kept out of the option annotation
        // grammar deliberately: annotations live in shader source, where a sentence of user-facing
        // prose per option would bury the code, and where the same option declared in two files would
        // have to repeat it byte-identically.
        Map<String, String> descriptions = new LinkedHashMap<>();
        if (root.contains("descriptions")) {
            Config table = requireTable(root.get("descriptions"), "descriptions", file);
            for (Config.Entry e : table.entrySet()) {
                Object raw = e.getValue();
                if (!(raw instanceof String text)) {
                    throw new FornaxPackError(file, "descriptions." + e.getKey(),
                            "a description must be a string");
                }
                descriptions.put(e.getKey(), text);
            }
        }

        return new ScreensSpec(mainSpec, screens, profiles,
                TomlSupport.getStringList(root, "sliders", file), metas, yaclPages, descriptions);
    }

    public static BlocksSpec loadBlocks(Reader reader, String file) {
        Config root = parse(reader, file);
        TomlSupport.rejectUnknownKeys(root, Set.of("categories"), file);
        Map<String, CategorySpec> cats = new LinkedHashMap<>();
        if (root.contains("categories")) {
            Config c = requireTable(root.get("categories"), "categories", file);
            for (Config.Entry e : c.entrySet()) {
                String name = e.getKey();
                if (!CATEGORY_NAME.matcher(name).matches()) {
                    throw new FornaxPackError(file, "categories." + name,
                            "category name must match [a-z][a-z0-9_]* (used to form MAT_<NAME>)");
                }
                if (!(e.getValue() instanceof Config spec)) {
                    throw new FornaxPackError(file, "categories." + name, "category must be a table");
                }
                TomlSupport.rejectUnknownKeys(spec,
                        Set.of("blocks", "smoothness", "f0", "emissive", "force_override", "glsl",
                                "cutout", "cross"), file);
                String f0 = TomlSupport.getStringOrNull(spec, "f0", file);
                if (f0 != null && !"metal_albedo".equals(f0)) {
                    throw new FornaxPackError(file, "categories." + name + ".f0",
                            "unknown f0 mode '" + f0 + "' (legal values: metal_albedo)");
                }
                cats.put(name, new CategorySpec(
                        name,
                        TomlSupport.getStringList(spec, "blocks", file),
                        TomlSupport.getBoolean(spec, "force_override", false, file),
                        TomlSupport.getStringOrNull(spec, "glsl", file),
                        parseSmoothness(spec, name, file),
                        f0,
                        parseEmissive(spec, name, file),
                        TomlSupport.getBoolean(spec, "cutout", false, file),
                        TomlSupport.getBoolean(spec, "cross", false, file)));
            }
        }
        return new BlocksSpec(cats);
    }

    private static SmoothnessSpec parseSmoothness(Config cat, String name, String file) {
        String keyPath = "categories." + name + ".smoothness";
        Config t = synthesisTable(cat, "smoothness", keyPath, file, Set.of("source", "curve", "min", "scale"));
        if (t == null) return null;
        return new SmoothnessSpec(
                // source is optional: a category can declare smoothness purely to `scale` AUTHORED
                // _s data (no albedo-luma synthesis at all) -- see SmoothnessSpec's own doc comment.
                optionalSmoothnessSource(t, keyPath, file),
                // curve in (0, 8]: pow() exponent over albedo luma -- 0 degenerates to constant 1.0
                // (min never matters), and past 8 the curve is a near-step that only posterizes.
                inRange(TomlSupport.getDouble(t, "curve", 1.0, file), 0.0, false, 8.0,
                        keyPath + ".curve", file),
                // min in [0, 1]: clamp floor for a [0, 1] smoothness value.
                inRange(TomlSupport.getDouble(t, "min", 0.0, file), 0.0, true, 1.0,
                        keyPath + ".min", file),
                // scale in (0, 4]: multiplier applied to AUTHORED _s smoothness (terrain.fsh applies it
                // once, before Tier-2 gap-fill/override runs, so it never touches a synthesized fallback
                // value) -- 1.0 is the neutral default every category gets unless it declares otherwise.
                inRange(TomlSupport.getDouble(t, "scale", 1.0, file), 0.0, false, 4.0,
                        keyPath + ".scale", file));
    }

    private static @Nullable String optionalSmoothnessSource(Config t, String keyPath, String file) {
        String source = TomlSupport.getStringOrNull(t, "source", file);
        if (source != null && !"albedo_luma".equals(source)) {
            throw new FornaxPackError(file, keyPath + ".source",
                    "unknown source '" + source + "' (legal values: albedo_luma)");
        }
        return source;
    }

    private static EmissiveSpec parseEmissive(Config cat, String name, String file) {
        String keyPath = "categories." + name + ".emissive";
        Config t = synthesisTable(cat, "emissive", keyPath, file, Set.of("source", "strength", "color", "force"));
        if (t == null) return null;
        return new EmissiveSpec(
                requireSource(t, keyPath, file),
                // strength in [0, 4]: emission multiplier; 1.0 is nominal, 4x is the HDR headroom cap.
                inRange(TomlSupport.getDouble(t, "strength", 1.0, file), 0.0, true, 4.0,
                        keyPath + ".strength", file),
                parseEmissiveColor(t, keyPath, file),
                // force: synthesize emission even over authored (including explicit-zero) LabPBR _s
                // alpha data -- see EmissiveSpec's doc comment. Defaults false (gap-fill-only, matching
                // every other Tier-2 synthesis knob's default behavior).
                TomlSupport.getBoolean(t, "force", false, file));
    }

    /** Optional {@code color = [r, g, b]}: either 0-255 ints or 0-1 floats. Disambiguated per-array
     * (not per-component, since a lone 0 or 1 component is ambiguous on its own): if ANY component is
     * greater than 1, the whole triple is read as 0-255 ints; otherwise every component is a 0-1
     * float scaled by 255. This means {@code [1, 1, 1]} reads as float white (255,255,255) rather than
     * near-black int (1,1,1) -- the far more likely authoring intent, and consistent with "small
     * values mean the 0-1 form" being the only case actually ambiguous. */
    private static @Nullable EmissiveColor parseEmissiveColor(Config t, String keyPath, String file) {
        String colorKeyPath = keyPath + ".color";
        if (!t.contains("color")) return null;
        Object raw = t.get("color");
        if (!(raw instanceof List<?> list) || list.size() != 3) {
            throw new FornaxPackError(file, colorKeyPath, "expected [r, g, b] (3 numbers)");
        }
        double[] components = new double[3];
        boolean normalized = true;
        for (int i = 0; i < 3; i++) {
            if (!(list.get(i) instanceof Number n)) {
                throw new FornaxPackError(file, colorKeyPath, "color components must be numbers");
            }
            components[i] = n.doubleValue();
            if (components[i] > 1.0) {
                normalized = false;
            }
        }
        int[] rgb = new int[3];
        for (int i = 0; i < 3; i++) {
            double scaled = normalized ? components[i] * 255.0 : components[i];
            rgb[i] = (int) Math.round(inRange(scaled, 0.0, true, 255.0, colorKeyPath, file));
        }
        return new EmissiveColor(rgb[0], rgb[1], rgb[2]);
    }

    /** Fetches an optional synthesis inline table, strict on shape and keys; null when absent. */
    private static Config synthesisTable(Config cat, String key, String keyPath, String file, Set<String> allowed) {
        if (!cat.contains(key)) return null;
        if (!(cat.get(key) instanceof Config t)) {
            throw new FornaxPackError(file, keyPath, "must be an inline table");
        }
        TomlSupport.rejectUnknownKeys(t, allowed, file);
        return t;
    }

    private static String requireSource(Config t, String keyPath, String file) {
        String source = TomlSupport.requireString(t, "source", file);
        if (!"albedo_luma".equals(source)) {
            throw new FornaxPackError(file, keyPath + ".source",
                    "unknown source '" + source + "' (legal values: albedo_luma)");
        }
        return source;
    }

    private static double inRange(double v, double min, boolean minInclusive, double max, String key, String file) {
        boolean ok = (minInclusive ? v >= min : v > min) && v <= max;
        if (!ok) {
            throw new FornaxPackError(file, key, "value " + v + " out of range "
                    + (minInclusive ? "[" : "(") + min + ", " + max + "]");
        }
        return v;
    }

    private static Config requireTable(Object raw, String key, String file) {
        if (!(raw instanceof Config c)) {
            throw new FornaxPackError(file, key, "[" + key + "] must be a table");
        }
        return c;
    }

    private static PassType parsePassType(String raw, String passName, String file) {
        try {
            return PassType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new FornaxPackError(file, "pass." + passName + ".type",
                    "unknown pass type '" + raw + "' (expected geometry|fullscreen|mipchain|copy|compute|particles|temporal|consolidate)");
        }
    }

    /**
     * {@code vertex_shader} + {@code instances} for a {@link PassType#PARTICLES} pass, or null for
     * every other type -- which must not name either key at all. Same "reject, don't ignore" rule
     * {@code slot}/{@code dispatch}/{@code local_size} already follow above: a key that silently does
     * nothing is worse than a load error, because the author has no way to tell it was dropped.
     *
     * <p>Both keys are required on a particles pass. There is no defensible default for either:
     * without a vertex stage there is no billboard to draw, and an implied instance count would make
     * the single most performance-relevant number in the pass invisible in {@code graph.toml}.
     */
    @Nullable
    private static ParticleSpec parseParticleSpec(Config p, PassType type, String name, String file) {
        String vertexShader = TomlSupport.getStringOrNull(p, "vertex_shader", file);
        boolean hasInstances = p.contains("instances");
        if (type != PassType.PARTICLES) {
            if (vertexShader != null) {
                throw new FornaxPackError(file, "pass." + name + ".vertex_shader",
                        "'vertex_shader' is only valid on a particles pass (pass '" + name + "' is "
                                + type + "); every other pass type names at most one shader stage");
            }
            if (hasInstances) {
                throw new FornaxPackError(file, "pass." + name + ".instances",
                        "'instances' is only valid on a particles pass (pass '" + name + "' is " + type + ")");
            }
            return null;
        }
        if (vertexShader == null) {
            throw new FornaxPackError(file, "pass." + name + ".vertex_shader",
                    "a particles pass must declare vertex_shader (the billboard vertex stage); "
                            + "'shader' names its fragment partner");
        }
        int instances = TomlSupport.requireInt(p, "instances", file);
        if (instances <= 0) {
            throw new FornaxPackError(file, "pass." + name + ".instances",
                    "instances must be positive, got " + instances);
        }
        return new ParticleSpec(vertexShader, instances);
    }

    /**
     * {@code stride_bytes} x {@code count} for a PACK-owned buffer target, or null when the target
     * declares neither (an ENGINE-owned buffer -- see {@link TargetSpec#buffer(String, String)}).
     * {@code GraphValidator} decides which of those two states each NAME is allowed to be in; this
     * method only decides whether the pair, if present, is well-formed.
     *
     * <p>Every failure below is a load error rather than a clamp, because each one degrades into
     * something silent otherwise: half a size pair reads as "engine-owned" and the buffer is never
     * allocated at all, a zero or negative product reaches {@code ensureBufferSize}'s runtime
     * positivity check on a frame path instead of at load, and a stride that is not a multiple of 4
     * makes the total size illegal for {@code vkCmdFillBuffer} (whose {@code size} the Vulkan spec
     * requires to be a multiple of 4 -- see {@code TargetRegistry.ensureBufferSize}'s own contract),
     * which would abort the zero-clear every buffer in this engine depends on.
     */
    @Nullable
    private static BufferSize parseBufferSize(Config spec, String name, String file) {
        String keyPath = "targets." + name;
        boolean hasStride = spec.contains("stride_bytes");
        boolean hasCount = spec.contains("count");
        if (!hasStride && !hasCount) {
            return null;
        }
        if (!hasStride || !hasCount) {
            throw new FornaxPackError(file, keyPath + (hasStride ? ".count" : ".stride_bytes"),
                    "a pack-sized buffer target must declare BOTH 'stride_bytes' and 'count'"
                            + " (got only '" + (hasStride ? "stride_bytes" : "count") + "'); one alone"
                            + " reads as an engine-owned buffer and would never be allocated");
        }
        int stride = requireBufferInt(spec, "stride_bytes", keyPath, file);
        int count = requireBufferInt(spec, "count", keyPath, file);
        if (stride <= 0) {
            throw new FornaxPackError(file, keyPath + ".stride_bytes",
                    "stride_bytes must be positive, got " + stride);
        }
        if (count <= 0) {
            throw new FornaxPackError(file, keyPath + ".count",
                    "count must be positive, got " + count);
        }
        if ((stride & 3) != 0) {
            throw new FornaxPackError(file, keyPath + ".stride_bytes",
                    "stride_bytes must be a multiple of 4 (got " + stride + "): the total size is passed"
                            + " to vkCmdFillBuffer for the mandatory allocation-time zero-clear, whose"
                            + " size argument the Vulkan spec requires to be a multiple of 4. A"
                            + " 4-aligned stride also matches std430's own minimum member alignment,"
                            + " so no legitimate element layout is excluded by this");
        }
        BufferSize size = new BufferSize(stride, count);
        if (size.sizeBytes() > BufferSize.MAX_SIZE_BYTES) {
            throw new FornaxPackError(file, keyPath,
                    "stride_bytes " + stride + " x count " + count + " = " + size.sizeBytes()
                            + " bytes, past the " + BufferSize.MAX_SIZE_BYTES + "-byte per-buffer ceiling"
                            + " -- see BufferSize.MAX_SIZE_BYTES for why this is a typo rather than a"
                            + " legitimate allocation");
        }
        return size;
    }

    /**
     * {@link TomlSupport#requireInt} with its {@code Math.toIntExact} overflow converted into a
     * {@link FornaxPackError}. A TOML integer past {@code int} range otherwise escapes the whole
     * loader as a bare {@code ArithmeticException}, which carries neither the file nor the key --
     * and "count = 99999999999" is exactly the kind of typo this size syntax has to name precisely.
     */
    private static int requireBufferInt(Config spec, String key, String keyPath, String file) {
        try {
            return TomlSupport.requireInt(spec, key, file);
        } catch (ArithmeticException ex) {
            throw new FornaxPackError(file, keyPath + "." + key,
                    "value is outside the 32-bit integer range");
        }
    }

    private static Config parse(Reader reader, String file) {
        try {
            return TomlFormat.instance().createParser().parse(reader);
        } catch (RuntimeException ex) {
            throw new FornaxPackError(file, "", "TOML syntax error: " + ex.getMessage());
        }
    }
}
