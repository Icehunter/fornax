package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pass.water.WaterSurfaceManager;
import dev.icehunter.fornax.pipeline.GeometryInputs;
import dev.icehunter.fornax.pipeline.OpaqueDepth;
import dev.icehunter.fornax.pipeline.SceneHistory;
import dev.icehunter.fornax.voxel.BrickGridUpload;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates a parsed graph against its options + targets, and reports total target VRAM. */
public final class GraphValidator {
    public static final Set<String> BUILTINS = Set.of(
            "builtin.depth", "builtin.blockAtlas", "builtin.materialAtlas",
            "builtin.normalAtlas", "builtin.lightmap", "builtin.output",
            // Atlas rectangles indexed by the sprite ID each terrain vertex carries, so parallax can
            // clamp its marching inside the sprite it started in.
            "builtin.spriteBounds", "builtin.spriteHeightRange",
            // The remaining G-buffer attachments, resolved by GraphInputResolver against
            // GBufferManager's live instance exactly like builtin.depth already was. Additive to the
            // set validated packs may reference -- existing packs/tests naming only builtin.depth
            // are unaffected.
            "builtin.gNormal", "builtin.gAlbedo", "builtin.gMaterial", "builtin.gAo", "builtin.gMotion",
            // The vanilla celestials atlas (sun + all 8 moon-phase sprites), resolved by
            // GraphInputResolver against CelestialSprites' live capture -- see
            // TextureAtlasCelestialHookMixin.
            "builtin.celestials",
            // The engine-generated 512x512 tileable noise texture (see NoiseTexture) -- resolved by
            // GraphInputResolver against its lazy static holder. Bound LINEAR + REPEAT at
            // FullscreenPassRunner's bind site (special-cased by this literal name), unlike every
            // other builtin's NEAREST + CLAMP_TO_EDGE.
            "builtin.noise",
            // The engine-owned, sampleable D32 copy of the opaque G-buffer depth (see OpaqueDepth),
            // captured once per frame at the finish-opaque boundary -- resolved by GraphInputResolver
            // against GraphRunner's live OpaqueDepth instance, the same way the G-buffer attachments
            // resolve against GBufferManager's live instance.
            "builtin.depth_opaque",
            // The engine-owned water-surface pre-pass targets (see WaterSurfaceManager): wave
            // world-normal + water-present flag (waterNormal) and reversed-Z surface depth
            // (waterDepth), resolved by GraphInputResolver against WaterSurfaceManager's live
            // instance. Unlike builtin.depth_opaque, both are written during the OPAQUE stage HEAD
            // (fornax$renderWaterPrepass, called immediately after the shadow pass, before Sodium's
            // own SOLID/CUTOUT draws) -- so they are already final-for-frame for every geometry
            // sub-draw AND every fullscreen pass, with no PassType-based restriction below (see
            // WaterSurfaceManager.NORMAL_NAME's own doc for the freshness argument).
            WaterSurfaceManager.NORMAL_NAME, WaterSurfaceManager.DEPTH_NAME);

    /**
     * Every buffer-kind target name the ENGINE sizes itself, via its own
     * {@code TargetRegistry.ensureBufferSize} call site. These are the only buffer targets a pack
     * may declare WITHOUT a {@code stride_bytes}/{@code count} size -- and, conversely, the only
     * ones it may NOT give a size to (the engine would immediately overwrite it, and the pack's
     * number would be a lie sitting in {@code graph.toml}).
     *
     * <p>The set exists so that "buffer target with no size" can be a LOAD ERROR for every other
     * name. Without it there is no way to tell a pack's own persistent buffer from an engine-owned
     * one, and the former's failure mode is completely silent: nothing allocates it, nothing
     * complains, and the pass that binds it throws once per frame forever inside
     * {@code ensureRunnersBuilt}'s swallowed retry loop -- taking every OTHER runner in the graph
     * down with it. That is the exact failure this whole size syntax exists to make impossible.
     *
     * <p>Referenced through the owning classes' own constants rather than repeated literals, so a
     * rename cannot drift. All are compile-time {@code String} constants, so naming them here
     * inlines at compile time and triggers no class initialization (which matters: {@code
     * BrickGridUpload}'s own {@code <clinit>} would drag Vulkan/Sodium types into headless tests).
     * A future engine buffer added without a line here fails LOUDLY -- a pack declaring it is told
     * to give it a size -- rather than silently.
     */
    static final Set<String> ENGINE_BUFFERS = Set.of(
            BrickGridUpload.INDEX_GRID_TARGET, BrickGridUpload.OCCUPANCY_TARGET,
            BrickGridUpload.PAYLOAD_TARGET, BrickGridUpload.FACE_SEAL_TARGET,
            BrickGridUpload.PALETTE_TARGET, BrickGridUpload.LIGHT_VOLUME_TARGET,
            BrickGridUpload.BRICK_SUMMARY_TARGET,
            VoxelWaterReflBuffer.TARGET, AnalyticLightListBuffer.TARGET,
            PrecipClipmapBuffer.TARGET, SurfaceFluidClipmapBuffer.TARGET,
            WaterActorBuffer.TARGET);

    /** Legal {@code pass.blend} values -- see {@code PassSpec.blend()}'s own doc. */
    private static final Set<String> BLEND_VALUES = Set.of("translucent", "additive", "multiply");

    private static final String FILE = "graph.toml";

    private GraphValidator() {}

    /**
     * Compat overload for callers with only one resolution to give (every existing caller,
     * pre-basis) -- delegates with {@code output == render}, matching {@link TargetPlan}'s own
     * 4-arg/6-arg split.
     */
    public static VramReport validate(GraphSpec graph, Map<String, PackOption> options,
                                      int renderWidth, int renderHeight) {
        return validate(graph, options, renderWidth, renderHeight, renderWidth, renderHeight);
    }

