package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.GBufferDebugView;
import dev.icehunter.fornax.pack.graph.TargetInstance;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.ByteOrder;
import java.util.Locale;

/**
 * LabPBR decode audit (2026-08-09) instrument: a one-shot VRAM-to-CPU readback of an {@link
 * #WINDOW}x{@link #WINDOW} block centred on the crosshair pixel of {@code sceneHdr}, ONLY
 * meaningful while one of the ten ordinals this class knows about -- {@link
 * GBufferDebugView#ENV_SPEC_RATIO}, {@link GBufferDebugView#ENV_DECOMP_SKY}, {@link
 * GBufferDebugView#ENV_DECOMP_MIX}, {@link GBufferDebugView#ENV_DECOMP_MAT}, {@link
 * GBufferDebugView#ENV_DECOMP_LOCAL}, {@link GBufferDebugView#ENV_DECOMP_AO}, {@link
 * GBufferDebugView#ENV_DECOMP_RESIDUAL}, {@link GBufferDebugView#ENV_DECOMP_ALBEDO_WRITE_VS_READ},
 * {@link GBufferDebugView#ENV_DECOMP_ALBEDO_IDENTITY_INPUTS}, {@link
 * GBufferDebugView#UW_CLOSURE_DEBUG}, {@link GBufferDebugView#SHADOW_QUERY_1}, {@link
 * GBufferDebugView#SHADOW_QUERY_2}, {@link GBufferDebugView#SHADOW_QUERY_3}, {@link
 * GBufferDebugView#GLINT_OCCLUSION_QUERY}, {@link GBufferDebugView#UW_GLINT_1}, {@link
 * GBufferDebugView#UW_GLINT_2}, {@link GBufferDebugView#UW_GLINT_3}, {@link
 * GBufferDebugView#UW_GLINT_4}, {@link GBufferDebugView#UW_GLINT_5} -- is the active debug view; see
 * each ordinal's own doc comment and its matching shader branch for exactly what it packs.
 * {@link GBufferDebugView#SHADOW_MAP_VIEW} is deliberately NOT in that list -- it is a full-screen
 * visualization, not a crosshair readback, so this class has no formatter for it and {@link
 * #maybeLog} falls through to its "select a debug view first" message if it is ever active here.
 * GLINT_OCCLUSION_QUERY and the five UW_GLINT ordinals each read a different target than the rest
 * -- see {@link #targetFor}. The ratio (21) named the specular path as ~50x brighter than diffuse
 * for the same
 * surroundings; the first decomposition triple (22-24) reports every term the ratio is built from;
 * the local-light/AO pair (25-26) is a follow-up question -- whether the diffuse and specular paths
 * see the same local-light picture and apply the same occlusion to it; the residual (27) closes the
 * litDiffuse/diffuseWithHeld ratio question by measurement instead of estimate; the final pair
 * (28-29) checks the albedo identity itself -- texLuma * tintLuma == albedoLuma -- against the
 * runtime value rather than the parsed curve constants every earlier check assumed; ordinal 30
 * (water/underwater investigation round) is unrelated to the LabPBR decode audit -- it reads the
 * underwater horizon-closure transition's own applied {@code uwClosureNear}/{@code uwClosureFar}/
 * {@code uwClosureWidth}/{@code horizonClosure} at the crosshair, added after two formula-only
 * predictions of that transition's behaviour both turned out wrong when checked in-game. Split
 * across ten ordinals because one vec4 cannot hold that many values -- select one at a time with
 * Debug View Cycle and measure again for the next; the readback is one-shot, so successive reads
 * across ordinals is the intended flow, not a limitation.
 *
 * <p>Exists because a false-colour ramp cannot settle "how large, in absolute terms" -- the
 * values in question (roughly 0.01-0.15 linear) sit close enough together that no amount of
 * palette design distinguishes 5% from 15% as reliably as printing the numbers. This mirrors
 * {@link GBufferReadbackDiagnostic}'s proven "{@code USAGE_MAP_READ} buffer + {@code
 * copyTextureToBuffer} + map/read/close" sequence (see that class's own doc comment for why this
 * is allowed to stall the render thread) but reads a named PACK TARGET via {@link TargetRegistry}
 * rather than one of {@link GBuffer}'s five hardcoded attachments, which is why it is a separate
 * class rather than a sixth method there.
 *
 * <p><b>Round 10: a window, not a single pixel.</b> The single-pixel predecessor of this class
 * could not tell a real difference from TAAU's own sub-pixel jitter -- successive presses on the
 * SAME static crosshair position moved the reported number frame to frame, and a texture whose own
 * albedo varies texel to texel (acacia planks measured roughly 2x) made a single sample
 * unrepresentative of the surface being asked about. Averaging a {@link #WINDOW}x{@link #WINDOW}
 * block centred on the crosshair (clamped to the target's own bounds, so this degrades gracefully
 * on anything smaller) absorbs both: the standard deviation reported alongside every mean is what
 * makes a reading trustworthy, not the mean alone -- a small std means the window sat on a flat
 * patch and the mean is representative; a large one means it did not, or straddled an edge, and the
 * number should be read with that caveat rather than trusted at face value.
 *
 * <p><b>Stage 0 (celestial rework decision, 2026-08-11): min/max alongside mean/std.</b> The window
 * average absorbed jitter, but it also silently hid a different failure mode from every Bug A
 * report so far: a 16x16 mean cannot distinguish a genuinely flat reading from a mixture of mostly-
 * real values and the shadow map's 1.0 clear-value sentinel (either can average to the same ~0.099),
 * so an inference like "this mean is below the sentinel threshold, therefore a real captured
 * surface" is only sound for a single texel, not a window. {@link Stats} now carries the sampled
 * min/max alongside mean/std, and {@code toString} always prints all four -- a flat patch reads
 * {@code [x..x]}; a patch straddling the sentinel reads a range that says so on its own, without
 * requiring the reader to remember to ask.
 *
 * <p>Crosshair = screen centre: vanilla's crosshair is not repositionable, so centring on {@code
 * sceneHdr}'s own width/height (which matches the render target's resolution, not necessarily the
 * window's) is exact, not an approximation -- same reasoning {@link GBufferReadbackDiagnostic}
 * already applies to its own centred reads.
 *
 * <p>INVALID OVER WATER/TRANSLUCENT SURFACES: {@code water_composite.fsh} runs strictly after the
 * resolve pass every branch this class reads lives in, and unconditionally overwrites/blends water
 * pixels with water's own real composited reflection -- it has no debug-view awareness at all.
 * Point the crosshair at OPAQUE geometry (this is why the acacia-planks question is answerable
 * here and the water-reads-like-the-final-image symptom on the earlier two-view attempt was not a
 * bug in which terms were selected).
 */
