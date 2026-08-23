package dev.icehunter.fornax.pipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Frame-stable, renderer-facing state for the local player or the vehicle they are controlling.
 *
 * <p>This is deliberately an engine ABI rather than a water implementation: packs decide how an
 * actor's position, shape, motion and fluid contact affect their own simulations. Absolute position
 * remains a {@code double} until the final snapshot so motion is still accurate near the world
 * border. Actor/level changes, long frame gaps and teleports reset temporal consumers instead of
 * exposing a destructive one-frame impulse.
 */
public final class LocalActorFrameState {
    public static final int ACTOR_NONE = 0;
    public static final int ACTOR_PLAYER = 1;
    public static final int ACTOR_BOAT = 2;
    public static final int ACTOR_OTHER_VEHICLE = 3;

    public static final int FLUID_NONE = 0;
    public static final int FLUID_WATER = 1;
    public static final int FLUID_LAVA = 2;

    private static final float DEFAULT_DELTA_SECONDS = 1.0f / 60.0f;
    private static final float MAX_DELTA_SECONDS = 0.05f;
    private static final double TELEPORT_DISTANCE_SQUARED = 16.0 * 16.0;
    private static final LocalActorFrameState LIVE = new LocalActorFrameState();

    private Object previousLevel;
    private int previousActorId = Integer.MIN_VALUE;
    private int previousActorKind = ACTOR_NONE;
    private double previousX;
    private double previousY;
    private double previousZ;
    private boolean initialized;
    private Snapshot snapshot = Snapshot.empty();

    public record Input(
            Object level,
            int actorId,
            int actorKind,
            double x,
            double y,
            double z,
            float forwardX,
            float forwardZ,
            float halfWidth,
            float halfLength,
            int fluidKind,
            boolean surfaceContact,
            float deltaSeconds
    ) {
    }

    public record Snapshot(
            float x,
            float y,
            float z,
            int actorKind,
            float deltaX,
            float deltaY,
            float deltaZ,
            float deltaSeconds,
            float forwardX,
            float forwardZ,
            float halfWidth,
            float halfLength,
            int fluidKind,
            float surfaceContact,
            float verticalSpeed,
            boolean reset
    ) {
        private static Snapshot empty() {
            return new Snapshot(0.0f, 0.0f, 0.0f, ACTOR_NONE,
                    0.0f, 0.0f, 0.0f, DEFAULT_DELTA_SECONDS,
                    0.0f, 1.0f, 0.0f, 0.0f,
                    FLUID_NONE, 0.0f, 0.0f, true);
        }
    }

    public void update(Input input) {
        float deltaSeconds = sanitizeDeltaSeconds(input.deltaSeconds());
        double deltaX = input.x() - previousX;
        double deltaY = input.y() - previousY;
        double deltaZ = input.z() - previousZ;
        double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        boolean reset = !initialized
                || input.level() != previousLevel
                || input.actorId() != previousActorId
                || input.actorKind() != previousActorKind
                || !Double.isFinite(distanceSquared)
                || distanceSquared > TELEPORT_DISTANCE_SQUARED
                || !Float.isFinite(input.deltaSeconds())
                || input.deltaSeconds() > MAX_DELTA_SECONDS;

        float frameDeltaX = reset ? 0.0f : (float) deltaX;
        float frameDeltaY = reset ? 0.0f : (float) deltaY;
        float frameDeltaZ = reset ? 0.0f : (float) deltaZ;
        float verticalSpeed = reset ? 0.0f : frameDeltaY / deltaSeconds;
        snapshot = new Snapshot(
                (float) input.x(), (float) input.y(), (float) input.z(), input.actorKind(),
                frameDeltaX, frameDeltaY, frameDeltaZ, deltaSeconds,
                finiteOr(input.forwardX(), 0.0f), finiteOr(input.forwardZ(), 1.0f),
                Math.max(0.0f, finiteOr(input.halfWidth(), 0.0f)),
                Math.max(0.0f, finiteOr(input.halfLength(), 0.0f)),
                input.fluidKind(), input.surfaceContact() ? 1.0f : 0.0f,
                verticalSpeed, reset);

        previousLevel = input.level();
        previousActorId = input.actorId();
        previousActorKind = input.actorKind();
        previousX = input.x();
        previousY = input.y();
        previousZ = input.z();
        initialized = true;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public static Snapshot current() {
        return LIVE.snapshot();
    }

    /** Captures the local actor once, before the frame's first globals-buffer write. */
    public static void commitFromClient() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            LIVE.clear();
            return;
        }

        Entity actor = client.player.getRootVehicle();
        int actorKind = actor == client.player
                ? ACTOR_PLAYER
                : actor instanceof AbstractBoat ? ACTOR_BOAT : ACTOR_OTHER_VEHICLE;
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var position = actor.getPosition(partialTick);
        float yawRadians = (float) Math.toRadians(actor.getYRot(partialTick));
        float forwardX = -(float) Math.sin(yawRadians);
        float forwardZ = (float) Math.cos(yawRadians);
        float halfWidth = actor.getBbWidth() * 0.5f;
        var bounds = actor.getBoundingBox();
        float halfLength = (float) (Math.max(bounds.getXsize(), bounds.getZsize()) * 0.5);

        int fluidKind = actor.isInWater() || actor.getFluidHeight(FluidTags.WATER) > 0.0
                ? FLUID_WATER
                : actor.isInLava() || actor.getFluidHeight(FluidTags.LAVA) > 0.0
                        ? FLUID_LAVA : FLUID_NONE;
        boolean surfaceContact = fluidKind != FLUID_NONE && !actor.isUnderWater();
        float deltaSeconds = client.getDeltaTracker().getRealtimeDeltaTicks() / 20.0f;
        LIVE.update(new Input(client.level, actor.getId(), actorKind,
                position.x(), position.y(), position.z(),
                forwardX, forwardZ, halfWidth, halfLength,
                fluidKind, surfaceContact, deltaSeconds));
    }

    private void clear() {
        initialized = false;
        previousLevel = null;
        previousActorId = Integer.MIN_VALUE;
        previousActorKind = ACTOR_NONE;
        snapshot = Snapshot.empty();
    }

    private static float sanitizeDeltaSeconds(float value) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            return DEFAULT_DELTA_SECONDS;
        }
        return Math.min(value, MAX_DELTA_SECONDS);
    }

    private static float finiteOr(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }
}
