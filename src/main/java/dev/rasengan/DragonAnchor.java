package dev.rasengan;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where the dragon's head is - the one place that answers it, for both sides.
 *
 * <h2>Why this exists, and why it is COMMON</h2>
 * The rider stands on the dragon's head, and the mount is server-authoritative, so the <em>server</em>
 * has to know where the head is. But GeckoLib evaluates bones only in the render path - a dedicated
 * server never runs an animation. So the server needs its own answer, and the client needs the
 * <em>same</em> answer or the rider would be drawn somewhere other than where the server put them.
 * Hence one COMMON class, alongside {@link PalmAnchor} and {@link SummonTimeline}.
 *
 * <h2>The numbers are measured from the asset, not chosen</h2>
 * Every constant below comes out of {@code tools/dragon/measure_head_anchor.py}, which replicates
 * GeckoLib's bake maths using the convention {@code convert_gltf_to_geckolib.py} proved by
 * round-tripping against the source glTF ({@code pivotX = -pivot.x},
 * {@code baseRotX = toRadians(-rotation.x)}).
 *
 * <p>Two findings shaped the design:
 * <ul>
 *   <li><b>The whole neck-to-head chain animates on X only.</b> Checked, not assumed: across both
 *       {@code idle} and {@code fly}, the maximum |Y| or |Z| rotation anywhere in
 *       Torso&rarr;Neck&rarr;bone&rarr;bone2&rarr;Head is {@code 0.000000} degrees. So the head never
 *       yaws or rolls relative to the body - it only pitches. That is why the anchor needs no
 *       per-bone quaternion chain.</li>
 *   <li><b>A sinusoid is not good enough for idle.</b> A best-fit single sine tracks the flight bob to
 *       1.92 cm but misses the idle bob by <b>42.17 cm</b>, because the idle head motion is not
 *       sinusoidal. So the head height is a <b>sampled table</b> of the real animation instead, which
 *       is accurate for both.</li>
 * </ul>
 *
 * <h2>Accuracy</h2>
 * 32 samples per cycle, linearly interpolated. Worst-case error against the exact animated position:
 * <b>2.46 cm vertically in flight</b>, 0.54 cm idle, and under 0.3 cm horizontally in both. That is
 * the distance between the rider's feet and the rendered skull plate; the rider's position itself is
 * exact, because every observer draws them where the server put them.
 *
 * <h2>The phase has to be forced</h2>
 * These tables are indexed by animation phase, and the server only knows the phase because
 * {@code DragonEntity} <em>forces</em> it from the entity's age rather than letting GeckoLib free-run.
 * Left alone, GeckoLib anchors the phase to the first frame each client rendered the dragon, so two
 * players would disagree about where the head is. See {@code DragonEntity.registerControllers}.
 */
public final class DragonAnchor {

    private DragonAnchor() {}

    // ------------------------------------------------------------------
    // Rest-pose geometry, in model space. Model units are 1/16 block.
    // Model space: +Y up, -Z forward (the converter confirmed NORTH = -Z across all 145 cubes).
    // ------------------------------------------------------------------

    /**
     * The rider's standing point: the centre of the top face of the broad skull plate.
     *
     * <p>Deliberately <em>not</em> the highest point of the head. That is the horn pair, which tops out
     * at y = 5.189 blocks but is only 0.25 blocks wide per horn and set out at |x| 0.43..0.68 - a
     * player standing there would be balanced on a spike. The broad plate between the horns tops out
     * at y = 4.5625 and is 0.25 blocks deep by 0.25 wide at minimum, which is a surface.
     */
    private static final double ANCHOR_UP = 4.56250D;
    private static final double ANCHOR_FORWARD = -3.87500D;

    /** Head cube bounding box in model space - the mount interaction region. */
    private static final double HEAD_MIN_X = -0.68019D;
    private static final double HEAD_MAX_X = 0.68019D;
    private static final double HEAD_MIN_Y = 3.31250D;
    private static final double HEAD_MAX_Y = 5.18934D;
    private static final double HEAD_MIN_Z = -5.12500D;
    private static final double HEAD_MAX_Z = -3.31250D;

