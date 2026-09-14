package dev.icehunter.fornax.debug;

import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/** Snapshot of the actual native sampler creation arguments, independent of the stack lifetime. */
public final class CaptureSamplerState {
    private CaptureSamplerState() {}

    public static Map<String, Object> snapshot(VkSamplerCreateInfo info) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("flags", info.flags());
        state.put("magFilter", info.magFilter()); state.put("minFilter", info.minFilter());
        state.put("mipmapMode", info.mipmapMode());
        state.put("addressModeU", info.addressModeU()); state.put("addressModeV", info.addressModeV());
        state.put("addressModeW", info.addressModeW()); state.put("mipLodBias", info.mipLodBias());
        state.put("anisotropyEnable", info.anisotropyEnable()); state.put("maxAnisotropy", info.maxAnisotropy());
        state.put("compareEnable", info.compareEnable()); state.put("compareOp", info.compareOp());
        state.put("minLod", info.minLod()); state.put("maxLod", info.maxLod());
        state.put("borderColor", info.borderColor()); state.put("unnormalizedCoordinates", info.unnormalizedCoordinates());
        state.put("pNext", info.pNext());
        return Map.copyOf(state);
    }
}
