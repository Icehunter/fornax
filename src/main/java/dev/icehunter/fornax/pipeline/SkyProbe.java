package dev.icehunter.fornax.pipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;

/**
 * The sky's per-frame DATA -- sky colour, sunrise/sunset colour, star brightness, true sun
 * direction, moon phase, rain level, sun angle -- read live from the camera's own
 * {@link EnvironmentAttributeProbe} every frame, in every dimension, with no dependence on which
 * render passes fired.
 *
 * <p><b>Why this class exists.</b> Sky DATA must never gate behind a STYLING decision (who draws the
 * sky): reading it through a call site that only commits down the branch that CANCELS vanilla's sky
 * ({@code GraphRunner.packOwnsSky()}, the pack's {@code SKY_PROCEDURAL} compile option) would leave
 * every other pack unable to read the sky's colour, the rain level, the sun angle, or the moon
 * phase. Iris/OptiFine hand every pack {@code skyColor}/{@code rainStrength}/{@code sunAngle}
 * regardless of whether the pack draws sky, and Fornax is a data-exposure layer before it is a host
 * for any one pack, so it does the same.
 *
 * <p>Three consumers depend on this not being gated:
 * <ul>
 *   <li>{@code u_SkyColor}/{@code u_SkyState.x} in {@code u_Globals} -- a pack's ambient light
 *       colour and every rain-driven term. A zero vector is a plausible colour, so a gated read
 *       fails as "the ambient looks wrong" rather than as an error.</li>
 *   <li>{@code GraphRunner.applyEmitterSunDirection} -- {@code light_inject.comp}'s
 *       {@code GI_SUN_BOUNCE} term gates on {@code clamp(sunDir.y, 0, 1)}, so a zero vector
 *       silently disables indirect sun bounce entirely for those packs.</li>
 *   <li>{@code CelestialSprites.moonPhaseRect} -- would pin to phase 0 (full moon) forever.</li>
 * </ul>
 *
 * <p><b>Why live rather than a frame-state holder.</b> A lane fed by a conditionally-invoked pass
 * mixin goes stale or stuck on exactly the frames that mixin does not run: a wind-clock-shaped lane
 * committed only from {@code addCloudsPass} freezes whenever that call does not run, and an
 * eye-in-water-shaped lane committed only from {@code addSkyPass} sticks for an entire visit to any
 * {@code SkyType.NONE} dimension, since that call never fires there. A live read inside
 * {@code GlobalUniformsWriteMixin}, which runs exactly once per frame in every dimension, has no such
 * gap. {@link SunDirection} already reads {@code EnvironmentAttributes.SUN_ANGLE} off this same
 * probe this same way, which is why {@code u_PassParams.u_SunDirection} works for every pack
 * regardless of which passes fire.
 *
 * <p><b>What stays gated.</b> {@link SkyFrameState} keeps exactly the two did-cancel flags
 * ({@code u_SkyColor.w}, {@code u_SkyState.z}). Those are not data about the world -- they are the
 * record of a decision this engine made this frame ("I cancelled vanilla's sky pass, so the pack
 * must paint one"), and the shader's paint decision reads the flag precisely so the cancel/paint
 * pair cannot drift. They are correctly conditional; the numbers here are not.
 *
 * <p><b>Vanilla parity.</b> Every read below is the same attribute, in the same units, with the same
 * conversion that {@code SkyRenderer.extractRenderState} applies before storing to
 * {@code SkyRenderState} (javap-verified against the 26.2 jar): the {@code SUN_ANGLE} degrees-to-
 * radians multiply by {@code 0.017453292f}, the {@code SKY_COLOR}/{@code SUNRISE_SUNSET_COLOR}
 * packed-ARGB ints, {@code STAR_BRIGHTNESS}, {@code MOON_PHASE}. Rain is the one deliberate
 * difference in presentation: vanilla stores {@code rainBrightness = 1 - getRainLevel}, while
 * {@code u_SkyState.x} has always carried the rain LEVEL, so this reads
 * {@code ClientLevel.getRainLevel} directly rather than inverting an inversion.
 *
 * <p>Unlike {@code SkyRenderer.extractRenderState}, this does NOT early-return for
 * {@code Skybox.NONE} or {@code Skybox.END}. That early return is vanilla declining to compute
 * fields its own sky renderer will not draw with, not a statement that the values are undefined --
 * the probe carries a real sky colour in the Nether and the End, packs branch on dimension
 * themselves, and returning stale or zeroed values in some dimensions is the exact failure this
 * class was written to remove.
 *
 * <p>Render-thread only, called once per frame from the uniform tail writer. Allocates one record
 * per frame, in a method that already allocates several {@code Matrix4f}s.
 */
