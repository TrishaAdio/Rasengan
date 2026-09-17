package dev.rasengan.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.rasengan.PowerState;

/**
 * The authoritative POWER BAR state for one player. Lives only on the server.
 *
 * <p>Stored as a data attachment on the {@code ServerPlayer} so it is written into that
 * player's save data. That gives two properties the brief asks for: the value survives a
 * server restart, and it survives a dimension change (which recreates the player entity but
 * copies attachments).
 *
 * <p>Mutable by design - it is ticked in place once per player per tick.
 */
public final class PowerData {
    public static final MapCodec<PowerData> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.optionalFieldOf("charge_ticks", 0).forGetter(d -> d.chargeTicks),
            Codec.INT.optionalFieldOf("cooldown_ticks", 0).forGetter(d -> d.cooldownTicks),
            Codec.INT.optionalFieldOf("state", PowerState.CHARGING.id()).forGetter(d -> d.state.id())
    ).apply(instance, PowerData::new));

    private int chargeTicks;
    private int cooldownTicks;
    private PowerState state;

    /** Set when the in-memory value differs from what the client was last told. */
    private transient boolean dirty = true;

    /** Tick countdown until the next heartbeat sync. */
    private transient int syncCountdown;

    public PowerData() {
        this(0, 0, PowerState.CHARGING.id());
    }

    private PowerData(int chargeTicks, int cooldownTicks, int stateId) {
        this.chargeTicks = chargeTicks;
        this.cooldownTicks = cooldownTicks;
        this.state = PowerState.byId(stateId);
    }

    public int chargeTicks() {
        return chargeTicks;
    }

    public int cooldownTicks() {
        return cooldownTicks;
    }

    public PowerState state() {
        return state;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean tickSyncCountdown(int interval) {
        if (--syncCountdown <= 0) {
            syncCountdown = interval;
            return true;
        }
        return false;
    }

    public void setState(PowerState next) {
        if (this.state != next) {
            this.state = next;
            markDirty();
        }
    }

    public void setChargeTicks(int value) {
        if (this.chargeTicks != value) {
            this.chargeTicks = value;
            markDirty();
        }
    }

    public void setCooldownTicks(int value) {
        if (this.cooldownTicks != value) {
            this.cooldownTicks = value;
            markDirty();
        }
    }

    /** Advances charge by one tick without exceeding the configured maximum. */
    public void advanceCharge(int max) {
        if (chargeTicks < max) {
            chargeTicks++;
            // Deliberately not marking dirty every tick: the client predicts this increment
            // itself. Only transitions and the periodic heartbeat push a packet.
        }
    }

    public void decrementCooldown() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
    }

    /** Full reset used after a successful cast and by the reset-on-death path. */
    public void resetCharge() {
        setChargeTicks(0);
    }
}
