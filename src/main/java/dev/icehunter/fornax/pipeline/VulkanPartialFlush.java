package dev.icehunter.fornax.pipeline;

/**
 * Explicit graphics-queue dispatch for event-ordered interop seams with no open render pass.
 * Transient handles become invalid across this boundary. Ordinary GPU fences still refer to the
 * normal encoder completion epoch: callers that wait on those fences must use a full submit.
 */
public interface VulkanPartialFlush {
    void fornax$flushPending();
}
