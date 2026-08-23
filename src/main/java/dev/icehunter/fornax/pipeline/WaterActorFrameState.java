package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.graph.WaterActorBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Frame-stable state for every body near the camera that is touching water.
 *
 * <p>The plural of {@link LocalActorFrameState}, and it keeps that class's contract rather than
 * replacing it: absolute positions stay {@code double} until the final snapshot, an actor that was
 * not present last frame publishes zero displacement instead of a teleport-sized one, and nothing
 * here decides what any of it means. The local actor stays in its own uniforms because every
 * consumer needs it and it must not depend on this buffer existing; it also appears here, first, so
 * a pack can run one loop instead of a loop plus a special case.
 *
 * <p>Per-entity history is what this class exists for and what a stateless collection could not do.
 * Displacement is a difference against the same entity's own previous position, and the
 * surface-contact delta is a difference against its own previous contact -- neither is recoverable
 * from a single frame, and a pack cannot keep the history itself because it never learns which
 * entity a given record belongs to.
 */
public final class WaterActorFrameState {
    /** One body, resolved and relative, ready to be packed. */
    public record Actor(
            float offsetX,
            float offsetZ,
            float worldY,
            int kind,
            float deltaX,
            float deltaZ,
            float verticalSpeed,
            float contactDelta,
            float forwardX,
            float forwardZ,
            float halfWidth,
            float halfLength,
            int fluidKind,
            float surfaceContact
    ) {}

    private record History(double x, double y, double z, float surfaceContact, int seenAtFrame) {}

    private static final double TELEPORT_DISTANCE_SQUARED = 16.0 * 16.0;
    private static final float MAX_DELTA_SECONDS = 0.05f;
    private static final WaterActorFrameState LIVE = new WaterActorFrameState();

    private final Map<Integer, History> history = new HashMap<>();
    private List<Actor> actors = List.of();
    private Object previousLevel;
    private int frameCounter;

    public List<Actor> actors() {
        return actors;
    }

    public static List<Actor> current() {
        return LIVE.actors();
    }

    /**
     * Collects this frame's set. Runs after {@link LocalActorFrameState#commitFromClient()} so the
     * local actor's own snapshot is the frame's anchor rather than a second, differently-timed read
     * of the same entity.
     */
    public static void commitFromClient() {
        LIVE.update();
    }

    private void update() {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            clear();
            return;
        }
        if (level != previousLevel) {
            // A level change invalidates every id: entity 47 in the nether is not entity 47 in the
            // overworld, and reusing its position would publish one frame of garbage displacement.
            history.clear();
            previousLevel = level;
        }
        frameCounter++;

        LocalActorFrameState.Snapshot local = LocalActorFrameState.current();
        float deltaSeconds = Math.min(Math.max(local.deltaSeconds(), 1.0e-4f), MAX_DELTA_SECONDS);
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Entity localActor = client.player.getRootVehicle();

        List<Actor> collected = new ArrayList<>(WaterActorBuffer.MAX_ACTORS);
        double rangeSquared = WaterActorBuffer.RANGE_BLOCKS * WaterActorBuffer.RANGE_BLOCKS;
        // The local actor first and unconditionally -- it anchors the field, so a pack looping this
        // list must find it even on the frame it stops touching water (that frame is its exit).
        Actor localRecord = describe(localActor, local.x(), local.z(), partialTick, deltaSeconds);
        if (localRecord != null) {
            collected.add(localRecord);
        }