    public static VramReport validate(GraphSpec graph, Map<String, PackOption> options,
                                      int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        for (TargetSpec t : graph.targets().values()) {
            if (t.kind() == TargetKind.TEXTURE) {
                TargetFormat.parse(t.format(), t.name(), FILE);
            } else {
                checkBufferOwnership(t);
            }
            checkEnabledIf(t.enabledIf(), options, "targets." + t.name() + ".enabled_if");
        }

        // A [textures.*] name sharing a [targets.*] name would make every input-ref resolution
        // ambiguous (GraphInputResolver checks builtins, then pack textures, then the target
        // registry -- see its own doc) -- refused loudly at load rather than left to whichever
        // check happens to run first at runtime.
        for (String name : graph.textures().keySet()) {
            if (graph.targets().containsKey(name)) {
                throw new FornaxPackError(FILE, "textures." + name,
                        "'" + name + "' is declared both as a [targets." + name + "] render target and a"
                                + " [textures." + name + "] pack texture asset -- names must be unique"
                                + " across both tables");
            }
        }

        for (PassSpec p : graph.passes()) {
            checkEnabledIf(p.enabledIf(), options, "pass." + p.name() + ".enabled_if");
            // shaders/vanilla/* files are vanilla core-shader overrides (VanillaShaderOverrides),
            // never a pass shader -- they're excluded from the fullscreen-pass preamble splices
            // (u_PackOptions, EngineDefines) GraphRunner.rebuild applies to every pass's own shader,
            // so a pass naming one here would silently compile without those splices instead of
            // failing loudly.
            if (p.shader() != null && p.shader().startsWith("shaders/vanilla/")) {
                throw new FornaxPackError(FILE, "pass." + p.name() + ".shader",
                        "'" + p.shader() + "' -- shaders/vanilla/* files are vanilla core-shader"
                                + " overrides, not a valid pass shader");
            }
            if (p.type() == PassType.COMPUTE && p.outputs().isEmpty()) {
                throw new FornaxPackError(FILE, "pass." + p.name() + ".outputs",
                        "a compute pass must declare at least one output");
            }
            if (p.blend() != null) {
                if (p.type() != PassType.FULLSCREEN) {
                    throw new FornaxPackError(FILE, "pass." + p.name() + ".blend",
                            "'blend' is only valid on a fullscreen pass (pass '" + p.name()
                                    + "' is " + p.type() + ")");
                }
                if (!BLEND_VALUES.contains(p.blend())) {
                    throw new FornaxPackError(FILE, "pass." + p.name() + ".blend",
                            "unknown blend '" + p.blend() + "' (legal values: translucent, additive, multiply)");
                }
            }
            for (String in : p.inputs()) {
                checkInputRef(in, graph, p);
                checkBufferBindable(p, in, graph, false);
                checkGateConsistency(p, in, graph.targets(), options, "pass." + p.name() + ".inputs");
            }
            if (p.type() == PassType.GEOMETRY) {
                // GeometryInputs.RESERVED is a fixed, process-wide bind-group shape (u_GeomInput0..N,
                // baked into Sodium's shared terrain bind group at class-init, before any pack loads
                // -- see that class's own doc) -- a pack declaring more inputs than that has nowhere
                // to bind the overflow and must be refused at load, not silently truncated at runtime.
                if (p.inputs().size() > GeometryInputs.RESERVED) {
                    throw new FornaxPackError(FILE, "pass." + p.name() + ".inputs",
                            "geometry pass declares " + p.inputs().size() + " inputs but only "
                                    + GeometryInputs.RESERVED + " geometry-input slots are reserved");
                }
                for (String in : p.inputs()) {
                    checkGeometryInputFinality(in, graph, p);
                }
            }
            for (String out : p.outputs()) {
                checkOutputRef(out, p, graph);
                checkBufferBindable(p, out, graph, true);
                checkGateConsistency(p, out, graph.targets(), options, "pass." + p.name() + ".outputs");
            }
            if (p.type() == PassType.PARTICLES) {
                // After the output loop above, so checkOutputRef has already rejected the generic
                // failures (unknown name, pack-texture-as-output) and this only has to add the rules
                // that are specific to drawing into an attachment from a raw-Vulkan pipeline.
                checkParticlesPass(p, graph);
            }
            if (p.type() == PassType.MIPCHAIN && (p.target() == null || !graph.targets().containsKey(p.target()))) {
                throw new FornaxPackError(FILE, "pass." + p.name() + ".target",
                        "mipchain pass must name an existing target");
            }
            if (p.type() == PassType.MIPCHAIN && p.target() != null) {
                checkBufferBindable(p, p.target(), graph, true);
                checkGateConsistency(p, p.target(), graph.targets(), options, "pass." + p.name() + ".target");
            }
            if (p.outputs().contains("builtin.sceneDepth")
                    && !(p.inputs().size() == 1 && p.inputs().get(0).equals("builtin.depth"))) {
                throw new FornaxPackError(FILE, "pass." + p.name() + ".inputs",
                        "a copy pass writing 'builtin.sceneDepth' must have inputs = [\"builtin.depth\"]");
            }
            if (p.type() == PassType.TEMPORAL) {
                checkTemporalPass(p, graph);
            }
        }

        checkAtMostOneGeometryPassPerSlot(graph);
        detectCycles(graph);

        Map<String, Integer> defaults = compileDefaults(options);
        long total = 0;
        List<String> lines = new ArrayList<>();
        for (TargetSpec t : graph.targets().values()) {
            if (!enabledAtDefaults(t.enabledIf(), defaults)) continue;
            if (t.kind() == TargetKind.BUFFER) {
                BufferSize size = t.bufferSize();
                if (size == null) {
                    // Engine-owned: its bytes come from a runtime quantity (voxel window diameter,
                    // render resolution) this load-time report has no access to, so there is nothing
                    // honest to print. Unchanged from before pack-sized buffers existed.
                    continue;
                }
                // A pack-sized buffer's bytes ARE statically known, so they belong in the estimate
                // for the same reason the engine-injected sceneHistory pair does below: a report that
                // silently omits real, permanently-held VRAM understates every pack that uses one,
                // with nothing in the log to say so.
                total += appendBufferLine(lines, t.name(), size);
                continue;
            }
            total += appendLine(lines, t, renderWidth, renderHeight, outputWidth, outputHeight, "");
        }
        // The engine-injected sceneHistory pair is real VRAM every pack session holds (see
        // GraphRunner.rebuild's SceneHistory.injectInto call) but never appears in graph.toml --
        // account for it explicitly, or the estimate understates every pack by two full-size
        // color textures with nothing in the log to say so.
        total += appendLine(lines, SceneHistory.spec(), renderWidth, renderHeight, outputWidth, outputHeight, " (engine-injected)");
        return new VramReport(total, lines);
    }

