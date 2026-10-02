"""Controllers for the v3 simulator.

LearnerCtl  — mirror of BotController's PURE MODE pipeline (v2.3): four-head
              decision -> retreat governor / aggression floor -> sprint-hit law
              -> FIFO reaction-delay line -> execution gates (jump/sneak) ->
              click oracle behind the hard band gate + click governors ->
              tracker aim through the humanizer shaping. Rewards mirror
              HitWatcher + movementShaping + pure shaping exactly.
ScriptedCtl — a randomized human-like opponent (aim skill, click discipline,
              movement plans, w/s-taps, jump resets, crit hops, kiting ...).
"""
import math

from physics import wrap, ray_hits, REACH

MOVE_VEC = {0: (0, 0), 1: (1, 0), 2: (-1, 0), 3: (0, -1), 4: (0, 1),
            5: (1, -1), 6: (1, 1), 7: (-1, -1), 8: (-1, 1)}   # (fwd, strafe_right)
BACK_MOVES = (2, 7, 8)


def encode_move(fwd, right):
    for k, v in MOVE_VEC.items():
        if v == (fwd, right):
            return k
    return 0


def angles_to(ex, ey, ez, px, py, pz):
    dx, dy, dz = px - ex, py - ey, pz - ez
    yaw = math.degrees(math.atan2(-dx, dz))
    pitch = -math.degrees(math.atan2(dy, max(1e-6, math.hypot(dx, dz))))
    return yaw, pitch


# ---------------------------------------------------------------- aim tracker
class Tracker:
    """Tracker + humanizer.shapeAim approximation (per game tick)."""

    def __init__(self, rng, skill=1.0, learner=False):
        self.rng = rng
        u = rng.uniform
        if learner:
            self.smin = u(0.45, 0.62)
            self.smax = self.smin + u(0.12, 0.22)
            self.cap = u(40.0, 55.0)
            self.noise = 0.12
            self.err_sigma = u(0.2, 0.9)      # aim-net / wander imperfection
            self.lead = 2.0
            self.zone = (0.75, 0.93)
        else:
            s = skill
            self.smin = 0.2 + 0.45 * s * u(0.7, 1.0)
            self.smax = self.smin + u(0.05, 0.25)
            self.cap = 15.0 + 40.0 * s * u(0.7, 1.0)
            self.noise = u(0.1, 0.6) + 2.0 * (1.0 - s)
            self.err_sigma = u(0.3, 1.5) + 4.0 * (1.0 - s)
            self.lead = u(0.0, 2.5) * s
            lo = u(0.45, 0.8)
            self.zone = (lo, min(0.95, lo + u(0.05, 0.2)))
        self.max_deg = 40.0
        self.phase = u(0, 6.28)
        self.flick_ticks = 0
        self.last_err = 0.0
        self.bias_y = 0.0
        self.bias_p = 0.0
        self.deadzone = 0.3

    def desired(self, me, view, vel, t):
        """Raw tracker correction (deg) toward the aim point on the viewed target."""
        frac = self.zone[0] + (self.zone[1] - self.zone[0]) * (0.5 + 0.5 * math.sin(self.phase + t * 0.09))
        d = math.hypot(view.x - me.x, view.z - me.z)
        lx, lz = vel[0] * self.lead, vel[1] * self.lead
        lm = math.hypot(lx, lz)
        cap = 0.35 * max(0.8, d)
        if lm > cap and lm > 1e-6:
            lx, lz = lx * cap / lm, lz * cap / lm
        px, pz = view.x + lx, view.z + lz
        py = view.y + view.height * frac
        yaw, pitch = angles_to(me.x, me.eye_y(), me.z, px, py, pz)
        # slowly-drifting imperfection (aim net / wander / human error)
        self.bias_y += 0.15 * (self.rng.normal(0, self.err_sigma) - self.bias_y)
        self.bias_p += 0.15 * (self.rng.normal(0, self.err_sigma * 0.6) - self.bias_p)
        dy = wrap(yaw - me.yaw) + self.bias_y
        dp = (pitch - me.pitch) + self.bias_p
        if abs(dy) < self.deadzone:
            dy = 0.0
        if abs(dp) < self.deadzone:
            dp = 0.0
        return dy, dp

    def shape(self, dy, dp):
        mag = math.hypot(dy, dp)
        smooth = self.rng.uniform(self.smin, self.smax)
        boost = 1.0 + max(0.0, min(1.0, (max(abs(dy), abs(dp)) - 12.0) / 20.0))
        if mag > 8.0 and self.last_err <= 8.0:
            self.flick_ticks = 0
        else:
            self.flick_ticks += 1
        self.last_err = mag
        tt = min(1.0, self.flick_ticks / 3.0)
        ease = 0.35 + 0.65 * (tt * tt * (3 - 2 * tt))
        y = max(-self.cap, min(self.cap, dy * smooth * boost * ease))
        p = max(-self.cap, min(self.cap, dp * smooth * boost * ease))
        if dy != 0.0 or dp != 0.0:
            y += self.rng.uniform(-1, 1) * self.noise
            p += self.rng.uniform(-1, 1) * self.noise
        return y, p


