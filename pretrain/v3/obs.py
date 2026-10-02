"""OBSERVATION v4 — exact Python mirror of dev.z.pvpbot.ml.obs.ObsV4 (Java).

Line-for-line port. Any change here MUST be made in ObsV4.java too; the
JUnit parity test (src/test/java/.../ObsV4ParityTest.java) replays a fixture
recorded from this module through the Java class and fails on any mismatch.
"""
import math

DIM = 100
VERSION = 4
H = 40
NEVER = -1_000_000


class Fighter:
    __slots__ = ("x", "y", "z", "yaw", "pitch", "health", "absorption", "width", "height",
                 "on_ground", "sprinting", "sneaking", "swinging", "using_item", "hurt_time")

    def __init__(self):
        self.x = self.y = self.z = 0.0
        self.yaw = self.pitch = 0.0
        self.health = 20.0
        self.absorption = 0.0
        self.width = 0.6
        self.height = 1.8
        self.on_ground = True
        self.sprinting = False
        self.sneaking = False
        self.swinging = False
        self.using_item = False
        self.hurt_time = 0

    def eye_y(self):
        return self.y + (1.27 if self.sneaking else 1.62)

    def copy(self):
        f = Fighter()
        for k in Fighter.__slots__:
            setattr(f, k, getattr(self, k))
        return f

    def to_dict(self):
        return {k: getattr(self, k) for k in Fighter.__slots__}


class Frame:
    __slots__ = ("me", "them", "my_charge", "food", "my_move", "my_jump_held", "terrain",
                 "drop_ahead", "ceiling_low", "los", "crosshair_on_target",
                 "i_swung", "i_hit_them", "dmg_dealt", "i_crit", "i_was_hit", "dmg_taken")

    def __init__(self):
        self.me = Fighter()
        self.them = Fighter()
        self.my_charge = 1.0
        self.food = 20.0
        self.my_move = 0
        self.my_jump_held = False
        self.terrain = [0.0] * 8
        self.drop_ahead = 0.0
        self.ceiling_low = 0.0
        self.los = True
        self.crosshair_on_target = False
        self.i_swung = False
        self.i_hit_them = False
        self.dmg_dealt = 0.0
        self.i_crit = False
        self.i_was_hit = False
        self.dmg_taken = 0.0

    def to_dict(self):
        d = {k: getattr(self, k) for k in Frame.__slots__ if k not in ("me", "them")}
        d["terrain"] = list(self.terrain)
        d["me"] = self.me.to_dict()
        d["them"] = self.them.to_dict()
        return d