    /**
     * Formats one VRAM report row for {@code t} and returns its byte cost. Sizes off
     * {@code outputWidth}/{@code outputHeight} for an OUTPUT-basis target (e.g. sceneHistory),
     * {@code renderWidth}/{@code renderHeight} otherwise, and labels the row with whichever basis
     * it used -- so a TAAU config's log makes the low-res render targets and the native-resolution
     * output targets equally visible instead of one implied basis for every row.
     */
    private static long appendLine(List<String> lines, TargetSpec t, int renderWidth, int renderHeight,
                                    int outputWidth, int outputHeight, String suffix) {
        TargetFormat fmt = TargetFormat.parse(t.format(), t.name(), FILE);
        boolean outputBasis = t.basis() == TargetBasis.OUTPUT;
        int baseWidth = outputBasis ? outputWidth : renderWidth;
        int baseHeight = outputBasis ? outputHeight : renderHeight;
        TextureSize fixed = t.fixedSize();
        int w = fixed != null ? fixed.width() : Math.max(1, (int) Math.round(baseWidth * t.scale()));
        int h = fixed != null ? fixed.height() : Math.max(1, (int) Math.round(baseHeight * t.scale()));
        long bytes = (long) fmt.bytesPerPixel() * w * h * (t.history() ? 2 : 1);
        lines.add(String.format("%-16s %-14s %dx%d (%s)%s = %.2f MB%s",
                t.name(), fmt.gpuFormatName(), w, h,
                fixed != null ? "fixed" : (outputBasis ? "output" : "render"),
                t.history() ? " x2(history)" : "", bytes / (1024.0 * 1024.0), suffix));
        return bytes;
    }

    /**
     * Every buffer-kind target must be sized by exactly one of the two owners, and which one is
     * decided by NAME -- see {@link #ENGINE_BUFFERS}. Both directions are refused at load because
     * both degrade silently otherwise: an unsized pack buffer is never allocated by anything (and
     * takes the whole runner build down every frame when a pass binds it), and a sized engine buffer
     * is a number in {@code graph.toml} that the engine's own {@code ensureBufferSize} overwrites
     * on the next frame, which no author would ever be told about.
     */
    private static void checkBufferOwnership(TargetSpec t) {
        boolean engineOwned = ENGINE_BUFFERS.contains(t.name());
        if (engineOwned && t.bufferSize() != null) {
            throw new FornaxPackError(FILE, "targets." + t.name(),
                    "'" + t.name() + "' is an ENGINE-owned buffer -- the engine sizes it via its own"
                            + " TargetRegistry.ensureBufferSize call site and would overwrite anything"
                            + " declared here. Drop 'stride_bytes'/'count'; declaring the target alone"
                            + " is what makes the name referenceable.");
        }
        if (!engineOwned && t.bufferSize() == null) {
            throw new FornaxPackError(FILE, "targets." + t.name(),
                    "buffer target '" + t.name() + "' declares no size, and it is not one of the"
                            + " engine-owned buffers " + ENGINE_BUFFERS + " -- nothing would ever"
                            + " allocate it. Declare 'stride_bytes' (bytes per element, a multiple of"
                            + " 4) and 'count' (elements).");
        }
    }

    /**
     * Refuses a buffer-kind target named in a position no runner can bind it in. A buffer is bound
     * as a {@code STORAGE_BUFFER} descriptor by the two raw-Vulkan pass types
     * ({@link ComputePassRunner}, {@link ParticlePassRunner}) and as a {@code UNIFORM_TEXEL_BUFFER}
     * input by {@link FullscreenPassRunner}; nothing else in this engine has a code path for one.
     *
     * <p>So: legal as an INPUT to COMPUTE, PARTICLES and FULLSCREEN, and as an OUTPUT of COMPUTE
     * only. Every other position is refused here, where the message can name the pass, rather than
     * at runner build, where it becomes one of:
     *
     * <ul>
     *   <li>a {@code FullscreenPassRunner} output -- {@code requireTarget(...).format()} on a target
     *       with no {@link TargetInstance} at all, thrown inside {@code ensureRunnersBuilt}'s catch,
     *       which aborts EVERY runner and retries forever;
     *   <li>a {@code MipchainRunner} target -- same, plus a scale/format a buffer does not have;
     *   <li>a {@code CopyRunner} or geometry-slot input -- resolved through
     *       {@code GraphInputResolver.resolveView}, which has only textures to hand back;
     *   <li>a {@code ParticlePassRunner} output -- {@link #checkParticlesPass} refuses this too, with
     *       a message about color attachments specifically, but this check runs FIRST (the outputs
     *       loop precedes the particles block) so the message an author actually sees is this one.
     * </ul>
     *
     * <p>{@code writePosition} distinguishes a pass's {@code outputs}/{@code target} from its
     * {@code inputs}: the write side is strictly narrower, since a fullscreen pass can READ a buffer
     * (texel fetch) but has no way to WRITE one (Blaze3D's fragment pipeline has no storage-buffer
     * uniform type -- see {@code FullscreenPassRunner}'s own note on that limit).
     */
    private static void checkBufferBindable(PassSpec p, String ref, GraphSpec graph, boolean writePosition) {
        String base = ref.endsWith(".history") ? ref.substring(0, ref.length() - ".history".length()) : ref;
        TargetSpec t = graph.targets().get(base);
        if (t == null || t.kind() != TargetKind.BUFFER) {
            return; // a builtin, a reserved engine name, a pack texture asset, or a texture target
        }
        boolean legal = writePosition
                ? p.type() == PassType.COMPUTE
                : p.type() == PassType.COMPUTE || p.type() == PassType.PARTICLES
                        || p.type() == PassType.FULLSCREEN;
        if (!legal) {
            throw new FornaxPackError(FILE, "pass." + p.name() + (writePosition ? ".outputs" : ".inputs"),
                    "'" + ref + "' is a buffer-kind target, which a " + p.type() + " pass cannot bind"
                            + (writePosition ? " as an output" : " as an input")
                            + ". A buffer is readable by compute, particles and fullscreen passes, and"
                            + " writable only by a compute pass.");
        }
    }

