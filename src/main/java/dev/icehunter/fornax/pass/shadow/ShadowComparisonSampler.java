package dev.icehunter.fornax.pass.shadow;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import dev.icehunter.fornax.mixin.vulkan.GpuDeviceBackendAccessor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.OptionalDouble;

/**
 * The engine's one HARDWARE COMPARISON sampler ({@code VkSamplerCreateInfo.compareEnable = true}):
 * every fixed-function GPU exposes this as a core Vulkan 1.0 feature, but Blaze3D's own sampler
 * surface (both {@code VulkanDevice.createSampler} and {@code RenderSystem.getSamplerCache()})
 * never sets {@code compareEnable}/{@code compareOp} -- verified by decompiling {@code
 * VulkanGpuSampler}'s constructor (game jar, mc26.2): it builds a {@code VkSamplerCreateInfo} that
 * never touches either field, so both stay at their {@code calloc}-zeroed defaults (compare
 * disabled). This class exists purely to fill that gap for ONE consumer: the sun/moon shadow map
 * ({@link ShadowMapManager#TARGET}), whose manual {@code refDepth <= texture(...).r} depth compare
 * in {@code gbuffer_resolve.fsh} is acne-prone by construction (a
 * plain NEAREST-filtered manual compare has no hardware 2x2 PCF to anti-alias the pass/fail
 * boundary the reference technique's {@code shadow2D} sampling gets for free).
 *
 * <p><b>Compare semantics.</b> {@link ShadowMapManager}'s doc comment nails the shadow map's own
 * depth convention down precisely: forward-Z {@code [0,1]}, cleared to {@code 1.0} (far/no
 * occluder), the stored texel is the light-NEAREST occluder depth, and
 * {@code refDepth <= storedDepth} means "nothing occludes this point" = lit. The Vulkan/GLSL
 * hardware-compare contract (core spec, independent of any vendor) is
 * {@code result = (Dref <compareOp> texel) ? 1.0 : 0.0}, so {@code compareOp =
 * VK_COMPARE_OP_LESS_OR_EQUAL} reproduces that exact predicate: {@code texture(sampler2DShadow,
 * vec3(uv, refDepth))} returns 1.0 (lit) exactly when {@code refDepth <= storedDepth}, hardware
 * 2x2-bilinear-PCF'd across the four texels straddling {@code uv} -- the anti-aliased edge the
 * manual NEAREST compare could never produce, on every one of a spiral PCF loop's taps.
 *
 * <p><b>Why a subclass of {@code VulkanGpuSampler}, not a fresh {@code GpuSampler}
 * implementation.</b> {@code VulkanRenderPass.bindTexture}/{@code pushDescriptors} (decompiled,
 * game jar) {@code checkcast} the bound sampler to the concrete {@code VulkanGpuSampler} type and
 * read its native handle via {@code invokevirtual VulkanGpuSampler.vkSampler()} -- an arbitrary
 * {@code GpuSampler} implementation would fail that checkcast at the first bind. {@code
 * VulkanGpuSampler} is not {@code final} and {@code vkSampler()} is not {@code final} either
 * (javap-verified), so a subclass overriding {@code vkSampler()} is a legal, minimally-invasive
 * seam -- the same "extend the Blaze3D Vulkan wrapper type, override just enough" shape {@code
 * RawVulkanGpuBuffer} already uses for texel-buffer binds. The one wrinkle: {@code
 * VulkanGpuSampler} has exactly one constructor, and it unconditionally builds and stores a
 * PLAIN (non-comparison) {@code VkSampler} of its own via {@code vkCreateSampler} before returning
 * -- there is no lighter super-constructor to call. This class lets that call happen (there is no
 * way to avoid it through public API) and then immediately destroys the throwaway handle via
 * {@code super.destroy()} once its own real comparison sampler is built, so nothing leaks. {@code
 * vkSampler()} is overridden to return the real comparison handle; {@code destroy()} is
 * deliberately NOT overridden (this sampler is a session-lifetime singleton, like {@code
 * NoiseTexture}'s texture/view -- never explicitly closed once created, exactly the same "static
 * holder, created once, held for the process" lifecycle).
 */
public final class ShadowComparisonSampler {
    @Nullable
    private static GpuSampler sampler;

    /** Set once {@link #get()} has determined the active backend is not Vulkan (GL) -- the
     * comparison-sampler seam only reaches {@code VulkanDevice}, so this never retries on GL,
     * mirroring {@code VulkanComputeBackend.tryCreate()}'s own "GL backend: no compute path
     * exists" early-out. */
    private static boolean unavailableOnThisBackend;

    private ShadowComparisonSampler() {
    }

    /**
     * The shared hardware comparison sampler for {@link ShadowMapManager#TARGET}, lazily created
     * on first call once a GPU device exists. Returns {@code null} before any device exists yet
     * (retry next frame, the established device-not-ready convention -- see {@code
     * VulkanComputeBackend#tryCreate}) or permanently on the GL backend, which has no raw-Vulkan
     * seam to reach.
     */
    @Nullable
    public static GpuSampler get() {
        if (sampler != null) {
            return sampler;
        }
        if (unavailableOnThisBackend) {
            return null;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return null;
        }

        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) device).fornax$backend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            unavailableOnThisBackend = true;
            return null;
        }

        sampler = new ComparisonSampler(vulkanDevice);
        return sampler;
    }

    private static final class ComparisonSampler extends VulkanGpuSampler {
        private final long comparisonVkSampler;

        ComparisonSampler(VulkanDevice device) {
            // Matches the plain shadow-input sampler's own filter/address contract (LINEAR +
            // CLAMP_TO_EDGE -- see FullscreenPassRunner) so this object's own getAddressModeU/V/
            // getMinFilter/getMagFilter (GpuSampler's abstract descriptor-metadata surface) report
            // truthfully, even though the real bound handle (see vkSampler() below) is the
            // hand-built comparison sampler below, not whatever this super-constructor allocates.
            super(device, AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.of(0.0));
            this.comparisonVkSampler = createComparisonSampler(device);
            // Frees the throwaway plain sampler the super constructor above had no choice but to
            // create (see this class's doc comment) -- this object's own vkSampler() below never
            // returns that handle, so nothing keeps it alive or needs it again.
            super.destroy();
        }

        @Override
        public long vkSampler() {
            return comparisonVkSampler;
        }

        private static long createComparisonSampler(VulkanDevice device) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                        .sType$Default()
                        .magFilter(VK10.VK_FILTER_LINEAR)
                        .minFilter(VK10.VK_FILTER_LINEAR)
                        // Single mip level (the shadow map has none), so mode is moot -- NEAREST
                        // matches VulkanGpuSampler's own ctor for a zero maxLod.
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .mipLodBias(0f)
                        .anisotropyEnable(false)
                        .maxAnisotropy(1f)
                        .compareEnable(true)
                        // See this class's doc comment: LESS_OR_EQUAL reproduces
                        // sampleSunShadow's own `refDepth <= storedDepth == lit` predicate exactly.
                        .compareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL)
                        .minLod(0f)
                        .maxLod(0f);

                LongBuffer out = stack.callocLong(1);
                VkDevice vkDevice = device.vkDevice();
                int result = VK10.vkCreateSampler(vkDevice, info, null, out);
                VulkanUtils.crashIfFailure(device, result, "Can't create shadow comparison sampler");
                return out.get(0);
            }
        }
    }
}
