package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.FornaxMod;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Exact opaque cuboids reconstructed from final rendered rectangles, within the palette ABI.
 * A shape the proof cannot close keeps the block's selection shape. No box is made bigger to fit.
 *
 * <p>Each corner of a quad is rounded outward to the 1/16 grid. The low side of a face rounds
 * down, the high side rounds up, and the face plane rounds away from the solid it belongs to. A
 * box 1/16 too big shades a little too much. A box 1/16 too small lets a shadow ray start inside
 * it, and the surface goes black. That black surface is the bug this rebuild exists to stop. A
 * plane past the edge of the cell (a sign board that pokes into the block above) is cut at the
 * edge. Only a face with nothing left after that cut is dropped. Vanilla signs are the test case:
 * {@code assets/minecraft/models/block/template_sign_rot_0.json} puts the post and board at thirds
 * of a 1/16 and turns the board by 0.0001 degrees so the game does not cull its faces.
 *
 * <p>Two kinds of turn fail in two places. A quad turned flat about its own normal (a floor tile
 * spun in place) still has all four corners on one plane, so the axis search passes, but the
 * corners do not sit at the low and high ends of that plane, so the corner check drops it. A
 * quad tipped up on one edge has no axis its four corners share, so the axis search drops it. Both
 * keep the selection shape. */
final class VoxelModelShape {
    // Six faces per ABI box bounds proof work; one coincident overlay per face bounds capture.
    private static final int MAX_FACES = 6 * VoxelShapeClassifier.MAX_BOXES;
    private static final int MAX_QUADS = 2 * MAX_FACES;
    // The shadow ray starts 1/4096 block off a surface, which is 1/256 of a 1/16. Any error under
    // that is already treated as exact by the ray. A quarter of it, 1e-3 of a 1/16, is well inside.
    // The 0.0001 degree sign board turn and float noise are both far below this. A real offset such
    // as a third of a 1/16 (0.33333) is far above it, stays as is, and still rounds outward.
    private static final float GRID_EPSILON = 1e-3f;
    // A resolver lives for one section harvest (16^3 cells), never across model/atlas lifetimes.
    private static final int MAX_CACHE_ENTRIES = 16 * 16 * 16;
    private static final Comparator<Box> ORDER = Comparator.comparingInt(Box::x0).thenComparingInt(Box::y0)
            .thenComparingInt(Box::z0).thenComparingInt(Box::x1).thenComparingInt(Box::y1).thenComparingInt(Box::z1);
    // One warning flag per loaded model class, with class unloading supported; no state/position keys.
    private static final ClassValue<AtomicBoolean> REPORTED_FAILURE = new ClassValue<>() {
        @Override protected AtomicBoolean computeValue(Class<?> type) { return new AtomicBoolean(); }
    };
    private VoxelModelShape() { }


    /** Caller must retain its harvest lease through all calls and then discard this resolver. */
    static final class Resolver {
        private final Supplier<Renderer> renderer;
        private final Function<BlockState, @Nullable BlockStateModel> models;
        private final Function<QuadView, @Nullable TextureAtlasSprite> sprites;
        private final HashMap<Object, Optional<List<VoxelShapeClassifier.PackedBox>>> cache = new HashMap<>();

        Resolver() {
            this(Renderer::get, state -> {
                var client = Minecraft.getInstance();
                return client == null ? null : client.getModelManager().getBlockStateModelSet().get(state);
            }, quad -> {
                if (quad.atlas() == null) return null;
                var atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(quad.atlas().getTextureLocation());
                return ((FabricTextureAtlas) atlas).spriteFinder().find(quad);
            });
        }

        Resolver(Renderer renderer, Function<BlockState, @Nullable BlockStateModel> models,
                 Function<QuadView, @Nullable TextureAtlasSprite> sprites) {
            this(() -> renderer, models, sprites);
        }

        private Resolver(Supplier<Renderer> renderer, Function<BlockState, @Nullable BlockStateModel> models,
                         Function<QuadView, @Nullable TextureAtlasSprite> sprites) {
            this.renderer = renderer; this.models = models; this.sprites = sprites;
        }

