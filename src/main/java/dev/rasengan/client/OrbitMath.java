package dev.rasengan.client;

import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * Deterministic motion maths for the sphere.
 *
 * <h2>Why everything here is a pure function of time</h2>
 * Every position is computed as {@code f(seed, layerIndex, parameter, timeInTicks)} where time is
 * a <em>float</em> that already includes the frame's partial tick. Nothing is integrated
 * frame-to-frame and nothing is stored between frames, which is what removes jitter: there is no
 * accumulated state to drift, and between two ticks the value simply moves along a smooth curve.
 * Rendering the same cast at t=10.0 and t=10.5 gives two points on the same continuous path.
 *
 * <h2>Why it is seeded</h2>
 * All per-layer variation (axis tilt, radius, speed, direction, phase) is drawn once from a
 * {@link RandomSource} seeded with the cast's server-issued seed. Every client that receives the
 * same {@code CastStart} therefore generates byte-identical layer parameters, so two players
 * standing side by side see the same sphere rather than two different ones.
 */
public final class OrbitMath {

    private OrbitMath() {}

    /**
     * One rotating orbital layer.
     *
     * @param axis      unit rotation axis; the ring lies in the plane perpendicular to it
     * @param basisU    unit vector in the ring plane
     * @param basisV    unit vector in the ring plane, perpendicular to basisU
     * @param radius    ring radius as a fraction of the sphere radius
     * @param speed     revolutions per tick
     * @param direction +1 clockwise, -1 counter-clockwise
     * @param phase     starting angle offset in radians
     * @param width     ribbon half-width as a fraction of the sphere radius
     * @param wobble    how much the radius breathes over time
     */
    public record Layer(Vector3f axis, Vector3f basisU, Vector3f basisV,
                        float radius, float speed, float direction,
                        float phase, float width, float wobble) {

        /** Point on this ring at parameter {@code s} in 0..1 and time {@code t} in ticks. */
        public Vector3f point(float s, float t, float sphereRadius, Vector3f out) {
            float angle = (float) (s * Math.TAU) + phase + direction * speed * t;
            float breathing = 1.0F + wobble * (float) Math.sin(t * 0.31F + phase * 2.0F);
            float r = radius * sphereRadius * breathing;
            float cos = (float) Math.cos(angle) * r;
            float sin = (float) Math.sin(angle) * r;
            out.set(
                    basisU.x * cos + basisV.x * sin,
                    basisU.y * cos + basisV.y * sin,
                    basisU.z * cos + basisV.z * sin);
            return out;
        }

        /** Unit tangent along the ring, used to orient ribbon geometry. */
        public Vector3f tangent(float s, float t, Vector3f out) {
            float angle = (float) (s * Math.TAU) + phase + direction * speed * t;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            // d/dangle of (U*cos + V*sin) = -U*sin + V*cos
            out.set(
                    -basisU.x * sin + basisV.x * cos,
                    -basisU.y * sin + basisV.y * cos,
                    -basisU.z * sin + basisV.z * cos);
            return out.normalize();
        }
    }

