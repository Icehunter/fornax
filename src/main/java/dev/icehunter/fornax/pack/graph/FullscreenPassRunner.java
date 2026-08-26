package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.layout.PackOptionsBuffer;
import dev.icehunter.fornax.pack.layout.RuntimeShaderPack;
import dev.icehunter.fornax.pass.shadow.ShadowComparisonSampler;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generalizes the shape every hardcoded full-screen post pass already follows
 * ({@code SsaoPass}/{@code SsaoBlurPass}/{@code SsrTracePass}/{@code SsrBlurPass}/
 * {@code TaaBlendPass}/{@code GBufferResolvePass}): one {@code BindGroupLayout} built from the
 * pass's declared {@code inputs} (one sampler per input, named positionally {@code u_Input0.. N-1}
 * so a pack's own fixture shader can bind against a stable, generic convention regardless of what
 * each input actually references) plus {@code u_Globals}/{@code u_PackOptions}/{@code
 * u_PassParams}; one {@code RenderPipeline} pairing vanilla's {@code core/screenquad} vertex shader
 * with the pack's fragment shader served over {@link RuntimeShaderPack}; one draw of a full-screen
 * triangle per invocation.
 *
 * <p>Single-output only: every hardcoded pass this generalizes writes exactly one color attachment
 * (blaze3d's {@code CommandEncoder.createRenderPass} has no public multi-color-attachment overload
 * outside the deferred G-buffer MRT path {@code DefaultChunkRendererRenderPassMixin} builds by hand
 * for Sodium's own chunk renderer) -- a pack declaring more than one {@code outputs} entry for a
 * {@code fullscreen} pass is rejected at build time with a clear message rather than silently
 * dropping every output past the first.
 */
public final class FullscreenPassRunner implements AutoCloseable {
    private final PassSpec spec;
    private final RenderPipeline pipeline;
    private final MappableRingBuffer passParamsData;

    /** True once {@link #pipeline} has failed to bind (compile/link failure -- e.g. a bind-group
     * uniform the compiled shader text doesn't declare, see {@code GraphRunner.rebuild}'s doc on the
     * stale-shader-snapshot race this most commonly comes from). Blaze3D marks a pipeline object
     * permanently invalid the instant its first bind fails to compile -- a SECOND {@code
     * setPipeline} call against the same object throws {@code IllegalStateException: Pipeline is not
     * valid}, not a retryable error. Once this flips, {@link #run} skips the pass entirely rather
     * than ever calling {@code setPipeline} again: "pass doesn't render" instead of "game dies",
     * exactly like a missing runner already degrades in {@code GraphRunner.finish}. Cleared only by
     * building a brand-new runner (the next successful rebuild), never in place. */
    private boolean invalid;
    /** Consecutive {@link #run} failures. A permanently misclassified target throws every frame and
     * still latches; a transient one (a frame-timing fence error from a settings change) does not
     * take the pass down for the session. Reset on any successful frame. */
    private int consecutiveFailures;
    private static final int CONSECUTIVE_FAILURES_BEFORE_LATCH = 3;

    /** Positional (matches {@code spec.inputs()}): {@code true} when input {@code i} names a
     * registry BUFFER target and must bind as a TEXEL_BUFFER uniform instead of a sampler -- see
     * {@link #build}. */
    private final boolean[] bufferInputs;

    /** Cached per-input texel-buffer wrappers, keyed positionally; entry recreated only when the
     * underlying vkBuffer handle changes (registry reallocation) -- see RawVulkanGpuBuffer's javadoc
     * on why per-frame fresh wrappers are forbidden. Index space matches spec.inputs(). */
    private final RawVulkanGpuBuffer[] texelWrappers;
    private final long[] texelWrapperHandles;

    /** Logged once, process-wide, the first time any fullscreen pass binds a TEXEL_BUFFER input --
     * see {@link #logTexelBufferBindOnce}. This is the first compute-written-buffer ->
     * fragment-texelFetch bind this backend exercises; the log gives a capture a concrete element
     * count to compare against the device's maxTexelBufferElements limit. */
    private static volatile boolean loggedTexelBufferBindOnce = false;

    private FullscreenPassRunner(PassSpec spec, RenderPipeline pipeline, boolean[] bufferInputs) {
        this.spec = spec;
        this.pipeline = pipeline;
        this.bufferInputs = bufferInputs;
        this.texelWrappers = new RawVulkanGpuBuffer[bufferInputs.length];
        this.texelWrapperHandles = new long[bufferInputs.length];
        this.passParamsData = new MappableRingBuffer(
                () -> "Fornax pass params (" + spec.name() + ")",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, PassParams.BUFFER_SIZE);
    }

    public static FullscreenPassRunner build(PassSpec spec, TargetRegistry registry) {
        if (spec.outputs().size() != 1) {
            throw new IllegalArgumentException("Fornax graph: fullscreen pass '" + spec.name()
                    + "' must declare exactly one output (got " + spec.outputs().size() + ")");
        }

        BindGroupLayout.Builder bindGroupBuilder = BindGroupLayout.builder();
        // An input naming a registry BUFFER target (kind = "buffer" in graph.toml -- e.g. the
        // engine-injected voxelLightVolume) binds as a TEXEL_BUFFER uniform, not a sampler: the
        // fragment pipeline's only buffer-shaped UniformTypes are UNIFORM_BUFFER and TEXEL_BUFFER
        // (no STORAGE_BUFFER -- Blaze3D design limit), and R32_UINT texelFetch is exactly the
        // access shape the voxel buffers' uint-word layouts want. Classified at build time against
        // the live registry (ensureRunnersBuilt allocates the voxel grid BEFORE building runners,
        // GraphRunner:508-510, so a buffer input referenced by an enabled pass already exists here).
        // v1 convention: every buffer-kind fullscreen input is R32_UINT (usamplerBuffer) -- a
        // per-target format field is future work no current consumer needs.
        boolean[] bufferInputs = new boolean[spec.inputs().size()];
        for (int i = 0; i < spec.inputs().size(); i++) {
            String name = spec.inputs().get(i);
            bufferInputs[i] = !name.startsWith("builtin.") && !name.endsWith(".history")
                    && registry.getBuffer(name) != null;
            if (bufferInputs[i]) {
                bindGroupBuilder.withUniform(inputSamplerName(i), UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT);
            } else {
                bindGroupBuilder.withSampler(inputSamplerName(i));
            }
        }
        bindGroupBuilder.withUniform("u_Globals", UniformType.UNIFORM_BUFFER);
        bindGroupBuilder.withUniform("u_PackOptions", UniformType.UNIFORM_BUFFER);
        bindGroupBuilder.withUniform("u_PassParams", UniformType.UNIFORM_BUFFER);
        BindGroupLayout bindGroup = bindGroupBuilder.build();

        String outputRef = spec.outputs().get(0);
        TargetFormat outputFormat = outputRef.equals("builtin.output")
                ? TargetFormat.RGBA8
                : requireTarget(registry, outputRef).format();

        // spec.blend() is pre-validated by GraphValidator (fullscreen-only, {translucent, additive,
        // multiply} only) -- blendFunction() below only ever sees one of those three values or null
        // here. BlendFunction.TRANSLUCENT is STRAIGHT (non-premultiplied) alpha-over against whatever
        // the attachment's LOAD op preserved: color = src.rgb * src.a + dst.rgb * (1 - src.a), alpha =
        // src.a * 1 + dst.a * (1 - src.a) -- so the pack's fragment shader must output straight
        // (not premultiplied) color and alpha. BlendFunction.ADDITIVE is color/alpha = src + dst
        // (both factors ONE) -- e.g. for a light-scattering composite that only ever adds. "multiply"
        // is color/alpha = dst * src (src factor ZERO, dst factor SRC_COLOR) -- e.g. for a
        // pre-tonemap chromatic-extinction recolor pass that never samples its own destination.
        // Cache-busting generation suffix: mirrors
        // ShaderChunkRendererConstantsMixin.fornax$generationConstant(), the same fix applied for
        // the same root cause on terrain shaders -- blaze3d's device shader-module cache is keyed
        // by Identifier WITHOUT hashing source text, so a pack republish that changes a fullscreen
        // pass's shader TEXT under the same fixed "fornax_runtime:<name>" location can silently
        // keep serving the stale compiled SPIR-V module from a PRIOR generation, even across a
        // cold relaunch. Unlike terrain (which appends a cache-busting DEFINE via a separate
        // shader-constants list), this generic RenderPipeline.builder() path has no equivalent
        // defines-list hook -- the pipeline's OWN `location` identifier is what RenderPipeline
        // registration/caching keys on here, so embedding the generation directly in THAT
        // identifier is the equivalent fix: every pack rebuild gets a genuinely distinct location,
        // forcing a fresh pipeline (and thus a fresh GLSL->SPIR-V compile) instead of ever risking
        // a stale reuse. shaderPath(spec) below (the SEPARATE identifier RuntimeShaderPack
        // actually resolves to real file content) is deliberately left untouched -- only the
        // pipeline's own cache-key identity changes, not how the real shader source gets located.
        Identifier pipelineLocation = Identifier.fromNamespaceAndPath("fornax_runtime",
                spec.name() + "_gen" + GraphRunner.shaderCacheGeneration());

        RenderPipeline pipeline = RenderPipeline.builder()
                .withBindGroupLayout(bindGroup)
                .withLocation(pipelineLocation)
                .withCull(false)
                .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(RuntimeShaderPack.NAMESPACE, shaderPath(spec)))
                .withDepthStencilState(Optional.empty())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(blendFunction(spec.blend()), TargetRegistry.gpuFormat(outputFormat), ColorTargetState.WRITE_ALL))
                .build();

        return new FullscreenPassRunner(spec, pipeline, bufferInputs);
    }

    public void run(TargetRegistry registry, Map<String, MipchainRunner> mipchainTargets,
                     GpuBufferSlice globals, PackOptionsBuffer packOptions, PassParams params) {
        if (invalid) {
            return; // Already failed to bind once this runner's lifetime -- see the field's own doc.
        }

        try {
            runFrame(registry, mipchainTargets, globals, packOptions, params);
            consecutiveFailures = 0;
        } catch (RuntimeException e) {
            // Defense in depth against an input/output resolving to no allocated target -- most
            // commonly a buffer-kind input whose build()-time classification
            // (registry.getBuffer(name) != null, a live-registry SNAPSHOT -- see build()'s own doc)
            // is wrong because the underlying compute buffer's own allocation can race ahead of
            // this runner's construction (a transient "no compute backend available" window at
            // world-join/pack-rebuild time -- see TargetRegistry.ensureBufferSize's own doc). That
            // misclassification is baked into bufferInputs[] for this runner's whole lifetime (a
            // texture-shaped bind-group slot can never correctly read a buffer target, and vice
            // versa), so retrying next frame would just throw again -- same permanent-until-rebuild
            // degradation as the setPipeline catch below.
            //
            // This catch is deliberately broad (RuntimeException): not every RuntimeException
            // reaching it is the permanent, misclassified-target kind described above. A transient
            // frame-timing failure -- e.g. IllegalStateException("Cannot wait on a fence for the
            // current submit") out of PackOptionsBuffer.currentBuffer during a slider drag -- must
            // not permanently disable resolve, tonemap, water_composite, underwater_refraction and
            // every SSR/SSAO pass at once: that reads as a washed-out untonemapped scene rather
            // than as an error, not as a dead pipeline. GraphRunner.updateRuntimeValues defers the
            // ring rotate to the frame boundary to keep this kind of failure rare, but the latch
            // policy below still has to tolerate it when it happens.
            //
            // A genuinely misclassified target throws every frame, so it still latches -- just after
            // CONSECUTIVE_FAILURES_BEFORE_LATCH frames instead of one, which costs a few wasted
            // frames in the permanent case and saves the whole pass chain in the transient one.
            consecutiveFailures++;
            if (consecutiveFailures < CONSECUTIVE_FAILURES_BEFORE_LATCH) {
                FornaxMod.LOGGER.warn("[Fornax] FullscreenPassRunner: pass '{}' threw {} ({}) -- "
                        + "retrying next frame ({}/{} before this pass is disabled)",
                        spec.name(), e.getClass().getSimpleName(), e.getMessage(),
                        consecutiveFailures, CONSECUTIVE_FAILURES_BEFORE_LATCH);
                return;
            }
            invalid = true;
            // Names the actual exception rather than asserting a cause -- the failure reaching
            // this point is not always an unresolved input/output target (the transient
            // fence-timing case above can land here too), so the log states what happened, not
            // what usually happens.
            FornaxMod.LOGGER.error("[Fornax] FullscreenPassRunner: pass '{}' threw {} on {} "
                    + "consecutive frames ({}) -- skipping it for the rest of this runner's "
                    + "lifetime; deferred output will be incomplete until the next successful "
                    + "rebuild. Most commonly an input/output resolving to no allocated target.",
                    spec.name(), e.getClass().getSimpleName(), consecutiveFailures, e.getMessage(), e);
        }
    }

    private void runFrame(TargetRegistry registry, Map<String, MipchainRunner> mipchainTargets,
            GpuBufferSlice globals, PackOptionsBuffer packOptions, PassParams params) {
        String outputRef = spec.outputs().get(0);
        GpuTextureView outputView = GraphInputResolver.resolveView(outputRef, registry, mipchainTargets);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "Fornax " + spec.name(), outputView, Optional.empty())) {
            try {
                pass.setPipeline(pipeline);
            } catch (RuntimeException e) {
                // Defense in depth against a shader/bind-group mismatch (stale resource snapshot
                // race, or a genuinely broken pack) reaching this point at all -- see the field doc
                // on `invalid`. Without this catch, blaze3d's own "Pipeline is not valid" exception
                // propagates straight up through GraphRunner.finish() and crashes the game; caught
                // here, the pass just stops rendering, exactly like a missing runner already does.
                invalid = true;
                FornaxMod.LOGGER.error("[Fornax] FullscreenPassRunner: pass '{}' failed to bind its "
                        + "pipeline (shader/bind-group mismatch) -- skipping it for the rest of this "
                        + "runner's lifetime; deferred output will be incomplete until the next "
                        + "successful rebuild", spec.name(), e);
                return;
            }

            pass.setUniform("u_Globals", globals);
            pass.setUniform("u_PackOptions", packOptions.currentBuffer());

            passParamsData.rotate();
            try (var data = passParamsData.currentBuffer().map(false, true)) {
                float[] sunRect = params.sunSpriteRect();
                float[] moonRect = params.moonSpriteRect();
                Std140Builder.intoBuffer(data.data())
                        .putVec2(params.texelSizeX(), params.texelSizeY())
                        .putFloat(params.param2())
                        .putFloat(params.param3())
                        // vec4, not vec3: the fourth component occupies padding the vec3 already
                        // pays for, and carries the TRUE sun elevation so a shader can tell day from
                        // night. u_SunDirection.xyz is unchanged.
                        .putVec4(params.sunDirX(), params.sunDirY(), params.sunDirZ(),
                                params.trueSunHeight())
                        .putVec4(sunRect[0], sunRect[1], sunRect[2], sunRect[3])
                        .putVec4(moonRect[0], moonRect[1], moonRect[2], moonRect[3])
                        .get();
            }
            pass.setUniform("u_PassParams", passParamsData.currentBuffer());

            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            // builtin.noise's CONTRACT is filtered tileable noise (see NoiseTexture's class doc) --
            // that lives on the INPUT, not a generic per-input filter syntax in graph.toml (YAGNI
            // until a second consumer needs anything but NEAREST + CLAMP_TO_EDGE), so it's special-
            // cased here by the literal input-ref string instead. Every other input keeps binding
            // NEAREST + CLAMP_TO_EDGE via `sampler` above, unchanged. Every pack-declared
            // [textures.*] asset (e.g. waterWaveNormal) shares the exact same contract -- a
            // tileable-image asset needs bilinear filtering and wraps like any other tiled texture --
            // so it reuses the same sampler, checked via PackTextureRegistry.isDeclared rather than a
            // literal name (there can be any number of pack-declared textures, unlike the two fixed
            // engine builtins).
            GpuSampler noiseSampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
            // PackTextureRegistry uploads a real mip chain for every static pack texture. The true
            // flag is load-bearing: without it SamplerCache clamps maxLod to zero and all uploaded
            // levels are ignored. builtin.noise remains on the non-mipped sampler above.
            GpuSampler packTextureSampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR, true);
            // For targets that declared `filter = "linear"` -- bilinear but still CLAMPed, since a
            // render target has real edges where a tileable image does not.
            GpuSampler linearSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            PackTextureRegistry packTextures = GraphRunner.packTextureRegistry();
            List<String> inputs = spec.inputs();
            for (int i = 0; i < inputs.size(); i++) {
                if (bufferInputs[i]) {
                    BufferInstance buf = registry.getBuffer(inputs.get(i));
                    if (buf == null) {
                        throw new IllegalStateException("Fornax graph: fullscreen pass '" + spec.name()
                                + "' buffer input '" + inputs.get(i) + "' is not allocated");
                    }
                    if (texelWrappers[i] == null || texelWrapperHandles[i] != buf.vkBuffer()) {
                        texelWrappers[i] = new RawVulkanGpuBuffer(buf.vkBuffer(), GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, buf.sizeBytes());
                        texelWrapperHandles[i] = buf.vkBuffer();
                    }
                    pass.setUniform(inputSamplerName(i), texelWrappers[i]);
                    logTexelBufferBindOnce(buf);
                } else {
                    GpuTextureView inputView = GraphInputResolver.resolveView(inputs.get(i), registry, mipchainTargets);
                    boolean builtinNoise = inputs.get(i).equals("builtin.noise");
                    boolean packTexture = packTextures != null && packTextures.isDeclared(inputs.get(i));
                    InputSamplerKind kind = samplerKindFor(inputs.get(i), packTexture, builtinNoise,
                            registry.filterFor(inputs.get(i)));
                    GpuSampler inputSampler = switch (kind) {
                        case SHADOW_COMPARISON -> {
                            // Falls back to the plain NEAREST sampler (byte-identical to the
                            // pre-existing behavior) on the GL backend, or for one frame before any
                            // GPU device exists yet -- ShadowComparisonSampler.get()'s own null-safe,
                            // retry-next-frame convention (see VulkanComputeBackend.tryCreate).
                            GpuSampler comparisonSampler = ShadowComparisonSampler.get();
                            yield comparisonSampler != null ? comparisonSampler : sampler;
                        }
                        case PACK_TEXTURE_REPEAT -> packTextureSampler;
                        case NOISE_REPEAT -> noiseSampler;
                        case LINEAR_CLAMP -> linearSampler;
                        case NEAREST_CLAMP -> sampler;
                    };
                    pass.bindTexture(inputSamplerName(i), inputView, inputSampler);
                }
            }

            pass.draw(3, 1, 0, 0);
        }
    }

    /** {@code null} (opaque, the pre-existing default) or one of the three values {@code
     * GraphValidator} already restricted {@code spec.blend()} to -- see this class's doc comment on
     * {@link #build} for each preset's exact src/dst factors. */
    private static Optional<BlendFunction> blendFunction(@org.jspecify.annotations.Nullable String blend) {
        if (blend == null) return Optional.empty();
        return switch (blend) {
            case "translucent" -> Optional.of(BlendFunction.TRANSLUCENT);
            case "additive" -> Optional.of(BlendFunction.ADDITIVE);
            case "multiply" -> Optional.of(new BlendFunction(BlendFactor.ZERO, BlendFactor.SRC_COLOR));
            default -> throw new IllegalStateException(
                    "Fornax graph: unreachable -- GraphValidator must reject blend '" + blend + "' before build()");
        };
    }

    private static TargetInstance requireTarget(TargetRegistry registry, String name) {
        TargetInstance t = registry.get(name);
        if (t == null) {
            throw new IllegalStateException("Fornax graph: output target '" + name + "' is not allocated");
        }
        return t;
    }

    private static String inputSamplerName(int index) {
        return "u_Input" + index;
    }

    /** Which {@link GpuSampler} kind a fullscreen pass input gets. Precedence matches {@code
     * runFrame}'s pre-extraction branch order exactly: shadow comparison, then pack texture, then
     * builtin noise, then a target's own declared {@code filter = "linear"}, then plain NEAREST. */
    enum InputSamplerKind {
        SHADOW_COMPARISON, PACK_TEXTURE_REPEAT, NOISE_REPEAT, LINEAR_CLAMP, NEAREST_CLAMP
    }

    /**
     * Pure classification, extracted so the shadow-map comparison-vs-raw split is a single line a
     * unit test can pin directly, rather than living only inside {@link #runFrame}'s large per-input
     * branch where nothing but a comment guarded it -- see {@link ShadowMapManager#RAW_TARGET}'s own
     * doc for why two pack-visible names exist for one texture.
     *
     * <p>{@code ref.equals(ShadowMapManager.TARGET)} is the ONLY way to reach {@code
     * SHADOW_COMPARISON}: {@link ShadowMapManager#RAW_TARGET} is a different string, so it can never
     * match here and always falls through to {@code NEAREST_CLAMP} like any other input -- that
     * fall-through, not a second explicit branch, is what makes the split structural rather than a
     * maintained invariant. {@code ShadowMapManagerSamplerKindTest} pins both directions.
     */
    static InputSamplerKind samplerKindFor(String ref, boolean packTexture, boolean builtinNoise,
            TargetFilter filter) {
        if (ref.equals(ShadowMapManager.TARGET)) {
            return InputSamplerKind.SHADOW_COMPARISON;
        }
        if (packTexture) {
            return InputSamplerKind.PACK_TEXTURE_REPEAT;
        }
        if (builtinNoise) {
            return InputSamplerKind.NOISE_REPEAT;
        }
        if (filter == TargetFilter.LINEAR) {
            // A target that declared `filter = "linear"` (see TargetFilter). CLAMP, not REPEAT --
            // this is a render target being magnified, and wrapping would pull the opposite screen
            // edge in. Distinct from the tileable-noise branch above for exactly that reason.
            return InputSamplerKind.LINEAR_CLAMP;
        }
        return InputSamplerKind.NEAREST_CLAMP;
    }

    /**
     * VERIFY-EARLY (this task's brief): the first compute-written-buffer -> fragment-texelFetch
     * bind on this backend. Logged once, process-wide, on the first frame any fullscreen pass binds
     * a TEXEL_BUFFER input. {@code VulkanDevice} exposes only {@code vkDevice()}/{@code
     * computeQueue()} -- no accessor reaches the {@code VkPhysicalDevice}/{@code
     * VkPhysicalDeviceLimits} needed to read {@code maxTexelBufferElements} without adding new mixin
     * plumbing, which this task deliberately does not build speculatively (no consumer needs it
     * yet). Logging the bound element count alone instead, so a GPU capture (or a future limits
     * accessor) has a concrete number to compare against that device limit: typical desktop Vulkan
     * drivers report >=128M elements, but MoltenVK (macOS) is documented to cap as low as 64M or
     * lower on some configs -- a 61MB voxel light volume at R32_UINT is ~16M elements, comfortably
     * under either ceiling today, but this log is the tripwire if a future buffer grows past it.
     */
    private static void logTexelBufferBindOnce(BufferInstance buf) {
        if (loggedTexelBufferBindOnce) {
            return;
        }
        loggedTexelBufferBindOnce = true;
        long elementCount = buf.sizeBytes() / 4;
        FornaxMod.LOGGER.info("[Fornax] First TEXEL_BUFFER fullscreen bind: '{}' ({} bytes, {} R32_UINT "
                        + "elements) -- compare against this device's VkPhysicalDeviceLimits.maxTexelBufferElements "
                        + "in a capture if texture-view creation ever fails here",
                buf.name(), buf.sizeBytes(), elementCount);
    }

    /** Releases the per-pass uniform ring. RenderPipeline objects are owned/cached by Blaze3D. */
    @Override
    public void close() {
        passParamsData.close();
    }

    /** {@code PassSpec.shader()} is a pack-root-relative path like {@code shaders/post/ssao.fsh}; a
     * blaze3d fragment-shader {@link Identifier} path has no extension (matches {@code fornax:post/ssao}
     * elsewhere in this codebase). */
    private static String shaderPath(PassSpec spec) {
        String shader = spec.shader();
        if (shader == null) {
            throw new IllegalArgumentException("Fornax graph: fullscreen pass '" + spec.name() + "' declares no shader");
        }
        String noPrefix = shader.startsWith("shaders/") ? shader.substring("shaders/".length()) : shader;
        int dot = noPrefix.lastIndexOf('.');
        return dot < 0 ? noPrefix : noPrefix.substring(0, dot);
    }
}
