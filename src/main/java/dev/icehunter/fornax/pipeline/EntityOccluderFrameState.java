package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.graph.EntityOccluderBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * State for the local player and every nearby entity that a pack's local-light shadow march may
 * block against, held steady for the whole frame.
 *
 * <p>Follows the same contract as {@link WaterActorFrameState}: positions stay {@code double}
 * until the last step, a body missing from last frame gets zero motion instead of a jump like a
 * teleport, and this class does not decide what any of it means to a pack. It exists to keep
 * history for each body: a centre change is the difference from that same body's last centre,
 * which cannot be found from a single frame and cannot be kept by a pack, since a pack never learns
 * which id a record belongs to.
 */
public final class EntityOccluderFrameState {
    /** One body, camera-relative and ready to be packed. */
    public record Occluder(
            float minX,
            float minY,
            float minZ,
            int kind,
            float maxX,
            float maxY,
            float maxZ,
            float yawRadians,
            float deltaX,
            float deltaY,
            float deltaZ,
            float eyeHeight
    ) {}

    private record History(double x, double y, double z, int seenAtFrame) {}

    /** Same size as {@link WaterActorFrameState}'s own teleport threshold: a living body crossing
     * 16 blocks in one frame is a teleport, not motion, and using that distance as its motion would
     * draw a stretched-out occluder for one frame. */
    private static final double TELEPORT_DISTANCE_SQUARED = 16.0 * 16.0;
    private static final EntityOccluderFrameState LIVE = new EntityOccluderFrameState();

    private final Map<Integer, History> history = new HashMap<>();
    private List<Occluder> occluders = List.of();
    private Object previousLevel;
    private int frameCounter;

    public List<Occluder> occluders() {
        return occluders;
    }

    public static List<Occluder> current() {
        return LIVE.occluders();
    }

    /** Collects this frame's set, camera-relative to {@code camX, camY, camZ}. */
    public static void commitFromClient(double camX, double camY, double camZ) {
        LIVE.update(camX, camY, camZ);
    }

    /** This frame's counter, so the code that writes the header can use it without keeping a
     * second copy. */
    static int frameCounter() {
        return LIVE.frameCounter;
    }

