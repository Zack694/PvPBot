"""Vanilla-accurate (1.21.x) melee physics for the v3 simulator.

Per tick, per fighter (client movement, LivingEntity.travel):
  input (+-1 * 0.98, sneak x0.3, normalized if |v|>1)
  jump: vy = 0.42 (+0.2 along facing when sprinting), 10-tick hold cooldown
  accel: ground 0.1 (0.13 sprinting), air 0.02 (0.026 sprinting)
  move (axis-separated collisions), then vy = (vy - 0.08) * 0.98,
  horizontal *= 0.546 on ground (slipperiness 0.6 * 0.91) / 0.91 in air.

Combat (PlayerEntity.attack + LivingEntity.damage):
  dmg = base * (0.2 + p^2 * 0.8), p = charge at progress(0.5)
  crit x1.5: p > 0.9, falling (fallDistance > 0), airborne, not sprinting
  sprint knockback: p > 0.9 and sprinting -> extra takeKnockback(0.5) along
  the attacker's facing, attacker velocity x0.6 and sprint dropped
  takeKnockback(s, x, z): v = (v/2 - n*s), vy = onGround ? min(0.4, vy/2 + s) : vy
  i-frames: timeUntilRegen 20; while > 10 only damage above the last hit
  counts (difference applied, no knockback, no hurt animation)
  armor: dmg * (1 - clamp(armor - dmg / (2 + tough/4), armor/5, 20) / 25)
"""
import math

GRAVITY = 0.08
REACH = 3.0
EYE = 1.62
EYE_SNEAK = 1.27
HALF_W = 0.3


def wrap(d):
    d = math.fmod(d, 360.0)
    if d >= 180.0:
        d -= 360.0
    if d < -180.0:
        d += 360.0
    return d


class Kit:
    def __init__(self, sword=7.0, armor=20.0, tough=8.0, epf=0.0, kb_res=0.0, cooldown=12.5):
        self.sword = sword
        self.armor = armor
        self.tough = tough
        self.epf = epf          # protection enchant points (0..20)
        self.kb_res = kb_res
        self.cooldown = cooldown

    def reduce(self, dmg):
        f = 2.0 + self.tough / 4.0
        g = min(20.0, max(self.armor * 0.2, self.armor - dmg / f))
        dmg = dmg * (1.0 - g / 25.0)
        if self.epf > 0:
            dmg = dmg * (1.0 - min(20.0, self.epf) / 25.0)
        return dmg


DIAMOND = Kit()
NETHERITE = Kit(sword=8.0, armor=20.0, tough=12.0, kb_res=0.4)


class Body:
    """One fighter's true (server) state."""

    def __init__(self, x, z, yaw, kit):
        self.x, self.y, self.z = x, 0.0, z
        self.vx = self.vy = self.vz = 0.0
        self.yaw = yaw
        self.pitch = 0.0
        self.kit = kit
        self.hp = 20.0
        self.absorption = 0.0
        self.food = 20.0
        self.saturation = 5.0
        self.on_ground = True
        self.sprinting = False
        self.sneaking = False
        self.jump_cd = 0
        self.fall = 0.0
        self.h_collided = False
        self.hurt_time = 0
        self.regen_cd = 0           # timeUntilRegen (i-frames)
        self.last_dmg = 0.0
        self.attack_ticks = 100     # ticks since last attack (charge clock)
        self.swing_ticks = 0        # >0 while the swing animation plays
        self.regen_timer = 0
        self.air_ticks = 0

    # ------------------------------------------------------------ queries
    def height(self):
        return 1.5 if self.sneaking else 1.8

    def eye_y(self):
        return self.y + (EYE_SNEAK if self.sneaking else EYE)

    def charge(self, base=0.0):
        """getAttackCooldownProgress(base)."""
        return max(0.0, min(1.0, (self.attack_ticks + base) / self.kit.cooldown))

    def look(self):
        yr, pr = math.radians(self.yaw), math.radians(self.pitch)
        return (-math.sin(yr) * math.cos(pr), -math.sin(pr), math.cos(yr) * math.cos(pr))

    def speed(self):
        return math.hypot(self.vx, self.vz)