public final class SkyProbe {
    /** Degrees to radians, the same literal {@code SkyRenderer.extractRenderState} multiplies by. */
    private static final float DEGREES_TO_RADIANS = 0.017453292f;

    /**
     * One frame's sky data. Colours are already unpacked to 0..1 floats; {@code sunAngleRadians} is
     * radians; {@code sunDir*} is the TRUE sun (the moon is its negation), matching
     * {@code globals.glsl}'s {@code u_SkyCelestial} contract.
     */
    public record Values(
            float skyR, float skyG, float skyB,
            float sunriseR, float sunriseG, float sunriseB,
            float starBrightness,
            float sunDirX, float sunDirY, float sunDirZ,
            float moonPhase,
            float rainLevel,
            float sunAngleRadians) {
    }

    /**
     * All-zero data, returned when there is no level or no camera to probe (headless test JVMs, the
     * frames between world load and the first camera setup). Zero rather than a guessed default
     * because the uniform lanes must never carry uninitialized bytes, which is the same contract
     * {@code SkyFrameState.commitInactive} was written to satisfy.
     */
    public static final Values ZERO = new Values(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);

    private SkyProbe() {
    }

    /** This frame's sky data, or {@link #ZERO} when there is nothing to probe. */
    public static Values read() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.gameRenderer == null) {
            return ZERO;
        }
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        EnvironmentAttributeProbe probe = client.gameRenderer.mainCamera().attributeProbe();

        int skyArgb = probe.getValue(EnvironmentAttributes.SKY_COLOR, partialTick);
        int sunriseArgb = probe.getValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, partialTick);
        float starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partialTick);
        MoonPhase moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partialTick);
        float sunAngleRadians =
                probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) * DEGREES_TO_RADIANS;

        return new Values(
                red(skyArgb), green(skyArgb), blue(skyArgb),
                red(sunriseArgb), green(sunriseArgb), blue(sunriseArgb),
                starBrightness,
                // TILTED, and by the same function SunDirection uses. This vector becomes
                // u_SkyCelestial, which the pack draws the visible sun and moon from -- tilting the
                // shading direction alone moved every shadow and left the sun where it was.
                sunDirX(sunAngleRadians),
                dev.icehunter.fornax.util.SunDirection.tiltedY(sunDirY(sunAngleRadians)),
                dev.icehunter.fornax.util.SunDirection.tiltedZ(sunDirY(sunAngleRadians)),
                moonPhase.ordinal(),
                client.level.getRainLevel(partialTick),
                sunAngleRadians);
    }

    /**
     * The TRUE sun direction's x for a sun angle in radians. Split out as a pure function (with
     * {@link #sunDirY}) so the {@code (-sin, cos, 0)} convention has one definition shared with
     * {@code SunDirection}'s own closed form and can be unit-tested without a live client.
     */
    public static float sunDirX(float sunAngleRadians) {
        return (float) -Math.sin(sunAngleRadians);
    }

    /** The TRUE sun direction's y for a sun angle in radians -- see {@link #sunDirX}. */
    public static float sunDirY(float sunAngleRadians) {
        return (float) Math.cos(sunAngleRadians);
    }

    /** 0..1 red channel of a packed ARGB int, the format both sky colour attributes report in. */
    public static float red(int argb) {
        return ((argb >> 16) & 0xFF) / 255.0f;
    }

    /** 0..1 green channel of a packed ARGB int. */
    public static float green(int argb) {
        return ((argb >> 8) & 0xFF) / 255.0f;
    }

    /** 0..1 blue channel of a packed ARGB int. */
    public static float blue(int argb) {
        return (argb & 0xFF) / 255.0f;
    }
}
