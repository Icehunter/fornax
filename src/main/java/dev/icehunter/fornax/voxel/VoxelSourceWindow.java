package dev.icehunter.fornax.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.Collection;
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

    private static final class Source {
        final SectionHarvester.Result result;
        final VoxelSectionState.Snapshot token;
        final BitSet eligible = new BitSet();
        final BitSet admitted = new BitSet();
        final int[] facts;
        int unknown;
        boolean pending;
        Source(SectionHarvester.Result result, VoxelSectionState.Snapshot token) {
            this.result = result; this.token = token;
            facts = paletteFacts(result);
            for (int cell = 0; cell < CAPACITY; cell++) {
                int entry = result.paletteIndices()[cell] & 255;
                int value = entry < facts.length ? facts[entry] : UNKNOWN_SOURCE;
                if ((value & UNKNOWN_SOURCE) != 0) unknown++;
                else if ((value & 63) != 0) eligible.set(cell);
            }
        }
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
                next.admitted.or(previous.admitted);
                next.admitted.and(next.eligible);
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
            for (int cell = source.eligible.nextSetBit(0); cell >= 0; cell = source.eligible.nextSetBit(cell + 1)) {
                if (!source.admitted.get(cell)) { source.admitted.set(cell); admitted++; }
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
                value = evidence.eligibleMask(entry) | (evidence.intrinsicEmission(entry) << 8)
                        | (evidence.missingMask(entry) << 16);
                if (evidence.knownZeroSource(entry)) value = 0;
                else if (evidence.unknownMask(entry) != 0) value |= UNKNOWN_SOURCE;
            }
            facts[entry] = value;
        }
        return facts;
    }

    /** ABI2 header: version, capacity, count, storage, atlas low/high, publication low/high,
     * deferred eligible cell count, unknown cell count, six reserved zeros. Section ranges start
     * at word16 and contain (first row,count), indexed by the current toroidal slot. Cell rows
     * start at CELL_BASE: absolute xyz, global palette entry, facts, geometry revision, valid, zero.
     * Rows are grouped by slot; row indices can change while admitted world identities stay fixed. */
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
            eligible += source.eligible.cardinality(); unknown += source.unknown;
            int count = source.admitted.cardinality();
            if (count == 0) continue;
            put(bytes, HEADER_WORDS + slot * RANGE_WORDS, row);
            put(bytes, HEADER_WORDS + slot * RANGE_WORDS + 1, count);
            for (int cell = source.admitted.nextSetBit(0); cell >= 0; cell = source.admitted.nextSetBit(cell + 1)) {
                int base = CELL_BASE + row * CELL_WORDS;
                int entry = source.result.paletteIndices()[cell] & 255;
                put(bytes, base, source.token.x() * 16 + (cell & 15));
                put(bytes, base + 1, source.token.y() * 16 + (cell >> 8));
                put(bytes, base + 2, source.token.z() * 16 + ((cell >> 4) & 15));
                put(bytes, base + 3, slot * SectionHarvester.MAX_PALETTE_ENTRIES + entry);
                put(bytes, base + 4, source.facts[entry]);
                put(bytes, base + 5, source.token.geometryRevision());
                put(bytes, base + 6, VoxelSectionState.COMMITTED);
                row++;
            }
        }
        put(bytes, 2, row); put(bytes, 8, eligible - row); put(bytes, 9, unknown);
        pending = new Publication(stateVersion, bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder()));
        return pending;
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
