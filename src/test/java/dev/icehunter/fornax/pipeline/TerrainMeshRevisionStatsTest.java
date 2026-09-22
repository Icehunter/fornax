package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A single total {@code rt_shadow_dirty_meshes} count cannot say which storage event fires on
 * camera motion alone; each site is checked on its own. */
class TerrainMeshRevisionStatsTest {

    private static Map<String, Double> publish() {
        Map<String, Double> values = new HashMap<>();
        TerrainMeshRevisionStats.publish(values::put);
        return values;
    }

    @Test
    void publishesOneCountPerSite() {
        TerrainMeshRevisionStats.count(TerrainMeshRevisionStats.Site.VERTEX_DATA);
        TerrainMeshRevisionStats.count(TerrainMeshRevisionStats.Site.VERTEX_DATA);
        TerrainMeshRevisionStats.count(TerrainMeshRevisionStats.Site.SECTION_REMOVAL);

        Map<String, Double> values = publish();

        assertEquals(2.0, values.get("rt_mesh_invalidate_vertex"));
        assertEquals(1.0, values.get("rt_mesh_invalidate_remove"));
        assertEquals(0.0, values.get("rt_mesh_invalidate_resize"));
    }

    @Test
    void resetsAfterPublish() {
        TerrainMeshRevisionStats.count(TerrainMeshRevisionStats.Site.SECTION_REMOVAL);
        TerrainMeshRevisionStats.count(TerrainMeshRevisionStats.Site.STORAGE_REPLACEMENT);
        publish();

        Map<String, Double> values = publish();

        assertEquals(0.0, values.get("rt_mesh_invalidate_vertex"));
        assertEquals(0.0, values.get("rt_mesh_invalidate_remove"));
        assertEquals(0.0, values.get("rt_mesh_invalidate_resize"));
    }
}