    /**
     * Builds the full layer set for one cast.
     *
     * <p>The first three layers are deliberately seeded toward horizontal, vertical and diagonal
     * planes so the sphere always reads as three-dimensional from any camera angle, rather than
     * risking a random draw that puts every ring in a similar plane. The remainder are freely
     * oriented. Each gets a distinct radius, speed, direction and phase.
     */
    public static Layer[] buildLayers(long seed, int count) {
        RandomSource random = RandomSource.create(seed);
        Layer[] layers = new Layer[count];

        for (int i = 0; i < count; i++) {
            Vector3f axis;
            if (i == 0) {
                axis = new Vector3f(0.0F, 1.0F, 0.0F); // horizontal ring
            } else if (i == 1) {
                axis = new Vector3f(1.0F, 0.0F, 0.0F); // vertical ring
            } else if (i == 2) {
                axis = new Vector3f(0.0F, 0.0F, 1.0F); // the other vertical plane
            } else if (i == 3) {
                axis = new Vector3f(0.7071F, 0.7071F, 0.0F); // diagonal
            } else if (i == 4) {
                axis = new Vector3f(0.0F, 0.7071F, 0.7071F); // diagonal
            } else {
                axis = new Vector3f(
                        random.nextFloat() * 2.0F - 1.0F,
                        random.nextFloat() * 2.0F - 1.0F,
                        random.nextFloat() * 2.0F - 1.0F);
                if (axis.lengthSquared() < 1.0E-4F) {
                    axis.set(0.0F, 1.0F, 0.0F);
                }
                axis.normalize();
            }

            // Nudge the fixed axes slightly so repeated casts are not visually identical,
            // while keeping their broad orientation.
            if (i < 5) {
                axis.add(
                        (random.nextFloat() - 0.5F) * 0.35F,
                        (random.nextFloat() - 0.5F) * 0.35F,
                        (random.nextFloat() - 0.5F) * 0.35F).normalize();
            }

            Vector3f basisU = perpendicular(axis, new Vector3f());
            Vector3f basisV = new Vector3f(axis).cross(basisU).normalize();

            float radius = 0.62F + random.nextFloat() * 0.42F;
            float speed = 0.14F + random.nextFloat() * 0.26F;
            float direction = random.nextBoolean() ? 1.0F : -1.0F;
            float phase = random.nextFloat() * (float) Math.TAU;
            float width = 0.030F + random.nextFloat() * 0.045F;
            float wobble = 0.05F + random.nextFloat() * 0.10F;

            layers[i] = new Layer(axis, basisU, basisV, radius, speed, direction, phase, width, wobble);
        }
        return layers;
    }

    /** Any unit vector perpendicular to {@code v}, chosen stably. */
    public static Vector3f perpendicular(Vector3f v, Vector3f out) {
        // Cross with whichever cardinal axis is least aligned, to avoid a degenerate result.
        float ax = Math.abs(v.x);
        float ay = Math.abs(v.y);
        float az = Math.abs(v.z);
        if (ax <= ay && ax <= az) {
            out.set(1.0F, 0.0F, 0.0F);
        } else if (ay <= az) {
            out.set(0.0F, 1.0F, 0.0F);
        } else {
            out.set(0.0F, 0.0F, 1.0F);
        }
        return out.cross(v).normalize();
    }

    /**
     * A spiral/helix path wrapping the core.
     *
     * <p>{@code s} runs 0..1 along the trail. Latitude sweeps pole to pole while longitude winds
     * multiple times, producing a true spherical helix rather than a flat circle.
     */
    public static Vector3f helixPoint(float s, float t, float sphereRadius, int windings,
                                      float phase, float direction, float radiusScale, Vector3f out) {
        float latitude = (float) Math.PI * s;                     // 0..PI, pole to pole
        float longitude = (float) (windings * Math.TAU * s) + phase + direction * t * 0.22F;
        float ringRadius = (float) Math.sin(latitude);            // pinches at the poles
        float r = sphereRadius * radiusScale;
        out.set(
                (float) Math.cos(longitude) * ringRadius * r,
                (float) Math.cos(latitude) * r,
                (float) Math.sin(longitude) * ringRadius * r);
        return out;
    }

    /**
     * Smooth pseudo-noise in 0..1, continuous in {@code t}.
     *
     * <p>A sum of sines rather than a lookup table: it is cheap, allocation-free, and - crucially
     * for this use - infinitely differentiable in time, so anything driven by it can never
     * visibly step between ticks.
     */
    public static float noise(float x, float y, float z, float t, float offset) {
        float a = (float) Math.sin(x * 2.31F + t * 0.17F + offset);
        float b = (float) Math.sin(y * 1.83F - t * 0.23F + offset * 1.7F);
        float c = (float) Math.sin(z * 2.07F + t * 0.19F + offset * 2.3F);
        float d = (float) Math.sin((x + y + z) * 1.11F + t * 0.13F + offset * 0.7F);
        return (a * b + c * d) * 0.25F + 0.5F;
    }

    /** Smoothstep easing, used for every ramp so nothing starts or stops abruptly. */
    public static float smoothstep(float edge0, float edge1, float value) {
        if (edge1 - edge0 == 0.0F) {
            return value < edge0 ? 0.0F : 1.0F;
        }
        float x = Math.clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    /** Ease-out curve for expansion effects. */
    public static float easeOut(float x) {
        float c = Math.clamp(x, 0.0F, 1.0F);
        return 1.0F - (1.0F - c) * (1.0F - c);
    }
}
