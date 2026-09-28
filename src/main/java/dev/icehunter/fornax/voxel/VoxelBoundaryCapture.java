package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.FornaxMod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Immutable optical facts observed during the renderer's own model emission.
 * The harvest worker never invokes a live model callback. Captures with no complete box proof
 * explicitly remove the fallback proof for that position. */
public final class VoxelBoundaryCapture {
    private static final Store STORE = new Store();
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong ACCEPTED = new AtomicLong(), REJECTED = new AtomicLong();
    private VoxelBoundaryCapture() { }

    public static void beginSection(@Nullable Level level, SectionPos owner) {
        ACTIVE.remove();
        // Keep slot ownership and collector activation atomic with window reuse. Otherwise an
        // old build can begin after retirement and consume the replacement owner's remesh request.
        synchronized (dev.icehunter.fornax.pass.compute.VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (level == null || !VoxelWindow.boundaryCaptureEnabled() || !VoxelHarvestLifecycle.isAvailable()) return;
            int slot = VoxelWindow.slotFor(owner.x(), owner.y(), owner.z());
            if (slot < 0) return;
            ACTIVE.set(STORE.begin(slot, owner, level, VoxelHarvestLifecycle.generation()));
        }
    }

    public static void finishSection(boolean completed) {
        Scope scope = ACTIVE.get();
        ACTIVE.remove();
        if (scope == null || !VoxelHarvestLifecycle.isCurrent(scope.generation)
                || VoxelWindow.slotFor(scope.owner.x(), scope.owner.y(), scope.owner.z()) != scope.slot) return;
        if (!completed) { STORE.retry(scope); return; }
        if (STORE.publish(scope)) VoxelWindow.queueMeshTriggeredHarvest((Level) scope.world, scope.owner);
    }

    /** Slot reuse invalidates contextual facts even if the displaced section never remeshes. */
    static void retireSlots(java.util.Collection<Integer> slots) { STORE.retireSlots(slots); }

    static @Nullable Snapshot snapshot(@Nullable Object world, int x, int y, int z, long generation) {
        SectionPos owner = SectionPos.of(x >> 4, y >> 4, z >> 4);
        int slot = VoxelWindow.slotFor(owner.x(), owner.y(), owner.z());
        return slot < 0 ? null : STORE.snapshot(slot, owner, world, generation);
    }

    public static void clear() {
        STORE.clear();
        long accepted = ACCEPTED.getAndSet(0), rejected = REJECTED.getAndSet(0);
        if (accepted + rejected > 0) FornaxMod.LOGGER.info(
                "[Fornax] Emitted voxel boundaries retired: accepted={}, rejected={}", accepted, rejected);
        REPORTED.clear();
    }

    static void enqueueRemesh(Level level, List<SectionPos> nearestFirst) {
        if (!VoxelWindow.boundaryCaptureEnabled()) return;
        for (SectionPos owner : nearestFirst) {
            int slot = VoxelWindow.slotFor(owner.x(), owner.y(), owner.z());
            if (slot >= 0) STORE.request(slot, owner, level, VoxelHarvestLifecycle.generation());
        }
    }

