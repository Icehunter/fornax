package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.pack.layout.RuntimeShaderPack;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4f;

import java.util.Map;
import java.util.Optional;

/**
 * Generalizes {@code HiZDownsamplePass}'s exact shape: a seed pass copies the pack's single-level
 * input (e.g. {@code builtin.depth}) 1:1 into mip 0 of a dedicated multi-level texture, then a chain
 * of 2x2-max-reduce passes fills each subsequent level from the one before it, one full-screen
 * triangle draw per level.
 *
 * <p>Owns its multi-level texture independently of {@link TargetRegistry}: a plain
 * {@link TargetInstance} models exactly one mip level, since none of the hardcoded pipeline's other
 * targets need more, so a {@code mipchain} pass's own declared target is instead allocated here,
 * sized from its {@link TargetSpec}'s own format/scale exactly like any other target would be, but
 * with {@link TargetPlan#computeLevelCount} levels instead of one. Any other pass referencing this
 * target by name (e.g. an SSR-trace-shaped pass sampling the full chain) resolves it through the
 * {@code mipchainTargets} map {@link GraphRunner} threads into every runner, ahead of a plain
 * {@link TargetRegistry} lookup -- see {@link GraphInputResolver}.
 */
public final class MipchainRunner implements AutoCloseable {
    private final PassSpec spec;
    private final TargetSpec targetSpec;
    private final TargetFormat format;
    private final RenderPipeline pipeline;
    private final GpuBuffer seedParams;
    private final GpuBuffer reduceParams;

    private int width;
    private int height;
    private int levels;
    @Nullable
    private GpuTexture texture;
    @Nullable
    private GpuTextureView fullView;
    private GpuTextureView[] levelViews = new GpuTextureView[0];

    private MipchainRunner(PassSpec spec, TargetSpec targetSpec, TargetFormat format, RenderPipeline pipeline,
                            GpuBuffer seedParams, GpuBuffer reduceParams) {
        this.spec = spec;
        this.targetSpec = targetSpec;
        this.format = format;
        this.pipeline = pipeline;
        this.seedParams = seedParams;
        this.reduceParams = reduceParams;
    }

