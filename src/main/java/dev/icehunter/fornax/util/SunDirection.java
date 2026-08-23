package dev.icehunter.fornax.util;

import dev.icehunter.fornax.config.FornaxConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Vector3f;

/**
 * Approximate world-space, normalized direction of whichever celestial body should be casting RT
 * shadows this frame -- the sun during the day, the moon at night -- mirroring the rotation vanilla
 * applies when rendering the sun/moon discs ({@code SkyRenderer#renderSunMoonAndStars}: a -90 degree
 * rotation about +Y followed by a rotation of the body's own angle in radians about +X). That
 * composition reduces to the closed form {@code (-sin(angle), cos(angle), 0)}, which is already
 * unit-length for any real angle (no NaNs, no explicit normalization required), and rotates smoothly
 * as the in-game time of day advances.
 * <p>
 * Shadows are meant to be ever-present day and night, and the sun's own closed form goes to zero
 * shadow contribution (and eventually points below the horizon entirely) once night falls -- vanilla
 * renders an independent moon disc for exactly this reason, rotating on its own
 * {@link EnvironmentAttributes#MOON_ANGLE} rather than continuing the sun's rotation past the
 * horizon. This class picks whichever body is actually above the horizon (sun by day, moon by
 * night) as the real shadow-casting light direction, decided purely by each body's own computed
 * {@code y} component (positive = above horizon) -- no clock/time-of-day branching, so the existing
 * {@code dayFactor = smoothstep(0.05, 0.15, y)} gate in the resolve composite fades shadows smoothly
 * through the sun/moon handoff with zero shader changes, exactly as it already does for the sun's own
 * dawn/dusk transition. When both bodies are below the horizon (the brief overlap around dusk/dawn),
 * whichever is higher wins, keeping the handoff continuous rather than snapping.
 * <p>
 * {@link EnvironmentAttributes#SUN_ANGLE} and {@link EnvironmentAttributes#MOON_ANGLE} are both
 * reported in <b>degrees</b>; {@code SkyRenderer} converts each to radians (multiplying by
 * {@code 0.017453292f}, pi/180) before storing them on {@code SkyRenderState}. That same conversion
 * is replicated here before the trig calls below.
 * <p>
 * Independent Fornax-side reimplementation, not a call into Sodium's {@code DrawContext}: upstream
 * {@code DrawContext} has no {@code computeSunDirection()} method, so this class exists to let
 * {@code GraphRunner} and the {@code DrawContextGLMixin}/{@code DrawContextVKMixin} push-constant
 * writers share one implementation.
 */
public final class SunDirection {
    private SunDirection() {
    }

