package dev.rasengan.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector3f;

/**
 * A procedurally generated unit icosphere, built once at class-init and reused for every cast.
 *
 * <p>An icosphere is used rather than a UV sphere because its triangles are near-uniform in size,
 * which matters here: the shell is drawn with additive blending, so uneven triangle density would
 * show up as uneven brightness banding at the poles.
 *
 * <p>The mesh is generated in code from an icosahedron and repeated edge-midpoint subdivision.
 * No model file, no texture, nothing imported - the geometry is entirely original and the vertex
 * data is just numbers computed at runtime.
 */
public final class SphereMesh {

    /** Immutable triangle of unit-length vertices. */
    public record Tri(Vector3f a, Vector3f b, Vector3f c) {}

    /** Low detail: 80 triangles. Used for outer shells and distant casts. */
    public static final List<Tri> LOW = generate(1);

    /** High detail: 320 triangles. Used for the bright core. */
    public static final List<Tri> HIGH = generate(2);

    private SphereMesh() {}

    /** Chooses a detail level from a 0..1 quality factor. */
    public static List<Tri> forQuality(float quality) {
        return quality >= 0.6F ? HIGH : LOW;
    }

    private static List<Tri> generate(int subdivisions) {
        // ---- Base icosahedron ----
        final float t = (1.0F + (float) Math.sqrt(5.0)) * 0.5F;

        List<Vector3f> verts = new ArrayList<>(List.of(
                v(-1, t, 0), v(1, t, 0), v(-1, -t, 0), v(1, -t, 0),
                v(0, -1, t), v(0, 1, t), v(0, -1, -t), v(0, 1, -t),
                v(t, 0, -1), v(t, 0, 1), v(-t, 0, -1), v(-t, 0, 1)));
        verts.forEach(Vector3f::normalize);

        int[][] faces = {
                {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
                {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };

        List<int[]> current = new ArrayList<>();
        for (int[] f : faces) {
            current.add(new int[]{f[0], f[1], f[2]});
        }

        // ---- Subdivide ----
        for (int pass = 0; pass < subdivisions; pass++) {
            Map<Long, Integer> midpointCache = new HashMap<>();
            List<int[]> next = new ArrayList<>(current.size() * 4);

            for (int[] face : current) {
                int a = face[0];
                int b = face[1];
                int c = face[2];
                int ab = midpoint(a, b, verts, midpointCache);
                int bc = midpoint(b, c, verts, midpointCache);
                int ca = midpoint(c, a, verts, midpointCache);

                next.add(new int[]{a, ab, ca});
                next.add(new int[]{b, bc, ab});
                next.add(new int[]{c, ca, bc});
                next.add(new int[]{ab, bc, ca});
            }
            current = next;
        }

        // ---- Freeze into triangles ----
        List<Tri> result = new ArrayList<>(current.size());
        for (int[] face : current) {
            result.add(new Tri(
                    new Vector3f(verts.get(face[0])),
                    new Vector3f(verts.get(face[1])),
                    new Vector3f(verts.get(face[2]))));
        }
        return List.copyOf(result);
    }

    /** Returns the index of the normalised midpoint of edge (a,b), creating it once. */
    private static int midpoint(int a, int b, List<Vector3f> verts, Map<Long, Integer> cache) {
        long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
        Integer existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        Vector3f va = verts.get(a);
        Vector3f vb = verts.get(b);
        Vector3f mid = new Vector3f(va).add(vb).mul(0.5F).normalize();
        verts.add(mid);
        int index = verts.size() - 1;
        cache.put(key, index);
        return index;
    }

    private static Vector3f v(float x, float y, float z) {
        return new Vector3f(x, y, z);
    }
}
