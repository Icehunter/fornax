package dev.icehunter.fornax.pass.compute;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK13;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComputePipelineBuilderCacheFallbackTest {

    @Test
    void cachedSuccessDoesNotRetryWithoutTheCache() {
        List<Long> attempts = new ArrayList<>();

        int result = ComputePipelineBuilder.createWithCacheFallback(77L, cache -> {
            attempts.add(cache);
            return VK13.VK_SUCCESS;
        });

        assertEquals(VK13.VK_SUCCESS, result);
        assertEquals(List.of(77L), attempts);
    }

    @Test
    void cachedFailureRetriesOnceWithoutTheCache() {
        List<Long> attempts = new ArrayList<>();

        int result = ComputePipelineBuilder.createWithCacheFallback(77L, cache -> {
            attempts.add(cache);
            return cache == VK13.VK_NULL_HANDLE
                    ? VK13.VK_SUCCESS : VK13.VK_ERROR_INITIALIZATION_FAILED;
        });

        assertEquals(VK13.VK_SUCCESS, result);
        assertEquals(List.of(77L, VK13.VK_NULL_HANDLE), attempts);
    }

    @Test
    void absentCacheFailureIsNotRetriedAgainstTheSameNullHandle() {
        List<Long> attempts = new ArrayList<>();

        int result = ComputePipelineBuilder.createWithCacheFallback(VK13.VK_NULL_HANDLE, cache -> {
            attempts.add(cache);
            return VK13.VK_ERROR_INITIALIZATION_FAILED;
        });

        assertEquals(VK13.VK_ERROR_INITIALIZATION_FAILED, result);
        assertEquals(List.of(VK13.VK_NULL_HANDLE), attempts);
    }

    @Test
    void uncachedFailureReplacesTheCachedFailureResult() {
        List<Long> attempts = new ArrayList<>();

        int result = ComputePipelineBuilder.createWithCacheFallback(77L, cache -> {
            attempts.add(cache);
            return cache == VK13.VK_NULL_HANDLE
                    ? VK13.VK_ERROR_DEVICE_LOST : VK13.VK_ERROR_INITIALIZATION_FAILED;
        });

        assertEquals(VK13.VK_ERROR_DEVICE_LOST, result);
        assertEquals(List.of(77L, VK13.VK_NULL_HANDLE), attempts);
    }
}