def clamp(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


def wrap(deg):
    # Java: d = deg % 360 (sign follows dividend), then fold
    d = math.fmod(deg, 360.0)
    if d >= 180.0:
        d -= 360.0
    if d < -180.0:
        d += 360.0
    return d


def f32(v):
    """Round-trip through float32 (Java float fields)."""
    import struct
    return struct.unpack("f", struct.pack("f", v))[0]


def b(v):
    return 1.0 if v else 0.0


def move_fwd(m):
    return 1 if m in (1, 5, 6) else (-1 if m in (2, 7, 8) else 0)


def move_str(m):
    return 1 if m in (4, 6, 8) else (-1 if m in (3, 5, 7) else 0)


def reach_dist(ex, ey, ez, o):
    hw = o.width * 0.5
    cx = clamp(ex, o.x - hw, o.x + hw)
    cy = clamp(ey, o.y, o.y + o.height)
    cz = clamp(ez, o.z - hw, o.z + hw)
    ax, ay, az = ex - cx, ey - cy, ez - cz
    return math.sqrt(ax * ax + ay * ay + az * az)


class ObsV4:
    def __init__(self):
        self.t = 0
        self.cur = Frame()
        self.reset_opponent()

    # ------------------------------------------------------------ lifecycle
    def reset_episode(self):
        self.episode_start = self.t
        self.combo_dealt = 0
        self.combo_taken = 0
        self.dmg_dealt = 0.0
        self.dmg_taken = 0.0
        self.momentum = 0.0
        self.last_my_hit = NEVER
        self.last_taken = NEVER
        self.last_my_swing = NEVER
        self.last_their_swing = NEVER
        self.last_their_jump = NEVER
        self.has_prev = False
        self.prev_me = None
        self.prev_them = None
        self.my_vx = self.my_vy = self.my_vz = 0.0
        self.th_vx = self.th_vy = self.th_vz = 0.0
        self.th_svx = self.th_svz = 0.0
        self.prev_hdist = -1.0
        self.closing = 0.0
        self.their_air_ticks = 0
        self.prev_their_swinging = False
        self.prev_their_ground = True
        self.have_their_yaw = False
        self.prev_their_yaw = 0.0
        self.reset_watch_until = -1
        self.reset_watch_toward = 0.0
        self.jump_watch_until = -1
        self.acc_watch_swing = -1
        self.their_acc_watch = -1
        self.filled = 0
        self.r_their_swing_edge = [False] * H
        self.r_their_air = [False] * H
        self.r_their_sneak = [False] * H
        self.r_their_strafe = [0] * H
        self.r_my_swing = [False] * H
        self.r_my_hit = [False] * H
        self.r_taken = [False] * H
        self.r_my_jump = [False] * H
        self.r_my_fwd = [0] * H
        self.r_my_str = [0] * H
        self.r_my_sprint = [False] * H
        self.r_their_radial = [0.0] * H
        self.last_their_toward = 0.0
        self.last_their_lateral = 0.0

    def reset_opponent(self):
        self.reset_episode()
        self.their_swing_interval = 0.0
        self.their_yaw_rate = 0.0
        self.reset_rate = 0.3
        self.jump_reset_rate = 0.3
        self.their_accuracy = 0.4
        self.their_swing_dist = 3.0
        self.their_speed = 0.15
        self.my_accuracy = 0.5

    # ------------------------------------------------------------------ tick
    def tick(self, f):
        self.t += 1
        t = self.t
        self.cur = f
        me, th = f.me, f.them

        if self.has_prev:
            pm, pt = self.prev_me, self.prev_them
            mdx, mdy, mdz = me.x - pm.x, me.y - pm.y, me.z - pm.z
            tdx, tdy, tdz = th.x - pt.x, th.y - pt.y, th.z - pt.z
            tele = (abs(mdx) > 2 or abs(mdz) > 2 or abs(mdy) > 3
                    or abs(tdx) > 2 or abs(tdz) > 2 or abs(tdy) > 3)
            if tele:
                self.my_vx = self.my_vy = self.my_vz = 0.0
                self.th_vx = self.th_vy = self.th_vz = 0.0
                self.th_svx = self.th_svz = 0.0
                self.prev_hdist = -1.0
            else:
                self.my_vx, self.my_vy, self.my_vz = mdx, mdy, mdz
                self.th_vx, self.th_vy, self.th_vz = tdx, tdy, tdz
                self.th_svx += 0.5 * (self.th_vx - self.th_svx)
                self.th_svz += 0.5 * (self.th_vz - self.th_svz)
        self.prev_me = me.copy()
        self.prev_them = th.copy()
        self.has_prev = True

        dx, dz = th.x - me.x, th.z - me.z
        hd = math.sqrt(dx * dx + dz * dz)
        self.closing = 0.0 if self.prev_hdist < 0 else (self.prev_hdist - hd)
        self.prev_hdist = hd
        inv = 1.0 / hd if hd > 1e-6 else 0.0
        ux, uz = dx * inv, dz * inv
        their_toward = -(self.th_vx * ux + self.th_vz * uz)
        their_lateral = self.th_vx * uz - self.th_vz * ux
        t_speed = math.sqrt(self.th_vx * self.th_vx + self.th_vz * self.th_vz)

        # their swing rhythm
        edge = th.swinging and not self.prev_their_swinging
        self.prev_their_swinging = th.swinging
        if edge:
            gap = t - self.last_their_swing
            if self.last_their_swing > NEVER and 3 <= gap <= 60:
                if self.their_swing_interval <= 0.0:
                    self.their_swing_interval = f32(float(gap))
                else:
                    self.their_swing_interval = f32(self.their_swing_interval + 0.25 * (gap - self.their_swing_interval))
            self.last_their_swing = t
            self.their_swing_dist = f32(self.their_swing_dist + 0.2 * (f32(hd) - self.their_swing_dist))
            if self.reset_watch_until < t:
                self.reset_watch_until = t + 8
                self.reset_watch_toward = their_toward
            if self.their_acc_watch >= 0:
                self.their_accuracy = f32(self.their_accuracy + 0.15 * (0.0 - self.their_accuracy))
            self.their_acc_watch = t
        if self.reset_watch_until >= t and self.reset_watch_until - t < 8:
            if self.reset_watch_toward > 0.12 and their_toward < self.reset_watch_toward - 0.10:
                self.reset_rate = f32(self.reset_rate + 0.2 * (1.0 - self.reset_rate))
                self.reset_watch_until = -1
            elif self.reset_watch_until == t:
                self.reset_rate = f32(self.reset_rate + 0.2 * (0.0 - self.reset_rate))
                self.reset_watch_until = -1

        # their own take-offs
        air = not th.on_ground
        if air and self.prev_their_ground and self.th_vy > 0.2 and th.hurt_time < 9:
            self.last_their_jump = t
            if self.jump_watch_until >= t:
                self.jump_reset_rate = f32(self.jump_reset_rate + 0.2 * (1.0 - self.jump_reset_rate))
                self.jump_watch_until = -1
        if 0 <= self.jump_watch_until < t:
            self.jump_reset_rate = f32(self.jump_reset_rate + 0.2 * (0.0 - self.jump_reset_rate))
            self.jump_watch_until = -1
        self.prev_their_ground = th.on_ground
        self.their_air_ticks = self.their_air_ticks + 1 if air else 0

        # their look activity
        if self.have_their_yaw:
            dyaw = abs(f32(wrap(f32(f32(th.yaw) - f32(self.prev_their_yaw)))))
            self.their_yaw_rate = f32(self.their_yaw_rate + 0.2 * (dyaw - self.their_yaw_rate))
        self.prev_their_yaw = th.yaw
        self.have_their_yaw = True
        self.their_speed = f32(self.their_speed + 0.1 * (f32(t_speed) - self.their_speed))

        # events
        if f.i_swung:
            self.last_my_swing = t
            if self.acc_watch_swing >= 0:
                self.my_accuracy = f32(self.my_accuracy + 0.15 * (0.0 - self.my_accuracy))
            self.acc_watch_swing = t
        if f.i_hit_them:
            self.last_my_hit = t
            self.combo_dealt += 1
            self.combo_taken = 0
            self.dmg_dealt = f32(self.dmg_dealt + f32(f.dmg_dealt))
            self.jump_watch_until = t + 12
            if self.acc_watch_swing >= 0:
                self.my_accuracy = f32(self.my_accuracy + 0.15 * (1.0 - self.my_accuracy))
                self.acc_watch_swing = -1
        if self.acc_watch_swing >= 0 and t - self.acc_watch_swing > 8:
            self.my_accuracy = f32(self.my_accuracy + 0.15 * (0.0 - self.my_accuracy))
            self.acc_watch_swing = -1
        if f.i_was_hit:
            self.last_taken = t
            self.combo_taken += 1
            self.combo_dealt = 0
            self.dmg_taken = f32(self.dmg_taken + f32(f.dmg_taken))
            if self.their_acc_watch >= 0:
                self.their_accuracy = f32(self.their_accuracy + 0.15 * (1.0 - self.their_accuracy))
                self.their_acc_watch = -1
        if self.their_acc_watch >= 0 and t - self.their_acc_watch > 8:
            self.their_accuracy = f32(self.their_accuracy + 0.15 * (0.0 - self.their_accuracy))
            self.their_acc_watch = -1
        if t - self.last_taken > 40:
            self.combo_taken = 0
        if t - self.last_my_hit > 40:
            self.combo_dealt = 0
        bal = f32((f32(f.dmg_dealt) if f.i_hit_them else 0.0) - (f32(f.dmg_taken) if f.i_was_hit else 0.0))
        self.momentum = f32(self.momentum + 0.05 * (bal - self.momentum))

        i = t % H
        self.r_their_swing_edge[i] = edge
        self.r_their_air[i] = air
        self.r_their_sneak[i] = th.sneaking
        self.r_their_strafe[i] = 1 if their_lateral > 0.03 else (-1 if their_lateral < -0.03 else 0)
        self.r_their_radial[i] = f32(their_toward)
        self.r_my_swing[i] = f.i_swung
        self.r_my_hit[i] = f.i_hit_them
        self.r_taken[i] = f.i_was_hit
        self.r_my_jump[i] = f.my_jump_held or ((not me.on_ground) and self.my_vy > 0.2)
        self.r_my_fwd[i] = move_fwd(f.my_move)
        self.r_my_str[i] = move_str(f.my_move)
        self.r_my_sprint[i] = me.sprinting
        if self.filled < H:
            self.filled += 1
        self.last_their_toward = their_toward
        self.last_their_lateral = their_lateral

    # ----------------------------------------------------------------- build
    def _since(self, ev, tau):
        dt = self.t - ev
        if dt < 0:
            dt = 0
        if dt > 100000:
            return 1.0
        return 1.0 - math.exp(-dt / tau)

    def _idx(self, k):
        return (self.t - k) % H

    def _count(self, r, n):
        m = min(n, self.filled)
        c = 0
        for k in range(m):
            if r[(self.t - k) % H]:
                c += 1
        return c

    def _mean(self, r, n):
        m = min(n, self.filled)
        if m == 0:
            return 0.0
        s = 0
        for k in range(m):
            s += r[(self.t - k) % H]
        return s / m

    def _changes(self, r, n):
        m = min(n, self.filled)
        c = 0
        last = 0
        for k in range(m):
            v = r[(self.t - k) % H]
            if v != 0:
                if last != 0 and v != last:
                    c += 1
                last = v
        return c

    def _strafe_streak(self):
        if self.filled == 0:
            return 0
        s0 = self.r_their_strafe[self.t % H]
        if s0 == 0:
            return 0
        c = 0
        for k in range(self.filled):
            if self.r_their_strafe[(self.t - k) % H] == s0:
                c += 1
            else:
                break
        return c

    def _toward_streak(self, toward):
        c = 0
        for k in range(self.filled):
            v = self.r_their_radial[(self.t - k) % H]
            if (v > 0.05) if toward else (v < -0.05):
                c += 1
            else:
                break
        return c

    def build(self):
        s = [0.0] * DIM
        f = self.cur
        me, th = f.me, f.them
        t = self.t
        yr = math.radians(me.yaw)
        fx, fz = -math.sin(yr), math.cos(yr)
        rx, rz = -fz, fx
        dx, dz = th.x - me.x, th.z - me.z
        hd = math.sqrt(dx * dx + dz * dz)
        myvx, myvy, myvz = self.my_vx, self.my_vy, self.my_vz
        thvx, thvy, thvz = self.th_vx, self.th_vy, self.th_vz

        # A. self
        s[0] = clamp(me.health / 20.0, 0, 1.5)
        s[1] = clamp(me.absorption / 20.0, 0, 1)
        s[2] = clamp(f.food / 20.0, 0, 1)
        s[3] = clamp(f.my_charge, 0, 1)
        s[4] = b(me.sprinting)
        s[5] = b(me.on_ground)
        s[6] = b(me.sneaking)
        s[7] = clamp(myvy / 0.4, -2, 2)
        s[8] = clamp((myvx * fx + myvz * fz) / 0.28, -2, 2)
        s[9] = clamp((myvx * rx + myvz * rz) / 0.28, -2, 2)
        s[10] = clamp(me.hurt_time / 10.0, 0, 1)
        s[11] = self._since(self.last_my_hit, 10)
        s[12] = self._since(self.last_taken, 10)
        s[13] = self._since(self.last_my_swing, 10)
        s[14] = clamp(self.combo_dealt / 4.0, 0, 1.5)
        s[15] = clamp(self.combo_taken / 4.0, 0, 1.5)
        s[16] = move_fwd(f.my_move)
        s[17] = move_str(f.my_move)
        s[18] = b(f.my_jump_held)

        # B. relative geometry
        my_reach = reach_dist(me.x, me.eye_y(), me.z, th)
        their_reach = reach_dist(th.x, th.eye_y(), th.z, me)
        s[19] = clamp(hd / 3.0, 0, 4)
        s[20] = clamp(my_reach / 3.0, 0, 4)
        s[21] = b(my_reach <= 3.0)
        s[22] = clamp(their_reach / 3.0, 0, 4)
        s[23] = clamp((th.y - me.y) / 2.0, -2, 2)
        bearing = math.degrees(math.atan2(-dx, dz))
        rel_yaw = wrap(bearing - me.yaw)
        s[24] = math.sin(math.radians(rel_yaw))
        s[25] = math.cos(math.radians(rel_yaw))
        s[26] = rel_yaw / 180.0
        chest_y = th.y + th.height * 0.6
        pitch_need = -math.degrees(math.atan2(chest_y - me.eye_y(), max(1e-6, hd)))
        s[27] = clamp(wrap(pitch_need - me.pitch) / 45.0, -2, 2)
        s[28] = b(f.crosshair_on_target)
        s[29] = b(f.los)
        s[30] = clamp(math.degrees(math.atan2(th.width * 0.5, max(0.3, hd))) / 10.0, 0, 2)
        s[31] = clamp(self.closing / 0.5, -2, 2)
        inv = 1.0 / hd if hd > 1e-6 else 0.0
        ux, uz = dx * inv, dz * inv
        s[32] = clamp((myvx * ux + myvz * uz) / 0.3, -2, 2)
        s[33] = clamp(self.last_their_toward / 0.3, -2, 2)
        s[34] = clamp((thvx * fx + thvz * fz) / 0.3, -2, 2)
        s[35] = clamp((thvx * rx + thvz * rz) / 0.3, -2, 2)
        s[36] = clamp(thvy / 0.4, -2, 2)

        # C. them
        s[37] = clamp(th.health / 20.0, 0, 1.5)
        s[38] = clamp(th.absorption / 20.0, 0, 1)
        s[39] = clamp((me.health - th.health) / 20.0, -1, 1)
        s[40] = b(th.on_ground)
        s[41] = b(th.sprinting)
        s[42] = b(th.sneaking)
        s[43] = clamp(th.hurt_time / 10.0, 0, 1)
        s[44] = b(th.swinging)
        s[45] = self._since(self.last_their_swing, 10)
        s[46] = clamp((t - self.last_their_swing) / 12.5, 0, 1)
        tyr, tpr = math.radians(th.yaw), math.radians(th.pitch)
        tlx = -math.sin(tyr) * math.cos(tpr)
        tly = -math.sin(tpr)
        tlz = math.cos(tyr) * math.cos(tpr)
        mcx, mcy, mcz = me.x - th.x, (me.y + me.height * 0.6) - th.eye_y(), me.z - th.z
        ml = max(1e-6, math.sqrt(mcx * mcx + mcy * mcy + mcz * mcz))
        their_aim_err = math.degrees(math.acos(clamp((tlx * mcx + tly * mcy + tlz * mcz) / ml, -1, 1)))
        s[47] = clamp(their_aim_err / 90.0, 0, 2)
        s[48] = clamp(self.their_yaw_rate / 30.0, 0, 2)
        their_bearing = math.degrees(math.atan2(-(me.x - th.x), me.z - th.z))
        s[49] = math.sin(math.radians(wrap(their_bearing - th.yaw)))
        s[50] = b(their_reach <= 3.0 and their_aim_err < 25.0)
        s[51] = b((not th.on_ground) and thvy < 0)
        s[52] = clamp(self.their_air_ticks / 10.0, 0, 1.5)

        # D. prediction
        for k in range(2):
            lead = 3 if k == 0 else 6
            px = th.x + self.th_svx * lead - me.x
            pz = th.z + self.th_svz * lead - me.z
            s[53 + 2 * k] = clamp((px * fx + pz * fz) / 3.0, -3, 3)
            s[54 + 2 * k] = clamp((px * rx + pz * rz) / 3.0, -3, 3)
        pdx = (th.x + self.th_svx * 3) - (me.x + myvx * 3)
        pdz = (th.z + self.th_svz * 3) - (me.z + myvz * 3)
        s[57] = clamp(math.sqrt(pdx * pdx + pdz * pdz) / 3.0, 0, 4)
        s[58] = clamp((hd - 3.0) / self.closing / 20.0, 0, 1) if self.closing > 0.02 else 1.0

        # E. opponent rhythm
        s[59] = clamp(self._count(self.r_their_swing_edge, 10) / 3.0, 0, 1.5)
        s[60] = clamp(self._count(self.r_their_swing_edge, 40) / 6.0, 0, 1.5)
        s[61] = clamp(self.their_swing_interval / 20.0, 0, 2)
        s[62] = self._count(self.r_their_air, 20) / 20.0
        s[63] = self._count(self.r_their_sneak, 20) / 20.0
        s[64] = float(self.r_their_strafe[t % H])
        s[65] = clamp(self._strafe_streak() / 20.0, 0, 1.5)
        s[66] = clamp(self._changes(self.r_their_strafe, 40) / 8.0, 0, 1.5)
        s[67] = clamp(self._toward_streak(True) / 20.0, 0, 1.5)
        s[68] = clamp(self._toward_streak(False) / 20.0, 0, 1.5)
        s[69] = self.reset_rate
        s[70] = self.jump_reset_rate
        s[71] = self._since(self.last_their_jump, 20)
        s[72] = self.their_accuracy
        s[73] = clamp(self.their_swing_dist / 3.0, 0, 2)
        s[74] = clamp(self.their_speed / 0.28, 0, 2)

        # F. my history
        s[75] = clamp(self._count(self.r_my_swing, 20) / 3.0, 0, 1.5)
        s[76] = clamp(self._count(self.r_my_hit, 40) / 4.0, 0, 1.5)
        s[77] = self._count(self.r_my_jump, 20) / 20.0
        s[78] = self._mean(self.r_my_fwd, 10)
        s[79] = self._mean(self.r_my_str, 10)
        s[80] = clamp(self._changes(self.r_my_str, 20) / 6.0, 0, 1.5)
        s[81] = self._count(self.r_my_sprint, 10) / 10.0
        s[82] = self.my_accuracy

        # G. terrain
        for k in range(8):
            s[83 + k] = f.terrain[k]
        s[91] = f.drop_ahead
        s[92] = f.ceiling_low

        # H. match
        s[93] = self._since(self.episode_start, 600)
        s[94] = clamp(self.dmg_dealt / 20.0, 0, 2)
        s[95] = clamp(self.dmg_taken / 20.0, 0, 2)
        s[96] = clamp((self.dmg_dealt - self.dmg_taken) / 10.0, -2, 2)
        s[97] = clamp(self.momentum / 0.5, -2, 2)
        s[98] = clamp(self._count(self.r_taken, 40) / 4.0, 0, 1.5)
        s[99] = 1.0
        return s
