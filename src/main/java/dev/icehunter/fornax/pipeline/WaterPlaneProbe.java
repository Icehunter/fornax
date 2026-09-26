package dev.icehunter.fornax.pipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.function.IntToDoubleFunction;

/**
 * The surface directly below the player: the plane the player-mirror pass draws the player's
 * reflection against. Two candidates are scanned independently: the water surface, and the
 * standing surface, whichever solid top or water the player's feet would rest on, because a dock
 * plank, a bridge deck, or dry ground all deserve a reflection just as much as open water does.
 * The render plane is always the standing plane when it validates (see {@link #combine} for why,
 * and for the water consumer's shifted-lookup workaround when the two planes differ).
 *
 * <p>Read every frame the eye is dry. A submerged eye has no plane to mirror against from
 * outside it, so the caller guards on the same eye-in-water flag {@link
 * dev.icehunter.fornax.mixin.sodium.GlobalUniformsWriteMixin} already computes for the water
 * tail, rather than this class running a second submersion test.
 */
public final class WaterPlaneProbe {
    /**
     * Blocks below the feet block each scan searches, beyond the feet block itself. A player who can
     * see their reflection is floating or standing at most a couple of blocks above that surface
     * (swimming, wading, a dock plank, a low bridge); 8 is a generous margin over that without
     * either scan finding an unrelated surface far below an elevated position.
     */
    public static final int SCAN_BOUND_BLOCKS = 8;

    /**
     * Sentinel for one plane's lane (z/w) when that scan found nothing. World height never
     * reaches this far in either direction, so it is exact and unambiguous. Independent of the
     * overall render-plane validity in x: z/w report this whether or not the other plane was
     * found, so a pack reading one lane alone still gets an honest answer.
     */
    public static final float NO_PLANE = -1.0e4f;

    /**
     * x = valid, 1.0 if either plane below was found (see {@link #read(ClientLevel, BlockPos)}).
     * y = h_r, the render height: {@code standHeight} when the standing scan validated, else
     * {@code waterHeight}. See {@link #combine} for why this is not a minimum: a model of the
     * mirror frustum found that rendering about the lower plane drags the mirrored image further
     * below the guarded frustum and loses most of it. z = h_water, the water scan's height or
     * {@link #NO_PLANE}, read by a water consumer that needs a different plane than the render
     * one, shifting its lookups by {@code 2*(z - y)}. w = h_stand, the standing-surface scan's
     * height or {@link #NO_PLANE} again, kept alongside y for symmetry with z even though it is
     * normally bit-identical to y. When only water validates, y is bit-identical to what the
     * water-only scan produces.
     */
    public record Values(float valid, float renderHeight, float waterHeight, float standHeight) {}

    public static final Values ZERO = new Values(0.0f, 0.0f, NO_PLANE, NO_PLANE);

    /**
     * The result of the most recent {@link #read(boolean)} call, for {@code PlayerMirrorCaster} to
     * reuse at its call site without a second scan. Safe by construction rather than by
     * convention: {@link dev.icehunter.fornax.mixin.sodium.GlobalUniformsWriteMixin}'s doc states
     * this class's read runs once per frame as part of the globals write, which {@code
     * FeatureSolidFeaturesGraphMixin.fornax$runGraphAfterSolidFeatures} (where the mirror caster
     * runs) always fires strictly after: Sodium uploads globals while preparing the frame, before
     * {@code PreparedFrame.executeSolid} is reached at all.
     */
    private static volatile Values lastValues = ZERO;

    private WaterPlaneProbe() {}

