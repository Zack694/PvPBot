"""Simplified but faithful Minecraft 1.9+ sword-PvP physics simulator.

Mirrors the Java mod's state encoding (56 dims) and action space (72 actions)
so weights trained here load directly into the live client-side bot.
Physics approximates vanilla: ground/air acceleration, sprint, jump + sprint
boost, gravity/drag, 1.9 knockback with sprint bonus, attack-cooldown damage
scaling, crits (require falling + not sprinting).

Kit (mcpvp.club Sword kit): DIAMOND SWORD + FULL DIAMOND ARMOR, nothing else
— no food, no golden apples, NO healing of any kind: hp only ever goes down.
"""
import math
import numpy as np

TICK = 1.0 / 20.0
ARENA = 12.0          # walls at +-12
REACH = 3.0
COOLDOWN_TICKS = 12.5
SWORD_DAMAGE = 7.0    # diamond sword
ARMOR, TOUGH = 20.0, 8.0   # full diamond armor (20 armor points, 8 toughness)
WALK_SPEED = 0.216
SPRINT_SPEED = 0.2806


def armor_reduce(dmg):
    capped = min(20.0, max(ARMOR / 5.0, ARMOR - dmg / (2.0 + TOUGH / 4.0)))
    return dmg * (1.0 - capped / 25.0)


class Agent:
    def __init__(self, x, z):
        self.x = x
        self.z = z
        self.vx = 0.0
        self.vz = 0.0
        self.vy = 0.0
        self.y = 0.0            # height above ground
        self.yaw = 0.0          # degrees, MC convention (0 = +Z)
        self.hp = 20.0
        self.sprinting = False
        self.on_ground = True
        self.air_ticks = 0
        self.since_attack = 999
        self.last_attack_tick = -999
        self.jump_requested = False

    @property
    def speed(self):
        return math.hypot(self.vx, self.vz)

    def cooldown_progress(self):
        return min(1.0, self.since_attack / COOLDOWN_TICKS)

    def apply_action(self, move, sprint, jump):
        # move -> (fwd, strafe) in facing frame
        dirs = {0: (0, 0), 1: (1, 0), 2: (-1, 0), 3: (0, -1), 4: (0, 1),
                5: (1, -1), 6: (1, 1), 7: (-1, -1), 8: (-1, 1)}
        fwd, strafe = dirs[move]
        self.sprinting = bool(sprint and fwd > 0)
        if jump and self.on_ground:
            self.vy = 0.42
            self.on_ground = False
            self.air_ticks = 0
            if self.sprinting:
                # vanilla sprint-jump boost
                self.vx += 0.2 * self.fx()
                self.vz += 0.2 * self.fz()
        if self.on_ground:
            target = SPRINT_SPEED if self.sprinting else WALK_SPEED
            mag = math.hypot(fwd, strafe)
            if mag > 0:
                fx, fz = self.fx(), self.fz()
                rx, rz = -fz, fx
                tx = (fx * fwd + rx * strafe) / mag * target
                tz = (fz * fwd + rz * strafe) / mag * target
                speed_now = math.hypot(self.vx, self.vz)
                if speed_now > target * 1.25:
                    # knockback regime: friction decay + weak steering
                    self.vx *= 0.6
                    self.vz *= 0.6
                    self.vx += 0.03 * tx / target
                    self.vz += 0.03 * tz / target
                else:
                    self.vx += (tx - self.vx) * 0.5
                    self.vz += (tz - self.vz) * 0.5
            else:
                self.vx *= 0.6
                self.vz *= 0.6
        else:
            self.vx += fwd * 0.02 * self.fx()
            self.vz += strafe * 0.02 * self.fz()

    def fx(self):
        return -math.sin(math.radians(self.yaw))

    def fz(self):
        return math.cos(math.radians(self.yaw))

    def integrate(self):
        # gravity / drag
        self.vy = (self.vy - 0.08) * 0.98
        self.y += self.vy
        if self.y <= 0.0:
            self.y = 0.0
            self.vy = 0.0
            if not self.on_ground:
                self.on_ground = True
                self.air_ticks = 0
        else:
            self.on_ground = False
            self.air_ticks += 1
        if not self.on_ground:
            self.vx *= 0.91
            self.vz *= 0.91
        self.x += self.vx
        self.z += self.vz
        # arena walls
        lim = ARENA - 0.4
        if abs(self.x) > lim:
            self.x = math.copysign(lim, self.x)
            self.vx = 0.0
        if abs(self.z) > lim:
            self.z = math.copysign(lim, self.z)
            self.vz = 0.0
        self.since_attack += 1


class World:
    def __init__(self, rng, pillars=True):
        self.rng = rng
        self.pillars = []
        if pillars and rng.random() < 0.7:
            for _ in range(rng.integers(1, 4)):
                px = float(rng.uniform(-9, 9))
                pz = float(rng.uniform(-9, 9))
                self.pillars.append((px, pz, float(rng.uniform(0.6, 1.4))))

    def blocked(self, x, z, r=0.35):
        if abs(x) > ARENA - 0.5 or abs(z) > ARENA - 0.5:
            return True
        for (px, pz, rad) in self.pillars:
            if (x - px) ** 2 + (z - pz) ** 2 < (rad + r) ** 2:
                return True
        return False

    def line_blocked(self, x1, z1, x2, z2):
        n = 8
        for i in range(1, n):
            t = i / n
            if self.blocked(x1 + (x2 - x1) * t, z1 + (z2 - z1) * t, r=0.05):
                return True
        return False


def try_attack(att: Agent, dfd: Agent, world: World):
    """Attempt attack. Returns (hit, damage, crit, whiff)."""
    cp = att.cooldown_progress()
    base = SWORD_DAMAGE * (0.2 + cp * cp * 0.8)
    crit = (not att.on_ground) and att.vy < 0 and (not att.sprinting)
    if crit:
        base *= 1.5
    dx = dfd.x - att.x
    dz = dfd.z - att.z
    dist = math.hypot(dx, dz)
    # cone check (auto-aim quality handled outside; small cone = facing discipline)
    bearing = math.degrees(math.atan2(-dx, dz))
    rel = (bearing - att.yaw + 180.0) % 360.0 - 180.0
    if dist > REACH or abs(rel) > 16.0 or world.line_blocked(att.x, att.z, dfd.x, dfd.z):
        return (False, 0.0, False, True)
    dmg = armor_reduce(base)
    # knockback: from attacker position toward defender
    nx, nz = dx / max(dist, 1e-6), dz / max(dist, 1e-6)
    kb = 0.4
    dfd.vx = nx * kb
    dfd.vz = nz * kb
    if att.sprinting:
        dfd.vx += nx * 0.5
        dfd.vz += nz * 0.5
    dfd.vy = 0.36
    dfd.on_ground = False
    dfd.hp -= dmg
    att.since_attack = 0
    att.last_attack_tick = 0
    return (True, dmg, crit, False)
