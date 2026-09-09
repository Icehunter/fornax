package dev.icehunter.fornax.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Bounded CPU admission and GPU ABI for a diagnostic subset of committed source faces.
 * Admission cycles through section owners, one face per turn, preserving rows until replacement
 * or invalidation. It is neither nearest-source selection nor an unbiased transport sample.
 * All runtime calls hold the voxel upload lock; the core is Vulkan-free for pinning tests. */
public final class VoxelEmitterPool {
    public static final String TARGET = "voxelEmitterPool";
    // The local coloured-light design bounds its experimental candidate pool at 4096 faces.
    public static final int CAPACITY = 4096;
    // One section's cell count bounds refill work per frame, including repeated directional reads.
    public static final int CELL_SCAN_BUDGET = 16 * 16 * 16;
    // ABI: sixteen scalar uint header words and sixteen words per owner/face/UV record.
    public static final int HEADER_WORDS = 16, RECORD_WORDS = 16;
    public static final int BYTE_SIZE = (HEADER_WORDS + CAPACITY * RECORD_WORDS) * Integer.BYTES;
    public static final int ABI_VERSION = 1;
    public record Stats(int stored, long eligible, long deferred, long unsupported, boolean rebuilding,
                        long publications, int committedSlots) { }
    public static final Stats EMPTY_STATS = new Stats(0, 0, 0, 0, false, 0, 0);
    public record Publication(long stateVersion, ByteBuffer bytes) { }

    private record Owner(int x, int y, int z) implements Comparable<Owner> {
        @Override public int compareTo(Owner other) {
            int order = Integer.compare(x, other.x);
            if (order == 0) order = Integer.compare(y, other.y);
            return order == 0 ? Integer.compare(z, other.z) : order;
        }
    }
    private static final class Source {
        final Owner owner;
        SectionHarvester.Result result;
        VoxelSectionState.Snapshot token;
        int nextKey;
        Source(SectionHarvester.Result result, VoxelSectionState.Snapshot token) {
            owner = new Owner(token.x(), token.y(), token.z());
            this.result = result;
            this.token = token;
        }
    }
    private record Face(Source source, int localKey) { }
    private final int capacity;
    // References, not copies of per-cell data: one current committed snapshot per occupied slot.
    private final Map<Integer, Source> committed = new HashMap<>();
    private final TreeMap<Owner, Source> ready = new TreeMap<>();
    private final ArrayList<Face> faces = new ArrayList<>();
    private Owner after;
    private Source scanning;
    private int storageGeneration;
    private long atlasGeneration, eligible, unsupported, stateVersion, publishedVersion = -1;
    private long revision, publications;

    public VoxelEmitterPool() { this(CAPACITY); }
    VoxelEmitterPool(int capacity) {
        if (capacity < 1 || capacity > CAPACITY) throw new IllegalArgumentException("voxel emitter capacity must be in 1..4096");
        this.capacity = capacity;
    }

    public void reset(int storage, long atlas) {
        if (storage <= 0 || atlas < 0) throw new IllegalArgumentException("voxel emitter generations must be initialized");
        committed.clear(); ready.clear(); faces.clear(); after = null; scanning = null;
        storageGeneration = storage; atlasGeneration = atlas;
        eligible = unsupported = 0;
        changed();
    }

    /** Called only after the matching geometry transfer completes. Content-only publication keeps
     * the same geometry/evidence, so it changes neither the selected rows nor the cursor. */
    public void commit(int slot, SectionHarvester.Result result, VoxelSectionState.Snapshot token) {
        if (slot < 0) throw new IllegalArgumentException("voxel emitter slot must not be negative");
        if (token.storageGeneration() != storageGeneration || result.sourceSummary().atlasGeneration() != atlasGeneration) return;
        if (token.geometryRevision() <= 0) throw new IllegalArgumentException("voxel emitter geometry token must be initialized");
        var previous = committed.get(slot);
        if (previous != null && sameGeometry(previous.token, token)) {
            if (previous.result.sourceEvidence() != result.sourceEvidence()
                    || previous.result.paletteIndices() != result.paletteIndices() || previous.result.palette() != result.palette())
                throw new IllegalArgumentException("voxel emitter content update cannot replace geometry evidence");
            previous.result = result; previous.token = token;
            return;
        }
        var evidence = result.sourceEvidence();
        if (result.paletteIndices().length != CELL_SCAN_BUDGET
                || (evidence.available() && result.palette().entries().size() != evidence.paletteSize()))
            throw new IllegalArgumentException("voxel emitter snapshot does not match its evidence palette");
        remove(slot);
        var source = new Source(result, token);
        committed.put(slot, source);
        eligible += evidence.eligibleFaces(); unsupported += evidence.unsupportedFaces();
        if (evidence.eligibleFaces() > 0) ready.put(source.owner, source);
        changed();
    }

    private static boolean sameGeometry(VoxelSectionState.Snapshot a, VoxelSectionState.Snapshot b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z()
                && a.storageGeneration() == b.storageGeneration() && a.geometryRevision() == b.geometryRevision();
    }

    public void invalidate(Collection<Integer> slots) {
        boolean removed = false;
        for (int slot : slots) removed |= remove(slot);
        if (removed) changed();
    }