    private void update(double camX, double camY, double camZ) {
        Minecraft client = Minecraft.getInstance();
        var level = client.level;
        Player localPlayer = client.player;
        if (level == null || localPlayer == null) {
            clear();
            return;
        }
        if (level != previousLevel) {
            // A change of level makes every id useless: entity 47 in the nether is not entity 47
            // in the overworld, and reusing its position would give one frame of garbage motion.
            history.clear();
            previousLevel = level;
        }
        frameCounter++;
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        Occluder local = null;
        if (!localPlayer.isSpectator() && !localPlayer.isInvisible()) {
            AABB localBounds = interpolatedBounds(localPlayer, partialTick);
            if (localBounds != null) {
                local = describe(localPlayer, localPlayer, localBounds, camX, camY, camZ, partialTick);
            }
        }

        List<Occluder> others = new ArrayList<>();
        double rangeSquared = EntityOccluderBuffer.RANGE_BLOCKS * EntityOccluderBuffer.RANGE_BLOCKS;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == localPlayer || !entity.isAlive() || entity.isSpectator() || entity.isInvisible()) {
                continue;
            }
            AABB bounds = interpolatedBounds(entity, partialTick);
            if (bounds == null) {
                continue;
            }
            // The same partial-tick-shifted centre this body is sent out with, not the
            // one from the last tick. A fast-moving body would otherwise be left out, or kept in,
            // based on a position it does not hold this frame.
            Vec3 center = bounds.getCenter();
            double dx = center.x() - camX;
            double dy = center.y() - camY;
            double dz = center.z() - camZ;
            if (dx * dx + dy * dy + dz * dz > rangeSquared) {
                continue;
            }
            others.add(describe(entity, localPlayer, bounds, camX, camY, camZ, partialTick));
        }

        occluders = select(local, others, EntityOccluderBuffer.MAX_OCCLUDERS);
        // Anything not described this frame is gone: dead, out of range, or invisible. Drop it
        // here so its id cannot bring back a stale centre later.
        history.entrySet().removeIf(e -> e.getValue().seenAtFrame() != frameCounter);
    }

    /**
     * Orders and limits an already-described frame: the local player first when present, then
     * every other body nearest first by its own box centre distance to the camera, cut to
     * {@code max}. A pure function of records already relative to the camera: distance to the
     * camera is how far a record's own centre sits from zero, so this can be tested with no
     * client, no level and no GPU.
     */
    static List<Occluder> select(Occluder local, List<Occluder> others, int max) {
        if (max <= 0) {
            return List.of();
        }
        List<Occluder> sorted = new ArrayList<>(others);
        sorted.sort(Comparator.comparingDouble(EntityOccluderFrameState::centerDistanceSquared));

        List<Occluder> result = new ArrayList<>(Math.min(max, sorted.size() + 1));
        if (local != null) {
            result.add(local);
        }
        for (Occluder occluder : sorted) {
            if (result.size() >= max) {
                break;
            }
            result.add(occluder);
        }
        return List.copyOf(result);
    }

    private static double centerDistanceSquared(Occluder occluder) {
        double cx = (occluder.minX() + occluder.maxX()) * 0.5;
        double cy = (occluder.minY() + occluder.maxY()) * 0.5;
        double cz = (occluder.minZ() + occluder.maxZ()) * 0.5;
        return cx * cx + cy * cy + cz * cz;
    }

    /**
     * This body's box, moved from its last-tick position to its partial-tick one, the same shape
     * every reader of this frame's position uses. {@code null} when the entity's interpolated
     * position or any of the six resulting bounds is not finite, so neither the range check nor
     * {@link #describe} ever has to handle a box with NaN or infinite values.
     */
    private static AABB interpolatedBounds(Entity entity, float partialTick) {
        Vec3 p = entity.getPosition(partialTick);
        if (!Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())) {
            return null;
        }
        Vec3 base = entity.position();
        AABB bounds = entity.getBoundingBox().move(p.x() - base.x(), p.y() - base.y(), p.z() - base.z());
        if (!Double.isFinite(bounds.minX) || !Double.isFinite(bounds.minY) || !Double.isFinite(bounds.minZ)
                || !Double.isFinite(bounds.maxX) || !Double.isFinite(bounds.maxY) || !Double.isFinite(bounds.maxZ)) {
            return null;
        }
        return bounds;
    }

    private Occluder describe(Entity entity, Entity localPlayer, AABB bounds, double camX, double camY,
                              double camZ, float partialTick) {
        Vec3 center = bounds.getCenter();

        History previous = history.get(entity.getId());
        double dx = previous == null ? 0.0 : center.x() - previous.x();
        double dy = previous == null ? 0.0 : center.y() - previous.y();
        double dz = previous == null ? 0.0 : center.z() - previous.z();
        boolean teleported = dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE_SQUARED;
        if (previous == null || teleported) {
            dx = dy = dz = 0.0;
        }
        history.put(entity.getId(), new History(center.x(), center.y(), center.z(), frameCounter));

        int kind = entity == localPlayer ? EntityOccluderBuffer.KIND_PLAYER
                : entity instanceof Player ? EntityOccluderBuffer.KIND_OTHER_PLAYER
                : entity instanceof ItemEntity ? EntityOccluderBuffer.KIND_ITEM
                : entity instanceof LivingEntity ? EntityOccluderBuffer.KIND_LIVING
                : EntityOccluderBuffer.KIND_OTHER;

        // Body yaw, not head yaw: a pack's shadow shape follows the way the body is turned, the
        // same way a blocking silhouette would, not wherever the head happens to be looking.
        float yawDegrees = entity instanceof LivingEntity living
                ? Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot)
                : entity.getYRot(partialTick);
        float yawRadians = (float) Math.toRadians(yawDegrees);

        return new Occluder(
                (float) (bounds.minX - camX), (float) (bounds.minY - camY), (float) (bounds.minZ - camZ),
                kind,
                (float) (bounds.maxX - camX), (float) (bounds.maxY - camY), (float) (bounds.maxZ - camZ),
                yawRadians,
                (float) dx, (float) dy, (float) dz,
                entity.getEyeHeight());
    }

    private void clear() {
        history.clear();
        occluders = List.of();
        previousLevel = null;
    }
}
