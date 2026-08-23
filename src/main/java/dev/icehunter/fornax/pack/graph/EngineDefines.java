package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.config.AaMethod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Translates the engine's {@link AaMethod} selection into compile facts a pack's own graph/GLSL can
 * react to, so a pack never needs its own copy of "which AA method is active" logic. Two views of
 * the same facts:
 * <ul>
 *   <li>{@link #forMethod} -- overlaid onto {@code GraphRunner.rebuild}'s {@code compileValues}, so
 *       a pack's {@code enabled_if} expressions (evaluated against that same map) can gate whole
 *       passes/targets on {@code FX_TAA}/{@code FX_UPSCALE}/{@code FX_METHOD_*}.</li>
 *   <li>{@link #glslPreamble} -- the same facts as literal {@code #define} lines, prepended to every
 *       fullscreen pass's shader source so its GLSL can {@code #ifdef FX_UPSCALE} directly.</li>
 * </ul>
 * Engine facts always win over anything a pack itself might declare under these names -- {@code
 * GraphRunner.rebuild} overlays this map onto (not merges under) the pack's own compile values.
 */
public final class EngineDefines {
    private EngineDefines() {
    }

    /**
     * The seven {@code FX_*} keys, always all present (0 or 1) regardless of method -- callers never
     * need to special-case a missing key. {@code FX_TAA} is the "some form of temporal resolve is
     * active" umbrella (true for both {@code TAA} and {@code TAAU}); {@code FX_UPSCALE} is the
     * "rendering below native and reconstructing" fact (true only for {@code TAAU}); the four
     * {@code FX_METHOD_*} keys are a one-hot encoding of the exact method for anything that needs to
     * distinguish TAA from TAAU specifically rather than react to the umbrella facts; {@code
     * FX_COMPUTE} is an orthogonal capability fact -- whether a live compute backend exists this
     * session -- so a pack's {@code enabled_if} can gate an entire compute-backed pass/target
     * subtree on it. It never interacts with the AA-method facts above; {@code computeAvailable} is
     * a separate, independent signal callers pass in themselves (see {@code GraphRunner.rebuild}'s
     * cached {@code computeBackend} field -- {@code computeBackend != null} -- never a fresh probe).
     */
    /** The seven key names {@link #forMethod} always populates, regardless of method or compute
     * availability -- the set {@code GraphValidator.checkEnabledIf} treats as always-known so a
     * pack's {@code enabled_if} may reference any of them without its own {@code PackOption}
     * declaration (unlike every pack-declared compile option, {@code OptionScanner} never sees these
     * names -- they exist only in the runtime {@code compileValues} overlay {@link #forMethod}
     * produces, never as a {@code //[...] compile} annotation in pack shader source). Load-time
     * validation must therefore special-case this exact key set rather than require it be scanned,
     * mirroring {@code sunShadowMap}/{@code sceneHistory}/{@code packOptions}' own engine-owned-name
     * carve-outs elsewhere in {@code GraphValidator}. */
    public static final Set<String> KEYS = Set.of(
            "FX_TAA", "FX_UPSCALE", "FX_METHOD_OFF", "FX_METHOD_TAA", "FX_METHOD_SSAA", "FX_METHOD_TAAU",
            "FX_METHOD_METALFX",
            "FX_COMPUTE");

    public static Map<String, Integer> forMethod(AaMethod m, boolean computeAvailable) {
        Map<String, Integer> defines = new LinkedHashMap<>();
        // METALFX counts as both "temporal resolve active" (FX_TAA -- the frame is jittered and
        // temporally integrated, just by MetalFX instead of the engine reconstruct) and
        // "upscaling active" (FX_UPSCALE -- render res is below native), so packs' existing
        // jitter/upscale-aware branches behave identically to TAAU with no pack change.
        defines.put("FX_TAA", (m == AaMethod.TAA || m == AaMethod.TAAU || m == AaMethod.METALFX) ? 1 : 0);
        defines.put("FX_UPSCALE", (m == AaMethod.TAAU || m == AaMethod.METALFX) ? 1 : 0);
        defines.put("FX_METHOD_OFF", m == AaMethod.OFF ? 1 : 0);
        defines.put("FX_METHOD_TAA", m == AaMethod.TAA ? 1 : 0);
        defines.put("FX_METHOD_SSAA", m == AaMethod.SSAA ? 1 : 0);
        defines.put("FX_METHOD_TAAU", m == AaMethod.TAAU ? 1 : 0);
        defines.put("FX_METHOD_METALFX", m == AaMethod.METALFX ? 1 : 0);
        defines.put("FX_COMPUTE", computeAvailable ? 1 : 0);
        return defines;
    }

    /** {@link #forMethod} rendered as literal {@code #define KEY VALUE} lines, one per line, in the same order. */
    public static String glslPreamble(AaMethod m, boolean computeAvailable) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : forMethod(m, computeAvailable).entrySet()) {
            sb.append("#define ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }
}