        // Nearest-first, so the cap drops the bodies whose wake is least likely to be looked at
        // rather than whichever ones the iteration order happened to reach last.
        List<Entity> candidates = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == localActor || entity.getVehicle() != null || !entity.isAlive()) {
                continue;
            }
            if (!touchesWater(entity)) {
                continue;
            }
            double dx = entity.getX() - local.x();
            double dz = entity.getZ() - local.z();
            if (dx * dx + dz * dz > rangeSquared) {
                continue;
            }
            candidates.add(entity);
        }
        candidates.sort((a, b) -> Double.compare(
                distanceSquared(a, local), distanceSquared(b, local)));

        for (Entity entity : candidates) {
            if (collected.size() >= WaterActorBuffer.MAX_ACTORS) {
                break;
            }
            Actor record = describe(entity, local.x(), local.z(), partialTick, deltaSeconds);
            if (record != null) {
                collected.add(record);
            }
        }

        actors = List.copyOf(collected);
        // Anything not seen this frame is gone: dead, out of range, or dismounted onto something
        // else. Dropping it now means its id cannot resurrect a stale position later.
        history.entrySet().removeIf(e -> e.getValue().seenAtFrame() != frameCounter);
    }

    private static double distanceSquared(Entity entity, LocalActorFrameState.Snapshot local) {
        double dx = entity.getX() - local.x();
        double dz = entity.getZ() - local.z();
        return dx * dx + dz * dz;
    }

    private static boolean touchesWater(Entity entity) {
        return entity.isInWater() || entity.getFluidHeight(FluidTags.WATER) > 0.0;
    }

    private Actor describe(Entity entity, double localX, double localZ,
                           float partialTick, float deltaSeconds) {
        var position = entity.getPosition(partialTick);
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y())
                || !Double.isFinite(position.z())) {
            return null;
        }

        // Same enum, same precedence as the single-actor path: water wins over lava.
        int fluidKind = touchesWater(entity)
                ? LocalActorFrameState.FLUID_WATER
                : entity.isInLava() || entity.getFluidHeight(FluidTags.LAVA) > 0.0
                        ? LocalActorFrameState.FLUID_LAVA : LocalActorFrameState.FLUID_NONE;
        float surfaceContact = fluidKind != LocalActorFrameState.FLUID_NONE
                && !entity.isUnderWater() ? 1.0f : 0.0f;

        History previous = history.get(entity.getId());
        double dx = previous == null ? 0.0 : position.x() - previous.x();
        double dy = previous == null ? 0.0 : position.y() - previous.y();
        double dz = previous == null ? 0.0 : position.z() - previous.z();
        boolean teleported = dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE_SQUARED;
        if (previous == null || teleported) {
            dx = dy = dz = 0.0;
        }
        // A body that was not here last frame has no crossing either: its contact is new
        // information, not a change, and treating it as one splashes every entity that walks into
        // the collection radius already swimming.
        float contactDelta = previous == null || teleported
                ? 0.0f : surfaceContact - previous.surfaceContact();
        history.put(entity.getId(),
                new History(position.x(), position.y(), position.z(), surfaceContact, frameCounter));

        // Boat is the only kind a consumer has to treat differently -- a hull displaces water along
        // its own axis where a swimmer is radial -- so players and everything else are separated
        // only because the distinction is free and a pack may want it.
        int kind = entity instanceof AbstractBoat
                ? WaterActorBuffer.KIND_BOAT
                : entity instanceof net.minecraft.world.entity.player.Player
                        ? WaterActorBuffer.KIND_PLAYER : WaterActorBuffer.KIND_OTHER;

        float yawRadians = (float) Math.toRadians(entity.getYRot(partialTick));
        var bounds = entity.getBoundingBox();
        return new Actor(
                (float) (position.x() - localX),
                (float) (position.z() - localZ),
                (float) position.y(),
                kind,
                (float) dx,
                (float) dz,
                (float) (dy / deltaSeconds),
                contactDelta,
                -(float) Math.sin(yawRadians),
                (float) Math.cos(yawRadians),
                Math.max(0.0f, entity.getBbWidth() * 0.5f),
                (float) (Math.max(bounds.getXsize(), bounds.getZsize()) * 0.5),
                fluidKind,
                surfaceContact);
    }

    private void clear() {
        history.clear();
        actors = List.of();
        previousLevel = null;
    }
}