public final class EnvSpecularRatioReadback {
    private static final String SCENE_HDR_TARGET = "sceneHdr";

    /** {@code glint_occlusion.fsh} writes here, not {@link #SCENE_HDR_TARGET} -- every other
     * ordinal this class knows about reads {@code sceneHdr} directly. */
    private static final String GLINT_OCCLUSION_TARGET = "glintOcclusion";

    /** {@code water_composite.fsh} runs as a hardware TRANSLUCENT blend and writes here, not
     * {@link #SCENE_HDR_TARGET} -- used by the UW_GLINT ordinals. */
    private static final String SCENE_HDR_COMPOSITED_TARGET = "sceneHdrComposited";

    /** Side length of the square window averaged around the crosshair. Clamped to the target's
     * own bounds at read time, so this is a request, not a guarantee -- a target smaller than
     * this (or a crosshair near its edge) reads a smaller window, not an error. */
    private static final int WINDOW = 16;

    /** Set by {@link #requestMeasure()}, consumed by the very next {@link #maybeLog} call -- same
     * render-thread-only field pattern as {@link GBufferReadbackDiagnostic#requestDump()}. */
    private static boolean measureRequested;

    private EnvSpecularRatioReadback() {
    }

    /** Requests a one-shot crosshair measurement on the next {@link #maybeLog} call. */
    public static void requestMeasure() {
        measureRequested = true;
    }