    private boolean remove(int slot) {
        Source source = committed.remove(slot);
        if (source == null) return false;
        ready.remove(source.owner);
        if (scanning == source) scanning = null;
        faces.removeIf(face -> face.source() == source);
        eligible -= source.result.sourceEvidence().eligibleFaces();
        unsupported -= source.result.sourceEvidence().unsupportedFaces();
        return true;
    }

    /** Returns actual cell-index reads. Cursors survive a sparse section exhausting this budget;
     * full pools do no scan work. No per-section candidate array is materialized. */
    public int advance(int cellBudget) {
        if (cellBudget < 0) throw new IllegalArgumentException("voxel emitter scan budget must not be negative");
        int scanned = 0, oldSize = faces.size();
        boolean wasRebuilding = rebuilding();
        while (scanned < cellBudget && faces.size() < capacity && !ready.isEmpty()) {
            if (scanning == null) {
                var next = after == null ? ready.firstEntry() : ready.higherEntry(after);
                if (next == null) next = ready.firstEntry();
                scanning = next.getValue();
            }
            Source source = scanning;
            int cell = source.nextKey / 6;
            int direction = source.nextKey % 6;
            int paletteIndex = source.result.paletteIndices()[cell] & 0xff;
            var evidence = source.result.sourceEvidence();
            if (paletteIndex >= evidence.paletteSize())
                throw new IllegalArgumentException("voxel emitter cell exceeds its evidence palette");
            int mask = evidence.eligibleMask(paletteIndex) & (-1 << direction);
            scanned++;
            if (mask != 0) {
                int face = Integer.numberOfTrailingZeros(mask);
                int key = cell * 6 + face;
                source.nextKey = key + 1;
                faces.add(new Face(source, key));
                after = source.owner; scanning = null;
            } else source.nextKey = (cell + 1) * 6;
            if (source.nextKey == CELL_SCAN_BUDGET * 6) {
                ready.remove(source.owner);
                after = source.owner; scanning = null;
            }
        }
        if (faces.size() != oldSize || wasRebuilding != rebuilding()) changed();
        return scanned;
    }

    private boolean rebuilding() { return faces.size() < capacity && !ready.isEmpty(); }
    private void changed() { stateVersion = Math.incrementExact(stateVersion); }
    public Stats stats() {
        return new Stats(faces.size(), eligible, eligible - faces.size(), unsupported, rebuilding(), publications, committed.size());
    }

    /** A full immutable staging snapshot. Header totals derive only from committed admissions;
     * preparation is not GPU completion. The queue embeds these bytes at its next compute consumer. */
    public Publication preparePublication() {
        if (stateVersion == publishedVersion) return null;
        ByteBuffer bytes = ByteBuffer.allocateDirect((HEADER_WORDS + capacity * RECORD_WORDS) * Integer.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int offset = 0; offset < bytes.capacity(); offset += Integer.BYTES) bytes.putInt(offset, 0);
        var stats = stats();
        put(bytes, 0, ABI_VERSION); put(bytes, 1, capacity); put(bytes, 2, stats.stored());
        put(bytes, 3, (stats.rebuilding() ? 1 : 0) | (stats.deferred() > 0 ? 2 : 0));
        putLong(bytes, 4, eligible); putLong(bytes, 6, stats.deferred()); putLong(bytes, 8, unsupported);
        revision = Math.incrementExact(revision);
        putLong(bytes, 10, revision); putLong(bytes, 12, atlasGeneration); put(bytes, 14, storageGeneration);
        for (int index = 0; index < faces.size(); index++) {
            Face face = faces.get(index);
            Source source = face.source();
            var token = source.token;
            int base = HEADER_WORDS + index * RECORD_WORDS;
            put(bytes, base, token.x()); put(bytes, base + 1, token.y()); put(bytes, base + 2, token.z());
            put(bytes, base + 3, token.storageGeneration()); put(bytes, base + 4, token.geometryRevision());
            putLong(bytes, base + 5, atlasGeneration); put(bytes, base + 7, face.localKey());
            int cell = face.localKey() / 6, direction = face.localKey() % 6;
            int paletteIndex = source.result.paletteIndices()[cell] & 0xff;
            var evidence = source.result.sourceEvidence();
            int facts = evidence.intrinsicEmission(paletteIndex)
                    | ((evidence.missingMask(paletteIndex) >>> direction & 1) << 4);
            put(bytes, base + 8, facts);
            int[] uv = source.result.palette().entries().get(paletteIndex).faceTextureWords();
            if ((uv[direction * VoxelFaceTexture.FACE_WORDS] >>> 24 & 1) == 0)
                throw new IllegalArgumentException("eligible voxel emitter face lacks its exact UV mapping");
            for (int word = 0; word < VoxelFaceTexture.FACE_WORDS; word++)
                put(bytes, base + 9 + word, uv[direction * VoxelFaceTexture.FACE_WORDS + word]);
        }
        return new Publication(stateVersion, bytes);
    }

    public void markPublished(Publication publication) {
        publishedVersion = Math.max(publishedVersion, publication.stateVersion());
        publications = Math.incrementExact(publications);
    }
    private static void put(ByteBuffer bytes, int word, int value) { bytes.putInt(word * Integer.BYTES, value); }
    private static void putLong(ByteBuffer bytes, int word, long value) {
        put(bytes, word, (int) value); put(bytes, word + 1, (int) (value >>> 32));
    }
}
