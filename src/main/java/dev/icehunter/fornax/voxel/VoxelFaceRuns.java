package dev.icehunter.fornax.voxel;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers a section's emitting faces with rectangles.
 *
 * <p>One light per cell is the wrong unit for a surface made of emitters: a lava fall is a
 * light per cell where the shape is a few rectangles, and every cost in the receiver's walk is
 * per record. A run is one flat rectangle of touching faces, all from the same palette entry, all
 * facing the same way, all in the same plane. A lone torch is a run of one cell, so nothing needs
 * a small case.
 *
 * <p>Runs stay inside one section. Sections are harvested on their own, so reaching across one
 * would make the answer depend on which arrived first.
 *
 * <p>A run wider than one cell needs a face that fills its cell. Two torches side by side have a
 * gap between them and a rectangle over both would light the gap as well.
 */
public final class VoxelFaceRuns {
    /** Origin cell, which way the face points, and how many cells the run spans on each of the
     * two axes across that face. Span is in cells and is at least 1. */
    public record Run(int cell, int face, int spanU, int spanV) {
        public Run {
            if (cell < 0 || cell >= 4096) throw new IllegalArgumentException("run cell outside its section");
            if (face < 0 || face > 5) throw new IllegalArgumentException("run face is not a direction");
            if (spanU < 1 || spanU > 16 || spanV < 1 || spanV > 16)
                throw new IllegalArgumentException("run span must be 1..16 cells");
        }
    }

    private VoxelFaceRuns() {
    }

    /**
     * Which axis a face points along, and the two it spans, matching the pack's own reading of a
     * face: a Y face spans x then z, a Z face spans x then y, an X face spans y then z.
     */
    static int normalAxis(int face) { return face < 2 ? 1 : face < 4 ? 2 : 0; }
    static int axisU(int face) { return face < 4 ? 0 : 1; }
    static int axisV(int face) { return face < 2 ? 2 : face < 4 ? 1 : 2; }

    private static int cellOf(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    private static int cellAt(int face, int normal, int u, int v) {
        int[] xyz = new int[3];
        xyz[normalAxis(face)] = normal;
        xyz[axisU(face)] = u;
        xyz[axisV(face)] = v;
        return cellOf(xyz[0], xyz[1], xyz[2]);
    }

    /**
     * Every run in one section.
     *
     * @param faces     per cell, which of its six faces reach the world
     * @param entries   per cell, its palette entry
     * @param fullFace  per palette entry and face, whether that face fills its cell; only a face
     *                  that does may join a run wider than one cell
     */
    public static List<Run> cover(byte[] faces, byte[] entries, boolean[][] fullFace) {
        if (faces.length != 4096 || entries.length != 4096)
            throw new IllegalArgumentException("a section cover needs 4096 cells");
        List<Run> runs = new ArrayList<>();
        boolean[] taken = new boolean[256];
        for (int face = 0; face < 6; face++) {
            for (int normal = 0; normal < 16; normal++) {
                coverPlane(faces, entries, fullFace, face, normal, taken, runs);
            }
        }
        return List.copyOf(runs);
    }

    /** One plane of one face direction, covered greedily: a row run first, then down while the
     * rows below match it exactly. */
    private static void coverPlane(byte[] faces, byte[] entries, boolean[][] fullFace, int face,
                                   int normal, boolean[] taken, List<Run> runs) {
        java.util.Arrays.fill(taken, false);
        for (int v = 0; v < 16; v++) {
            for (int u = 0; u < 16; u++) {
                int index = v * 16 + u;
                if (taken[index]) continue;
                int cell = cellAt(face, normal, u, v);
                if ((faces[cell] >> face & 1) == 0) continue;
                int entry = entries[cell] & 255;
                taken[index] = true;
                if (entry >= fullFace.length || !fullFace[entry][face]) {
                    // A partial face stands alone: a rectangle over two of them covers the gap.
                    runs.add(new Run(cell, face, 1, 1));
                    continue;
                }
                int spanU = 1;
                while (u + spanU < 16 && matches(faces, entries, fullFace, face, normal,
                        u + spanU, v, entry) && !taken[index + spanU]) {
                    spanU++;
                }
                int spanV = 1;
                while (v + spanV < 16 && rowMatches(faces, entries, fullFace, face, normal,
                        u, v + spanV, spanU, entry, taken)) {
                    spanV++;
                }
                for (int dv = 0; dv < spanV; dv++) {
                    for (int du = 0; du < spanU; du++) taken[(v + dv) * 16 + u + du] = true;
                }
                runs.add(new Run(cell, face, spanU, spanV));
            }
        }
    }

    private static boolean matches(byte[] faces, byte[] entries, boolean[][] fullFace, int face,
                                   int normal, int u, int v, int entry) {
        int cell = cellAt(face, normal, u, v);
        return (faces[cell] >> face & 1) != 0 && (entries[cell] & 255) == entry
                && entry < fullFace.length && fullFace[entry][face];
    }

    private static boolean rowMatches(byte[] faces, byte[] entries, boolean[][] fullFace, int face,
                                      int normal, int u, int v, int spanU, int entry, boolean[] taken) {
        for (int du = 0; du < spanU; du++) {
            if (taken[v * 16 + u + du]) return false;
            if (!matches(faces, entries, fullFace, face, normal, u + du, v, entry)) return false;
        }
        return true;
    }
}