class World:
    def __init__(self, radius=14.0, pillars=None):
        self.r = radius
        self.pillars = pillars or []   # (cx, cz, half_size)

    def _hits_pillar(self, x, z, hw):
        for (px, pz, hs) in self.pillars:
            if abs(x - px) < hs + hw and abs(z - pz) < hs + hw:
                return True
        return False

    def free(self, x, z, hw=HALF_W):
        lim = self.r - hw
        if x < -lim or x > lim or z < -lim or z > lim:
            return False
        return not self._hits_pillar(x, z, hw)

    def segment_blocked(self, x1, z1, x2, z2, steps=10):
        if not self.pillars:
            # convex arena: a segment is blocked only if an endpoint is outside
            r = self.r
            return not (-r < x1 < r and -r < z1 < r and -r < x2 < r and -r < z2 < r)
        for i in range(1, steps + 1):
            t = i / steps
            x = x1 + (x2 - x1) * t
            z = z1 + (z2 - z1) * t
            if abs(x) >= self.r or abs(z) >= self.r or self._hits_pillar(x, z, 0.0):
                return True
        return False

    def probes(self, b):
        """TerrainSense: 8 rays of 2.4 blocks around the facing (0 fwd, 2 right ...)."""
        out = [0.0] * 8
        for i in range(8):
            a = math.radians(b.yaw + i * 45.0)
            dx, dz = -math.sin(a), math.cos(a)
            out[i] = 1.0 if self.segment_blocked(b.x, b.z, b.x + dx * 2.4, b.z + dz * 2.4, 6) else 0.0
        return out


def move_body(b, world):
    """Axis-separated collision move with the current velocity."""
    # vertical (flat floor at y = 0, open sky)
    ny = b.y + b.vy
    was_ground = b.on_ground
    if ny <= 0.0:
        ny = 0.0
        b.vy = 0.0
        b.on_ground = True
    else:
        b.on_ground = False
    dy = ny - b.y
    b.y = ny
    if b.on_ground:
        b.fall = 0.0
    elif dy < 0:
        b.fall += -dy
    b.h_collided = False
    nx = b.x + b.vx
    if world.free(nx, b.z):
        b.x = nx
    else:
        b.vx = 0.0
        b.h_collided = True
    nz = b.z + b.vz
    if world.free(b.x, nz):
        b.z = nz
    else:
        b.vz = 0.0
        b.h_collided = True
    b.air_ticks = 0 if b.on_ground else b.air_ticks + 1
    return was_ground


def physics_tick(b, fwd, strafe_right, jump, sprint_key, sneak, world):
    """One client movement tick. fwd/strafe_right in {-1,0,1} (ActionSpace convention)."""
    b.sneaking = bool(sneak)
    can_sprint = fwd > 0 and not b.sneaking and b.food > 6.0
    if b.sprinting and (not can_sprint or b.h_collided):
        b.sprinting = False
    elif (not b.sprinting) and sprint_key and can_sprint:
        b.sprinting = True

    if b.jump_cd > 0:
        b.jump_cd -= 1
    if jump and b.on_ground and b.jump_cd == 0:
        b.vy = 0.42
        if b.sprinting:
            yr = math.radians(b.yaw)
            b.vx += -math.sin(yr) * 0.2
            b.vz += math.cos(yr) * 0.2
        b.jump_cd = 10
    elif not jump:
        b.jump_cd = 0

    fi = fwd * 0.98
    si = -strafe_right * 0.98          # MC sideways: + = left
    if b.sneaking:
        fi *= 0.3
        si *= 0.3
    l2 = fi * fi + si * si
    if l2 > 1.0:
        n = math.sqrt(l2)
        fi /= n
        si /= n
    friction = 0.546 if b.on_ground else 0.91
    if b.on_ground:
        spd = 0.13 if b.sprinting else 0.1
    else:
        spd = 0.026 if b.sprinting else 0.02
    if l2 > 1e-7:
        yr = math.radians(b.yaw)
        c, s = math.cos(yr), math.sin(yr)
        b.vx += (si * c - fi * s) * spd
        b.vz += (fi * c + si * s) * spd
    move_body(b, world)
    b.vy = (b.vy - GRAVITY) * 0.98
    b.vx *= friction
    b.vz *= friction
    if abs(b.vx) < 0.003:
        b.vx = 0.0
    if abs(b.vz) < 0.003:
        b.vz = 0.0

    # timers
    b.attack_ticks += 1
    if b.swing_ticks > 0:
        b.swing_ticks -= 1
    if b.hurt_time > 0:
        b.hurt_time -= 1
    if b.regen_cd > 0:
        b.regen_cd -= 1


