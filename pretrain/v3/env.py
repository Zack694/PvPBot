"""v3 duel environment: two fighters, latency, rounds, rewards, ObsV4 frames.

Per tick (mirrors the client tick order of the mod):
  1. each learning side builds its CombatFrame (own body now, opponent as seen
     `lag` ticks ago) -> ObsV4.tick -> observation
  2. HitWatcher rewards + movement shaping close the previous transition
  3. four-head decisions (batched by the actor) -> governors -> delay line
  4. clicks resolve against the TRUE opponent (client-side ray on the seen one)
  5. aim updates, movement physics, regen
Death ends a round; a match is 1-3 rounds vs the same opponent (the
opponent profile inside ObsV4 persists across rounds, like in-game).
"""
import math
from collections import deque

import numpy as np

from physics import Body, World, Kit, DIAMOND, NETHERITE, physics_tick, attack, regen_tick, wrap
from bots import LearnerCtl, LearnerCfg, ScriptedCtl, on_target, MOVE_VEC, dist3
from obs import ObsV4, Frame, Fighter, f32

ROUND_TICKS = 1600


class View:
    __slots__ = ("x", "y", "z", "yaw", "pitch", "hp", "absorption", "height", "on_ground",
                 "sprinting", "sneaking", "swinging", "hurt_time", "vx", "vz")

    def eye_y(self):
        return self.y + (1.27 if self.sneaking else 1.62)


def snap(b):
    v = View()
    v.x, v.y, v.z = b.x, b.y, b.z
    v.yaw, v.pitch = b.yaw, b.pitch
    v.hp, v.absorption = b.hp, b.absorption
    v.height = b.height()
    v.on_ground = b.on_ground
    v.sprinting = b.sprinting
    v.sneaking = b.sneaking
    v.swinging = b.swing_ticks > 0
    v.hurt_time = b.hurt_time
    v.vx, v.vz = b.vx, b.vz
    return v


def to_fighter(v, as_float32=True):
    f = Fighter()
    f.x, f.y, f.z = v.x, v.y, v.z
    f.yaw, f.pitch = f32(v.yaw), f32(v.pitch)
    f.health, f.absorption = f32(max(0.0, v.hp)), f32(v.absorption)
    f.width = f32(0.6)
    f.height = f32(v.height)
    f.on_ground = v.on_ground
    f.sprinting = v.sprinting
    f.sneaking = v.sneaking
    f.swinging = v.swinging
    f.using_item = False
    f.hurt_time = v.hurt_time
    return f


class Side:
    def __init__(self, body, ctl, learning, lag):
        self.body = body
        self.ctl = ctl
        self.learning = learning          # four-head policy drives this side
        self.lag = lag
        self.obs = ObsV4() if learning else None
        self.hist = deque(maxlen=8)
        self.swung = False
        self.prev_seen_hp = None
        self.prev_seen_hurt = 0
        self.held = [False, False, False]  # sticky sprint/jump/sneak (PolicyNet hysteresis)
        self.last_obs = None
        self.last_act = None
        self.last_labels = None
        self.click_due = False
        self.desire = 0.0
        self.events = (False, 0.0, False, 0.0)
        self.prev_hp = 20.0
        self.stats = None