    /** Mean, standard deviation, and range of one channel over the sampled window. {@code std} is
     * the population standard deviation (divisor N, not N-1) -- the window is the entire population
     * being asked about, not a sample drawn from a larger one.
     *
     * <p>{@code min}/{@code max} exist because the mean alone can hide a sentinel mixed into an
     * otherwise-flat reading: a window that is genuinely flat at 0.099 and a window that is ~90% at
     * ~0.0 mixed with ~10% at a 1.0 clear-value sentinel both average to ~0.099, and the "0.0986 ≤
     * 0.2, so this is a real captured surface, not the sentinel" style of inference this class exists
     * to support is only sound for a single texel or a window whose range confirms it didn't
     * straddle one. {@code toString} always prints both, alongside mean/std, so a formatter cannot
     * accidentally report a trustworthy-looking mean while omitting the one thing that would call it
     * into question -- see this class's own doc on why the std was never quoted in any prior reading. */
    private record Stats(float mean, float std, float min, float max) {
        /** Same distribution, rescaled -- e.g. a 0-1 ratio channel printed as a percentage without
         * re-deriving mean/std/min/max from raw samples (all four scale linearly with the channel
         * itself). */
        Stats scaled(float factor) {
            return new Stats(mean * factor, std * factor, min * factor, max * factor);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%.5f±%.5f [%.5f..%.5f]", mean, std, min, max);
        }
    }

