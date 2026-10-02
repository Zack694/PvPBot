"""Classic (v1) brain mirror for the v3 simulator.

Mirrors, as of v2.3.1:
  Perception.build (64 dims) + the HitWatcher / OpponentMemory fields it reads
  CombatTactics.movePolicy (escape > backoff > crit window > W/S-tap window >
      intercept chase > over-retreat > combo strafe > anti-freeze > far remap)
  BotController v1 applyAction: sprint-hit law, jump reset (fires on landing),
      escape hop, crit/mid-air technique jumps, 10+ chase hops, TriggerBot
      click inside the rolled band with the sprint gate, crit-descent gate and
      sneak-at-click
  Humanizer.submit: single-slot reaction queue (decisions made while one is in
      flight are dropped; the EXECUTED action is what gets stored)
The DecisionMind / AdaptiveEngine votes are not simulated (neutral defaults).
"""
import math

from bots import LearnerCtl, MOVE_VEC, BACK_MOVES, on_target, dist3, Tracker
from physics import wrap

DIM = 64


def clamp(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


def move_of(a):
    return max(0, min(8, a >> 3))


def sprint_of(a):
    return (a & 4) != 0


def jump_of(a):
    return (a & 2) != 0


class V1Cfg:
    band_min = 0.82
    band_max = 0.96
    react_min = 1
    react_max = 3
    click_max_dist = 3.2
    win_reward = 45.0
    loss_reward = -10.0
    wtap_chance = 0.90
    wtap_pure_s = 0.80
    wtap_ms = (550, 650)
    jreset_ms = (100, 150)
    sneak_chance = 0.18
    sneak_jump_chance = 0.06
    sneak_cd = 40
    crit_chance = 0.15
    crit_cd = 100
    midair_chance = 0.10
    midair_cd = 80
    too_close = 1.35
    backoff_release = 2.1
    backoff_max = 24
    sprint_patience = 60
    combo_breaker = True
    crit_denial = True
    hit_select = False     # wiki: sprint-hit right after the opponent swings (less KB taken)
    hit_select_wait = 4
    pcrit = False          # wiki: crit off the vertical KB of their hit (no own jump)
    fifo = True            # v2.3.3 Java parity: no-drop reaction delay line
    combo_strafe = True
    backoff_on = True
    wtap_on = True
    immediate = True       # v2.3.5 Java default: click the moment the sword is strong-charged + crosshair on hitbox
    imm_thr = 0.87         # stored charge 0.87 -> progress(0.5) > 0.9 = full-power hit
    imm_sprint_wait = 0    # immediate: wait up to N ticks for sprint, only while W is held
    free_move = True       # v2.3.5 Java default: the DQN owns movement (no backoff/over-retreat/combo strafe/freeze floor)


BACKOFF_ARC = (7, 8, 2, 8, 7, 8, 6, 7)


class OppMem:
    """OpponentMemory fields read by Perception (features 0..7 + hits observed)."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.wtap = 0.0
        self.jumps = 0.0
        self.crits = 0.0
        self.their_hits = 0.0
        self.my_hits = 0.0
        self.snapshots = 0.0
        self.since_snap = 0
        self.avg_dist = 3.0
        self.air_before = 0
        self.watch = -1
        self.jt = self.jh = self.rt = self.rh = 0.0
        self.start_dist = 0.0
        self.pj = self.pr = False
        self.wtap_watch = -1

    def features(self):
        denom = max(1.0, self.their_hits)
        f = [0.0] * 8
        f[0] = min(1.0, self.wtap / max(4.0, denom))
        f[1] = 0.0
        f[2] = min(1.0, self.jumps / max(4.0, self.snapshots / 8.0))
        f[3] = min(1.0, self.crits / denom)
        f[4] = min(1.0, self.their_hits / 20.0)
        f[5] = min(2.0, self.avg_dist / 3.0) / 2.0
        f[6] = min(1.0, self.jh / self.jt) if self.jt >= 3 else 0.5
        f[7] = min(1.0, self.rh / self.rt) if self.rt >= 3 else 0.5
        return f


class V1Ctl(LearnerCtl):
    """Classic stack for ONE fighter (v1 DQN drives the 72-way action)."""

    def __init__(self, rng, cfg=None):
        self.v1 = cfg or V1Cfg()
        super().__init__(rng, None)
        self.cfg = self.v1
        self.mem = OppMem()

    def reset_episode(self):
        super().reset_episode()
        self.q_action = -1
        self.q_ticks = -1
        self.exec_a = 0
        self.wtap_left = 0
        self.wtap_var = 0
        self.last_wtap = -1000
        self.jr_at = -1
        self.last_jr = -1000
        self.sneak_left = 0
        self.last_sneak_hit = -1000
        self.sneak_jump_pending = False
        self.backoff = False
        self.backoff_start = -1
        self.last_backoff_end = -1000
        self.retreat_pressure = 0.0
        self.over_retreat = 0
        self.escape_left = 0
        self.escape_move = 1
        self.escape_reeval = 0
        self.last_escape_jump = -1000
        self.idle_in_reach = 0
        self.freeze = False
        self.last_crit = -1000
        self.last_midair = -1000
        self.crit_window = 0
        self.combo_dir = 0
        self.combo_left = 0
        self.chase_vx = self.chase_vz = 0.0
        self.chase = False
        self.chase_jump = False
        self.last_chase_hop = -1000
        self.their_last_attack = -1000
        self.prev_their_swing = False
        self.dist_ema = 3.0
        self.prev_dist = 3.0
        self.hits_landed = 0
        self.whiffs = 0
        self.episode_start = 0
        self.prev_target_air = False
        self.cur_move = 0
        self.no_sprint = 0
        self.sprint_bypass = False
        self.cb_left = 0
        self.cb_dir = 1
        self.cd_left = 0
        self.t_air_prev = False
        self.hs_wait = 0
        self.pcrit_until = -1

    # ------------------------------------------------------------ hit watch
    def hitwatch_v1(self, t, dealt, took, view, vview, me):
        """LearnerCtl.hitwatch + the extra HitWatcher/OpponentMemory bookkeeping."""
        flight_before = self.attack_in_flight and t - self.attack_flight_tick >= 10
        my_before = self.last_my_hit_tick
        if took > 0.01:
            self.their_last_attack = t
            # OpponentMemory.onTheirHitMe
            self.mem.their_hits += 1
            vx, vz = vview
            if (not view.on_ground) and self._vy_seen < 0:
                self.mem.crits += 1
            dx, dz = me.x - view.x, me.z - view.z
            ln = math.hypot(dx, dz)
            toward = 0.0 if ln < 1e-4 else (vx * dx + vz * dz) / ln
            if math.hypot(vx, vz) > 0.24 and toward > 0.1:
                self.mem.wtap_watch = 6
        self.hitwatch(t, dealt, took)
        if self.last_my_hit_tick == t and my_before != t:
            self.hits_landed += 1
            d = dist3(me, view)
            self.mem.my_hits += 1
            self.mem.watch = 10
            self.mem.start_dist = d
            self.mem.jt += 1
            self.mem.rt += 1
            self.mem.pj = self.mem.pr = False
        if flight_before and not self.attack_in_flight:
            if t - self.last_my_hit_tick > 10:
                self.whiffs += 1
        # their swing edge (HitWatcher)
        if view.swinging and not self.prev_their_swing and dist3(me, view) <= 4.5:
            self.their_last_attack = t
        self.prev_their_swing = view.swinging
        d = dist3(me, view)
        self.dist_ema += 0.01 * (d - self.dist_ema)
        self.prev_dist = d

    def memory_tick(self, t, me, view, vview):
        m = self.mem
        d = dist3(me, view)
        m.avg_dist += 0.02 * (d - m.avg_dist)
        air = 0 if view.on_ground else (-1 if self._vy_seen < 0 else 1)
        if m.air_before == 0 and air > 0 and m.snapshots > 20:
            m.jumps += 1
        m.air_before = air
        m.since_snap += 1
        if m.since_snap >= 5:
            m.since_snap = 0
            m.snapshots += 1
        # probePostHit
        t_air = not view.on_ground
        if m.watch > 0:
            if t_air and not self.prev_target_air and not m.pj:
                m.jh += 1
                m.pj = True
            if d > m.start_dist + 0.9 and not m.pr:
                m.rh += 1
                m.pr = True
            m.watch -= 1
        self.prev_target_air = t_air
        if m.wtap_watch > 0:
            m.wtap_watch -= 1
        # noteSpeed (same tick)
        if m.wtap_watch > 0:
            if math.hypot(vview[0], vview[1]) < 0.16:
                m.wtap += 1
            m.wtap_watch = -1

    # ------------------------------------------------------------ perception
    def perceive(self, t, me, view, vview, vy_seen, probes):
        s = [0.0] * DIM
        yr = math.radians(me.yaw)
        fx, fz = -math.sin(yr), math.cos(yr)
        rx, rz = -fz, fx
        vel_fwd = me.vx * fx + me.vz * fz
        vel_str = me.vx * rx + me.vz * rz
        s[0] = me.hp / 20.0
        s[1] = clamp(me.absorption / 20.0, 0, 1)
        s[2] = me.food / 20.0
        s[3] = me.charge(0.0)
        s[4] = 1.0 if me.sprinting else 0.0
        s[5] = 1.0 if me.on_ground else 0.0
        s[6] = 0.0 if me.on_ground else (-1.0 if me.vy < 0 else 1.0)
        s[7] = clamp(me.vy, -1, 1)
        s[8] = clamp(vel_fwd / 0.35, -1.5, 1.5)
        s[9] = clamp(vel_str / 0.35, -1.5, 1.5)
        s[10] = me.hurt_time / 10.0
        s[11] = clamp((t - self.last_my_hit_tick) / 100.0, 0, 1)
        s[12] = clamp((t - self.last_taken_tick) / 100.0, 0, 1)
        s[13] = clamp(self.combo_dealt / 6.0, 0, 1.5)
        s[14] = clamp(self.combo_taken / 6.0, 0, 1.5)
        s[15] = 1.0
        dx, dz = view.x - me.x, view.z - me.z
        dist = math.hypot(dx, dz)
        s[16] = clamp(dist / 6.0, 0, 2)
        bearing = math.degrees(math.atan2(-dx, dz))
        rel = wrap(bearing - me.yaw)
        s[17] = rel / 180.0
        s[18] = clamp((view.eye_y() - me.eye_y()) / 4.0, -1, 1)
        tvx, tvz = vview
        s[19] = clamp((tvx * fx + tvz * fz) / 0.35, -1.5, 1.5)
        s[20] = clamp((tvx * rx + tvz * rz) / 0.35, -1.5, 1.5)
        s[21] = clamp(vy_seen, -1, 1)
        s[22] = 1.0 if view.on_ground else 0.0
        s[23] = 0.0 if view.on_ground else (-1.0 if vy_seen < 0 else 1.0)
        s[24] = view.hurt_time / 10.0
        s[25] = view.hp / 20.0
        s[26] = 1.0 if math.hypot(tvx, tvz) > 0.24 else 0.0
        s[27] = clamp((t - self.their_last_attack) / 12.5, 0, 1)
        s[28] = clamp((t - self.their_last_attack) / 100.0, 0, 1)
        s[29] = clamp(self.dist_ema / 6.0, 0, 2)
        s[30] = rel / 180.0
        s[31] = clamp(dist - self.prev_dist, -0.5, 0.5) * 2.0
        om = self.mem.features()
        for i in range(8):
            s[32 + i] = om[i]
        s[40] = clamp(1.0 / 4800.0, 0, 1)
        s[41] = clamp((self.mem.their_hits + self.mem.my_hits) / 50.0, 0, 1)
        for i in range(8):
            s[42 + i] = probes[i]
        s[50] = 0.0
        s[51] = 0.0
        s[52] = (probes[2] + probes[6]) * 0.5
        s[53] = clamp((t - self.episode_start) / 2400.0, 0, 1)
        att = self.hits_landed + self.whiffs
        s[54] = self.whiffs / att if att else 0.0
        s[55] = 1.0
        lead = 3.0 / 20.0
        px = view.x + tvx * lead - me.x
        pz = view.z + tvz * lead - me.z
        s[56] = clamp((px * fx + pz * fz) / 3.0, -2, 2)
        s[57] = clamp((px * rx + pz * rz) / 3.0, -2, 2)
        s[58] = wrap(view.yaw - me.yaw) / 180.0
        s[59] = view.pitch / 90.0
        s[60] = 1.0 if view.sneaking else 0.0
        s[61] = 1.0 if view.swinging else 0.0
        mv = self.cur_move
        s[62] = 1.0 if mv in (1, 5, 6) else (-1.0 if mv in (2, 7, 8) else 0.0)
        s[63] = 1.0 if mv in (4, 6, 8) else (-1.0 if mv in (3, 5, 7) else 0.0)
        return s

    def movement_shaping_v1(self, me, view, probes):
        exec_saved = self.exec
        self.exec = (self.cur_move, False, False, False)
        before = self.pending
        self.movement_shaping(me, view, probes)
        # v1: the frozen-in-reach penalty does not apply during W-tap / escape windows
        d = math.hypot(view.x - me.x, view.z - me.z)
        if (self.wtap_left > 0 or self.escape_left > 0) and d <= 3.4 and me.on_ground and math.hypot(me.vx, me.vz) < 0.06:
            self.pending += 0.008
        self.exec = exec_saved
        return self.pending - before

    # ------------------------------------------------------------ tactics hooks
    def on_my_hit(self, t, d3):
        c = self.v1
        if not c.wtap_on or d3 > 3.4 or t - self.last_wtap < 1:
            return
        if self.rng.random() >= c.wtap_chance:
            return
        r = self.rng.random()
        var = 0 if r < c.wtap_pure_s else (1 if r < c.wtap_pure_s + (1 - c.wtap_pure_s) / 2 else 2)
        ms = c.wtap_ms[0] + int(self.rng.integers(0, max(1, c.wtap_ms[1] - c.wtap_ms[0] + 1)))
        self.wtap_var = var
        self.wtap_left = max(1, round(ms / 50.0))
        self.last_wtap = t

    def on_hurt(self, t, probes):
        c = self.v1
        if c.pcrit:
            self.pcrit_until = t + 14
        ms = c.jreset_ms[0] + int(self.rng.integers(0, max(1, c.jreset_ms[1] - c.jreset_ms[0] + 1)))
        self.jr_at = t + max(1, round(ms / 50.0))
        side = probes[1] > 0.5 or probes[2] > 0.5 or probes[6] > 0.5 or probes[7] > 0.5
        back = probes[3] > 0.5 or probes[4] > 0.5 or probes[5] > 0.5
        if side or back:
            self.escape_left = 10 + int(self.rng.integers(0, 6))
            self.escape_reeval = 0

    def _pick_escape(self, me, view, probes):
        ax, az = me.x - view.x, me.z - view.z
        ln = math.hypot(ax, az)
        if ln < 1e-4:
            ax, az, ln = -math.sin(math.radians(me.yaw)), math.cos(math.radians(me.yaw)), 1.0
        ax, az = ax / ln, az / ln
        best, bs = 0, -1e9
        for i in range(8):
            a = math.radians(me.yaw + i * 45.0)
            dx, dz = -math.sin(a), math.cos(a)
            sc = (-10.0 if probes[i] > 0.5 else 0.0) + (dx * ax + dz * az) * 2.0
            if sc > bs:
                bs, best = sc, i
        self.escape_move = (1, 6, 4, 8, 2, 7, 3, 5)[best]

    def _intercept(self, me, view, probes):
        dx, dz = view.x - me.x, view.z - me.z
        dist = max(0.5, math.hypot(dx, dz))
        nx, nz = dx / dist, dz / dist
        away = self.chase_vx * nx + self.chase_vz * nz
        sp = 0.36 if self.chase_jump else 0.30
        tt = max(0.0, min(40.0, dist / max(0.06, sp + away)))
        px, pz = view.x + self.chase_vx * tt, view.z + self.chase_vz * tt
        bx, bz = px - me.x, pz - me.z
        rel = wrap(math.degrees(math.atan2(-bx, bz)) - me.yaw)
        i = int(math.floor(round(rel / 45.0))) % 8
        if 3 <= i <= 5:
            i = 2 if rel > 0 else 6
        if i == 1 and (probes[1] > 0.5 or probes[2] > 0.5):
            i = 7
        if i == 7 and (probes[7] > 0.5 or probes[6] > 0.5):
            i = 1
        if i in (1, 7) and probes[i] > 0.5:
            i = 0
        if i == 2 and probes[2] > 0.5:
            i = 0
        if i == 6 and probes[6] > 0.5:
            i = 0
        return {1: 6, 2: 4, 6: 3, 7: 5}.get(i, 1)

    @staticmethod
    def _side_from_aim(me, view, t):
        ty = math.radians(view.yaw)
        lx, lz = -math.sin(ty), math.cos(ty)
        dx, dz = me.x - view.x, me.z - view.z
        along = dx * lx + dz * lz
        px, pz = dx - along * lx, dz - along * lz
        yr = math.radians(me.yaw)
        rx, rz = -math.cos(yr), -math.sin(yr)
        side = px * rx + pz * rz
        if abs(side) < 1e-3:
            return 1 if (t // 20) % 2 == 0 else -1
        return 1 if side > 0 else -1

    def note_target(self, view, dh):
        air = not view.on_ground
        if air and not self.t_air_prev and self._vy_seen > 0.2 and view.hurt_time < 9 and 1.2 < dh < 4.2:
            self.cd_left = 14
        self.t_air_prev = air

    def move_policy(self, t, a_move, me, view, vview, probes, active_trade):
        dh = math.hypot(view.x - me.x, view.z - me.z)
        self.note_target(view, dh)
        # chase model
        self.chase_vx += 0.15 * (vview[0] - self.chase_vx)
        self.chase_vz += 0.15 * (vview[1] - self.chase_vz)
        self.chase = self.chase_jump = False
        if dh >= 4.5:
            ax, az = me.x - view.x, me.z - view.z
            ln = max(1e-4, math.hypot(ax, az))
            away = self.chase_vx * ax / ln + self.chase_vz * az / ln
            far = dh >= 10.0
            if far or away > 0.10:
                self.chase = True
                self.chase_jump = far
        if self.escape_left > 0:
            self.escape_left -= 1
            self.escape_reeval -= 1
            if self.escape_reeval <= 0:
                self._pick_escape(me, view, probes)
                self.escape_reeval = 4
            cornered = probes[1] + probes[2] + probes[3] + probes[5] + probes[6] + probes[7] > 0.5
            if self.escape_left > 0 and cornered:
                return self.escape_move
            self.escape_left = 0
        # v2.3.2 combo breaker
        if self.v1.combo_breaker and self.combo_taken >= 2 and dh < 4.0:
            if self.cb_left <= 0:
                self.cb_left = 8 + int(self.rng.integers(0, 5))
                self.cb_dir = self._side_from_aim(me, view, t)
            self.cb_left -= 1
            if self.cb_dir > 0 and (probes[1] > 0.5 or probes[2] > 0.5):
                self.cb_dir = -1
            elif self.cb_dir < 0 and (probes[7] > 0.5 or probes[6] > 0.5):
                self.cb_dir = 1
            return 6 if self.cb_dir > 0 else 5
        self.cb_left = 0
        # v2.3.2 crit denial
        if self.v1.crit_denial and self.cd_left > 0:
            self.cd_left -= 1
            if view.on_ground:
                self.cd_left = 0
            elif dh < 2.6:
                if not (probes[3] > 0.5 or probes[4] > 0.5 or probes[5] > 0.5):
                    return 7 if (t // 3) % 2 == 0 else 8
                return 4 if self._side_from_aim(me, view, t) > 0 else 3
            elif dh > 3.3:
                return 1
            else:
                return 4 if self._side_from_aim(me, view, t) > 0 else 3
        self.retreat_pressure *= 0.94
        if self.over_retreat > 0:
            self.over_retreat -= 1
        c = self.v1
        free = c.free_move
        if c.backoff_on and not free and not self.backoff and dh < c.too_close and t - self.last_backoff_end >= 20:
            self.backoff = True
            self.backoff_start = t
        elif self.backoff and (dh >= c.backoff_release or t - self.backoff_start > max(6, c.backoff_max)):
            self.backoff = False
            self.last_backoff_end = t
        if self.backoff:
            seg = (t - self.backoff_start) // 4
            mv = BACKOFF_ARC[seg % 8]
            if mv == 2 and (probes[3] > 0.5 or probes[4] > 0.5 or probes[5] > 0.5):
                mv = 5 if seg % 2 == 0 else 6
            return mv
        if self.v1.pcrit and t <= self.pcrit_until and not me.on_ground and dh <= 3.6:
            return 4 if self._side_from_aim(me, view, t) > 0 else 3   # strafe, no W: sprint drops
        if self.crit_window > 0:
            self.crit_window -= 1
            if me.on_ground:
                self.crit_window = 0
            else:
                return 0
        if self.wtap_left > 0:
            if dh > 3.4:
                self.wtap_left = 0
            else:
                self.wtap_left -= 1
                return (2, 7, 8)[self.wtap_var]
        if self.chase:
            return self._intercept(me, view, probes)
        if free:
            return a_move
        if a_move in BACK_MOVES and dh < 4.5:
            self.retreat_pressure += 1.0
        if self.retreat_pressure > 10.0 and dh > 1.6:
            self.over_retreat = 8
            self.retreat_pressure = 0.0
        if self.over_retreat > 0:
            return 1 if (t % 8) < 4 else 5
        if c.combo_strafe and (self.combo_dealt >= 1 or active_trade) and 1.35 < dh < 3.4 and a_move not in BACK_MOVES:
            self.combo_left -= 1
            if self.combo_left <= 0 or self.combo_dir == 0:
                self.combo_left = 9
                if self.rng.random() < 0.55:
                    self.combo_dir = -self.combo_dir
                if self.combo_dir == 0:
                    self.combo_dir = 1 if self.rng.random() < 0.5 else -1
            if self.combo_dir > 0 and (probes[1] > 0.5 or probes[2] > 0.5):
                self.combo_dir = -1
            elif self.combo_dir < 0 and (probes[7] > 0.5 or probes[6] > 0.5):
                self.combo_dir = 1
            if self.combo_left > 0:
                return 6 if self.combo_dir > 0 else 5
        in_reach = dh <= 3.2
        if in_reach and me.on_ground and a_move == 0:
            self.idle_in_reach += 1
            if self.idle_in_reach >= 8:
                self.freeze = True
        else:
            self.idle_in_reach = 0
            self.freeze = False
        if self.freeze:
            return 1
        if dh > 4.5 and a_move in (3, 4):
            return self._intercept(me, view, probes)
        return a_move

    # ------------------------------------------------------------ decide + execute
    def decide_v1(self, t, a, me, view, vview, probes, world):
        """a = DQN action (0..71). Returns (executed action, keys, aim, swing)."""
        c = self.v1
        # Humanizer.submit (single-slot, drops while in flight)
        delay = c.react_min + int(self.rng.integers(0, max(1, c.react_max - c.react_min + 1)))
        if c.fifo:
            et = max(t + delay, self.last_exec_tick)
            self.last_exec_tick = et
            self.pipe.append((et, a))
            while self.pipe and self.pipe[0][0] <= t:
                self.exec_a = self.pipe.pop(0)[1]
        elif self.q_action < 0:
            self.q_action = a
            self.q_ticks = delay
        if not c.fifo:
            if self.q_ticks <= 0:
                self.exec_a = self.q_action
                self.q_action = -1
                self.q_ticks = -1
            else:
                self.q_ticks -= 1
        ex = self.exec_a
        active_trade = self.combo_dealt >= 1 or t - max(self.last_my_hit_tick, self.last_taken_tick) <= 40
        mv = self.move_policy(t, move_of(ex), me, view, vview, probes, active_trade)
        self.cur_move = mv
        wtap_move = mv in BACK_MOVES
        sneak_window = self.sneak_left > 0
        sprint = sprint_of(ex) and not wtap_move
        if not wtap_move and not sneak_window:
            sprint = True
        sneak = False
        if self.sneak_left > 0:
            self.sneak_left -= 1
            sneak = True
        d3 = dist3(me, view)
        jump = False
        retreating = self.backoff or mv in BACK_MOVES
        if self.jr_at > 0 and t >= self.jr_at:
            if t - self.jr_at > 12:
                self.jr_at = -1
            elif me.on_ground and d3 <= 3.5 and t - self.last_jr >= 4:
                self.jr_at = -1
                self.last_jr = t
                jump = True
        if not jump and self.escape_left > 0 and me.on_ground:
            cornered = (probes[3] > 0.5 or probes[4] > 0.5 or probes[5] > 0.5) and probes[1] + probes[2] > 1 and probes[6] + probes[7] > 1
            if cornered and t - self.last_escape_jump >= 20:
                self.last_escape_jump = t
                jump = True
        if not jump and jump_of(ex) and not retreating and me.on_ground and 1.2 < d3 <= 3.4:
            elig = self.wtap_left <= 0 and not self.backoff and self.escape_left <= 0 and self.cd_left <= 0 and self.cb_left <= 0
            if elig and t - self.last_crit >= c.crit_cd and self.rng.random() < c.crit_chance:
                self.last_crit = t
                self.crit_window = 14
                jump = True
            elif elig and t - self.last_midair >= c.midair_cd and self.rng.random() < c.midair_chance:
                self.last_midair = t
                jump = True
        if not jump and self.chase_jump and me.on_ground and t - self.last_chase_hop >= 4:
            self.last_chase_hop = t
            jump = True
        if jump:
            self.jump_hold = 2
        jump_key = self.jump_hold > 0
        if self.jump_hold > 0:
            self.jump_hold -= 1
        # TriggerBot click
        swing = self._trigger(t, me, view, world, d3, sneak)
        if swing == "sneak":
            sneak = True
            swing = False
        fwd, right = MOVE_VEC[mv]
        aim = self.tracker.shape(*self.tracker.desired(me, view, vview, t))
        return ex, (fwd, right, jump_key, sprint, sneak), aim, swing

    def _trigger(self, t, me, view, world, d3, sneak_window):
        c = self.v1
        charge = me.charge(0.0)
        if c.immediate:
            if not (charge >= max(c.band_min, c.imm_thr) and t - self.last_any_swing >= 2
                    and t - self.last_click >= 3 and d3 <= c.click_max_dist):
                return False
            if (me.on_ground and not me.sprinting and self.cur_move in (1, 5, 6)
                    and self.no_sprint < c.imm_sprint_wait):
                self.no_sprint += 1
                return False
            self.no_sprint = 0
            return self._fire(t, me, view, world, sneak_window)
        band_ok = charge >= c.band_min and (charge <= c.band_max or charge >= 0.999)
        if not (band_ok and t - self.last_any_swing >= 2 and t - self.last_click >= 3 and d3 <= c.click_max_dist):
            return False
        if self.next_band < 0:
            band = c.band_min + self.rng.random() * max(0.01, c.band_max - c.band_min)
            floor = max(0.74, c.band_min - 0.08)
            self.next_band = min(max(band, floor), max(floor, c.band_max))
        gate = t - self.last_attack_attempt >= 2 + int(self.rng.integers(0, 2))
        if not (charge >= self.next_band and gate):
            return False
        sneak_click = sneak_window
        if me.on_ground and not me.sprinting and not sneak_click and not self.sprint_bypass:
            if self.cur_move in (1, 5, 6):
                self.no_sprint += 1
                if self.no_sprint > c.sprint_patience:
                    self.sprint_bypass = True
            return False
        self.no_sprint = 0
        if me.sprinting:
            self.sprint_bypass = False
        return self._fire(t, me, view, world, sneak_click)

    def _fire(self, t, me, view, world, sneak_click):
        c = self.v1
        if not on_target(me, view, world):
            return False
        if self.crit_window > 0 and me.vy >= 0:
            return False
        if c.pcrit and t <= self.pcrit_until and not me.on_ground and me.vy >= 0:
            return False   # p-crit: wait for the descent
        if c.hit_select and self.combo_dealt == 0 and not self.attack_in_flight:
            their_ready = t - self.their_last_attack >= 10
            just_swung = t - self.their_last_attack <= 1
            if their_ready and not just_swung and d3 <= 3.3 and self.hs_wait < c.hit_select_wait:
                self.hs_wait += 1
                return False
        self.hs_wait = 0
        if not sneak_click and self.wtap_left <= 0 and not self.backoff:
            if t - self.last_sneak_hit >= c.sneak_cd and self.rng.random() < c.sneak_chance:
                self.last_sneak_hit = t
                self.sneak_left = 4
                sneak_click = True
        if sneak_click and not me.sneaking:
            return "sneak"   # shift first; the click lands once the pose is sneaking
        self.last_any_swing = t
        self.last_click = t
        self.last_attack_attempt = t
        self.attack_in_flight = True
        self.attack_flight_tick = t
        self.attack_falling = (not me.on_ground) and me.vy < 0
        self.attack_sprinting = me.sprinting
        self.next_band = -1.0
        return True
