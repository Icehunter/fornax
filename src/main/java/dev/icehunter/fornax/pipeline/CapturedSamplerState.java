package dev.icehunter.fornax.pipeline;

import java.util.Map;

/** Read-only native sampler metadata, populated only when capture is configured at startup. */
public interface CapturedSamplerState {
    Map<String, Object> fornax$samplerState();
}