    /**
     * How far the interaction region is grown beyond the head's literal geometry, in blocks.
     *
     * <p>The head's own cubes span 1.36 x 1.88 x 1.81 blocks. Hitting that exactly, on a creature 29.5
     * blocks across, from four to six blocks away, is a harder shot than it sounds - and unlike a vanilla
     * hitbox there is no aim assist helping.
     *
     * <p>0.15 rather than something more generous, and the figure was measured rather than picked: at 0.30
     * the region covered 29.2% of a 16-block-wide aim fan and stood 2.48 blocks tall against a body only
     * 5.0 tall, which stops being "the head" and starts being "the front half". At 0.15 it is a
     * comfortable target that is still unambiguously the head. Most of the aiming problem is solved by
     * {@code DragonHeadHighlight} drawing the region instead, which is the honest fix - a bigger invisible
     * box is still invisible.
     *
     * <p>The margin applies to the interaction box only. The rider's anchor is unaffected: they still
     * stand on the measured skull plate.
     *
     * <p>The margin is applied to the interaction box only. The rider's anchor is unaffected - they still
     * stand on the measured skull plate.
     */
    private static final double HEAD_PICK_MARGIN = 0.15D;

    /** Head AABB in dragon-local model space. Static: it never changes, only the frame does. */
    private static final AABB HEAD_BOX = new AABB(
            HEAD_MIN_X, HEAD_MIN_Y, HEAD_MIN_Z, HEAD_MAX_X, HEAD_MAX_Y, HEAD_MAX_Z)
            .inflate(HEAD_PICK_MARGIN);

    // ------------------------------------------------------------------
    // Animated head offsets, sampled from the shipped animation files.
    // Generated by tools/dragon/measure_head_anchor.py - do not hand-edit.
    // ------------------------------------------------------------------

    /** IDLE: 32 samples over 4.0s = 80.0000 ticks. */
    private static final float IDLE_CYCLE_TICKS = 80.0000F;

    private static final float[] IDLE_UP = {
            3.96234F, 3.95714F, 3.98963F, 4.04989F, 4.13440F, 4.22723F,
            4.31476F, 4.38602F, 4.42513F, 4.43271F, 4.40189F, 4.33935F,
            4.25472F, 4.16099F, 4.07252F, 4.00281F, 3.96234F, 3.95917F,
            3.98787F, 4.05113F, 4.13388F, 4.22695F, 4.31579F, 4.38438F,
            4.42712F, 4.43067F, 4.40189F, 4.33811F, 4.25472F, 4.16128F,
            4.07252F, 4.00446F
    };

    private static final float[] IDLE_FORWARD = {
            -3.86367F, -3.84936F, -3.83719F, -3.82822F, -3.82411F, -3.82361F,
            -3.82755F, -3.83439F, -3.84469F, -3.85693F, -3.87010F, -3.88186F,
            -3.88977F, -3.89188F, -3.88745F, -3.87731F, -3.86367F, -3.84948F,
            -3.83697F, -3.82851F, -3.82380F, -3.82391F, -3.82727F, -3.83462F,
            -3.84454F, -3.85699F, -3.87010F, -3.88168F, -3.88977F, -3.89160F,
            -3.88745F, -3.87717F
    };

    /** FLY: 32 samples over 1.9583s = 39.1660 ticks. */
    private static final float FLY_CYCLE_TICKS = 39.1660F;

    private static final float[] FLY_UP = {
            5.14728F, 5.16490F, 5.16278F, 5.13411F, 5.08770F, 5.02519F,
            4.94180F, 4.84314F, 4.73203F, 4.61551F, 4.49486F, 4.37725F,
            4.26580F, 4.16300F, 4.07910F, 4.01357F, 3.96604F, 3.93812F,
            3.93767F, 3.95801F, 4.00160F, 4.06384F, 4.14455F, 4.23944F,
            4.34594F, 4.45982F, 4.57714F, 4.69337F, 4.80469F, 4.90605F,
            4.99540F, 5.06715F
    };

    private static final float[] FLY_FORWARD = {
            -3.64729F, -3.65450F, -3.67065F, -3.69855F, -3.73305F, -3.77259F,
            -3.81711F, -3.86245F, -3.90545F, -3.94358F, -3.97452F, -3.99640F,
            -4.00961F, -4.01445F, -4.01006F, -3.99938F, -3.98423F, -3.96586F,
            -3.94556F, -3.92477F, -3.90412F, -3.88369F, -3.86332F, -3.84243F,
            -3.82049F, -3.79712F, -3.77213F, -3.74620F, -3.71989F, -3.69537F,
            -3.67354F, -3.65781F
    };

