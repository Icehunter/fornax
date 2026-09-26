package dev.icehunter.fornax.pipeline;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3fc;

import java.util.function.IntToDoubleFunction;

/**
 * The vertical wall beside the player, on the X axis and the Z axis independently: a second plane
 * {@code PlayerMirrorCaster}'s draw can reflect the player against, alongside {@link
 * WaterPlaneProbe}'s horizontal plane below the feet. Nothing reads the published lane yet; this
 * class exists to publish it correctly, ahead of the consumer that will read it.
 *
 * <p>Scans outward from the player's column in all four cardinal directions, at feet height and
 * head height, for the first block whose visual shape ({@code getShape}, following {@link
 * WaterPlaneProbe}'s standing scan: the plane must sit where the block is drawn, not where a
 * hitbox stops) is not empty. Two independent per-axis results come out: an X-axis wall (the
 * nearer of whatever the +X and -X scans found, whichever one the camera faces) and a Z-axis
 * wall, found the same way.
 *
 * <p>Published camera-relative, in double precision, unlike {@link WaterPlaneProbe}'s absolute
 * height. A wall's X/Z position can sit anywhere in a world whose horizontal extent runs into the
 * tens of millions of blocks, where float32's spacing at that magnitude is coarser than a block,
 * well past the mirror pass's 0.05-block receiver guard. The water plane never has this problem,
 * since world height is bounded to a few thousand blocks either side of zero, well inside float
 * precision, which is why that class publishes an absolute height and this one cannot: the
 * subtraction against the camera's position must happen in double, on the CPU (see {@link
 * #toCameraRelative}), before the result is narrowed to the float32 a uniform buffer holds.
 */
public final class WallPlaneProbe {
    /**
     * Blocks beyond the player's column each directional scan searches. Matches {@link
     * WaterPlaneProbe#SCAN_BOUND_BLOCKS}'s reasoning: a generous margin over the room size a
     * player who could see a wall reflection would be standing in, without either scan reaching
     * into an unrelated, far-away wall.
     */
    public static final int SCAN_BOUND_BLOCKS = 8;

    /**
     * x = the X-axis wall's facing: -1 (found scanning toward +X, so the wall's face points back
     * at the player in -X) or +1 (found scanning toward -X, face points +X), 0 = no X-axis wall
     * selected. y = that wall's plane position, camera-relative (world X minus the camera's X,
     * both in double before the cast), zero-filled when x is 0, following the usual convention
     * that 0 in this lane means every consumer keeps its no-mirror behaviour. z/w are the same
     * pair for the Z axis.
     */
    public record Values(float xFacing, float xPlaneRel, float zFacing, float zPlaneRel) {}

    public static final Values ZERO = new Values(0.0f, 0.0f, 0.0f, 0.0f);

    private static volatile Values lastValues = ZERO;

    private WallPlaneProbe() {}

    /** Live read: the current player's feet and the live camera, guarded on the same eye-submersion
     * flag {@link WaterPlaneProbe#read(boolean)} uses, since a submerged eye has no wall to reflect
     * against from outside it either. Stores its result in {@link #current()}, matching {@link
     * WaterPlaneProbe}'s caching contract for the same reason: a later consumer reuses this frame's
     * scan rather than repeating it. */
    public static Values read(boolean eyeSubmerged) {
        Minecraft client = Minecraft.getInstance();
        boolean hasLevel = client != null && client.level != null;
        boolean hasPlayer = client != null && client.player != null;
        Values values;
        if (!shouldProbe(hasLevel, hasPlayer, eyeSubmerged)) {
            values = ZERO;
        } else {
            Camera camera = client.gameRenderer.mainCamera();
            values = read(client.level, client.player.blockPosition(), camera.position(),
                    camera.forwardVector());
        }
        lastValues = values;
        return values;
    }

    /** The most recent {@link #read(boolean)} result, or {@link #ZERO} before the first frame. */
    public static Values current() {
        return lastValues;
    }

    /** Whether the probe should run at all this frame. Pure: testable without a level or a player. */
    static boolean shouldProbe(boolean hasLevel, boolean hasPlayer, boolean eyeSubmerged) {
        return hasLevel && hasPlayer && !eyeSubmerged;
    }

