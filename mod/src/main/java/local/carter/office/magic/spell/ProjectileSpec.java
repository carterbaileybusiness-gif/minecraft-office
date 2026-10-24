package local.carter.office.magic.spell;

import net.minecraft.world.phys.Vec3;

/**
 * An entity-free projectile: starts at {@code origin}, moves {@code velocity} blocks per tick, losing {@code gravity} of
 * vertical speed each tick, for at most {@code maxTicks} ticks. {@code radius} widens its entity sweep (blocks use the bare
 * ray). {@code pierce} keeps it flying through entities (each is hit once) until a block or its time stops it.
 * The path is {@link Projectiles#step}, shared with the client fx.
 */
public record ProjectileSpec(Vec3 origin, Vec3 velocity, double gravity, double radius, int maxTicks, boolean pierce) {}