    /** Cycle length of the locomotion animation the dragon is currently playing, in ticks. */
    public static float cycleTicks(boolean airborne) {
        return airborne ? FLY_CYCLE_TICKS : IDLE_CYCLE_TICKS;
    }

    /**
     * Animation phase in ticks for a dragon of the given age.
     *
     * <p>Derived from age rather than read from GeckoLib, because the server has no GeckoLib state and
     * because a client-anchored phase would differ per observer. {@code DragonEntity} forces the
     * rendered animation to this same value, which is what keeps the drawn head and this calculation
     * in agreement.
     */
    public static float phase(float ageTicks, boolean airborne) {
        float cycle = cycleTicks(airborne);
        float p = ageTicks % cycle;
        return p < 0.0F ? p + cycle : p;
    }

    /** Linear table lookup by phase, wrapping at the cycle boundary. */
    private static double sample(float[] table, float phaseTicks, float cycle) {
        float k = (phaseTicks / cycle) * table.length;
        int i0 = ((int) Math.floor(k)) % table.length;
        if (i0 < 0) {
            i0 += table.length;
        }
        int i1 = (i0 + 1) % table.length;
        float f = k - (float) Math.floor(k);
        return Mth.lerp(f, table[i0], table[i1]);
    }

    // ------------------------------------------------------------------
    // Model space <-> world space
    // ------------------------------------------------------------------

    /**
     * The dragon's local basis in world space.
     *
     * <p>Built from the body's yaw and pitch directly rather than through {@code Vec3.yRot}, so the
     * sign conventions are visible instead of inherited. {@code right} is deliberately unaffected by
     * pitch, because pitch <em>is</em> a rotation about {@code right}.
     *
     * @param forward the body's facing, including pitch
     * @param up      perpendicular to forward, in the vertical plane containing it
     * @param right   horizontal, perpendicular to both
     */
    public record Basis(Vec3 forward, Vec3 up, Vec3 right) {

        /** Model-space offset (blocks) to a world-space offset from the dragon's position. */
        public Vec3 toWorld(double mx, double my, double mz) {
            // -mz because the model faces -Z; -mx because the renderer mirrors X.
            return forward.scale(-mz).add(up.scale(my)).add(right.scale(-mx));
        }

        /** World-space offset from the dragon's position back to model-space coordinates. */
        public Vec3 toModel(Vec3 worldOffset) {
            return new Vec3(
                    -worldOffset.dot(right),
                    worldOffset.dot(up),
                    -worldOffset.dot(forward));
        }
    }