        @Nullable List<VoxelShapeClassifier.PackedBox> resolve(BlockState state, BlockAndTintGetter world, BlockPos pos) {
            BlockStateModel model = null;
            try {
                // The render pipeline applies the state's offset outside emitQuads; it is not in the mesh.
                if (state.hasOffsetFunction()) return null;
                model = models.apply(state);
                if (!(model instanceof FabricBlockStateModel geometry)) return null;
                return resolveGeometry(geometry, state, world, pos);
            } catch (RuntimeException failure) {
                // Refinement is optional. A custom emitter's unsupported harvest context must not abort
                // section backfill, and an exception after partial emission must publish no new boxes.
                Class<?> type = model == null ? VoxelModelShape.class : model.getClass();
                if (REPORTED_FAILURE.get(type).compareAndSet(false, true))
                    FornaxMod.LOGGER.warn("[Fornax] Optional voxel model geometry failed for {}; retaining selection shape", type.getName(), failure);
                return null;
            }
        }

        private @Nullable List<VoxelShapeClassifier.PackedBox> resolveGeometry(FabricBlockStateModel geometry,
                BlockState state, BlockAndTintGetter world, BlockPos pos) {
            var at = pos.immutable();
            long seed = state.getSeed(at);
            // Fabric defines equal keys as exact final emitted geometry, even across model instances.
            // Null explicitly forbids reuse. Key creation cannot consume the emission random stream.
            Object key = geometry.createGeometryKey(world, at, state, RandomSource.create(seed));
            if (key != null) {
                var cached = cache.get(key);
                if (cached != null) return cached.orElse(null);
            }
            var capture = new Capture(sprites);
            // The active renderer owns the emitter; the callback reduces its reusable quad immediately.
            // There is no intermediate mesh and failed/over-budget captures retain no further geometry.
            var emitter = renderer.get().quadEmitter(capture::accept);
            geometry.emitQuads(emitter, world, at, state, RandomSource.create(seed), direction -> false);
            var boxes = capture.result();
            if (key != null && cache.size() < MAX_CACHE_ENTRIES) cache.put(key, Optional.ofNullable(boxes));
            return boxes;
        }
    }

    private static final class Capture {
        private final Function<QuadView, @Nullable TextureAtlasSprite> sprites;
        private final Set<Face> faces = new HashSet<>(), overlays = new HashSet<>();
        private int count;
        private boolean invalid;
        Capture(Function<QuadView, @Nullable TextureAtlasSprite> sprites) { this.sprites = sprites; }
        void accept(QuadView quad) {
            if (invalid) return;
            if (++count > MAX_QUADS) { invalid = true; return; }
            float[] position = new float[12];
            for (int v = 0; v < 4; v++) for (int c = 0; c < 3; c++) position[v * 3 + c] = quad.posByIndex(v, c);
            Face face = rectangle(position);
            if (face == null) { invalid = true; return; }
            boolean opaque = VoxelFaceOpacity.opaque(quad, sprites);
            (opaque ? faces : overlays).add(face);
            if (faces.size() > MAX_FACES || overlays.size() > MAX_FACES) invalid = true;
        }
        @Nullable List<VoxelShapeClassifier.PackedBox> result() {
            return invalid ? null : reconstruct(faces, overlays);
        }
    }

    /** {@code SectionHarvester} hands this a state's baked model parts, collected with no world and
     * no position, once for each distinct state in a section. Tests call it with made-up parts
     * lists. */
    static @Nullable List<VoxelShapeClassifier.PackedBox> reconstruct(List<BlockStateModelPart> parts) {
        Set<Face> faces = new HashSet<>(), overlays = new HashSet<>();
        int count = 0;
        for (var part : parts) for (int side = 0; side <= Direction.values().length; side++) {
            for (var quad : part.getQuads(side == Direction.values().length ? null : Direction.values()[side])) {
                if (++count > MAX_QUADS) return null;
                float[] position = new float[12];
                for (int v = 0; v < 4; v++) for (int c = 0; c < 3; c++) position[v * 3 + c] = quad.position(v).get(c);
                Face face = rectangle(position);
                if (face == null) return null;
                (VoxelFaceOpacity.opaque(quad) ? faces : overlays).add(face);
                if (faces.size() > MAX_FACES || overlays.size() > MAX_FACES) return null;
            }
        }
        return reconstruct(faces, overlays);
    }

