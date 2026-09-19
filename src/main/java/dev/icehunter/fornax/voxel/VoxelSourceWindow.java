package dev.icehunter.fornax.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Sparse committed source cells, independent of camera position. Mutations hold the voxel upload
 * lock. Capacity bounds publication, while stable admission preserves existing sources on overflow.
 * Unknown and deferred sources are counted separately; neither invalidates the admitted source set. */
public final class VoxelSourceWindow {
    public static final String TARGET = "voxelSourceWindow";
    // The engine's largest tier-zero window has 33 sections per axis. A source publication admits
    // one section's cell count; ranges provide bounded section lookup without a camera-selected list.
    public static final int MAX_SLOTS = 33 * 33 * 33, CAPACITY = 16 * 16 * 16;
    public static final int HEADER_WORDS = 16, RANGE_WORDS = 2, CELL_WORDS = 8;
    public static final int CELL_BASE = HEADER_WORDS + MAX_SLOTS * RANGE_WORDS;
    public static final int BYTE_SIZE = (CELL_BASE + CAPACITY * CELL_WORDS) * Integer.BYTES;
    public static final int ABI_VERSION = 2, UNKNOWN_SOURCE = 1 << 6;
    public record Publication(long stateVersion, ByteBuffer bytes) { }

    // Neighbour step per direction ID: down, up, north, south, west, east.
    private static final int[] FACE_DX = {0, 0, 0, 0, -1, 1};
    private static final int[] FACE_DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] FACE_DZ = {0, 0, -1, 1, 0, 0};

    private static final class Source {
        final SectionHarvester.Result result;
        final VoxelSectionState.Snapshot token;
        /** One flat rectangle of touching faces each. A lone lamp face is a run of one cell. */
        final List<VoxelFaceRuns.Run> runs;
        final BitSet admitted = new BitSet();
        final int[] facts;
        /** Which of a cell's own six faces can reach the world. Per cell, not per palette entry:
         * two blocks of the same kind sit in different places. */
        final byte[] faces = new byte[CAPACITY];
        int unknown;
        boolean pending;
        Source(SectionHarvester.Result result, VoxelSectionState.Snapshot token) {
            this.result = result; this.token = token;
            facts = paletteFacts(result);
            for (int cell = 0; cell < CAPACITY; cell++) {
                int entry = result.paletteIndices()[cell] & 255;
                int value = entry < facts.length ? facts[entry] : UNKNOWN_SOURCE;
                int mask = value & 63;
                if (mask != 0) mask &= exposedFaces(result, facts, cell);
                faces[cell] = (byte) mask;
                if ((value & UNKNOWN_SOURCE) != 0) unknown++;
            }
            runs = VoxelFaceRuns.cover(faces, result.paletteIndices(), fullFaces(result));
        }

        /** Where a run sits and which way it faces, which is what names it across a recommit. */
        int key(int run) {
            VoxelFaceRuns.Run r = runs.get(run);
            return r.cell() << 3 | r.face();
        }
    }

    /** Per palette entry and face, whether that face fills its cell. A run that spans a cell
     * before it reaches the next needs one: two torches side by side leave a gap, and a rectangle
     * over both would light the gap. An entry with no box list fills its cell, which is what a
     * cube and a fluid both do. */
    private static boolean[][] fullFaces(SectionHarvester.Result result) {
        var entries = result.palette().entries();
        boolean[][] full = new boolean[entries.size()][6];
        for (int entry = 0; entry < entries.size(); entry++) {
            if (entries.get(entry).boxes().isEmpty()) Arrays.fill(full[entry], true);
        }
        return full;
    }

    /**
     * The faces of one cell that are not buried.
     *
     * A face pressed against an opaque cube lights that cube's inside, which nothing can see. A
     * face pressed against another face that emits back lights nothing either: the two shine into
     * each other. Dropping both is what keeps a lava lake from being one light per cell, and it is
     * what stops an ore in solid ground casting into the room next door.
     *
     * A neighbour outside this section is not read, so its face is kept. Sections are harvested on
     * their own and reaching across one would make the answer depend on arrival order.
     */
    private static int exposedFaces(SectionHarvester.Result result, int[] facts, int cell) {
        var entries = result.palette().entries();
        int x = cell & 15, y = cell >> 8, z = (cell >> 4) & 15;
        int mask = 63;
        for (int face = 0; face < 6; face++) {
            int nx = x + FACE_DX[face], ny = y + FACE_DY[face], nz = z + FACE_DZ[face];
            if ((nx | ny | nz) < 0 || nx > 15 || ny > 15 || nz > 15) continue;
            int neighbour = result.paletteIndices()[(ny << 8) | (nz << 4) | nx] & 255;
            if (neighbour >= entries.size()) continue;
            var entry = entries.get(neighbour);
            // Glass is a full cube that light crosses, so a full shape alone does not bury a face.
            boolean sealed = entry.shapeKind() == VoxelShapeKind.FULL && !entry.lightTransmissive();
            boolean facing = neighbour < facts.length && (facts[neighbour] & 1 << (face ^ 1)) != 0;
            if (sealed || facing) mask &= ~(1 << face);
        }
        return mask;
    }
    private final int capacity;
    private final TreeMap<Integer, Source> sources = new TreeMap<>();
    private int storageGeneration, admitted;
    private long atlasGeneration, stateVersion, publishedVersion = -1;
    private @Nullable Publication pending;

    public VoxelSourceWindow() { this(CAPACITY); }
    VoxelSourceWindow(int capacity) {
        if (capacity < 1 || capacity > CAPACITY) throw new IllegalArgumentException("source cell capacity must be in 1..4096");
        this.capacity = capacity;
    }

    public void reset(int storage, long atlas) {
        if (storage <= 0 || atlas < 0) throw new IllegalArgumentException("source generations must be initialized");
        storageGeneration = storage; atlasGeneration = atlas;
        sources.clear(); admitted = 0; changed();
    }

    /** Accept only the successful-transfer callback's current geometry snapshot. No live-world reads
     * occur here: scan each replacement section once, then retain compact cell-membership bitsets. */
    public void commit(int slot, SectionHarvester.Result result, VoxelSectionState.Snapshot token) {
        if (slot < 0 || slot >= MAX_SLOTS) throw new IllegalArgumentException("source section slot outside supported window");
        if (storageGeneration == 0) throw new IllegalStateException("source inventory has no storage generation");
        if (token.storageGeneration() != storageGeneration || result.sourceSummary().atlasGeneration() != atlasGeneration) return;
        if (token.geometryRevision() <= 0 || result.paletteIndices().length != CAPACITY)
            throw new IllegalArgumentException("source snapshot requires committed geometry and 4096 cell indices");
        Source previous = sources.get(slot);
        if (previous != null && sameGeometry(previous.token, token) && sameData(previous.result, result) && !previous.pending) return;
        Source next = new Source(result, token);
        if (previous != null) {
            admitted -= previous.admitted.cardinality();
            if (sameOwner(previous.token, token)) {
                // Run indices move when the cover changes, so a run is recognised by where it is
                // and which way it faces. A lamp that was admitted stays admitted across a
                // neighbouring block's edit rather than losing its place to another section.
                Set<Integer> held = new HashSet<>();
                for (int run = previous.admitted.nextSetBit(0); run >= 0;
                        run = previous.admitted.nextSetBit(run + 1)) {
                    held.add(previous.key(run));
                }
                for (int run = 0; run < next.runs.size(); run++) {
                    if (held.contains(next.key(run))) next.admitted.set(run);
                }
                admitted += next.admitted.cardinality();
            }
        }
        sources.put(slot, next);
        refill(); changed();
    }

    /** A queued geometry replacement cannot certify old cells. Preserve its admitted identities
     * until replacement commits so transient updates cannot hand their capacity to another lamp. */
    public void pending(int slot) {
        Source source = sources.get(slot);
        if (source != null && !source.pending) { source.pending = true; changed(); }
    }

    public void invalidate(Collection<Integer> slots) {
        boolean removed = false;
        for (int slot : slots) {
            Source source = sources.remove(slot);
            if (source != null) { admitted -= source.admitted.cardinality(); removed = true; }
        }
        if (removed) { refill(); changed(); }
    }

    private void refill() {
        if (admitted == capacity) return;
        for (Source source : sources.values()) {
            if (source.pending) continue;
            for (int run = 0; run < source.runs.size(); run++) {
                if (!source.admitted.get(run)) { source.admitted.set(run); admitted++; }
                if (admitted == capacity) return;
            }
        }
    }

    private static int[] paletteFacts(SectionHarvester.Result result) {
        int size = result.palette().entries().size();
        int[] facts = new int[size];
        var policy = result.sourcePolicy();
        var evidence = result.sourceEvidence();
        for (int entry = 0; entry < size; entry++) {
            boolean mappedPolicy = policy.complete() && entry < policy.paletteSize();
            boolean available = evidence.available() && !evidence.paletteOverflow() && !evidence.incompletePalette()
                    && entry < evidence.paletteSize();
            int value = UNKNOWN_SOURCE;
            if (mappedPolicy && !policy.allows(entry)) value = 0;
            else if (mappedPolicy && available) {
                // A face whose only missing fact is animation counts as eligible, and stops
                // counting as unknown. Bits 22 to 27 carry it on for a reader that wants to know
                // which faces arrived that way; bits 16 to 21 are the missing-map mask below it.
                int intrinsicOnly = evidence.intrinsicOnlyMask(entry);
                int faces = evidence.eligibleMask(entry) | intrinsicOnly;
                int unknown = evidence.unknownMask(entry) & ~intrinsicOnly;
                value = faces | (evidence.intrinsicEmission(entry) << 8)
                        | (evidence.missingMask(entry) << 16) | (intrinsicOnly << 22);
                if (evidence.knownZeroSource(entry)) value = 0;
                else if (unknown != 0) value |= UNKNOWN_SOURCE;
            }
            facts[entry] = value;
        }
        return facts;
    }

    /** ABI2 header: version, capacity, count, storage, atlas low/high, publication low/high,
     * deferred eligible run count, unknown cell count, six reserved zeros. Section ranges start
     * at word16 and contain (first row,count), indexed by the current toroidal slot. Rows start at
     * CELL_BASE: absolute origin xyz, global palette entry, facts, geometry revision, valid, and
     * the run word. Rows are grouped by slot; row indices can change while admitted world
     * identities stay fixed.
     *
     * <p>A row is one RUN, a flat rectangle of touching faces, not one cell. The run word holds the
     * face direction in bits 0..2 and the two spans, each one less than the number of cells it
     * covers, in bits 3..6 and 7..10. A lone lamp face is a run of one cell by one. */
    public @Nullable Publication preparePublication() {
        if (storageGeneration == 0) throw new IllegalStateException("source inventory has no storage generation");
        if (stateVersion == publishedVersion) return null;
        if (pending != null) return pending;
        ByteBuffer bytes = ByteBuffer.allocateDirect(BYTE_SIZE).order(ByteOrder.nativeOrder());
        for (int word = 0; word < BYTE_SIZE / Integer.BYTES; word++) put(bytes, word, 0);
        put(bytes, 0, ABI_VERSION); put(bytes, 1, capacity); put(bytes, 3, storageGeneration);
        put(bytes, 4, (int)atlasGeneration); put(bytes, 5, (int)(atlasGeneration >>> 32));
        put(bytes, 6, (int)stateVersion); put(bytes, 7, (int)(stateVersion >>> 32));
        int row = 0, eligible = 0, unknown = 0;
        for (var item : sources.entrySet()) {
            int slot = item.getKey(); Source source = item.getValue();
            if (source.pending) continue;
            eligible += source.runs.size(); unknown += source.unknown;
            int count = source.admitted.cardinality();
            if (count == 0) continue;
            put(bytes, HEADER_WORDS + slot * RANGE_WORDS, row);
            put(bytes, HEADER_WORDS + slot * RANGE_WORDS + 1, count);
            for (int index = source.admitted.nextSetBit(0); index >= 0;
                    index = source.admitted.nextSetBit(index + 1)) {
                VoxelFaceRuns.Run run = source.runs.get(index);
                int cell = run.cell();
                int base = CELL_BASE + row * CELL_WORDS;
                int entry = source.result.paletteIndices()[cell] & 255;
                put(bytes, base, source.token.x() * 16 + (cell & 15));
                put(bytes, base + 1, source.token.y() * 16 + (cell >> 8));
                put(bytes, base + 2, source.token.z() * 16 + ((cell >> 4) & 15));
                put(bytes, base + 3, slot * SectionHarvester.MAX_PALETTE_ENTRIES + entry);
                // Only this run's own face survives in the low six bits; the facts above them,
                // the block's light level and its missing-map mask, are the entry's and stay.
                put(bytes, base + 4, source.facts[entry] & ~63 | 1 << run.face());
                put(bytes, base + 5, source.token.geometryRevision());
                put(bytes, base + 6, VoxelSectionState.COMMITTED);
                put(bytes, base + 7, packRun(run));
                row++;
            }
        }
        put(bytes, 2, row); put(bytes, 8, eligible - row); put(bytes, 9, unknown);
        pending = new Publication(stateVersion, bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder()));
        return pending;
    }

    /** Face in bits 0..2, then each span one less than its cell count, four bits each. */
    public static int packRun(VoxelFaceRuns.Run run) {
        return run.face() | (run.spanU() - 1) << 3 | (run.spanV() - 1) << 7;
    }

    public void markPublished(Publication publication) { publishedVersion = Math.max(publishedVersion, publication.stateVersion()); }
    private void changed() { stateVersion = Math.incrementExact(stateVersion); pending = null; }
    private static boolean sameOwner(VoxelSectionState.Snapshot a, VoxelSectionState.Snapshot b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z() && a.storageGeneration() == b.storageGeneration();
    }
    private static boolean sameGeometry(VoxelSectionState.Snapshot a, VoxelSectionState.Snapshot b) {
        return sameOwner(a, b) && a.geometryRevision() == b.geometryRevision();
    }
    private static boolean sameData(SectionHarvester.Result a, SectionHarvester.Result b) {
        return a == b || a.palette() == b.palette() && a.paletteIndices() == b.paletteIndices()
                && a.sourceEvidence() == b.sourceEvidence() && a.sourcePolicy().equals(b.sourcePolicy())
                && a.harvestGeneration() == b.harvestGeneration();
    }
    private static void put(ByteBuffer bytes, int word, int value) { bytes.putInt(word * Integer.BYTES, value); }
}
