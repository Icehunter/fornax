package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.graph.EntityOccluderBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ordering rule behind {@link EntityOccluderFrameState}: the local player takes slot zero,
 * and the rest are sorted nearest first, so a cap drops the bodies least likely to be seen.
 */
class EntityOccluderFrameStateTest {
    /** A box collapsed to one point, placed relative to the camera, so its centre is exactly {@code (x, y, z)}. */
    private static EntityOccluderFrameState.Occluder at(float x, float y, float z, int kind) {
        return new EntityOccluderFrameState.Occluder(
                x, y, z, kind,
                x, y, z, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f);
    }

    @Test
    void localPlayerStaysSlotZeroWhenAnotherBodyIsNearer() {
        EntityOccluderFrameState.Occluder local = at(10.0f, 0.0f, 0.0f, EntityOccluderBuffer.KIND_PLAYER);
        EntityOccluderFrameState.Occluder nearer = at(1.0f, 0.0f, 0.0f, EntityOccluderBuffer.KIND_LIVING);

        List<EntityOccluderFrameState.Occluder> result =
                EntityOccluderFrameState.select(local, List.of(nearer), 8);

        assertEquals(List.of(local, nearer), result);
    }

    @Test
    void othersAreOrderedNearestFirst() {
        EntityOccluderFrameState.Occluder far = at(20.0f, 0.0f, 0.0f, EntityOccluderBuffer.KIND_LIVING);
        EntityOccluderFrameState.Occluder near = at(2.0f, 0.0f, 0.0f, EntityOccluderBuffer.KIND_LIVING);
        EntityOccluderFrameState.Occluder mid = at(8.0f, 0.0f, 0.0f, EntityOccluderBuffer.KIND_LIVING);
        List<EntityOccluderFrameState.Occluder> others = new ArrayList<>();
        others.add(far);
        others.add(near);
        others.add(mid);

        List<EntityOccluderFrameState.Occluder> result = EntityOccluderFrameState.select(null, others, 8);

        assertEquals(List.of(near, mid, far), result);
    }

    @Test
    void capDropsTheFarthestBodies() {
        List<EntityOccluderFrameState.Occluder> others = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            others.add(at(i, 0.0f, 0.0f, EntityOccluderBuffer.KIND_LIVING));
        }

        List<EntityOccluderFrameState.Occluder> result = EntityOccluderFrameState.select(null, others, 3);

        assertEquals(List.of(others.get(0), others.get(1), others.get(2)), result);
    }

    @Test
    void aCapOfZeroPublishesNothingEvenWithALocalPlayer() {
        EntityOccluderFrameState.Occluder local = at(0.0f, 0.0f, 0.0f, EntityOccluderBuffer.KIND_PLAYER);

        List<EntityOccluderFrameState.Occluder> result = EntityOccluderFrameState.select(local, List.of(), 0);

        assertEquals(List.of(), result);
    }
}