    /**
     * Touches a level, a position, and the camera's state; every real decision is delegated to a
     * pure method below and tested there instead. {@code cameraPos} is the double-precision
     * camera position {@link #toCameraRelative} needs ({@code Camera.position()} in the live
     * caller). {@code cameraForward} decides which of a pair of candidate walls per axis the
     * camera faces; only its x/z components are read, since an axis-aligned wall's face normal
     * has no y component to dot against.
     */
    public static Values read(@Nullable ClientLevel level, @Nullable BlockPos feetBlock,
            @Nullable Vec3 cameraPos, @Nullable Vector3fc cameraForward) {
        if (level == null || feetBlock == null || cameraPos == null || cameraForward == null) {
            return ZERO;
        }
        AxisCandidate plusX = scanDirection(level, feetBlock, Direction.Axis.X, 1);
        AxisCandidate minusX = scanDirection(level, feetBlock, Direction.Axis.X, -1);
        AxisCandidate plusZ = scanDirection(level, feetBlock, Direction.Axis.Z, 1);
        AxisCandidate minusZ = scanDirection(level, feetBlock, Direction.Axis.Z, -1);

        AxisCandidate x = pickAxis(plusX, minusX, cameraForward.x());
        AxisCandidate z = pickAxis(plusZ, minusZ, cameraForward.z());

        float xFacing = x == null ? 0.0f : x.facing();
        float xPlaneRel = x == null ? 0.0f : toCameraRelative(x.planeAbsolute(), cameraPos.x());
        float zFacing = z == null ? 0.0f : z.facing();
        float zPlaneRel = z == null ? 0.0f : toCameraRelative(z.planeAbsolute(), cameraPos.z());
        return new Values(xFacing, xPlaneRel, zFacing, zPlaneRel);
    }

    /**
     * Precision matters here: the subtraction happens in double, and only the small,
     * camera-relative result is narrowed to float, never the other way around. At world
     * coordinates in the tens of millions, float32's spacing between representable values already
     * exceeds a block, so casting {@code planeAbsolute} to float before subtracting would destroy
     * the sub-block precision the mirror pass's 0.05-block receiver guard depends on. Subtracting
     * first cancels the large shared magnitude and leaves only the small residual, which float
     * represents exactly at any practical distance from the camera.
     */
    static float toCameraRelative(double absolute, double cameraCoord) {
        return (float) (absolute - cameraCoord);
    }

    /** One axis's surviving candidate: which way its face points (-1/+1, following {@link
     * Values#xFacing}'s convention), its absolute plane position (still a double: {@link
     * #toCameraRelative} runs as the last step, in {@link #read}, never here), and how many
     * blocks out the scan that found it walked, used for {@link #pickAxis}'s tie-break. */
    record AxisCandidate(float facing, double planeAbsolute, int distanceBlocks) {}

    /**
     * Chooses between a {@code +axis} candidate and a {@code -axis} candidate for the same axis
     * (never called across axes): the one the camera is facing, with {@code forwardComponent}
     * being the matching component (x for the X axis, z for the Z axis) of the camera's forward
     * vector. A {@code +axis} candidate's face normal is {@code -axis} (found scanning outward in
     * {@code +axis}, so its near face points back the way the scan came from), and a {@code
     * -axis} candidate's is {@code +axis}, so plusDot and minusDot below are always exact
     * negatives of each other for a given {@code forwardComponent}: normally exactly one is
     * negative (the wall the camera is turned toward) and the choice is unambiguous.
     *
     * <p>Only when {@code forwardComponent} is exactly zero (looking squarely along the other
     * axis, straight down a corridor with walls on both sides) do both dots land at exactly zero,
     * neither negative: nothing to choose between by facing alone, so the nearer wall wins
     * instead. When only one candidate is present, that one is returned outright with no facing
     * test: "choose the one the camera looks at" only means something once there are two to
     * choose between.
     */
    @Nullable
    static AxisCandidate pickAxis(@Nullable AxisCandidate plus, @Nullable AxisCandidate minus,
            float forwardComponent) {
        if (plus == null) return minus;
        if (minus == null) return plus;
        float plusDot = -forwardComponent;
        float minusDot = forwardComponent;
        if (plusDot < 0.0f && !(minusDot < 0.0f)) return plus;
        if (minusDot < 0.0f && !(plusDot < 0.0f)) return minus;
        return plus.distanceBlocks() <= minus.distanceBlocks() ? plus : minus;
    }

    /**
     * One directional scan ({@code sign} +1 or -1 along {@code axis}), reduced across both
     * reference rows (feet height and head height: a low windowsill at feet height with open
     * space above it, or the reverse, must not silently win over the row that actually finds the
     * nearer wall). Touches the level to build each row's extent-lookup callback and defers to
     * {@link #scanRow}/{@link #nearerRow} (both pure) for everything else.
     */
    @Nullable
    static AxisCandidate scanDirection(ClientLevel level, BlockPos feetBlock, Direction.Axis axis, int sign) {
        int startCoord = axis == Direction.Axis.X ? feetBlock.getX() : feetBlock.getZ();
        int otherCoord = axis == Direction.Axis.X ? feetBlock.getZ() : feetBlock.getX();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        RowHit feet = scanRow(SCAN_BOUND_BLOCKS,
                offset -> shapeExtentAt(level, cursor, axis, sign, startCoord, otherCoord, feetBlock.getY(), offset));
        RowHit head = scanRow(SCAN_BOUND_BLOCKS,
                offset -> shapeExtentAt(level, cursor, axis, sign, startCoord, otherCoord, feetBlock.getY() + 1, offset));
        RowHit nearer = nearerRow(feet, head);
        if (!nearer.valid()) {
            return null;
        }
        float facing = sign > 0 ? -1.0f : 1.0f;
        int coord = startCoord + sign * nearer.distanceBlocks();
        return new AxisCandidate(facing, coord + nearer.localExtent(), nearer.distanceBlocks());
    }