    /** One VRAM report row for a pack-sized buffer target, in the same column shape
     * {@link #appendLine} uses for a texture -- the format column carries the element layout
     * ({@code stride x count}) since a buffer has no {@code GpuFormat}, and the extent column its
     * byte count, since it has no pixel extent either. */
    private static long appendBufferLine(List<String> lines, String name, BufferSize size) {
        long bytes = size.sizeBytes();
        lines.add(String.format("%-16s %-14s %d bytes (buffer) = %.2f MB",
                name, size.strideBytes() + "x" + size.count(), bytes, bytes / (1024.0 * 1024.0)));
        return bytes;
    }

    /**
     * Refuses, at load time, any pass that can be ENABLED while a target it references is
     * DISABLED. An {@code enabled_if}-gated target is simply never allocated when its expression
     * evaluates false ({@link TargetRegistry} frees it), so a still-enabled pass referencing it
     * cannot build its runner -- and a runner-build failure is swallowed by {@code
     * GraphRunner.ensureRunnersBuilt()}'s retry loop, taking the ENTIRE post chain (resolve
     * included) down with it: terrain still draws into the G-buffer but nothing ever composites it
     * to the screen, with no load-time error anywhere. Same fail-loud philosophy as the eager
     * {@code #moj_import} validation: refuse at load with the offending pass/target named, never
     * degrade at render time.
     *
     * <p>The check is exact where domains are enumerable (every real compile option: booleans and
     * bracketed enum lists): it enumerates the combined domain of every compile option either
     * expression references and demands pass-enabled implies target-allocated at every point.
     * A non-enumerable domain or a combinatorial blow-up (>4096 points) falls back to the
     * conservative syntactic rule: the pass must carry a byte-identical {@code enabled_if}, or be
     * refused.
     */
    private static void checkGateConsistency(PassSpec pass, String ref, Map<String, TargetSpec> targets,
                                             Map<String, PackOption> options, String key) {
        String base = ref.endsWith(".history") ? ref.substring(0, ref.length() - ".history".length()) : ref;
        TargetSpec target = targets.get(base);
        if (target == null || target.enabledIf() == null) {
            return; // builtin/engine name (validated elsewhere), or an ungated always-allocated target
        }
        if (target.enabledIf().equals(pass.enabledIf())) {
            return; // byte-identical gates can never disagree
        }

        EnabledIfExpr targetExpr = EnabledIfExpr.parse(target.enabledIf());
        EnabledIfExpr passExpr = pass.enabledIf() == null ? null : EnabledIfExpr.parse(pass.enabledIf());

        Set<String> names = new java.util.LinkedHashSet<>(targetExpr.referencedNames());
        if (passExpr != null) names.addAll(passExpr.referencedNames());
        List<String> nameList = List.copyOf(names);

        List<int[]> domains = new ArrayList<>();
        long combos = 1;
        for (String n : nameList) {
            // An EngineDefines.KEYS name (e.g. FX_COMPUTE) is never a pack-declared PackOption --
            // checkEnabledIf already treats it as always-known/legal to reference, so gate-consistency
            // proof must be able to enumerate its domain too, or any pass/target pair whose gates
            // differ only by referencing one of these engine facts (e.g. voxel_water_refl's
            // "... && FX_COMPUTE" reading the plain "SSR_WATER_MODE > 1 && SSR_QUALITY != 0" ssrWater
            // target) falls through to the conservative "cannot prove" refusal below even though the
            // implication genuinely holds at both FX_COMPUTE values. Every key in the set is always
            // exactly 0 or 1 (see EngineDefines.forMethod), so the boolean domain is exact, not a guess.
            int[] d = EngineDefines.KEYS.contains(n) ? new int[]{0, 1} : enumerableDomain(options.get(n));
            if (d == null || (combos *= d.length) > 4096) {
                throw new FornaxPackError(FILE, key,
                        "'" + ref + "' -- cannot prove pass '" + pass.name() + "' is disabled whenever gated target '"
                                + base + "' (enabled_if = \"" + target.enabledIf() + "\") is unallocated; give the pass"
                                + " the identical enabled_if, or use enumerable compile options in both expressions");
            }
            domains.add(d);
        }

        int[] idx = new int[nameList.size()];
        Map<String, Integer> assignment = new HashMap<>();
        while (true) {
            for (int i = 0; i < nameList.size(); i++) assignment.put(nameList.get(i), domains.get(i)[idx[i]]);
            boolean passEnabled = passExpr == null || passExpr.evaluate(assignment);
            if (passEnabled && !targetExpr.evaluate(assignment)) {
                throw new FornaxPackError(FILE, key,
                        "'" + ref + "' -- pass '" + pass.name() + "' is enabled but gated target '" + base
                                + "' (enabled_if = \"" + target.enabledIf() + "\") is not allocated when "
                                + assignment + "; the pass would silently take the whole post chain down"
                                + " at runner build. Gate the pass so it is disabled whenever the target is.");
            }
            int i = 0;
            while (i < idx.length && ++idx[i] == domains.get(i).length) idx[i++] = 0;
            if (i == idx.length) break; // wrapped every position (or zero options): full domain enumerated
        }
    }

    /** {@code {0,1}} for a boolean option, the bracketed value list for an enum option, else null (not enumerable). */
    private static int @org.jspecify.annotations.Nullable [] enumerableDomain(@org.jspecify.annotations.Nullable PackOption o) {
        if (o == null) return null; // unknown option name -- checkEnabledIf already refused it
        if (o.isBoolean()) return new int[]{0, 1};
        if (o.allowedValues().isEmpty()) return null;
        int[] d = new int[o.allowedValues().size()];
        for (int i = 0; i < d.length; i++) {
            try {
                d[i] = (int) Double.parseDouble(o.allowedValues().get(i));
            } catch (NumberFormatException e) {
                return null; // non-numeric enum -- not meaningfully comparable in enabled_if arithmetic
            }
        }
        return d;
    }