    /** Call once per frame from {@code GraphRunner.finish}, alongside {@link
     * GBufferReadbackDiagnostic#maybeLog}. A single cheap field read and return unless {@link
     * #requestMeasure()} was called since the last frame. */
    public static void maybeLog(TargetRegistry registry) {
        if (!measureRequested) {
            return;
        }
        measureRequested = false;

        GBufferDebugView view = FornaxConfig.get().debugView;
        if (formatter(view) == null) {
            actionbarAndLog("[Fornax] Select an Env Specular Ratio/Decomp debug view first (F9 to "
                    + "cycle, or the Engine settings page) -- sceneHdr holds the final lit image "
                    + "otherwise, not the isolated terms.");
            return;
        }

        String targetName = targetFor(view);
        TargetInstance sceneHdr = registry.get(targetName);
        if (sceneHdr == null) {
            FornaxMod.LOGGER.warn("[Fornax][envSpecRatio] {} target not found -- no pack loaded, "
                    + "or this pack does not declare it", targetName);
            return;
        }
        GpuTexture texture = sceneHdr.texture();
        int width = sceneHdr.width();
        int height = sceneHdr.height();
        if (width <= 0 || height <= 0) {
            return;
        }
        int cx = Math.max(0, Math.min(width - 1, width / 2));
        int cy = Math.max(0, Math.min(height - 1, height / 2));

        // Centre the WINDOWxWINDOW block on the crosshair, then clamp to the target's own bounds --
        // the same "clamp rather than fail" shape GBufferReadbackDiagnostic's centred 2x2 read uses.
        int half = WINDOW / 2;
        int wx = Math.max(0, Math.min(width - 1, cx - half));
        int wy = Math.max(0, Math.min(height - 1, cy - half));
        int w = Math.min(WINDOW, width - wx);
        int h = Math.min(WINDOW, height - wy);

        int blockSize = texture.getFormat().blockSize();
        long size = (long) w * h * blockSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> "[Fornax] envSpecRatio readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_HINT_CLIENT_STORAGE | GpuBuffer.USAGE_COPY_DST,
                size);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        int windowW = w;
        int windowH = h;
        encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            // Welford's online mean/variance, one pass per channel -- avoids a second pass over the
            // window and avoids the cancellation error a naive sum-of-squares accumulator risks at
            // WINDOW*WINDOW samples. Population std (divisor n): the window IS the population this
            // reading describes, not a sample drawn from a larger one.
            double[] mean = new double[4];
            double[] m2 = new double[4];
            float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            int n = 0;
            try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
                read.data().order(ByteOrder.nativeOrder());
                for (int py = 0; py < windowH; py++) {
                    for (int px = 0; px < windowW; px++) {
                        int offset = (px + py * windowW) * blockSize;
                        // rgba16f: r, g, b, a as four consecutive half-floats -- same layout
                        // convention GBufferReadbackDiagnostic.readRg16Float already assumes for the
                        // motion attachment.
                        float[] sample = {
                                Float.float16ToFloat(read.data().getShort(offset)),
                                Float.float16ToFloat(read.data().getShort(offset + 2)),
                                Float.float16ToFloat(read.data().getShort(offset + 4)),
                                Float.float16ToFloat(read.data().getShort(offset + 6)),
                        };
                        n++;
                        for (int c = 0; c < 4; c++) {
                            double delta = sample[c] - mean[c];
                            mean[c] += delta / n;
                            m2[c] += delta * (sample[c] - mean[c]);
                            // Min/max alongside Welford, same single pass -- see Stats' own doc for
                            // why the range matters as much as the mean here: it is what tells a flat
                            // reading apart from one that mixes in the 1.0 clear sentinel.
                            min[c] = Math.min(min[c], sample[c]);
                            max[c] = Math.max(max[c], sample[c]);
                        }
                    }
                }
            }
            buffer.close();

            Stats[] stats = new Stats[4];
            for (int c = 0; c < 4; c++) {
                double variance = n > 0 ? m2[c] / n : 0.0;
                stats[c] = new Stats((float) mean[c], (float) Math.sqrt(Math.max(variance, 0.0)), min[c], max[c]);
            }
            actionbarAndLog(formatter(view).format(stats[0], stats[1], stats[2], stats[3],
                    cx, cy, windowW, windowH));
        }, 0, wx, wy, w, h);
    }

    /** Which render target a given ordinal's data actually lives in. Every ordinal except {@link
     * GBufferDebugView#GLINT_OCCLUSION_QUERY} and the five UW_GLINT ordinals is a {@code
     * gbuffer_resolve.fsh}/opaque-pass write into {@link #SCENE_HDR_TARGET} directly. */
    private static String targetFor(GBufferDebugView view) {
        return switch (view) {
            case GLINT_OCCLUSION_QUERY -> GLINT_OCCLUSION_TARGET;
            case UW_GLINT_1, UW_GLINT_2, UW_GLINT_3, UW_GLINT_4, UW_GLINT_5 -> SCENE_HDR_COMPOSITED_TARGET;
            default -> SCENE_HDR_TARGET;
        };
    }

    /** One label set per readable ordinal, matching exactly what {@code gbuffer_resolve.fsh}'s
     * matching branch packs into R/G/B/A -- see each ordinal's own doc comment. {@code null} for
     * any other view, which {@link #maybeLog} uses as "nothing this class knows how to read". Each
     * {@link Stats} prints as {@code mean±std} over the sampled window. */
    @FunctionalInterface
    private interface Formatter {
        String format(Stats r, Stats g, Stats b, Stats a, int x, int y, int w, int h);
    }

    private static Formatter formatter(GBufferDebugView view) {
        return switch (view) {
            case ENV_SPEC_RATIO -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] envSpec=%s  diffuse=%s  ratio%%=%s  (crosshair px %d,%d, %dx%d window)",
                    r, g, b.scaled(100.0f), x, y, w, h);
            case ENV_DECOMP_SKY -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] skyMiss=%s  ambientColour=%s  wideEnclosure=%s  reflWide=%s"
                            + "  (crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case ENV_DECOMP_MIX -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] reflColor=%s  sharpAvail=%s  reflEnv=%s  specularAlbedo=%s"
                            + "  (crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case ENV_DECOMP_MAT -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] NdotV=%s  mat.alpha=%s  surfaceF0=%s  (crosshair px %d,%d, %dx%d window)",
                    r, g, b, x, y, w, h);
            case ENV_DECOMP_LOCAL -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] diffuseWithHeld=%s  blockRadiance=%s  skyLight=%s  envAccess=%s"
                            + "  (crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case ENV_DECOMP_AO -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] wideHorizon=%s  litDiffuse=%s  vanillaAO(diffuse-path)=%s"
                            + "  ao(specular-path,raw)=%s  (crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case ENV_DECOMP_RESIDUAL -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] albedoLuma=%s  kDLuma=%s  diffuseWithHeldLuma=%s  "
                            + "residual(litDiffuseLuma/(R*G*B))=%s  (crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case ENV_DECOMP_ALBEDO_WRITE_VS_READ -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] rawWrittenLuma=%s  decodedAlbedoLuma=%s  (needs u_AlbedoIdentityDebug"
                            + " OFF)  (crosshair px %d,%d, %dx%d window)",
                    r, g, x, y, w, h);
            case ENV_DECOMP_ALBEDO_IDENTITY_INPUTS -> (r, g, b, a, x, y, w, h) -> {
                float tintLuma = 0.2126f * g.mean() + 0.7152f * b.mean() + 0.0722f * a.mean();
                return String.format(Locale.ROOT,
                        "[Fornax] texLuma=%s  tint.r=%s  tint.g=%s  tint.b=%s  tintLuma(derived)=%.5f"
                                + "  (needs u_AlbedoIdentityDebug ON)  (crosshair px %d,%d, %dx%d window)",
                        r, g, b, a, tintLuma, x, y, w, h);
            };
            // The closure is plagueGetWaterFog (Beer-Lambert, asymptotic, no boundary anywhere),
            // which takes a single scale rather than a near/far pair, so two of the four channels
            // here go unused. A smoothstep boundary keyed on length(worldPos) would draw its
            // transition as a sphere centred on the eye -- a curved, camera-following edge across
            // the view -- which is exactly what this asymptotic model avoids. Pinned literal:
            // gbuffer_resolve.fsh's vec4(uwClosureScale, uwClosureDist, horizonClosure, uwVisibilityMult).
            case UW_CLOSURE_DEBUG -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] uwClosureScale=%s\nuwClosureDist=%s\nhorizonClosure=%s"
                            + "\nvisibilityMult=%s"
                            + "\n(point crosshair at submerged seabed/terrain, camera underwater)"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case SHADOW_QUERY_1 -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] sunDir.x=%s\nsunDir.y=%s\nsunDir.z=%s\nndotl=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case SHADOW_QUERY_2 -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] shadowUv.x=%s\nshadowUv.y=%s\ninRange=%s\nvisibility=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            // Pack write: fragColor = vec4(dbgRawDepth, 0.0, dbgStoredDepth, 0.0); -- the compared
            // value is the raw light-clip depth with no scale constant between the two sides, so
            // green is intentionally empty and matching red/blue means the comparison would pass.
            case SHADOW_QUERY_3 -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] rawDepth(compared)=%s\nstoredDepth=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, b, x, y, w, h);
            // Pack write: fragColor = vec4(activeVisibility, trueSunVisibility, moonVisibility, 1.0).
            // glint_occlusion.fsh traces independent sun and moon visibility every frame regardless
            // of which body is above the horizon. (-1,0,0) is the "not a water texel" sentinel,
            // distinct from a genuine (0,0,0) both-occluded/no-light reading.
            case GLINT_OCCLUSION_QUERY -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] activeVisibility=%s\ntrueSunVisibility=%s\nmoonVisibility=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, x, y, w, h);
            // Pack write: fragColor = vec4(uwSunAlignment, uwMoonAlignment, uwFresnel, 1.0).
            // water_composite.fsh tracks the sun and moon as independent alignment and lobe terms.
            case UW_GLINT_1 -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] uwSunAlignment=%s\nuwMoonAlignment=%s\nuwFresnel=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, x, y, w, h);
            case UW_GLINT_2 -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] uwEyeFilter=(%s, %s, %s)"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, x, y, w, h);
            // Pack write: fragColor = vec4(uwSunGlint, uwMoonGlint, u_UnderwaterSunGlitterStrength,
            // 1.0). Each celestial body has its own glint term; skyVis gates uwGlintContribution
            // upstream but is not read through this instrument.
            case UW_GLINT_3 -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] uwSunGlint=%s\nuwMoonGlint=%s\nunderwaterSunGlitterStrength=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, x, y, w, h);
            case UW_GLINT_4 -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] uwGlintContribution=(%s, %s, %s)"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, x, y, w, h);
            // R/G/B order matches water_composite.fsh:308's real write, vec4(waveNormal.y, NdotV,
            // worldPos.y, 1.0) -- NOT R/G/B channel-name order. A prior version of this formatter
            // used the pre-restructure order and silently mislabelled every channel once Plague
            // changed the write; caught only because the same value (0.26782) appeared under two
            // different labels in consecutive readings. Keep this comment current if Plague's write
            // order ever changes again -- see GBufferDebugView.UW_GLINT_5's own doc comment.
            case UW_GLINT_5 -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] waveNormal.y=%s\nNdotV=%s\nworldPos.y=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, x, y, w, h);
            // Conductor-chain instrument (2026-08-22), seven parts walking one pixel's specular
            // chain -- channel labels mirror each shader branch's write exactly; see the
            // DBG_CONDUCTOR_* block in gbuffer_resolve.fsh and each enum constant's doc comment.
            case CONDUCTOR_F0 -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] surfaceF0.r=%s\nsurfaceF0.g=%s\nsurfaceF0.b=%s\nmetalness=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case CONDUCTOR_ENERGY -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] specularAlbedo.r=%s\nspecularAlbedo.g=%s\nspecularAlbedo.b=%s"
                            + "\nreflSmoothness=%s\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case CONDUCTOR_MIRROR -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] reflColor.r=%s\nreflColor.g=%s\nreflColor.b=%s\nsharpAvail=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case CONDUCTOR_WIDE -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] reflWide.r=%s\nreflWide.g=%s\nreflWide.b=%s\nwideTraceTrust=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case CONDUCTOR_ENV -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] reflEnv.r=%s\nreflEnv.g=%s\nreflEnv.b=%s\nenvShadowDim=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case CONDUCTOR_DIRECT -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] directSpec.r=%s\ndirectSpec.g=%s\ndirectSpec.b=%s"
                            + "\nsunVisibility=%s\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            case CONDUCTOR_LIT -> (r, g, b, a, x, y, w, h) -> String.format(Locale.ROOT,
                    "[Fornax] lit.r=%s\nlit.g=%s\nlit.b=%s\nlitLuma=%s"
                            + "\n(crosshair px %d,%d, %dx%d window)",
                    r, g, b, a, x, y, w, h);
            // SHADOW_MAP_VIEW (ordinal 40) intentionally has no case here: it is a full-screen
            // visualization, not a crosshair readback, so it falls through to null like every other
            // view this class does not know how to read -- see its own enum doc comment.
            default -> null;
        };
    }

    /** Prints via vanilla's chat/system-message HUD (one call per line) rather than the action bar --
     * the action bar is a single line by design and truncates/runs off-screen for anything wider
     * than a short readout, which is most of these once a reading carries more than one or two
     * numbers. Chat naturally stacks multiple recent messages, which is what actually renders
     * legibly for a multi-value readback. A single-line message (no {@code \n}) still prints as one
     * chat line, so every existing single-line formatter keeps working unchanged. */
    private static void actionbarAndLog(String message) {
        FornaxMod.LOGGER.info(message);
        for (String line : message.split("\n")) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(line));
        }
    }
}