    /** Live read: the current player's feet, guarded on the eye submersion the caller already knows.
     * Stores its result in {@link #current()} as a side effect; {@link #read(ClientLevel,
     * BlockPos)} does not, so that overload stays a pure scan for testing. */
    public static Values read(boolean eyeSubmerged) {
        Minecraft client = Minecraft.getInstance();
        boolean hasLevel = client != null && client.level != null;
        boolean hasPlayer = client != null && client.player != null;
        Values values = !shouldProbe(hasLevel, hasPlayer, eyeSubmerged)
                ? ZERO : read(client.level, client.player.blockPosition());
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
     * Touches only a level and a position; each scan is pure. Runs two independent {@link #scan}
     * calls down the same column: water (passes straight through solids, see that method's doc)
     * and standing surface (water or a solid top, whichever this column meets first), and reports
     * both, plus the render height (see {@link #combine}).
     *
     * <p>The two scans commonly stop at different depths. A dock plank or a bridge deck sits
     * above the water it is built over, so the standing scan returns the deck immediately while
     * the water scan, passing through the deck as a solid, keeps going to the real surface below.
     * Standing in shallow water hits the same fluid block from both scans at the same Y, so the
     * two lanes read identically.
     */
    public static Values read(@Nullable ClientLevel level, @Nullable BlockPos feetBlock) {
        if (level == null || feetBlock == null) return ZERO;
        int x = feetBlock.getX();
        int z = feetBlock.getZ();

        BlockPos.MutableBlockPos waterCursor = new BlockPos.MutableBlockPos();
        ScanResult water = scan(feetBlock.getY(), SCAN_BOUND_BLOCKS, y -> {
            waterCursor.set(x, y, z);
            FluidState fluid = level.getFluidState(waterCursor);
            return fluid.is(FluidTags.WATER) ? fluid.getOwnHeight() : -1.0;
        });

        // Water first, at the same block: a water-filled column is a valid standing surface too
        // (wading), and checking it here keeps that case identical to the water scan's answer
        // rather than falling through to a shape query fluid blocks do not meaningfully have.
        // Otherwise the block's visual shape (getShape, never getCollisionShape: the render plane
        // must sit where the block is drawn, not where a player's hitbox stops, and those two
        // differ for the blocks worth naming: a bottom slab draws and collides the same, but
        // farmland draws 1/16 short of a full block, and other collision quirks exist elsewhere).
        // An empty shape (air) is not a surface; the scan continues down through it.
        BlockPos.MutableBlockPos standCursor = new BlockPos.MutableBlockPos();
        ScanResult stand = scan(feetBlock.getY(), SCAN_BOUND_BLOCKS, y -> {
            standCursor.set(x, y, z);
            FluidState fluid = level.getFluidState(standCursor);
            if (fluid.is(FluidTags.WATER)) {
                return fluid.getOwnHeight();
            }
            VoxelShape shape = level.getBlockState(standCursor).getShape(level, standCursor);
            return shape.isEmpty() ? -1.0 : shape.max(Direction.Axis.Y);
        });

        return combine(water, stand);
    }

    record ScanResult(boolean valid, float height) {}

    /**
     * Pure core, the second half of the split: turns the two independent {@link ScanResult}s into
     * the published {@link Values}: x/valid true if either validated, z/w each plane's height or
     * {@link #NO_PLANE}, y/renderHeight the standing height when it validated, else the water
     * height (bit-identical to a water-only scan when stand did not validate).
     *
     * <p>Not a minimum. The standing plane is always the upper of the two (it is the first
     * surface the standing scan meets going down), so rendering about the lower, water, plane
     * would drag the mirrored geometry a further {@code 2*(h_stand - h_water)} below the mirror
     * pass's guard-band frustum. A model (tools/verify_player_mirror.py) measured only 87 of 252
     * plank receivers still covered under that choice, versus 252 of 252 when rendering about the
     * standing plane. Rendering about the standing plane keeps the stored image as high on screen
     * as either plane can put it, and a consumer that wants the lower plane, such as open water
     * under a dock or a bridge, shifts its lookups by {@code 2*(h_consumer - h_render)} rather
     * than the render moving. See {@code shaders/include/player_mirror_trace.glsl} for that half.
     */
    static Values combine(ScanResult water, ScanResult stand) {
        float waterHeight = water.valid() ? water.height() : NO_PLANE;
        float standHeight = stand.valid() ? stand.height() : NO_PLANE;
        boolean valid = water.valid() || stand.valid();
        float renderHeight = !valid ? 0.0f : stand.valid() ? standHeight : waterHeight;
        return new Values(valid ? 1.0f : 0.0f, renderHeight, waterHeight, standHeight);
    }

    /**
     * Pure core: scans from {@code feetY} down through {@code bound} further blocks (the feet
     * block itself checked first) for the first column {@code heightAt} reports, and returns that
     * block's Y plus its reported height. {@code heightAt} returns a surface height (a local
     * offset, roughly 0 to 1, added to the block's Y) at that absolute Y, or a negative number
     * when that block carries no surface for this scan: a plain function, so this is tested with
     * an array or a lambda, no level needed. Shared by both the water scan and the
     * standing-surface scan in {@link #read(ClientLevel, BlockPos)}; the two differ only in what
     * their {@code heightAt} callback considers a surface, not in how the column is walked.
     *
     * <p>Passes straight through solid blocks, for the water scan specifically. Its callback
     * tests only the fluid state at each Y; it never checks whether the blocks in between are
     * solid. A cave lake under a few blocks of stone floor is found as readily as open water, and
     * the mirror then draws for a reflection nobody can see: a wasted draw, cost only, never a
     * wrong picture (the fragment stage still discards anything above the real water surface).
     * Kept rather than adding a line-of-sight test because docks, low bridges, and jetties need
     * the same pass-through: the player's feet sit on solid planking with the water surface one
     * or more blocks below, and that is the common case this scan exists to find. The
     * standing-surface scan does not pass through solids in the same sense: a solid block's shape
     * stops that scan at its top, which is the point of it.
     */
    static ScanResult scan(int feetY, int bound, IntToDoubleFunction heightAt) {
        for (int offset = 0; offset <= bound; offset++) {
            int y = feetY - offset;
            double ownHeight = heightAt.applyAsDouble(y);
            if (ownHeight >= 0.0) {
                return new ScanResult(true, y + (float) ownHeight);
            }
        }
        return new ScanResult(false, 0.0f);
    }
}