    private static void checkInputRef(String ref, GraphSpec graph, PassSpec pass) {
        Map<String, TargetSpec> targets = graph.targets();
        String base = ref.endsWith(".history") ? ref.substring(0, ref.length() - ".history".length()) : ref;
        if (graph.textures().containsKey(base)) {
            // A pack-shipped static texture asset (see PackTextureSpec) -- no render-output
            // machinery, so unlike a declared target it has no history slot at all; referencing it
            // with '.history' is refused here rather than silently resolving to the same view every
            // frame (which '.history' would otherwise appear to promise).
            if (ref.endsWith(".history")) {
                throw new FornaxPackError(FILE, "pass." + pass.name() + ".inputs",
                        "'" + ref + "' -- pack texture '" + base + "' is a static asset with no history"
                                + " slot; reference '" + base + "' directly");
            }
            return;
        }
        if (base.equals(ComputePassRunner.PACK_OPTIONS_INPUT)) {
            // Reserved engine-recognized input, bound by ComputePassRunner.build to the live
            // PackOptionsBuffer -- never a declared target, so it must be recognized here by name
            // exactly like a builtin, or a pack compute pass declaring it (e.g. a shadow-ray-march
            // pass reading pack options) would fail load-time validation despite ComputePassRunner
            // knowing perfectly well how to bind it. Only meaningful for the two raw-Vulkan pass types
            // (COMPUTE and PARTICLES), which bind it as a positional descriptor: a FULLSCREEN pass
            // gets u_PackOptions automatically via the GLSL-prepend mechanism and must never declare
            // it as an input.
            if (pass.type() != PassType.COMPUTE && pass.type() != PassType.PARTICLES) {
                throw new FornaxPackError(FILE, "pass." + pass.name() + ".inputs",
                        "'" + ComputePassRunner.PACK_OPTIONS_INPUT + "' is only a valid input for a compute"
                                + " or particles pass (pass '" + pass.name() + "' is " + pass.type() + "); a "
                                + PassType.FULLSCREEN + " pass receives u_PackOptions automatically");
            }
            return;
        }
        if (base.equals(ParticlePassRunner.GLOBALS_INPUT)) {
            // Reserved engine-recognized input, bound to Sodium's live u_Globals slice -- never a
            // declared target, so it is recognized here by name exactly like packOptions above.
            //
            // Legal on the two raw-Vulkan pass types, which bind it as a positional descriptor.
            // PARTICLES needs the camera matrices to place a billboard at all. COMPUTE was refused
            // here originally, on the reasoning that "a compute pass has no camera to project
            // through" -- true, and beside the point: u_Globals is also where every per-frame WORLD
            // fact lives (the wind clock, the frame counter, rain/thunder/wetness, the weather
            // anchor, the true sun direction -- see fornax:globals.glsl), and refusing the name left
            // a pack compute pass with no clock of any kind. Its only other per-frame channel is the
            // PassParams push constant, whose two free scalars GraphRunner.computeParams fills in BY
            // PASS NAME, so a pack-authored name the engine does not recognize gets zeros forever.
            // A simulation cannot advance on that.
            //
            // Still refused on every OTHER type, and for the original reason: a FULLSCREEN pass
            // already has u_Globals in its bind group unconditionally (FullscreenPassRunner.build)
            // and a GEOMETRY pass gets Sodium's own terrain bind group, so there the name would bind
            // nothing while silently shifting the pass's other binding indices by one.
            if (pass.type() != PassType.PARTICLES && pass.type() != PassType.COMPUTE) {
                throw new FornaxPackError(FILE, "pass." + pass.name() + ".inputs",
                        "'" + ParticlePassRunner.GLOBALS_INPUT + "' is only a valid input for a particles"
                                + " or compute pass (pass '" + pass.name() + "' is " + pass.type() + "); a "
                                + PassType.FULLSCREEN + " pass receives u_Globals automatically");
            }
            return;
        }
        if (BUILTINS.contains(base)) {
            // builtin.depth_opaque is captured inside GraphRunner.finish(), which -- despite its name
            // mirroring FramePipeline.finishOpaque -- only WRAPS the OPAQUE (SOLID/CUTOUT) portion of
            // Sodium's terrain draw: the capture call is the LAST thing finish() does, still BEFORE
            // Sodium's own translucent draw runs (see finish()'s own doc). The graph's one allowed
            // geometry pass (see checkAtMostOneGeometryPass) shares ONE compiled shader across all
            // three terrain sub-draws (SOLID/CUTOUT/TRANSLUCENT -- ShaderChunkRendererShaderLocationMixin),
            // so builtin.depth_opaque's freshness differs BY SUB-DRAW, not just by pass type: SOLID/
            // CUTOUT run before finish()'s capture and therefore still sample LAST frame's copy; only
            // TRANSLUCENT runs after finish() and gets THIS frame's. A geometry pass's shader must
            // therefore branch to sample builtin.depth_opaque only in its translucent-only code path
            // -- never unconditionally -- or the opaque sub-draws silently read one-frame-stale depth.
            // Restricting this input to PassType.GEOMETRY (below) only rules out the OTHER pass types
            // (fullscreen/mipchain/copy/compute), which run entirely after finish()'s capture and so
            // would read a value that is always fresh but structurally cannot participate in the
            // per-sub-draw distinction above -- they have no SOLID/CUTOUT/TRANSLUCENT split at all.
            if (base.equals(OpaqueDepth.NAME) && pass.type() != PassType.GEOMETRY) {
                throw new FornaxPackError(FILE, "pass." + pass.name() + ".inputs",
                        "'" + OpaqueDepth.NAME + "' is only a valid input for a geometry pass (pass '"
                                + pass.name() + "' is " + pass.type() + "); fullscreen/mipchain/copy/"
                                + "compute passes have no translucent-only code path to safely sample"
                                + " it from, unlike a geometry pass's shared terrain shader");
            }
            return;
        }
        if (ShadowMapManager.isShadowMapRef(base)) {
            // Engine-owned, never pack-declared (see ShadowMapManager) -- resolved read-only
            // exactly like sceneHistory, except this target has no history slot: it is a single
            // current-frame depth target the engine overwrites every frame, so unlike sceneHistory
            // (which REQUIRES the ".history" suffix) this one REJECTS it. Covers BOTH pack-visible
            // names (TARGET and RAW_TARGET, see the latter's own doc) -- they share every
            // resolution/validation rule and differ only in which sampler binds them.
            if (ref.endsWith(".history")) {
                throw new FornaxPackError(FILE, "pass." + pass.name() + ".inputs",
                        "'" + ref + "' -- " + base + " has no history slot; reference '" + base
                                + "' directly");
            }
            return;
        }
        if (base.equals(SceneHistory.TARGET)) {
            // Engine-guaranteed, never pack-declared (see SceneHistory) -- GraphRunner.rebuild
            // injects it into the target set actually allocated at runtime, but the graph handed
            // to THIS validator (at pack-load time, before any rebuild) never carries it, so it's
            // recognized here by name instead, exactly like a builtin. A pack may only ever read
            // its previous-frame content; it never writes (or reads the live, mid-swap) current.
            if (!ref.endsWith(".history")) {
                throw new FornaxPackError(FILE, "pass." + pass.name() + ".inputs",
                        "'" + ref + "' -- sceneHistory is engine-written; a pack may only read '"
                                + SceneHistory.TARGET + ".history'");
            }
            return;
        }
        TargetSpec t = targets.get(base);
        if (t == null) {
            throw new FornaxPackError(FILE, "pass." + pass.name() + ".inputs",
                    "input '" + ref + "' references no declared target or built-in");
        }
        if (ref.endsWith(".history") && !t.history()) {
            throw new FornaxPackError(FILE, "pass." + pass.name() + ".inputs",
                    "'" + ref + "' reads history but target '" + base + "' is not declared history = true");
        }
    }