    public static MipchainRunner build(PassSpec spec, TargetSpec targetSpec) {
        TargetFormat format = TargetFormat.parse(targetSpec.format(), targetSpec.name(), "graph.toml");

        BindGroupLayout bindGroup = BindGroupLayout.builder()
                .withSampler("u_Input0")
                .withUniform("u_PassParams", UniformType.UNIFORM_BUFFER)
                .build();

        RenderPipeline pipeline = RenderPipeline.builder()
                .withBindGroupLayout(bindGroup)
                .withLocation(Identifier.fromNamespaceAndPath("fornax_runtime", spec.name()))
                .withCull(false)
                .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(RuntimeShaderPack.NAMESPACE, shaderPath(spec)))
                .withDepthStencilState(Optional.empty())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.empty(), TargetRegistry.gpuFormat(format), ColorTargetState.WRITE_ALL))
                .build();

        GpuBuffer seedParams = buildParamsBuffer(spec.name() + " seed params", 1);
        GpuBuffer reduceParams = buildParamsBuffer(spec.name() + " reduce params", 0);

        return new MipchainRunner(spec, targetSpec, format, pipeline, seedParams, reduceParams);
    }

    private static GpuBuffer buildParamsBuffer(String label, int seedFlag) {
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, PassParams.BUFFER_SIZE);
        try (var data = buffer.map(false, true)) {
            Std140Builder.intoBuffer(data.data()).putVec2(0.0f, 0.0f).putFloat(seedFlag).putFloat(0.0f)
                    .putVec3(0.0f, 0.0f, 0.0f).get();
        }
        return buffer;
    }

    @Nullable
    public GpuTextureView fullChainView() {
        return fullView;
    }

    public void ensureSize(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        int newWidth = TargetPlan.textureWidth(targetSpec, renderWidth, outputWidth);
        int newHeight = TargetPlan.textureHeight(targetSpec, renderHeight, outputHeight);
        int newLevels = TargetPlan.computeLevelCount(newWidth, newHeight);

        if (texture != null && width == newWidth && height == newHeight && levels == newLevels) {
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[Fornax] MipchainRunner '{}' skipping (re)build: no GPU device available", spec.name());
            return;
        }

        GpuTexture newTexture = device.createTexture("Fornax Mipchain " + targetSpec.name(),
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                TargetRegistry.gpuFormat(format), newWidth, newHeight, 1, newLevels);
        GpuTextureView newFullView = device.createTextureView(newTexture);
        GpuTextureView[] newLevelViews = new GpuTextureView[newLevels];
        for (int i = 0; i < newLevels; i++) {
            newLevelViews[i] = device.createTextureView(newTexture, i, 1);
            clear(device, newLevelViews[i]);
        }

        GpuTexture oldTexture = texture;
        GpuTextureView oldFullView = fullView;
        GpuTextureView[] oldLevelViews = levelViews;

        texture = newTexture;
        fullView = newFullView;
        levelViews = newLevelViews;
        width = newWidth;
        height = newHeight;
        levels = newLevels;

        if (oldLevelViews.length > 0 || oldFullView != null || oldTexture != null) {
            // Live per-frame resize path (window resize / SSAA render-scale change), reached every
            // frame from GraphRunner.prepare()'s mipchainRunners loop on the SAME live instance --
            // identical crash-class hazard to OpaqueDepth.ensureSize()/TargetRegistry.reconcile()
            // (see VulkanComputeBackend.waitForGpuIdleBeforeDestroy's own doc for the two live
            // MoltenVK crashes this guards against). GraphRunner.closeCurrent()'s own wait-idle
            // covers this runner's close() below (called from closeCurrent() itself), but NOT this
            // per-frame rebuild path, so it needs its own guard here too.
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        }
        for (GpuTextureView v : oldLevelViews) v.close();
        if (oldFullView != null) oldFullView.close();
        if (oldTexture != null) oldTexture.close();

        FornaxMod.LOGGER.info("[Fornax] Mipchain target '{}' (re)built at {}x{}, {} levels", targetSpec.name(), newWidth, newHeight, newLevels);
    }

    public void run(TargetRegistry registry, Map<String, MipchainRunner> mipchainTargets) {
        if (levels == 0 || levelViews.length == 0) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        GpuTextureView seedInput = GraphInputResolver.resolveView(spec.inputs().get(0), registry, mipchainTargets);
        try (RenderPass pass = encoder.createRenderPass(() -> "Fornax " + spec.name() + " Seed", levelViews[0], Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("u_PassParams", seedParams);
            pass.bindTexture("u_Input0", seedInput, sampler);
            pass.draw(3, 1, 0, 0);
        }

        for (int i = 1; i < levels; i++) {
            try (RenderPass pass = encoder.createRenderPass(() -> "Fornax " + spec.name() + " Reduce", levelViews[i], Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.setUniform("u_PassParams", reduceParams);
                pass.bindTexture("u_Input0", levelViews[i - 1], sampler);
                pass.draw(3, 1, 0, 0);
            }
        }
    }

    private static void clear(GpuDevice device, GpuTextureView view) {
        CommandEncoder encoder = device.createCommandEncoder();
        try (var pass = encoder.createRenderPass(() -> "Fornax Mipchain Clear", view, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 0.0f)))) {
            // Clear-only.
        }
    }

    private static String shaderPath(PassSpec spec) {
        String shader = spec.shader();
        if (shader == null) {
            throw new IllegalArgumentException("Fornax graph: mipchain pass '" + spec.name() + "' declares no shader");
        }
        String noPrefix = shader.startsWith("shaders/") ? shader.substring("shaders/".length()) : shader;
        int dot = noPrefix.lastIndexOf('.');
        return dot < 0 ? noPrefix : noPrefix.substring(0, dot);
    }

    /**
     * Document-safe: no wait-idle here by design. This runner's only caller (GraphRunner.closeCurrent())
     * already runs VulkanComputeBackend.waitForGpuIdleBeforeDestroy() once at the top of that method,
     * before this or any other GPU resource it owns is freed -- see that method's own doc.
     */
    @Override
    public void close() {
        for (GpuTextureView v : levelViews) v.close();
        if (fullView != null) fullView.close();
        if (texture != null) texture.close();
        seedParams.close();
        reduceParams.close();
    }
}