    /**
     * A shape counts as a wall face only if it is near-full across both axes it is not being
     * scanned along: {@code >= 0.9} of the block on each. Below that threshold the scan keeps
     * going rather than stopping here: a flower, a carpet, a button, or a wall torch each present
     * a non-empty {@code getShape} that would otherwise plant the plane on the decoration instead
     * of the real wall behind it, and the real wall would then never again match the mirror
     * pass's 0.05-block receiver guard, so the reflection would silently stop rendering with no
     * error anywhere. A slab (half extent on the height axis) is excluded by the same test:
     * accepting it would mean guessing which half of the block the flat face sits on, and a
     * missed wall behind a slab is a smaller, quieter loss than a mirror plane rendered a
     * half-block off from every slab-faced wall in the world.
     */
    private static final double FULL_FACE_MIN_EXTENT = 0.9;

    static boolean presentsFullFace(VoxelShape shape, Direction.Axis scanAxis) {
        for (Direction.Axis other : Direction.Axis.values()) {
            if (other == scanAxis) {
                continue;
            }
            if (shape.max(other) - shape.min(other) < FULL_FACE_MIN_EXTENT) {
                return false;
            }
        }
        return true;
    }

    /** The one level-touching leaf: the block's visual shape ({@code getShape}, never {@code
     * getCollisionShape}, see this class's header doc) at the position {@code offset} blocks out
     * along {@code axis} from the player's column, at row {@code y}. {@code sign > 0} reads the
     * shape's {@code min(axis)} (this scan's near face, approaching from the {@code -axis}
     * side); {@code sign < 0} reads {@code max(axis)} (approaching from {@code +axis}). Returns a
     * negative sentinel for an empty shape or one that fails {@link #presentsFullFace}, the same
     * "-1 means no surface here" convention {@link WaterPlaneProbe#read}'s column scans use, so
     * {@link #scanRow} keeps walking past a decoration to the real wall behind it, keeping the
     * same pure function shape as {@link WaterPlaneProbe#scan}. */
    private static double shapeExtentAt(ClientLevel level, BlockPos.MutableBlockPos cursor, Direction.Axis axis,
            int sign, int startCoord, int otherCoord, int y, int offset) {
        int coord = startCoord + sign * offset;
        if (axis == Direction.Axis.X) {
            cursor.set(coord, y, otherCoord);
        } else {
            cursor.set(otherCoord, y, coord);
        }
        VoxelShape shape = level.getBlockState(cursor).getShape(level, cursor);
        if (shape.isEmpty() || !presentsFullFace(shape, axis)) {
            return -1.0;
        }
        return sign > 0 ? shape.min(axis) : shape.max(axis);
    }

    /** Pure core: walks {@code offset} from 1 to {@code bound} for the first one {@code extentAt}
     * reports as a surface (non-negative), matching {@link WaterPlaneProbe#scan}'s shape (a plain
     * function of an int, tested with an array or a lambda, no level needed). Offset 0 is never
     * checked here, unlike that method's feet-block-inclusive column scan, since a wall search
     * starts one block out from the player's column, not in it. */
    static RowHit scanRow(int bound, IntToDoubleFunction extentAt) {
        for (int offset = 1; offset <= bound; offset++) {
            double extent = extentAt.applyAsDouble(offset);
            if (extent >= 0.0) {
                return new RowHit(true, offset, extent);
            }
        }
        return RowHit.INVALID;
    }

    /** Keeps whichever of the feet-row and head-row hits sits at the smaller {@code
     * distanceBlocks}. A tie (both rows hit at the identical offset, necessarily the same block,
     * since both rows walk the same coordinate at the same rate) keeps the feet row: an arbitrary
     * but deterministic pick between two answers that describe the same plane anyway. */
    static RowHit nearerRow(RowHit feet, RowHit head) {
        if (!feet.valid()) return head;
        if (!head.valid()) return feet;
        return feet.distanceBlocks() <= head.distanceBlocks() ? feet : head;
    }

    /** One row's scan result: {@code localExtent} is the hit block's {@code shape.min}/{@code
     * max} along the scanned axis (whichever {@link #scanRow}'s caller asked for), and {@code
     * distanceBlocks} is how many blocks out from the player's column the hit sat, the only thing
     * {@link #nearerRow}/{@link #pickAxis} compare on. */
    record RowHit(boolean valid, int distanceBlocks, double localExtent) {
        static final RowHit INVALID = new RowHit(false, 0, 0.0);
    }
}