    private static @Nullable List<VoxelShapeClassifier.PackedBox> reconstruct(Set<Face> faces, Set<Face> overlays) {
        if (faces.isEmpty()) return null;
        var candidates = new HashSet<Box>();
        for (var negative : faces) if (negative.sign == -1) for (var positive : faces) {
            if (positive.sign != 1 || negative.axis != positive.axis || negative.plane >= positive.plane
                    || negative.s0 != positive.s0 || negative.s1 != positive.s1
                    || negative.t0 != positive.t0 || negative.t1 != positive.t1) continue;
            int[] lo = new int[3], hi = new int[3];
            lo[negative.axis] = negative.plane; hi[negative.axis] = positive.plane;
            lo[(negative.axis + 1) % 3] = negative.s0; hi[(negative.axis + 1) % 3] = negative.s1;
            lo[(negative.axis + 2) % 3] = negative.t0; hi[(negative.axis + 2) % 3] = negative.t1;
            candidates.add(new Box(lo[0],lo[1],lo[2],hi[0],hi[1],hi[2]));
        }
        var certified = new HashSet<Box>();
        boolean changed;
        do {
            var next = new ArrayList<Box>();
            for (var box : candidates) if (!certified.contains(box) && closed(box, faces, certified)) next.add(box);
            changed = certified.addAll(next);
        } while (changed);
        // Every rendered rectangle must be accounted for, even when another body was certifiable.
        if (certified.isEmpty() || faces.stream().anyMatch(face -> certified.stream().noneMatch(box -> face.equals(box.face(face.axis, face.sign))))) return null;
        if (overlays.stream().anyMatch(overlay -> faces.stream().noneMatch(face -> face.covers(overlay)))) return null;
        var boxes = new ArrayList<>(certified);
        boxes.sort(ORDER);
        // Removing a contained body and merging identical cross-sections preserves the exact union.
        do {
            changed = false;
            outer: for (int i = 0; i < boxes.size(); i++) for (int j = i + 1; j < boxes.size(); j++) {
                Box a = boxes.get(i), b = boxes.get(j), union = a.union(b);
                if (union != null) {
                    boxes.set(i, union); boxes.remove(j); boxes.sort(ORDER); changed = true; break outer;
                }
            }
        } while (changed);
        if (boxes.size() > VoxelShapeClassifier.MAX_BOXES) return null;
        return boxes.stream().map(box -> new VoxelShapeClassifier.PackedBox(box.x0,box.y0,box.z0,box.x1,box.y1,box.z1)).toList();
    }

    /** Turns one drawn quad into an axis-aligned {@link Face} in 1/16 units, or null when the
     * quad cannot be shown to be the face of a box. A value within {@link #GRID_EPSILON} of a grid
     * line counts as on it. The face plane rounds away from the solid: up for a face that points
     * up or out, down for the other side. The face edges round outward: low side down, high side
     * up. All values are cut to 0..16, so a plane past the cell edge is cut to the edge. A quad with
     * no shared axis is tipped and is dropped here. A quad with a shared axis whose corners are not
     * at the ends of its edges is turned flat and is dropped by the corner check. A face with no
     * width or height left after the cut is dropped. */
    private static @Nullable Face rectangle(float[] position) {
        float[][] p = new float[4][3];
        for (int vertex = 0; vertex < 4; vertex++) for (int c = 0; c < 3; c++) {
            float sixteenths = position[vertex * 3 + c] * 16;
            float nearest = Math.round(sixteenths);
            p[vertex][c] = Math.abs(sixteenths - nearest) < GRID_EPSILON ? nearest : sixteenths;
        }
        int axis = -1;
        for (int c = 0; c < 3; c++) {
            float lo = min4(p, c), hi = max4(p, c);
            if (hi - lo < GRID_EPSILON) { axis = c; break; }
        }
        if (axis < 0) return null;
        int s = (axis + 1) % 3, t = (axis + 2) % 3;
        float loS = min4(p, s), hiS = max4(p, s), loT = min4(p, t), hiT = max4(p, t);
        // The winding sign below is a cross product of two corners. Each corner can carry noise up
        // to GRID_EPSILON, in opposite directions, so an edge under twice that could be noise alone.
        // Asking for a full 2 * GRID_EPSILON keeps the sign off pure noise.
        if (hiS - loS < 2 * GRID_EPSILON || hiT - loT < 2 * GRID_EPSILON) return null;
        int[] corners = new int[4]; int mask = 0;
        for (int v = 0; v < 4; v++) {
            boolean atLoS = Math.abs(p[v][s] - loS) < GRID_EPSILON, atHiS = Math.abs(p[v][s] - hiS) < GRID_EPSILON;
            boolean atLoT = Math.abs(p[v][t] - loT) < GRID_EPSILON, atHiT = Math.abs(p[v][t] - hiT) < GRID_EPSILON;
            if ((!atLoS && !atHiS) || (!atLoT && !atHiT)) return null;
            corners[v] = (atHiS ? 1 : 0) | (atHiT ? 2 : 0); mask |= 1 << corners[v];
        }
        if (mask != 15 || (corners[0] ^ corners[2]) != 3 || (corners[1] ^ corners[3]) != 3) return null;
        float winding = (p[1][s]-p[0][s])*(p[2][t]-p[0][t]) - (p[1][t]-p[0][t])*(p[2][s]-p[0][s]);
        int sign = winding >= 0 ? 1 : -1;
        int plane = sign > 0 ? ceilClamped(max4(p, axis)) : floorClamped(min4(p, axis));
        int roundedS0 = floorClamped(loS), roundedS1 = ceilClamped(hiS);
        int roundedT0 = floorClamped(loT), roundedT1 = ceilClamped(hiT);
        if (roundedS0 >= roundedS1 || roundedT0 >= roundedT1) return null;
        return new Face(axis, sign, plane, roundedS0, roundedT0, roundedS1, roundedT1);
    }