    /**
     * The rules a {@link PassType#PARTICLES} pass has that no other pass type does, all of them
     * consequences of it being the only pass that draws into a real attachment pair through a
     * pipeline this engine builds by hand ({@code ParticlePassRunner}).
     *
     * <p>Each one is here because the failure it prevents is silent or fatal rather than visible:
     * a missing fragment stage throws out of runner build (which {@code ensureRunnersBuilt} swallows
     * into a retry loop, taking every other runner in that attempt down with it); a mismatched
     * attachment format or extent is UNDEFINED BEHAVIOUR under dynamic rendering, not a validation
     * error the driver will report; and sampling the same depth texture the pass has bound as its
     * depth attachment is a layout conflict that a driver may or may not notice.
     */
    /**
     * A TEMPORAL pass is the engine's own motion-reprojected history accumulation placed at a
     * pack-chosen point in the graph (see {@code TemporalPassRunner}). The pack declares only the
     * data flow, so everything the engine's shader will assume has to be enforced here at load:
     *
     * <ul>
     *   <li>exactly one input and one output, both plain TEXTURE targets -- the runner binds one
     *       source and one destination and nothing else from the pack side;</li>
     *   <li>the output must be {@code history = true}: the accumulation reads its own previous
     *       frame from the history slot, and a single-slot target would be a same-texture
     *       read/write hazard;</li>
     *   <li>input and output must agree on format, scale and basis -- the blend is a texel-for-texel
     *       mix, so a shape mismatch is a silent resample, not a feature;</li>
     *   <li>no {@code shader} key: the program is engine-owned, and a pack shader here would be
     *       silently ignored -- refused loudly instead;</li>
     *   <li>no {@code .history} input and no builtin: the runner resolves the history slot itself,
     *       with mid-graph (pre-swap) phase rules that differ from what a pack pass gets.</li>
     * </ul>
     */
    private static void checkTemporalPass(PassSpec p, GraphSpec graph) {
        String key = "pass." + p.name();
        if (p.shader() != null) {
            throw new FornaxPackError(FILE, key + ".shader",
                    "a temporal pass's shader is engine-owned; remove the 'shader' key");
        }
        if (p.inputs().size() != 1 || p.outputs().size() != 1) {
            throw new FornaxPackError(FILE, key,
                    "a temporal pass must have exactly one input and one output (got "
                            + p.inputs().size() + " inputs, " + p.outputs().size() + " outputs)");
        }
        String in = p.inputs().get(0);
        String out = p.outputs().get(0);
        if (in.startsWith("builtin.") || in.endsWith(".history") || out.startsWith("builtin.")) {
            throw new FornaxPackError(FILE, key,
                    "a temporal pass must connect two pack targets directly (no builtin.*, no .history)");
        }
        TargetSpec inSpec = graph.targets().get(in);
        TargetSpec outSpec = graph.targets().get(out);
        if (inSpec == null || outSpec == null || inSpec.kind() != TargetKind.TEXTURE
                || outSpec.kind() != TargetKind.TEXTURE) {
            throw new FornaxPackError(FILE, key,
                    "a temporal pass's input and output must both be declared [targets.*] textures");
        }
        if (!outSpec.history()) {
            throw new FornaxPackError(FILE, key + ".outputs",
                    "a temporal pass's output must declare history = true -- the accumulation reads"
                            + " its own previous frame from the history slot");
        }
        if (!inSpec.format().equals(outSpec.format()) || !sameTextureShape(inSpec, outSpec)) {
            throw new FornaxPackError(FILE, key,
                    "a temporal pass's input and output must agree on format and texture shape ("
                            + in + " is " + describeTextureShape(inSpec) + ", " + out + " is "
                            + describeTextureShape(outSpec) + ")");
        }
    }

    private static boolean sameTextureShape(TargetSpec a, TargetSpec b) {
        if (a.fixedSize() != null || b.fixedSize() != null) {
            return a.fixedSize() != null && a.fixedSize().equals(b.fixedSize());
        }
        return a.scale() == b.scale() && a.basis() == b.basis();
    }

    private static String describeTextureShape(TargetSpec target) {
        TextureSize fixed = target.fixedSize();
        return target.format() + "/" + (fixed != null
                ? fixed.width() + "x" + fixed.height() + " fixed"
                : target.scale() + "/" + target.basis());
    }