def take_knockback(b, strength, x, z, server_vel_known):
    strength *= (1.0 - b.kit.kb_res)
    if strength <= 0:
        return
    n = math.hypot(x, z)
    if n < 1e-5:
        x, z, n = 0.01, 0.0, 0.01
    nx, nz = x / n * strength, z / n * strength
    vx, vy, vz = (b.vx, b.vy, b.vz) if server_vel_known else (0.0, 0.0, 0.0)
    b.vx = vx / 2.0 - nx
    b.vz = vz / 2.0 - nz
    if b.on_ground:
        b.vy = min(0.4, vy / 2.0 + strength)


def ray_hits(eye, look, o_x, o_y, o_z, o_h, reach=REACH, hw=HALF_W):
    """Slab test: does the look ray hit the AABB within reach? Returns distance or None."""
    ex, ey, ez = eye
    lo = (o_x - hw, o_y, o_z - hw)
    hi = (o_x + hw, o_y + o_h, o_z + hw)
    tmin, tmax = 0.0, reach
    for o, d, a, bnd in ((ex, look[0], lo[0], hi[0]), (ey, look[1], lo[1], hi[1]), (ez, look[2], lo[2], hi[2])):
        if abs(d) < 1e-9:
            if o < a or o > bnd:
                return None
        else:
            t1 = (a - o) / d
            t2 = (bnd - o) / d
            if t1 > t2:
                t1, t2 = t2, t1
            tmin = max(tmin, t1)
            tmax = min(tmax, t2)
            if tmin > tmax:
                return None
    return tmin


def attack(att, vic, rng, server_vel_known):
    """Resolve a landed click (ray already validated by the caller).
    Returns (damage_applied, crit, full_hit)."""
    p = att.charge(0.5)
    att.attack_ticks = 0
    att.swing_ticks = 6
    dmg = att.kit.sword * (0.2 + p * p * 0.8)
    strong = p > 0.9
    crit = strong and att.fall > 0.0 and not att.on_ground and not att.sprinting
    if crit:
        dmg *= 1.5
    sprint_kb = strong and att.sprinting
    dmg = vic.kit.reduce(dmg)
    # i-frames
    if vic.regen_cd > 10:
        if dmg <= vic.last_dmg:
            return 0.0, False, False
        applied = dmg - vic.last_dmg
        vic.last_dmg = dmg
        _apply(vic, applied)
        return applied, crit, False
    vic.last_dmg = dmg
    vic.regen_cd = 20
    vic.hurt_time = 10
    _apply(vic, dmg)
    take_knockback(vic, 0.4, att.x - vic.x, att.z - vic.z, server_vel_known)
    if sprint_kb:
        yr = math.radians(att.yaw)
        take_knockback(vic, 0.5, math.sin(yr), -math.cos(yr), True)
        att.vx *= 0.6
        att.vz *= 0.6
        att.sprinting = False
    return dmg, crit, True


def _apply(vic, dmg):
    if vic.absorption > 0:
        a = min(vic.absorption, dmg)
        vic.absorption -= a
        dmg -= a
    vic.hp -= dmg


def regen_tick(b, mode):
    """mode 0 none, 1 slow natural (1 hp / 80 t), 2 saturation-fast then slow."""
    if mode == 0 or b.hp <= 0 or b.hp >= 20.0:
        return
    b.regen_timer += 1
    if mode == 2 and b.saturation > 0 and b.food >= 20:
        if b.regen_timer >= 10:
            heal = min(1.0, b.saturation / 6.0 + 0.5)
            b.hp = min(20.0, b.hp + heal)
            b.saturation = max(0.0, b.saturation - 1.5)
            b.regen_timer = 0
    elif b.regen_timer >= 80:
        b.hp = min(20.0, b.hp + 1.0)
        b.regen_timer = 0