    /**
     * Basis for a body at the given rotation.
     *
     * <p>Banking (roll) is deliberately excluded. The rider stands upright on the head: a banking
     * anchor would swing them sideways through the air on every turn, which is both unpleasant and a
     * reliable way to clip them into the model. The bank angle tilts the dragon's <em>model</em>
     * beneath a rider who stays level - stated as a deliberate simplification in the docs.
     */
    public static Basis basis(float yRotDegrees, float xRotDegrees) {
        // Math.sin/cos rather than Mth.sin/cos. Mth's are table lookups with about 1e-4 of error, which
        // is fine for a particle and not fine here: this basis positions a rider and is inverted to test
        // a click, so the error shows up twice. The audit measured 7.7e-05 of normalisation drift from the
        // table version; with doubles it is 1e-16. This runs once per tick, not per particle.
        double yaw = Math.toRadians(yRotDegrees);
        double pitch = Math.toRadians(xRotDegrees);
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double sinPitch = Math.sin(pitch);
        double cosPitch = Math.cos(pitch);

        // Matches Minecraft's own view-vector convention: at yaw 0 the facing is +Z.
        Vec3 forward = new Vec3(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
        // The same view vector evaluated at (pitch - 90): cos(p-90) = sin(p) and sin(p-90) = -cos(p),
        // which gives (-sinYaw*sinPitch, cosPitch, cosYaw*sinPitch) and reduces to (0, 1, 0) when level.
        // The signs on x and z matter - getting them inverted leaves the basis non-orthogonal and makes
        // toModel stop being the inverse of toWorld, which the audit caught as a 5.97-block round-trip
        // error before this was corrected.
        Vec3 up = new Vec3(-sinYaw * sinPitch, cosPitch, cosYaw * sinPitch);
        Vec3 right = new Vec3(cosYaw, 0.0D, sinYaw);
        return new Basis(forward, up, right);
    }

    // ------------------------------------------------------------------
    // Public queries
    // ------------------------------------------------------------------

    /**
     * Model-space position of the rider's standing point at a given animation phase.
     *
     * @param airborne whether the flight animation is playing rather than the idle one
     */
    public static Vec3 riderPointModel(float ageTicks, boolean airborne) {
        float cycle = cycleTicks(airborne);
        float p = phase(ageTicks, airborne);
        float[] up = airborne ? FLY_UP : IDLE_UP;
        float[] forward = airborne ? FLY_FORWARD : IDLE_FORWARD;
        return new Vec3(0.0D, sample(up, p, cycle), sample(forward, p, cycle));
    }

    /** Rest-pose rider point, ignoring animation. Used as the fallback and in tests. */
    public static Vec3 riderPointRest() {
        return new Vec3(0.0D, ANCHOR_UP, ANCHOR_FORWARD);
    }

    /**
     * World-space offset from the dragon's position to the rider's feet.
     *
     * <p>This is what {@code DragonEntity.getPassengerAttachmentPoint} returns, so it is evaluated
     * once per tick per side and interpolated by the normal entity render path in between.
     */
    public static Vec3 riderOffset(float yRot, float xRot, float ageTicks, boolean airborne) {
        Vec3 model = riderPointModel(ageTicks, airborne);
        return basis(yRot, xRot).toWorld(model.x, model.y, model.z);
    }

    /**
     * World-space centre of the head's interaction region.
     *
     * <p>Follows the animated head rather than sitting at the rest pose, so the region a player has to
     * click tracks the head they can see.
     */
    public static Vec3 headCentre(Vec3 dragonPos, float yRot, float xRot, float ageTicks,
                                  boolean airborne) {
        Vec3 anim = riderPointModel(ageTicks, airborne);
        // The whole head moves together, so the box's animated displacement is the anchor's
        // displacement from its rest position.
        double dy = anim.y - ANCHOR_UP;
        double dz = anim.z - ANCHOR_FORWARD;
        double cx = (HEAD_MIN_X + HEAD_MAX_X) / 2.0D;
        double cy = (HEAD_MIN_Y + HEAD_MAX_Y) / 2.0D + dy;
        double cz = (HEAD_MIN_Z + HEAD_MAX_Z) / 2.0D + dz;
        return dragonPos.add(basis(yRot, xRot).toWorld(cx, cy, cz));
    }

    /**
     * Whether a look ray hits the head's interaction region.
     *
     * <h2>Why the test is done in model space</h2>
     * The head box is axis-aligned in the <em>model</em>, not in the world - it is 1.36 blocks wide,
     * 1.88 tall and 1.81 deep, and it rotates with the dragon. Testing a world-aligned box around it
     * would have to be inflated to cover every orientation, which would make "the head" a 1.9-block
     * cube that also covers a chunk of the neck. So the ray is transformed into the dragon's own frame
     * and clipped against the exact box instead. {@code AABB.clip} does the slab test.
     *
     * <p>This region is genuinely separate from the entity's 6.0 x 5.0 collision box - note that the
     * head sits <b>outside</b> it: the head centre is 4.22 blocks forward while the collision box
     * reaches only 3.0. That is precisely why mounting cannot go through vanilla's attack targeting,
     * which picks entities by their collision box and would never register a click on the head at all.
     *
     * @param eye   the player's eye position
     * @param look  unit look direction
     * @param reach how far the player can reach, in blocks
     */
    public static boolean rayHitsHead(Vec3 dragonPos, float yRot, float xRot, float ageTicks,
                                      boolean airborne, Vec3 eye, Vec3 look, double reach) {
        Basis basis = basis(yRot, xRot);
        Vec3 anim = riderPointModel(ageTicks, airborne);
        double dy = anim.y - ANCHOR_UP;
        double dz = anim.z - ANCHOR_FORWARD;

        Vec3 fromModel = basis.toModel(eye.subtract(dragonPos));
        Vec3 toModel = basis.toModel(eye.add(look.scale(reach)).subtract(dragonPos));

        // Shift the ray by the animated displacement instead of moving the box, so HEAD_BOX stays a
        // constant and there is nothing per-frame to allocate.
        Vec3 from = fromModel.subtract(0.0D, dy, dz);
        Vec3 to = toModel.subtract(0.0D, dy, dz);
        return HEAD_BOX.clip(from, to).isPresent();
    }

    /** Head box extents in model space, for the harness and for the client's highlight. */
    public static AABB headBoxModel() {
        return HEAD_BOX;
    }
}