    private static void checkParticlesPass(PassSpec p, GraphSpec graph) {
        String key = "pass." + p.name();
        if (p.shader() == null) {
            throw new FornaxPackError(FILE, key + ".shader",
                    "a particles pass must declare 'shader' (its fragment stage); 'vertex_shader'"
                            + " names the vertex partner");
        }
        if (p.outputs().size() != 1) {
            throw new FornaxPackError(FILE, key + ".outputs",
                    "a particles pass must declare exactly one output (got " + p.outputs().size()
                            + ") -- it draws into a single color attachment");
        }
        String out = p.outputs().get(0);
        TargetSpec target = graph.targets().get(out);
        if (target == null) {
            // Only reachable for a builtin name: checkOutputRef already accepted builtin.output (and
            // builtin.sceneDepth for a copy pass) and rejected everything else that isn't a declared
            // target. Neither is usable here -- both resolve against the MAIN render target, which
            // under TAAU is a different size from the G-buffer depth this pass tests against, and a
            // dynamic-rendering render area has to fit inside EVERY attachment.
            throw new FornaxPackError(FILE, key + ".outputs",
                    "'" + out + "' -- a particles pass must draw into a declared [targets.*] texture,"
                            + " not a builtin: it depth-tests against the G-buffer depth, and only a"
                            + " render-basis target is guaranteed to match that attachment's size."
                            + " Composite the result into the frame with a later fullscreen pass.");
        }
        if (target.kind() != TargetKind.TEXTURE) {
            throw new FornaxPackError(FILE, key + ".outputs",
                    "'" + out + "' is a buffer-kind target and cannot be a color attachment");
        }
        if (target.fixedSize() != null || target.basis() != TargetBasis.RENDER || target.scale() != 1.0) {
            throw new FornaxPackError(FILE, key + ".outputs",
                    "'" + out + "' must be basis = \"render\" at scale = 1.0 to be a particles pass's"
                            + " output (it is " + describeTextureShape(target) + ") -- the pass depth-tests against the"
                            + " full-resolution G-buffer depth, and a dynamic-rendering render area must"
                            + " fit inside every attachment it names");
        }
        for (String in : p.inputs()) {
            if (in.equals("builtin.depth")) {
                throw new FornaxPackError(FILE, key + ".inputs",
                        "'builtin.depth' is bound as this particles pass's own depth ATTACHMENT, so it"
                                + " cannot also be sampled by it -- one image cannot be in"
                                + " DEPTH_STENCIL_ATTACHMENT_OPTIMAL and SHADER_READ_ONLY_OPTIMAL at once."
                                + " The hardware depth test already does the occlusion.");
            }
        }
    }

    /**
     * A geometry pass's declared input must be final-for-frame at translucent-draw time. Every graph
     * pass runs inside {@code GraphRunner.finish()}, which completes before Sodium's translucent draw
     * -- so any target written by any enabled pass is final by then, regardless of that pass's own
     * position in {@code graph.passes()} relative to the geometry pass (this is why {@code ssr},
     * written by passes that sit AFTER {@code terrain_opaque} in file order, is legitimately
     * samplable by the geometry pass). {@code checkInputRef} already rejects a genuinely unresolvable
     * name; {@code checkGateConsistency} already rejects an enabled pass reading a disabled target.
     * This adds only the remaining guard: a declared-but-NEVER-written target, which would read
     * garbage forever, not just this frame.
     *
     * <p>Future extension point: once a post-translucent pass class exists, a target written ONLY by
     * one must also be rejected here (it would not yet be final at THIS translucent draw). Today
     * every graph pass runs before the translucent draw, so any written target already qualifies.
     */
    private static void checkGeometryInputFinality(String ref, GraphSpec graph, PassSpec pass) {
        // Finish-opaque builtins/engine-owned resources are always final for the translucent draw
        // (builtin.depth_opaque's own additional geometry-only restriction is enforced separately, by
        // checkInputRef, before this method ever runs for it). A pack-shipped static texture asset
        // (see PackTextureSpec) is likewise always final -- it is loaded once at pack activation and
        // never written by any pass, so there is no same-frame freshness question to ask of it.
        if (BUILTINS.contains(ref) || ref.equals(SceneHistory.TARGET + ".history")
                || ShadowMapManager.isShadowMapRef(ref) || graph.textures().containsKey(ref)) {
            return;
        }
        if (ref.endsWith(".history")) {
            String base = ref.substring(0, ref.length() - ".history".length());
            TargetSpec target = graph.targets().get(base);
            if (target != null && target.history()) {
                return;
            }
        }
        for (PassSpec writer : graph.passes()) {
            if (writer.outputs().contains(ref) || ref.equals(writer.target())) {
                return; // written by some pass this frame -> final at finish-opaque
            }
        }
        throw new FornaxPackError(FILE, "pass." + pass.name() + ".inputs",
                "geometry pass input '" + ref + "' is never written this frame, so it is not "
                        + "final-for-frame at translucent draw time");
    }

    /**
     * Refuses, at load time, a graph declaring more than one {@code GEOMETRY}-type pass <em>for the
     * same {@link GeometrySlot}</em>.
     *
     * <p>Geometry inputs resolve per slot into that slot's own {@code u_GeomInput0..RESERVED-1} bind
     * group ({@code GraphRunner.refreshGeometryInputViews()}), so two passes claiming one slot would
     * leave the second's declared {@code inputs} silently dead with nothing in {@code graph.toml}
     * warning the author. Distinct slots are independent and entirely legal.
     *
     * <p>Declaring a slot that does not render yet ({@link GeometrySlot#isRendered()}) is allowed
     * rather than refused: a pack should be able to author and ship programs for slots ahead
     * of the engine routing geometry into them, and the alternative -- rejecting the graph -- would
     * make every such pack unloadable until the day interception lands.
     */
    private static void checkAtMostOneGeometryPassPerSlot(GraphSpec graph) {
        Map<GeometrySlot, String> claimedBy = new EnumMap<>(GeometrySlot.class);
        for (PassSpec p : graph.passes()) {
            if (p.type() != PassType.GEOMETRY) continue;
            GeometrySlot slot = p.slot() == null ? GeometrySlot.DEFAULT : p.slot();
            String previous = claimedBy.putIfAbsent(slot, p.name());
            if (previous != null) {
                throw new FornaxPackError(FILE, "pass." + p.name() + ".slot",
                        "a graph may declare at most one geometry pass per slot, but both '" + previous
                                + "' and '" + p.name() + "' claim slot = \"" + slot.token() + "\""
                                + " -- only one of them could ever have its declared inputs resolved"
                                + " into that slot's bind group. Give one of them a different slot"
                                + " (one of " + GeometrySlot.tokens() + ").");
            }
        }
    }

