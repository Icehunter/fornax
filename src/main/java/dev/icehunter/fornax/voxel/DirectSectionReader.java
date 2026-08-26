package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.material.MaterialScalarsHolder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Reads a section's real block data directly from vanilla's own chunk storage, entirely independent
 * of Sodium -- the bootstrap/resync path for the voxel window: any section the window currently needs
 * but doesn't yet hold valid harvested data for (pack-load startup, or any section Sodium already
 * meshed before it entered the window) gets filled in via this path, reusing the exact same {@link
 * SectionHarvester#harvest} logic the Sodium-triggered path (Task 8) uses.
 */
public final class DirectSectionReader {
    /** Ghost-slot livefix (voxel water reflection round, polish 1): a section position whose Y lies
     * outside the chunk's real section range is not "not loaded yet" -- it structurally can never
     * have block data, ever (above the world's build height or below bedrock). Returning {@code null}
     * for this case (the pre-fix behavior) left the resync path treating it as
     * "retry later" -- {@link VoxelWindow#onSectionHarvested} is never called, so the toroidal slot's
     * GPU occupancy/payload/palette bytes would keep whatever a PREVIOUS, unrelated section left there
     * the last time the window scrolled through that slot index (buffers are zero-cleared only at
     * allocation, never per-resync -- the MoltenVK garbage-VRAM law's flip side). This constant,
     * shared by every out-of-range position, is a genuinely EMPTY section (single palette entry,
     * {@code VoxelShapeKind.EMPTY}, every voxel index 0) -- harvesting it "successfully" claims the
     * slot with correct all-zero occupancy instead of leaving stale bits behind, and (unlike a real
     * section) never needs rebuilding since its content can never change.
     *
     * <p>Every ORDINARY in-range section a normal camera-driven recenter newly exposes would read a
     * previous owner's stale bytes until the background harvester queue caught up (displaced/
     * "teleported" geometry in reflections, worst on pan/movement bursts) without a separate
     * safeguard: {@link VoxelWindow#recenterAndResync} zeroes GPU occupancy for every newly-exposed
     * slot on the render thread, in the same call that publishes the new window geometry, BEFORE
     * dispatching this class's background harvest -- see its own doc comment. This branch stays
     * because a permanently-EMPTY harvest needs no rebuilding, unlike a transient occupancy-only
     * clear waiting on a real harvest to land -- not because it is the only guard against stale
     * bytes for out-of-range slots any more. */
    // Package-private (not private) so DirectSectionReaderTest can assert its shape directly --
    // read(Level, SectionPos) itself needs a real vanilla Level/LevelChunk this suite has no headless
    // seam to construct (same class of gap as ComputePassRunner.build(), documented in that class's
    // own test file), so the out-of-range branch is proven correct by testing exactly what it returns
    // instead of the branch that returns it.
    static final SectionHarvester.Result EMPTY_RESULT = new SectionHarvester.Result(
            new byte[16 * 16 * 16],
            new SectionPalette(List.of(new SectionPalette.Entry(
                    VoxelShapeKind.EMPTY, List.of(), new int[6], 0.0, false, 0))));

    private DirectSectionReader() {
    }

    public static SectionHarvester.@Nullable Result read(Level level, SectionPos position) {
        LevelChunk chunk = level.getChunk(position.x(), position.z());
        if (chunk == null) {
            return null; // not loaded -- caller retries later, not an error
        }
        int sectionIndex = chunk.getSectionIndexFromSectionY(position.y());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            // Structurally out of the world's real height range -- never becomes available, so treat
            // it as a definite, permanent EMPTY harvest rather than leaving the slot stale forever
            // (see EMPTY_RESULT's doc comment).
            return EMPTY_RESULT;
        }
        LevelChunkSection section = chunk.getSection(sectionIndex);
        return SectionHarvester.harvest(section.getStates(), MaterialScalarsHolder.current());
    }
}