    private static float min4(float[][] p, int c) {
        return Math.min(Math.min(p[0][c], p[1][c]), Math.min(p[2][c], p[3][c]));
    }

    private static float max4(float[][] p, int c) {
        return Math.max(Math.max(p[0][c], p[1][c]), Math.max(p[2][c], p[3][c]));
    }

    private static int floorClamped(float value) {
        return (int) Math.max(0, Math.min(16, Math.floor(value)));
    }

    private static int ceilClamped(float value) {
        return (int) Math.max(0, Math.min(16, Math.ceil(value)));
    }

    private static boolean closed(Box box, Set<Face> faces, Set<Box> certified) {
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1,1}) {
            Face face = box.face(axis, sign);
            if (!faces.contains(face) && certified.stream().noneMatch(body -> body.buries(face))) return false;
        }
        return true;
    }

    private record Face(int axis, int sign, int plane, int s0, int t0, int s1, int t1) {
        boolean covers(Face other) {
            return axis == other.axis && sign == other.sign && plane == other.plane
                    && s0 <= other.s0 && t0 <= other.t0 && s1 >= other.s1 && t1 >= other.t1;
        }
    }
    private record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
        int lo(int axis) { return axis == 0 ? x0 : axis == 1 ? y0 : z0; }
        int hi(int axis) { return axis == 0 ? x1 : axis == 1 ? y1 : z1; }
        Face face(int axis,int sign) { return new Face(axis,sign,sign>0?hi(axis):lo(axis),lo((axis+1)%3),lo((axis+2)%3),hi((axis+1)%3),hi((axis+2)%3)); }
        boolean buries(Face f) {
            int s=(f.axis+1)%3,t=(f.axis+2)%3;
            return lo(f.axis)<f.plane && hi(f.axis)>f.plane && lo(s)<=f.s0 && hi(s)>=f.s1 && lo(t)<=f.t0 && hi(t)>=f.t1;
        }
        boolean contains(Box b) {
            for (int axis=0;axis<3;axis++) if(lo(axis)>b.lo(axis) || hi(axis)<b.hi(axis))return false;
            return true;
        }
        @Nullable Box union(Box b) {
            if(contains(b))return this;
            if(b.contains(this))return b;
            int differences=0;
            for(int axis=0;axis<3;axis++) {
                if(lo(axis)==b.lo(axis) && hi(axis)==b.hi(axis))continue;
                if(++differences>1 || Math.max(lo(axis),b.lo(axis))>Math.min(hi(axis),b.hi(axis)))return null;
            }
            return new Box(Math.min(x0,b.x0),Math.min(y0,b.y0),Math.min(z0,b.z0),Math.max(x1,b.x1),Math.max(y1,b.y1),Math.max(z1,b.z1));
        }
    }
}