    /** Render-thread pump: one dirty-section request per frame bounds startup work. Meshing itself
     * stays on the renderer's existing scheduler. Completion queues harvest, never another remesh. */
    static void requestPending(Level level) {
        if (!VoxelWindow.boundaryCaptureEnabled()) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level != level) return;
        Request request;
        while ((request = STORE.poll()) != null) {
            if (request.world != level || !VoxelHarvestLifecycle.isCurrent(request.generation)
                    || VoxelWindow.slotFor(request.owner.x(), request.owner.y(), request.owner.z()) != request.slot) continue;
            client.levelExtractor.setSectionDirty(request.owner.x(), request.owner.y(), request.owner.z());
            break;
        }
    }

    /** The callback invokes the original operation exactly once with the supplied early-cull test. */
    public static void emit(FabricBlockStateModel model, QuadEmitter emitter, BlockAndTintGetter world,
                            BlockPos pos, BlockState state, Predicate<Direction> cull,
                            Consumer<Predicate<Direction>> original) {
        Scope scope = ACTIVE.get();
        if (scope == null || !scope.owner.equals(SectionPos.of(pos))
                || !VoxelHarvestLifecycle.isCurrent(scope.generation)
                || (state.getLightDampening() >= 15
                    && !((net.minecraft.client.renderer.block.dispatch.BlockStateModel) model)
                            .hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT))) {
            original.accept(cull);
            return;
        }
        // The model, sprite finder and tint source are accessed only inside the renderer's own
        // invocation and an atlas lease. No world, random or emitter is retained by a fact.
        try (var lease = VoxelHarvestLifecycle.tryAcquire(scope.generation)) {
            if (lease == null) { original.accept(cull); return; }
            if (state.hasOffsetFunction()) {
                original.accept(cull);
                scope.add(pos, state, null);
                report(model, "position-offset", false);
                return;
            }
            Object key;
            int tint;
            try {
                var source = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
                tint = source == null ? -1 : source.colorInWorld(state, world, pos) | 0xff000000;
                key = model.createGeometryKey(world, pos, state, RandomSource.create(state.getSeed(pos)));
            } catch (RuntimeException failure) {
                original.accept(cull);
                scope.add(pos, state, null);
                report(model, "context-unavailable", false);
                return;
            }
            var cacheKey = key == null ? null : new GeometryKey(state, key, tint);
            Optional<Boundary> cached = cacheKey == null ? null : scope.keyed.get(cacheKey);
            if (cached != null) {
                original.accept(cull);
                scope.add(pos, state, cached.orElse(null));
                return;
            }
            List<VoxelShapeClassifier.PackedBox> candidateBoxes = List.of();
            try {
                var candidate = VoxelShapeClassifier.classify(state);
                if (candidate.kind() == VoxelShapeKind.PARTIAL) candidateBoxes = candidate.boxes();
            } catch (RuntimeException unavailable) {
                // A state shape only proposes boxes; emitted geometry can still prove a volume.
                report(model, "shape-candidate-unavailable", false, unavailable);
            }
            Capture capture = new Capture(VoxelAtlasLookup::sprite, tint, candidateBoxes);
            observe(emitter, cull, original, capture);
            Boundary boundary = capture.result();
            scope.add(pos, state, boundary);
            if (cacheKey != null && scope.keyed.size() < SectionHarvester.MAX_PALETTE_ENTRIES)
                scope.keyed.put(cacheKey, Optional.ofNullable(boundary));
            report(model, boundary == null ? capture.reason : "closed", boundary != null, capture.failure());
        }
    }

    /** Fabric defines the false early-cull predicate as complete model geometry. Its transforms
     * run last-pushed first, so this outer observer sees the final model's positions and UVs.
     * Late culling uses the final cullFace, while the normal renderer keeps its own culling too. */
    static void observe(QuadEmitter emitter, Predicate<Direction> cull,
                        Consumer<Predicate<Direction>> original, Capture capture) {
        emitter.pushTransform(quad -> {
            capture.accept(quad);
            Direction face = quad.cullFace();
            return face == null || !cull.test(face);
        });
        try { original.accept(face -> false); }
        finally { emitter.popTransform(); }
    }

    private static void report(Object model, String reason, boolean accepted) {
        report(model, reason, accepted, null);
    }

    private static void report(Object model, String reason, boolean accepted, @Nullable RuntimeException failure) {
        long yes = accepted ? ACCEPTED.incrementAndGet() : ACCEPTED.get();
        long no = accepted ? REJECTED.get() : REJECTED.incrementAndGet();
        String key = model.getClass().getName() + ":" + reason;
        if (!REPORTED.add(key)) return;
        if (failure == null) FornaxMod.LOGGER.info(
                    "[Fornax] Emitted voxel boundary model={} result={} accepted={} rejected={}",
                    model.getClass().getName(), reason, yes, no);
        else FornaxMod.LOGGER.warn(
                    "[Fornax] Emitted voxel boundary model={} result={} accepted={} rejected={}",
                    model.getClass().getName(), reason, yes, no, failure);
    }

    record GeometryKey(BlockState state, Object geometry, int tint) { }

    /** Contains no sprite, world, callback or mutable quad reference. */
    static final class Boundary {
        final VoxelShapeKind kind;
        final List<VoxelShapeClassifier.PackedBox> boxes;
        private final int[] words;
        private final int[] faceColors;
        final boolean cutout;
        private final float[] uvRect;
        final float extinction;
        Boundary(VoxelShapeKind kind, List<VoxelShapeClassifier.PackedBox> boxes, int[] words, int[] faceColors,
                 boolean cutout, float[] uvRect, float extinction) {
            this.kind = kind; this.boxes = List.copyOf(boxes); this.words = words.clone(); this.faceColors = faceColors.clone();
            this.cutout = cutout; this.uvRect = uvRect.clone(); this.extinction = extinction;
        }
        int[] words() { return words.clone(); }
        int[] faceColors() { return faceColors.clone(); }
        float[] uvRect() { return uvRect.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Boundary b && kind == b.kind && boxes.equals(b.boxes)
                    && Arrays.equals(words, b.words) && Arrays.equals(faceColors, b.faceColors)
                    && cutout == b.cutout && Arrays.equals(uvRect, b.uvRect) && Float.compare(extinction, b.extinction) == 0;
        }
        @Override public int hashCode() {
            int hash = 31 * (31 * (31 * kind.hashCode() + boxes.hashCode()) + Arrays.hashCode(words)) + Arrays.hashCode(faceColors);
            return 31 * (31 * (31 * hash + Boolean.hashCode(cutout)) + Arrays.hashCode(uvRect)) + Float.hashCode(extinction);
        }
    }

    static final class Capture {
        // One face and one coincident overlay for every box accepted by the palette ABI.
        private static final int MAX_QUADS = 12 * VoxelShapeClassifier.MAX_BOXES;
        private final Function<QuadView, @Nullable TextureAtlasSprite> sprites;
        private final int tint;
        private final List<VoxelShapeClassifier.PackedBox> candidateBoxes;
        private final List<BakedQuad> quads = new ArrayList<>();
        private final int[] colors = new int[6];
        private final boolean[] seen = new boolean[6], badColor = new boolean[6];
        private boolean invalid;
        private String reason = "incomplete-or-nonbox-geometry";
        private @Nullable RuntimeException failure;
        Capture(Function<QuadView, @Nullable TextureAtlasSprite> sprites, int tint) {
            this(sprites, tint, List.of());
        }
        Capture(Function<QuadView, @Nullable TextureAtlasSprite> sprites, int tint,
                List<VoxelShapeClassifier.PackedBox> candidateBoxes) {
            this.sprites = sprites; this.tint = tint; this.candidateBoxes = List.copyOf(candidateBoxes);
        }
        void accept(QuadView quad) {
            if (invalid) return;
            if (quads.size() >= MAX_QUADS) { invalid = true; reason = "quad-budget"; return; }
            try {
                if (quad.atlas() != QuadAtlas.BLOCK) { invalid = true; reason = "non-block-atlas"; return; }
                var layer = quad.chunkLayer();
                if (layer != ChunkSectionLayer.SOLID && layer != ChunkSectionLayer.CUTOUT
                        && layer != ChunkSectionLayer.TRANSLUCENT) { invalid = true; reason = "unsupported-layer"; return; }
                if (quad.tintIndex() > 0) { invalid = true; reason = "unsupported-tint-layer"; return; }
                Direction face = quad.lightFace();
                int side = face.get3DDataValue(), color = quad.color(0);
                for (int v = 0; v < 4; v++) badColor[side] |= quad.color(v) != color;
                badColor[side] |= (color >>> 24) != 255;
                if (seen[side]) badColor[side] |= colors[side] != color;
                seen[side] = true; colors[side] = color;
                var sprite = sprites.apply(quad);
                if (sprite == null) { invalid = true; reason = "missing-sprite"; return; }
                Vector3f[] p = new Vector3f[4]; long[] uv = new long[4];
                for (int v = 0; v < 4; v++) {
                    p[v] = new Vector3f(quad.x(v), quad.y(v), quad.z(v));
                    uv[v] = UVPair.pack(quad.u(v), quad.v(v));
                }
                quads.add(new BakedQuad(p[0],p[1],p[2],p[3],uv[0],uv[1],uv[2],uv[3],face,
                        new BakedQuad.MaterialInfo(sprite,layer,null,quad.tintIndex(),true,0)));
            } catch (RuntimeException unsupported) {
                invalid = true; reason = "quad-unavailable"; failure = unsupported;
            }
        }
        @Nullable RuntimeException failure() { return failure; }
        @Nullable Boundary result() {
            try { return reduce(); }
            catch (RuntimeException unsupported) { reason = "material-unavailable"; failure = unsupported; return null; }
        }
        private @Nullable Boundary reduce() {
            if (invalid || quads.isEmpty()) return null;
            for (boolean unsupported : badColor) if (unsupported) { reason = "nonuniform-vertex-color"; return null; }
            var parts = parts(quads);
            // Geometry reconstruction must ignore texture alpha: it proves volume boundaries,
            // never opaque visibility. A second exact-grid proof rejects any outward rounding.
            VoxelShapeKind kind = VoxelShapeKind.FULL;
            List<VoxelShapeClassifier.PackedBox> boxes = List.of();
            if (!VoxelBoundaryGeometry.certifies(parts, kind, boxes)) {
                kind = VoxelShapeKind.PARTIAL;
                // The partial sidecar has no opaque backing coverage proof. Mixed solid shells
                // cannot claim a homogeneous boundary through this representation.
                if (quads.stream().anyMatch(q -> q.materialInfo().layer() == ChunkSectionLayer.SOLID)) {
                    reason = "partial-solid-backing"; return null;
                }
                // Flush-connected bodies omit their internal end caps. Their state shape can
                // propose the union, but every exterior grid face still needs emitted coverage.
                if (VoxelBoundaryGeometry.certifies(parts, kind, candidateBoxes)) boxes = candidateBoxes;
                else {
                    var geometry = new ArrayList<BakedQuad>();
                    for (var q : quads) geometry.add(new BakedQuad(q.position0(),q.position1(),q.position2(),q.position3(),
                            q.packedUV0(),q.packedUV1(),q.packedUV2(),q.packedUV3(),q.direction(),
                            new BakedQuad.MaterialInfo(null,ChunkSectionLayer.SOLID,null,-1,true,0)));
                    boxes = VoxelModelShape.reconstruct(parts(geometry));
                }
                if (boxes == null) return null;
            }
            if (!VoxelBoundaryGeometry.certifies(parts, kind, boxes)) return null;
            int[] words = VoxelFaceTexture.withBoundaryFacts(kind == VoxelShapeKind.FULL
                    ? VoxelFaceTexture.pack(parts, tint) : VoxelFaceTexture.packPartial(parts, tint), parts, kind, boxes, true);
            if (kind == VoxelShapeKind.FULL) for (var quad : quads) {
                // A partial opaque backing cannot be described by the full-face coverage bit.
                // Keep that mixed geometry unknown rather than erase its opaque component.
                if (VoxelFaceOpacity.opaque(quad) && (words[quad.direction().get3DDataValue()
                        * VoxelFaceTexture.FACE_WORDS] & VoxelFaceTexture.OPAQUE_COVERAGE) == 0) {
                    reason = "unrepresentable-opaque-coverage"; return null;
                }
            }
            int[] measured = new int[6];
            for (int face = 0; face < 6; face++) {
                int base = face * VoxelFaceTexture.FACE_WORDS;
                int atlasColor = FaceColorResolver.resolve(parts, Direction.values()[face], tint, FaceColorResolver::averageQuadColor);
                {
                    int rgb = 0;
                    int faceRgb = 0;
                    for (int shift = 0; shift < 24; shift += 8) {
                        // Normalized byte multiplication, rounded to the nearest byte.
                        int channel = (((words[base] >>> shift) & 255) * ((colors[face] >>> shift) & 255) + 127) / 255;
                        rgb |= channel << shift;
                        faceRgb |= ((((atlasColor >>> shift) & 255) * ((colors[face] >>> shift) & 255) + 127) / 255) << shift;
                    }
                    words[base] = (words[base] & 0xff000000) | rgb;
                    measured[face] = (atlasColor & 0xff000000) | faceRgb;
                }
            }
            boolean cutout = quads.stream().anyMatch(q -> q.materialInfo().layer() == ChunkSectionLayer.CUTOUT);
            float[] rect = cutout ? FaceColorResolver.resolveCutoutRect(parts) : SectionPalette.NO_UV_RECT;
            if (rect == null) { reason = "missing-cutout-rect"; return null; }
            float extinction = cutout && kind == VoxelShapeKind.FULL ? FoliageDensityResolver.resolveExtinction(parts) : 0f;
            return new Boundary(kind, boxes, words, measured, cutout, rect, extinction);
        }
    }

    private static List<BlockStateModelPart> parts(List<BakedQuad> quads) {
        return List.of(new BlockStateModelPart() {
            public List<BakedQuad> getQuads(@Nullable Direction face) { return face == null ? quads : List.of(); }
            public boolean useAmbientOcclusion() { return false; }
            public Material.Baked particleMaterial() { return null; }
            public int materialFlags() { return 0; }
        });
    }

    record Cell(BlockState state, @Nullable Boundary boundary) { }
    record Request(int slot, SectionPos owner, Object world, long generation) { }

    static final class Snapshot {
        private final Object world;
        private final SectionPos owner;
        private final long generation;
        private final byte[] indices;
        private final List<Cell> cells;
        Snapshot(Scope scope) {
            world = scope.world; owner = scope.owner; generation = scope.generation;
            indices = scope.indices.clone(); cells = List.copyOf(scope.cells);
        }
        @Nullable Cell cell(int index, BlockState state) {
            int id = Byte.toUnsignedInt(indices[index]);
            if (id == 0) return null;
            if (id == 255) return new Cell(state, null); // Capacity failure is explicit, never a stale fallback proof.
            Cell cell = cells.get(id - 1);
            return cell.state == state ? cell : null;
        }
    }

    static final class Scope {
        final int slot;
        final SectionPos owner;
        final Object world;
        final long generation, epoch, serial;
        final byte[] indices = new byte[16 * 16 * 16];
        final List<Cell> cells = new ArrayList<>();
        final Map<Cell, Integer> ids = new HashMap<>();
        final Map<GeometryKey, Optional<Boundary>> keyed = new HashMap<>();
        Scope(int slot, SectionPos owner, Object world, long generation, long epoch, long serial) {
            this.slot=slot; this.owner=owner; this.world=world; this.generation=generation; this.epoch=epoch; this.serial=serial;
        }
        void add(BlockPos pos, BlockState state, @Nullable Boundary boundary) {
            Cell cell = new Cell(state, boundary);
            Integer id = ids.get(cell);
            if (id == null) {
                if (cells.size() >= SectionHarvester.MAX_PALETTE_ENTRIES) {
                    indices[((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15)] = (byte) 255;
                    report(this, "section-fact-budget", false);
                    return;
                }
                cells.add(cell); id = cells.size(); ids.put(cell, id);
            }
            indices[((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15)] = id.byteValue();
        }
    }

    /** One snapshot per toroidal window slot; a new build invalidates its predecessor immediately. */
    static final class Store {
        private long epoch, serial;
        private final Map<Integer, Long> newest = new HashMap<>();
        private final Map<Integer, Snapshot> published = new HashMap<>();
        private final Map<Integer, Request> requested = new HashMap<>();
        private final LinkedHashMap<Integer, Request> pending = new LinkedHashMap<>();
        synchronized void request(int slot, SectionPos owner, Object world, long generation) {
            var request = new Request(slot, owner, world, generation);
            if (snapshot(slot, owner, world, generation) != null || request.equals(requested.get(slot))) return;
            requested.put(slot, request); pending.put(slot, request);
        }
        synchronized @Nullable Request poll() {
            if (pending.isEmpty()) return null;
            var first = pending.entrySet().iterator();
            var request = first.next().getValue(); first.remove(); return request;
        }
        synchronized Scope begin(int slot, SectionPos owner, Object world, long generation) {
            Scope scope = new Scope(slot, owner, world, generation, epoch, ++serial);
            newest.put(slot, scope.serial); published.remove(slot);
            pending.remove(slot);
            return scope;
        }
        synchronized boolean publish(Scope scope) {
            if (scope.epoch != epoch || !Long.valueOf(scope.serial).equals(newest.get(scope.slot))) return false;
            published.put(scope.slot, new Snapshot(scope));
            return true;
        }
        synchronized void retry(Scope scope) {
            if (scope.epoch != epoch || !Long.valueOf(scope.serial).equals(newest.get(scope.slot))) return;
            newest.remove(scope.slot);
            requested.remove(scope.slot);
            request(scope.slot, scope.owner, scope.world, scope.generation);
        }
        synchronized void retireSlots(java.util.Collection<Integer> slots) {
            for (int slot : slots) {
                newest.remove(slot); published.remove(slot); requested.remove(slot); pending.remove(slot);
            }
        }
        synchronized @Nullable Snapshot snapshot(int slot, SectionPos owner, @Nullable Object world, long generation) {
            Snapshot snapshot = published.get(slot);
            return snapshot != null && snapshot.owner.equals(owner) && snapshot.world == world
                    && snapshot.generation == generation ? snapshot : null;
        }
        synchronized void clear() { epoch++; newest.clear(); published.clear(); requested.clear(); pending.clear(); }
    }
}