    /**
     * The TRUE sun's elevation, positive when the sun is above the horizon, regardless of which body
     * is currently lighting the scene.
     *
     * <p>{@link #computeSunDirection} deliberately returns the ACTIVE light -- the sun while it is
     * up, the moon once it sets -- which is right for shading and shadow casting and wrong for
     * anything asking "is it day". A shader reading that vector's height sees the moon high at
     * midnight, concludes the sun is overhead, and lights the world accordingly. This answers the
     * other question.
     */
    /*
     * DELIBERATELY NOT TILTED, and that is a choice rather than an oversight. sunPathRotation
     * rotates about X, so the tilted height is exactly cos(rotation) times this one -- the SIGN,
     * and therefore every dawn/dusk crossing and every "is it day" decision, is identical. Only the
     * peak magnitude differs (0.906 at the -25 degree default instead of 1.0). Scaling it would
     * push noon off 1.0 and shift colour tables the pack calibrated AT noon -- tools/verify_*.py
     * pin several of those parities -- for no gain to what this function is actually asked.
     */
    public static float trueSunHeight() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.gameRenderer == null) {
            return 1.0f;
        }
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float sunAngleDegrees = client.gameRenderer.mainCamera().attributeProbe()
                .getValue(EnvironmentAttributes.SUN_ANGLE, partialTick);
        return (float) Math.cos(sunAngleDegrees * 0.017453292f);
    }

    public static Vector3f computeSunDirection() {
        return computeSunDirection(new Vector3f());
    }

    /**
     * Allocation-free form for per-pass hot paths. The caller owns {@code destination}; the
     * returned reference is the same object.
     */
    public static Vector3f computeSunDirection(Vector3f destination) {
        Minecraft client = Minecraft.getInstance();

        if (client.level == null || client.gameRenderer == null) {
            return destination.set(0.0f, 1.0f, 0.0f);
        }

        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var attributeProbe = client.gameRenderer.mainCamera().attributeProbe();
        float sunAngleDegrees = attributeProbe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick);
        float moonAngleDegrees = attributeProbe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTick);

        float sunAngle = sunAngleDegrees * 0.017453292f;
        float moonAngle = moonAngleDegrees * 0.017453292f;
        float sunX = (float) -Math.sin(sunAngle);
        float sunY = (float) Math.cos(sunAngle);
        float moonX = (float) -Math.sin(moonAngle);
        float moonY = (float) Math.cos(moonAngle);

        if (sunY > 0.0f) {
            return tilt(destination.set(sunX, sunY, 0.0f));
        }
        if (moonY > 0.0f) {
            return tilt(destination.set(moonX, moonY, 0.0f));
        }
        // Both below the horizon (brief dusk/dawn overlap) -- return whichever is higher so the
        // handoff stays continuous instead of snapping.
        return sunY >= moonY
                ? tilt(destination.set(sunX, sunY, 0.0f))
                : tilt(destination.set(moonX, moonY, 0.0f));
    }

    /**
     * Tilts the celestial arc off the zenith by {@code FornaxSettings.sunPathRotation} degrees --
     * the equivalent of OptiFine/Iris's {@code sunPathRotation}.
     *
     * <p>Vanilla builds the path as {@code (-sin a, cos a, 0)}: a flat arc in the XY plane, whose
     * highest point is exactly straight up. At noon the sun sits in the zenith, every shadow
     * collapses underneath the thing casting it, and midday has no direction to it at all. Rotating
     * that vector about the X axis leaves the arc's shape alone and leans the plane it sweeps, so
     * peak elevation becomes {@code cos(rotation)} -- at the -25 degree default the sun tops out
     * around 65 degrees instead of 90.
     *
     * <p>Every consumer of this vector is tilted by construction, which is the reason it happens
     * here rather than in the pack: the shadow map, the pack's own lighting and the celestial discs
     * are all built from this one direction, and rotating it anywhere downstream would have lit the
     * world from a different place than the shadows were cast from.
     */
    private static Vector3f tilt(Vector3f direction) {
        return applyTilt(direction);
    }

    /**
     * PUBLIC because there are two places a celestial direction is built and they must not disagree.
     * {@link #computeSunDirection} feeds the shadow map and the pack's lighting;
     * {@code SkyProbe.values} feeds {@code u_SkyCelestial}, which is what the pack draws the visible
     * sun and moon from. Tilting only the first one moved every shadow while leaving the sun disc
     * where it was -- the setting appeared to do nothing, because the thing you look at had not
     * moved. Both call this.
     */
    public static Vector3f applyTilt(Vector3f direction) {
        float degrees = FornaxConfig.get().sunPathRotation;
        if (degrees == 0.0f) {
            return direction; // vanilla path, and bit-identical to before this existed
        }
        float radians = degrees * 0.017453292f;
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float y = direction.y();
        float z = direction.z();
        return direction.set(direction.x(), y * cos - z * sin, y * sin + z * cos);
    }

    /** The tilted celestial z for a flat {@code (x, y, 0)} direction -- {@code y * sin(tilt)}. */
    public static float tiltedZ(float y) {
        float degrees = FornaxConfig.get().sunPathRotation;
        return degrees == 0.0f ? 0.0f : y * (float) Math.sin(degrees * 0.017453292f);
    }

    /** The tilted celestial y for a flat {@code (x, y, 0)} direction -- {@code y * cos(tilt)}. */
    public static float tiltedY(float y) {
        float degrees = FornaxConfig.get().sunPathRotation;
        return degrees == 0.0f ? y : y * (float) Math.cos(degrees * 0.017453292f);
    }
}