class Match:
    def __init__(self, rng, opponent="scripted", preset=None, learner_cfg=None, rounds=None):
        self.rng = rng
        u = rng.uniform
        r = u(8.0, 22.0)
        pillars = []
        if rng.random() < 0.3:
            for _ in range(int(rng.integers(1, 5))):
                pillars.append((u(-r + 2, r - 2), u(-r + 2, r - 2), u(0.5, 1.5)))
        self.world = World(r, pillars)
        k = rng.random()
        if k < 0.8:
            self.kit = DIAMOND
        elif k < 0.95:
            self.kit = NETHERITE
        else:
            self.kit = Kit(epf=float(u(8, 16)))
        self.regen = int(rng.choice(3, p=[0.5, 0.3, 0.2]))
        self.server_vel = rng.random() < 0.5
        self.opponent = opponent
        self.preset = preset
        self.lcfg = learner_cfg or LearnerCfg()
        self.rounds_left = rounds if rounds else int(rng.integers(1, 4))
        lag_a, lag_b = int(rng.integers(0, 4)), int(rng.integers(0, 4))
        self.a = Side(None, LearnerCtl(rng, self.lcfg), True, lag_a)
        if opponent == "policy":
            self.b = Side(None, LearnerCtl(rng, self.lcfg), True, lag_b)
        else:
            self.b = Side(None, ScriptedCtl(rng, preset), False, lag_b)
        self.result = None
        self.t = 0
        self.recorder = None      # parity-fixture hook: list receiving ops for side a
        self.new_round()

    # ------------------------------------------------------------ rounds
    def new_round(self):
        rng = self.rng
        d = rng.uniform(3.5, 11.0)
        ang = rng.uniform(0, 2 * math.pi)
        lim = self.world.r - 2.0
        cx, cz = rng.uniform(-lim * 0.4, lim * 0.4), rng.uniform(-lim * 0.4, lim * 0.4)
        ax, az = cx + math.cos(ang) * d / 2, cz + math.sin(ang) * d / 2
        bx, bz = cx - math.cos(ang) * d / 2, cz - math.sin(ang) * d / 2
        for _ in range(20):
            if self.world.free(ax, az) and self.world.free(bx, bz):
                break
            ax, az, bx, bz = ax * 0.8, az * 0.8, bx * 0.8, bz * 0.8
        ya = math.degrees(math.atan2(-(bx - ax), bz - az)) + rng.normal(0, 25)
        yb = math.degrees(math.atan2(-(ax - bx), az - bz)) + rng.normal(0, 25)
        self.a.body = Body(ax, az, wrap(ya), self.kit)
        self.b.body = Body(bx, bz, wrap(yb), self.kit)
        for s in (self.a, self.b):
            s.hist.clear()
            s.ctl.reset_episode()
            s.swung = False
            s.prev_seen_hp = None
            s.prev_seen_hurt = 0
            s.held = [False, False, False]
            s.last_obs = None
            s.last_act = None
            s.prev_hp = 20.0
            s.events = (False, 0.0, False, 0.0)
            s.stats = {"dealt": 0.0, "taken": 0.0, "hits": 0, "swings": 0, "crits": 0, "jumps": 0}
            if s.obs is not None:
                if self.t == 0:
                    s.obs.reset_opponent()
                else:
                    s.obs.reset_episode()
                if s is self.a and getattr(self, "recorder", None) is not None:
                    self.recorder.append({"op": "opp" if self.t == 0 else "ep"})
        self.round_t = 0
        self._record()

    def _record(self):
        self.a.hist.append(snap(self.a.body))
        self.b.hist.append(snap(self.b.body))

    def seen(self, side, other):
        """`other` as `side` sees it (lag ticks ago)."""
        h = other.hist
        k = min(side.lag, len(h) - 1)
        return h[-1 - k]

    def seen_prev(self, side, other):
        h = other.hist
        k = min(side.lag + 1, len(h) - 1)
        return h[-1 - k]

    # ------------------------------------------------------------ phase 1
    def observe(self):
        """Build observations for learning sides. Returns {side_name: obs list}."""
        out = {}
        for name, s, o in (("a", self.a, self.b), ("b", self.b, self.a)):
            view = self.seen(s, o)
            pview = self.seen_prev(s, o)
            me = s.body
            # HitWatcher (health poll on the SEEN opponent + own health)
            dealt = 0.0
            if s.prev_seen_hp is not None:
                drop = s.prev_seen_hp - view.hp
                if drop > 0.01 or view.hurt_time > s.prev_seen_hurt:
                    dealt = max(0.0, drop)
                    if dealt <= 0.0:
                        dealt = 0.0001   # hurt flash without hp change
            s.prev_seen_hp = view.hp
            s.prev_seen_hurt = view.hurt_time
            took = max(0.0, s.prev_hp - me.hp)
            s.prev_hp = me.hp
            if not s.learning:
                if took > 0.01 and isinstance(s.ctl, ScriptedCtl):
                    s.ctl.on_hurt(self.t)
                continue
            ctl = s.ctl
            my_hit_before = ctl.last_my_hit_tick
            ctl.hitwatch(self.t, dealt, took)
            i_hit = ctl.last_my_hit_tick == self.t and my_hit_before != self.t
            probes = self.world.probes(me)
            ctl.movement_shaping(me, view, probes)
            f = Frame()
            f.me = to_fighter(snap(me))
            f.them = to_fighter(view)
            f.my_charge = f32(me.charge(0.0))
            f.food = f32(me.food)
            f.my_move = ctl.exec[0]
            f.my_jump_held = ctl.jump_hold > 0
            f.terrain = probes
            f.drop_ahead = 0.0
            f.ceiling_low = 0.0
            f.los = not self.world.segment_blocked(me.x, me.z, view.x, view.z, 8)
            f.crosshair_on_target = on_target(me, view, self.world)
            f.i_swung = s.swung
            f.i_hit_them = i_hit
            f.dmg_dealt = f32(dealt if i_hit else 0.0)
            f.i_crit = False
            f.i_was_hit = took > 0.01
            f.dmg_taken = f32(took if took > 0.01 else 0.0)
            s.obs.tick(f)
            s.frame = f
            s.cur_obs = s.obs.build()
            if s is self.a and self.recorder is not None:
                self.recorder.append({"op": "tick", "f": f.to_dict(), "obs": list(s.cur_obs)})
            out[name] = s.cur_obs
        return out

    # ------------------------------------------------------------ phase 2
    def act(self, heads, mature_click=False):
        """heads: {side_name: decision dict}. Advances one tick.
        Returns list of (side_name, s, act, labels, reward, s2, done, trunc) closed transitions
        — emitted lazily: a transition closes when the NEXT observation exists, so
        this returns transitions for the PREVIOUS decision of each side."""
        t = self.t
        trans = []
        sides = (("a", self.a, self.b), ("b", self.b, self.a))
        intents = {}
        for name, s, o in sides:
            view = self.seen(s, o)
            pv = self.seen_prev(s, o)
            vel = (view.x - pv.x, view.z - pv.z)
            me = s.body
            if s.learning:
                ctl = s.ctl
                # close previous transition with the reward accrued since
                if s.last_obs is not None:
                    trans.append((name, s.last_obs, s.last_act, s.last_labels, ctl.pending, s.cur_obs, False, False))
                ctl.pending = 0.0
                head = heads[name]
                act, labels, aim, click_due = ctl.decide(t, me, view, vel, self.world, head, None)
                s.last_obs = s.cur_obs
                s.last_act = act
                s.last_labels = labels
                s.click_due = click_due
                s.desire = head.get("click", 0.0)
                keys = ctl.execute(t, me, view)
                swing = ctl.try_click(t, me, view, self.world, click_due, s.desire, mature_click)
                intents[name] = (keys, aim, swing)
            else:
                keys, aim, swing = s.ctl.step(t, me, view, vel, self.world)
                intents[name] = (keys, aim, swing)

        # clicks resolve against TRUE bodies (client ray on the seen body already checked)
        for name, s, o in sides:
            keys, aim, swing = intents[name]
            s.swung = bool(swing)
            if swing:
                s.stats["swings"] += 1
                dmg, crit, full = attack(s.body, o.body, self.rng, self.server_vel)
                if dmg > 0:
                    s.stats["dealt"] += dmg
                    s.stats["hits"] += 1
                    if crit:
                        s.stats["crits"] += 1
                    o.stats["taken"] += dmg
                    if isinstance(s.ctl, ScriptedCtl):
                        s.ctl.on_my_hit(t, s.body.sprinting)
        # aim + movement
        for name, s, o in sides:
            keys, aim, swing = intents[name]
            b = s.body
            b.yaw = wrap(b.yaw + aim[0])
            b.pitch = max(-90.0, min(90.0, b.pitch + aim[1]))
            fwd, right, jump, sprint_key, sneak = keys
            was_ground = b.on_ground
            physics_tick(b, fwd, right, jump, sprint_key, sneak, self.world)
            if was_ground and not b.on_ground and b.vy > 0.3:
                s.stats["jumps"] += 1
            regen_tick(b, self.regen)
        self.t += 1
        self.round_t += 1
        self._record()

        # termination
        a_dead, b_dead = self.a.body.hp <= 0, self.b.body.hp <= 0
        if a_dead or b_dead or self.round_t >= ROUND_TICKS:
            trunc = not (a_dead or b_dead)
            for name, s, o in sides:
                if not s.learning or s.last_obs is None:
                    continue
                me_dead = s.body.hp <= 0
                they_dead = o.body.hp <= 0
                term = 0.0
                if they_dead and not me_dead:
                    term = self.lcfg.win_reward
                elif me_dead:
                    term = self.lcfg.loss_reward
                # final reward includes the last tick's hit/shaping signals
                took = max(0.0, s.prev_hp - s.body.hp)
                s.ctl.hitwatch(self.t, 0.0, took)
                r = s.ctl.pending + term
                s.ctl.pending = 0.0
                trans.append((name, s.last_obs, s.last_act, s.last_labels, r, s.last_obs, not trunc, trunc))
                s.last_obs = None
            res = "DRAW" if trunc else ("WIN" if b_dead and not a_dead else ("LOSS" if a_dead and not b_dead else "DRAW"))
            self.round_result = (res, dict(self.a.stats), dict(self.b.stats))
            self.rounds_left -= 1
            if self.rounds_left <= 0:
                self.result = res
            else:
                self.new_round()
            return trans, self.round_result
        return trans, None
