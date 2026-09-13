package dev.icehunter.fornax.pipeline;

/** Attached to terrain section storage. A stamp identifies the latest mutation of that slot,
 * independently of addresses, allocation sizes, or storage/region identity reuse. It describes
 * freshness only: a current stamp does not establish that the slot contains any vertices. */
public interface TerrainMeshRevision {
    long fornax$revision(int section);
}