    private static void checkOutputRef(String ref, PassSpec pass, GraphSpec graph) {
        Map<String, TargetSpec> targets = graph.targets();
        if (ref.equals("builtin.output")) return;
        if (ref.equals("builtin.sceneDepth")) {
            // Writable like builtin.output, but only for a copy pass -- it's the main render target's
            // depth, a write-only sink with no valid read-view, so any other pass type naming it as
            // an output would have nothing sane to write through.
            if (pass.type() != PassType.COPY) {
                throw new FornaxPackError(FILE, "pass." + pass.name() + ".outputs",
                        "'builtin.sceneDepth' is only a valid output for a copy pass");
            }
            return;
        }
        if (graph.textures().containsKey(ref)) {
            // A pack-shipped static texture asset is a read-only input, never a valid write target
            // (see PackTextureSpec) -- called out explicitly here rather than falling through to the
            // generic "no declared target" message below, since the name DOES exist in this graph,
            // just not as something writable.
            throw new FornaxPackError(FILE, "pass." + pass.name() + ".outputs",
                    "'" + ref + "' is a pack texture asset (declared under [textures." + ref + "]), which"
                            + " is read-only and never a valid pass output");
        }
        if (!targets.containsKey(ref)) {
            throw new FornaxPackError(FILE, "pass." + pass.name() + ".outputs",
                    "output '" + ref + "' references no declared target");
        }
    }

    private static void checkEnabledIf(String expr, Map<String, PackOption> options, String key) {
        if (expr == null) return;
        EnabledIfExpr parsed;
        try {
            parsed = EnabledIfExpr.parse(expr);
        } catch (FornaxPackError e) {
            throw new FornaxPackError(FILE, key, "invalid enabled_if expression: " + e.reason());
        }
        for (String name : parsed.referencedNames()) {
            if (EngineDefines.KEYS.contains(name)) {
                // Engine-injected compile fact (FX_TAA/FX_UPSCALE/FX_METHOD_*/FX_COMPUTE) -- never a
                // pack-declared PackOption (OptionScanner only sees //[...] compile annotations in
                // pack shader source), but GraphRunner.rebuild unconditionally overlays a value for
                // every one of these names onto compileValues, every pack, every session. See
                // EngineDefines.KEYS' own doc.
                continue;
            }
            PackOption o = options.get(name);
            if (o == null) {
                throw new FornaxPackError(FILE, key, "enabled_if references unknown option '" + name + "'");
            }
            if (o.type() == OptionType.RUNTIME) {
                throw new FornaxPackError(FILE, key,
                        "enabled_if may not reference runtime option '" + name + "' (compile options only)");
            }
        }
    }

    private static void detectCycles(GraphSpec graph) {
        Map<String, List<String>> producers = new HashMap<>();
        for (PassSpec p : graph.passes()) {
            // builtin.output is the fixed pipeline's own terminal sink, not a pack-declared target --
            // a linear post-process chain legitimately has many passes overwrite it in sequence (see
            // the dev_graph fixture's resolve -> ... -> taa_copy_out), which is a straight-line
            // handoff, not a same-frame producer/consumer relationship needing cycle validation.
            for (String out : p.outputs()) {
                if (!out.equals("builtin.output")) producers.computeIfAbsent(out, k -> new ArrayList<>()).add(p.name());
            }
            if (p.target() != null) producers.computeIfAbsent(p.target(), k -> new ArrayList<>()).add(p.name());
        }
        Map<String, List<String>> adj = new HashMap<>();
        for (PassSpec q : graph.passes()) {
            for (String in : q.inputs()) {
                if (in.endsWith(".history") || in.equals("builtin.output")) continue; // previous-frame/terminal-sink read: not a same-frame edge
                for (String producer : producers.getOrDefault(in, List.of())) {
                    if (producer.equals(q.name())) {
                        // Reading a target the same pass writes this frame is a GPU
                        // read-write feedback hazard; only '<target>.history' is legal.
                        throw new FornaxPackError(FILE, "pass." + q.name(),
                                "pass reads target '" + in + "' that it also writes in the same frame"
                                        + " (read '" + in + ".history' for the previous frame instead)");
                    }
                    adj.computeIfAbsent(producer, k -> new ArrayList<>()).add(q.name());
                }
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (PassSpec p : graph.passes()) {
            if (!done.contains(p.name())) dfs(p.name(), adj, visiting, done);
        }
    }

    private static void dfs(String node, Map<String, List<String>> adj, Set<String> visiting, Set<String> done) {
        if (visiting.contains(node)) {
            throw new FornaxPackError(FILE, "pass." + node, "pass dependency cycle detected at '" + node + "'");
        }
        if (done.contains(node)) return;
        visiting.add(node);
        for (String next : adj.getOrDefault(node, List.of())) dfs(next, adj, visiting, done);
        visiting.remove(node);
        done.add(node);
    }

    private static Map<String, Integer> compileDefaults(Map<String, PackOption> options) {
        Map<String, Integer> m = new HashMap<>();
        for (PackOption o : options.values()) {
            if (o.type() == OptionType.COMPILE) {
                try { m.put(o.name(), (int) Double.parseDouble(o.defaultValue())); }
                catch (NumberFormatException ignored) { m.put(o.name(), 0); }
            }
        }
        return m;
    }

    private static boolean enabledAtDefaults(String expr, Map<String, Integer> defaults) {
        if (expr == null) return true;
        return EnabledIfExpr.parse(expr).evaluate(defaults);
    }
}