def apply_look(body, dy, dp):
    body.yaw = wrap(body.yaw + dy)
    body.pitch = max(-90.0, min(90.0, body.pitch + dp))


def on_target(me, view, world):
    """vanillaOnTarget: look ray hits the viewed hitbox within reach + clear LOS."""
    t = ray_hits((me.x, me.eye_y(), me.z), me.look(), view.x, view.y, view.z, view.height)
    if t is None:
        return False
    return not world.segment_blocked(me.x, me.z, view.x, view.z, 8)


def dist3(a, b):
    return math.sqrt((a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2)


# ---------------------------------------------------------------- learner
class LearnerCfg:
    """Java BotConfig defaults relevant to pure mode."""
    band_min = 0.82
    band_max = 0.96
    react_min = 1
    react_max = 3
    retreat_limit = 4
    close_limit = 20
    sprint_patience = 10
    click_max_dist = 3.2
    win_reward = 45.0
    loss_reward = -10.0
    sneak_allowed = True


class LearnerCtl:
    """Mirror of the Java pure-mode controller for ONE fighter."""

    def __init__(self, rng, cfg=None):
        self.rng = rng
        self.cfg = cfg or LearnerCfg()
        self.tracker = Tracker(rng, learner=True)
        self.reset_episode()

    def reset_episode(self):
        self.pipe = []                 # [(exec_tick, (move, sprint, jump, sneak))]
        self.exec = (0, False, False, False)
        self.last_exec_tick = -1
        self.back_streak = 0
        self.close_streak = 0
        self.last_jump_tick = -1000
        self.jump_hold = 0
        self.sneak_hold = 0
        self.sneak_cd_until = 0
        self.next_band = -1.0
        self.last_click = -1000
        self.last_any_swing = -1000
        self.last_attack_attempt = -1000
        self.no_sprint_ticks = 0
        self.sprint_bypass = False
        self.pending = 0.0
        self.last_face_err = -1.0
        # HitWatcher mirror
        self.combo_dealt = 0
        self.combo_taken = 0
        self.last_taken_tick = -1000
        self.attack_in_flight = False
        self.attack_flight_tick = -1000
        self.attack_falling = False
        self.attack_sprinting = False
        self.last_my_hit_tick = -1000
        self.swung_last_tick = False
        self.band_mult = self.rng.uniform(0.92, 1.06)

    # ---- reward sources (Java HitWatcher.tick) ------------------------------
    def hitwatch(self, t, dealt, took, crit_unused=False):
        if took > 0.01:
            self.last_taken_tick = t
            self.combo_taken += 1
            self.combo_dealt = 0
            self.pending -= 0.25 * took
            if self.combo_taken > 2:
                self.pending -= 0.04
        elif t - self.last_taken_tick > 40:
            self.combo_taken = 0
        if dealt > 0.01:
            my_hit = self.attack_in_flight and t - self.attack_flight_tick <= 10
            if my_hit:
                crit = self.attack_falling and not self.attack_sprinting
                self.last_my_hit_tick = t
                self.combo_dealt += 1
                self.combo_taken = 0
                self.pending += 0.2 * dealt
                if crit:
                    self.pending += 0.1
                if self.combo_dealt > 2:
                    self.pending += 0.04
        if self.attack_in_flight and t - self.attack_flight_tick >= 10:
            if t - self.last_my_hit_tick > 10 or self.last_my_hit_tick < self.attack_flight_tick:
                self.pending -= 0.02
            self.attack_in_flight = False
        self.pending += 0.001

    def movement_shaping(self, me, view, probes):
        dx, dz = view.x - me.x, view.z - me.z
        d = math.hypot(dx, dz)
        sp = math.hypot(me.vx, me.vz)
        toward = 0.0 if d < 1e-4 else (me.vx * dx + me.vz * dz) / d
        r = 0.0
        if 2.2 <= d <= 3.3:
            r += 0.004
        if d > 4.5:
            r += 0.003 if toward > 0.05 else -0.003
        if d <= 3.4 and me.on_ground and sp < 0.06:
            r -= 0.008
        if d <= 3.4 and sp > 0.12:
            r += 0.002
        if d < 1.5:
            r -= 0.005
        back = self.exec[0] in BACK_MOVES
        if back and d > 3.2:
            r -= 0.006
        if back and 1.6 < d <= 3.2:
            r -= 0.002
        if back and not me.on_ground:
            r -= 0.003
        wall_behind = probes[3] > 0.5 or probes[4] > 0.5 or probes[5] > 0.5
        if wall_behind and toward < -0.08:
            r -= 0.004
        self.pending += r

    # ---- decision (Java pureDecisionStep v2.3) ------------------------------
    def decide(self, t, me, view, view_vel, world, head, eps_info):
        """head = dict(move, sprint, jump, sneak, sneak_margin, aim_y, aim_p, click).
        Returns (stored action tuple, labels, aim delta)."""
        cfg = self.cfg
        # tracker aim + labels
        ty, tp = self.tracker.desired(me, view, view_vel, t)
        ty = max(-40.0, min(40.0, ty))
        tp = max(-40.0, min(40.0, tp))
        aim_lbl = (ty / 40.0, tp / 40.0)
        shaped = self.tracker.shape(ty, tp)

        # face-error shaping (Java faceErrorDeg: target eye - 0.7)
        cy = view.eye_y() - 0.7
        yaw_n, pit_n = angles_to(me.x, me.eye_y(), me.z, view.x, cy, view.z)
        lx, ly, lz = me.look()
        vx, vy, vz = view.x - me.x, cy - me.eye_y(), view.z - me.z
        vl = max(1e-4, math.sqrt(vx * vx + vy * vy + vz * vz))
        ferr = math.degrees(math.acos(max(-1.0, min(1.0, (lx * vx + ly * vy + lz * vz) / vl))))
        if self.last_face_err >= 0:
            self.pending += max(-0.04, min(0.04, (self.last_face_err - ferr) * 0.02))
        if ferr < 6:
            self.pending += 0.004
        if ferr > 25:
            self.pending -= 0.006
        self.last_face_err = ferr

        # click oracle label
        charge = me.charge(0.0)
        band_ok = charge >= cfg.band_min and (charge <= cfg.band_max or charge >= 0.999)
        gaps = (t - self.last_any_swing >= 2) and (t - self.last_click >= 3)
        d3 = dist3(me, view)
        click_due = band_ok and gaps and d3 <= cfg.click_max_dist and on_target(me, view, world)

        # governors (decision time)
        move = head["move"]
        dh = math.hypot(view.x - me.x, view.z - me.z)
        losing = me.hp < view.hp - 4.0
        gov_pen = 0.0
        if move in BACK_MOVES:
            self.back_streak += 1
            if self.back_streak > cfg.retreat_limit and dh < 4.5 and not losing:
                move = 5 if self._target_left(me, view) else 6
                gov_pen -= 0.004
        else:
            self.back_streak = 0
        if dh > 3.0 and not losing and not me.sneaking:
            self.close_streak += 1
            if self.close_streak > cfg.close_limit:
                cross = self._cross(me, view)
                move = 1 if abs(cross) < 0.25 else (5 if cross > 0 else 6)
                gov_pen -= 0.002
        else:
            self.close_streak = 0
        self.pending += gov_pen

        sneak = cfg.sneak_allowed and head["sneak"] and head["sneak_margin"] >= 0.25
        sprint = head["sprint"] or (move not in BACK_MOVES and not sneak)
        jump = head["jump"]
        act = (move, sprint, jump, sneak)

        # FIFO reaction delay line (no decision is ever dropped)
        delay = int(self.rng.integers(cfg.react_min, cfg.react_max + 1))
        et = max(t + delay, self.last_exec_tick)
        self.last_exec_tick = et
        self.pipe.append((et, act))
        while self.pipe and self.pipe[0][0] <= t:
            self.exec = self.pipe.pop(0)[1]
        labels = (aim_lbl[0], aim_lbl[1], 1.0 if click_due else 0.0)
        return act, labels, shaped, click_due

    def _cross(self, me, view):
        yr = math.radians(me.yaw)
        fx, fz = -math.sin(yr), math.cos(yr)
        return fx * (view.z - me.z) - fz * (view.x - me.x)

    def _target_left(self, me, view):
        return self._cross(me, view) > 0

    def execute(self, t, me, view):
        """Execution-time gates -> (fwd, right, jump, sprint_key, sneak)."""
        move, sprint, jump, sneak = self.exec
        d3 = dist3(me, view)
        want_sneak = sneak and me.on_ground and d3 <= 3.2
        if not want_sneak:
            self.sneak_hold = 0
            sneak_on = False
        elif self.sneak_hold < 8 and t >= self.sneak_cd_until:
            self.sneak_hold += 1
            sneak_on = True
        else:
            if self.sneak_hold >= 8:
                self.sneak_cd_until = t + 10
            self.sneak_hold = 0
            sneak_on = False
        if jump and me.on_ground and 0.5 < d3 <= 3.4 and t - self.last_jump_tick >= 4:
            self.jump_hold = 2
            self.last_jump_tick = t
        jump_key = self.jump_hold > 0
        if self.jump_hold > 0:
            self.jump_hold -= 1
        fwd, right = MOVE_VEC[move]
        return fwd, right, jump_key, sprint, sneak_on

    def try_click(self, t, me, view, world, click_due, desire, mature):
        """Pure click path. Returns True when a swing is sent this tick."""
        cfg = self.cfg
        intent = click_due or (mature and desire >= 0.55)
        charge = me.charge(0.0)
        band_ok = charge >= cfg.band_min and (charge <= cfg.band_max or charge >= 0.999)
        if not (intent and band_ok and t - self.last_any_swing >= 2 and t - self.last_click >= 3
                and dist3(me, view) <= cfg.click_max_dist):
            return False
        if self.next_band < 0:
            band = cfg.band_min + self.rng.random() * max(0.01, cfg.band_max - cfg.band_min)
            band *= self.band_mult
            floor = max(0.74, cfg.band_min - 0.08)
            self.next_band = min(max(band, floor), max(floor, cfg.band_max))
        gate = t - self.last_attack_attempt >= 2 + int(self.rng.integers(0, 2))
        if not (charge >= self.next_band and gate):
            return False
        sneak_click = self.exec[3]
        if me.on_ground and not me.sprinting and not sneak_click and not self.sprint_bypass:
            self.no_sprint_ticks += 1
            if self.no_sprint_ticks > cfg.sprint_patience:
                self.sprint_bypass = True
            return False
        self.no_sprint_ticks = 0
        if not on_target(me, view, world):
            return False
        self.last_any_swing = t
        self.last_click = t
        self.last_attack_attempt = t
        self.attack_in_flight = True
        self.attack_flight_tick = t
        self.attack_falling = (not me.on_ground) and me.vy < 0
        self.attack_sprinting = me.sprinting
        self.next_band = -1.0
        return True


# ---------------------------------------------------------------- scripted opponent
class ScriptedCtl:
    """Randomized human-like duelist. All style knobs are sampled per match."""

    STYLES = ("rush", "circle", "jitter", "kite", "pocket")

    def __init__(self, rng, preset=None):
        self.rng = rng
        u = rng.uniform
        self.skill = u(0.35, 1.0)
        self.p = {
            "react": int(rng.integers(1, 6)),
            "thr_lo": u(0.78, 0.97),
            "thr_w": u(0.0, 0.08),
            "spam": rng.random() < 0.08,
            "spam_gap": int(rng.integers(2, 5)),
            "wtap": u(0.0, 0.8),
            "wtap_len": (int(rng.integers(1, 3)), int(rng.integers(3, 7))),
            "stap": u(0.0, 0.5),
            "stap_len": (int(rng.integers(3, 6)), int(rng.integers(6, 14))),
            "jreset": u(0.0, 0.9),
            "crit": u(0.0, 0.5),
            "sneak": u(0.0, 0.15),
            "kite_d": u(2.7, 3.4),
            "plan_len": (int(rng.integers(10, 30)), int(rng.integers(30, 90))),
            "styles": rng.dirichlet([1.0] * len(self.STYLES)),
            "flip": (int(rng.integers(2, 8)), int(rng.integers(8, 40))),
            "sprint": rng.random() < 0.92,
        }
        if preset == "practice":
            self.skill = u(0.85, 1.0)
            self.p.update(react=1, thr_lo=u(0.9, 0.97), thr_w=0.03, spam=False, wtap=u(0.3, 0.8),
                          jreset=u(0.6, 0.95), crit=u(0.0, 0.2), sneak=0.0,
                          styles=[0.75, 0.1, 0.05, 0.0, 0.1])
        elif preset == "crit":
            self.p.update(crit=u(0.7, 1.0), styles=[0.6, 0.2, 0.1, 0.0, 0.1])
        elif preset == "kiter":
            self.p.update(styles=[0.05, 0.2, 0.1, 0.55, 0.1], stap=u(0.3, 0.8))
        elif preset == "jitter":
            self.p.update(styles=[0.2, 0.2, 0.5, 0.0, 0.1], flip=(2, 6))
        self.tracker = Tracker(rng, skill=self.skill)
        self.reset_episode()

    def reset_episode(self):
        self.plan = "rush"
        self.plan_until = 0
        self.strafe = 1 if self.rng.random() < 0.5 else -1
        self.flip_at = 0
        self.tap_until = -1
        self.tap_kind = 0
        self.jump_at = -1
        self.crit_mode = False
        self.thr = self._roll_thr()
        self.last_click = -100
        self.queue = []
        self.cur = (1, 0, False, True, False)
        self.sneak_until = -1

    def _roll_thr(self):
        return min(1.0, self.p["thr_lo"] + self.rng.random() * self.p["thr_w"])

    def on_hurt(self, t):
        if self.rng.random() < self.p["jreset"]:
            self.jump_at = t + int(self.rng.integers(0, 3))

    def on_my_hit(self, t, sprinting):
        r = self.rng.random()
        if r < self.p["stap"]:
            lo, hi = self.p["stap_len"]
            self.tap_until = t + int(self.rng.integers(lo, hi + 1))
            self.tap_kind = 2
        elif r < self.p["stap"] + self.p["wtap"]:
            lo, hi = self.p["wtap_len"]
            self.tap_until = t + int(self.rng.integers(lo, hi + 1))
            self.tap_kind = 0

    def step(self, t, me, view, view_vel, world):
        """Returns (fwd, right, jump, sprint_key, sneak), aim delta, wants_click."""
        rng = self.rng
        p = self.p
        dh = math.hypot(view.x - me.x, view.z - me.z)
        if t >= self.plan_until:
            self.plan = self.STYLES[int(rng.choice(len(self.STYLES), p=p["styles"]))]
            lo, hi = p["plan_len"]
            self.plan_until = t + int(rng.integers(lo, hi + 1))
        if t >= self.flip_at:
            self.strafe = -self.strafe
            lo, hi = p["flip"]
            self.flip_at = t + int(rng.integers(lo, hi + 1))

        fwd, right = 1, 0
        if self.plan == "rush":
            fwd, right = (1, 0) if dh > 1.3 else (0, self.strafe)
        elif self.plan == "circle":
            fwd, right = (1, self.strafe) if dh > 2.2 else (0, self.strafe)
        elif self.plan == "jitter":
            fwd, right = (1 if dh > 2.0 else 0), self.strafe
        elif self.plan == "kite":
            kd = p["kite_d"]
            fwd = 1 if dh > kd + 0.35 else (-1 if dh < kd - 0.35 else 0)
            right = self.strafe if rng.random() < 0.7 else 0
        elif self.plan == "pocket":
            fwd = 1 if dh > 3.1 else (-1 if dh < 2.2 else 0)
            right = self.strafe
        if dh < 1.0:
            fwd = -1
        if t < self.tap_until:
            fwd = -1 if self.tap_kind == 2 else 0
            if self.tap_kind == 2:
                right = 0
        jump = False
        if self.jump_at >= 0 and t >= self.jump_at:
            jump = me.on_ground
            self.jump_at = -1
        crit_try = p["crit"] > 0 and dh < 3.6 and me.charge() > 0.6 and me.on_ground and rng.random() < p["crit"] * 0.25
        if crit_try:
            jump = True
            self.crit_mode = True
        if me.on_ground and not jump:
            self.crit_mode = False
        sneak = t < self.sneak_until
        if not sneak and rng.random() < p["sneak"] * 0.05 and dh < 3.2:
            self.sneak_until = t + int(rng.integers(3, 10))
        sprint_key = p["sprint"] and not self.crit_mode
        intent = (fwd, right, jump, sprint_key, sneak)
        # reaction delay
        self.queue.append((t + p["react"], intent))
        while self.queue and self.queue[0][0] <= t:
            self.cur = self.queue.pop(0)[1]

        dy, dp = self.tracker.desired(me, view, view_vel, t)
        aim = self.tracker.shape(dy, dp)

        # clicks
        want = False
        if on_target(me, view, world):
            if p["spam"]:
                want = t - self.last_click >= p["spam_gap"]
            elif self.crit_mode and not me.on_ground:
                want = me.vy < 0 and me.charge() >= self.thr - 0.05
            else:
                want = me.charge() >= self.thr and t - self.last_click >= 2
        if want:
            self.last_click = t
            self.thr = self._roll_thr()
        return self.cur, aim, want
