"""Self-play training: from-scratch sword-PvP brain.

Trains the 56-dim -> 96x96 -> 72-action policy against a rotating curriculum
of scripted archetypes (aggressive sprinter, w-tapper, crit-spammer,
backpedaler) plus frozen snapshots of itself, then trains the aim network on
recorded trajectories. Exports weights in the Java NeuralNet JSON schema.

Usage:  python3 train.py [episodes] [outdir] [time_seconds]

With time_seconds > 0 the run stops on the TIME budget instead of the episode
count (epsilon schedule follows expected_episodes below).
"""
import json
import math
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sim_pvp import Agent, World, try_attack, REACH, COOLDOWN_TICKS, ARENA
from dqn import DQN, Net

DIM = 64
DECISION_EVERY = 2          # ticks (10Hz, same as the live bot)
EP_LEN = 500                # 25 game-seconds cap
TRAIN_EVERY_DECISIONS = 2   # train step cadence

# v1.0.5 live-bot parity constants
BAND_MIN, BAND_MAX = 0.82, 0.96   # attack cooldown band (randomized per swing)
WTAP_CHANCE = 0.90                # user: wtap ~90% of the time (only while sprinting)
WTAP_PURE_S = 0.80                # pure S-tap share; rest splits SA / SD diagonals
WTAP_MIN_TICKS, WTAP_MAX_TICKS = 11, 14  # ~0.6s S hold (user spec), then release
JUMP_RESET_MIN, JUMP_RESET_MAX = 2, 3   # 100-150ms after being hit
CRIT_CHANCE, CRIT_CD = 0.15, 100
MIDAIR_CHANCE, MIDAIR_CD = 0.10, 80
# v1.0.7 chance semantics (live parity): a chance of 0.0 means NEVER,
# >= 1.0 means DETERMINISTIC — the technique self-drives on its cooldown
# without needing the policy's jump bit. In-between = probabilistic as before.
BACKOFF_START, BACKOFF_RELEASE = 1.35, 2.1   # v1.0.6: stop hugging, stop over-retreating
BACKOFF_MAX_TICKS = 24                        # v1.0.6: hard cap on any single backoff
WIN_REWARD, LOSS_REWARD = 45.0, -10.0   # kills pay 7.5x

# v1.0.5 movement shaping (per DECISION, i.e. 2x the Java per-tick values):
# train the model via movements — the #1 weakness
SHAPING_POCKET = 0.008       # holding the 2.2-3.3 block pocket
SHAPING_CLOSE = 0.006        # closing distance when far (good) / not (bad)
SHAPING_FROZEN = 0.016       # standing still inside reach — worst habit
SHAPING_CIRCLE = 0.004       # moving inside reach (circle-strafe)
SHAPING_WALLBACK = 0.008     # backing into a wall
SHAPING_TOO_CLOSE = 0.010    # v1.0.6: standing inside the opponent
SHAPING_BACKFAR = 0.008      # v1.0.6: backing up when already far
SHAPING_JUMP_BACK = 0.006    # v1.0.6: jumping while retreating
STRAFE_DISCIPLINE = False    # v1.0.5 default OFF: the policy owns movement (Java default matches)


def action_decode(a):
    return (a >> 3) & 7, (a & 4) != 0, (a & 2) != 0, (a & 1) != 0


def wrap_deg(d):
    return (d + 180.0) % 360.0 - 180.0


def auto_face(me: Agent, target, rng, max_turn=28.0, noise=1.5):
    dx = target.x - me.x
    dz = target.z - me.z
    desired = math.degrees(math.atan2(-dx, dz))
    d = wrap_deg(desired - me.yaw)
    d = float(np.clip(d, -max_turn, max_turn))
    me.yaw = wrap_deg(me.yaw + d + float(rng.normal(0, noise)))


class FightState:
    """Per-episode counters mirroring the Java side (HitWatcher + OpponentMemory)."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.last_my_hit = -999
        self.last_taken = -999
        self.my_last_attack = -999
        self.their_last_attack = -999
        self.combo_dealt = 0
        self.combo_taken = 0
        self.hits = 0
        self.whiffs = 0
        self.crits = 0
        self.dmg_dealt = 0.0
        self.dmg_taken = 0.0
        self.dist_ema = 3.0
        self.prev_dist = 3.0
        self.pending_reward = 0.0
        self.their_hits = 0
        self.their_jumps = 0
        self.their_crits = 0
        self.their_air_before = 0
        self.their_sprint_resets = 0
        self.my_hits_on_them = 0
        self.post_hit_jump_trials = 0
        self.post_hit_jump_hits = 0
        self.post_hit_retreat_trials = 0
        self.post_hit_retreat_hits = 0
        self.post_hit_watch = -1
        self.dist_at_post_hit = 0.0
        self.aim_buf = []          # (features, tick) for aim net labeling
        self.aim_samples = []
        self.wtap_proxy = 0        # sprint-off within 3t before own attack
        self.my_attacks = 0
        self.jump_resets = 0       # jump within 5t after taking a hit
        self.taken_at = -999
        # v1.0.4 technique state (mirrors Java CombatTactics)
        self.wtap_until = -999
        self.wtap_variant = 0      # 0=W none 1=WA 2=WD
        self.jump_reset_at = -999
        self.band_threshold = 0.9  # re-rolled after every executed swing
        self.last_crit_jump = -999
        self.last_midair_jump = -999
        self.last_chase_jump = -999
        self.backoff_wobble = 0
        self.backoff_on = False
        self.wtaps = 0
        self.backoffs = 0

    def hurttime_emu(self, ticks_since):
        return max(0.0, 10.0 - ticks_since) if ticks_since < 10 else 0.0


def terrain_probe(world, a: Agent):
    out = np.zeros(8, dtype=np.float32)
    for i in range(8):
        ang = math.radians(a.yaw + i * 45.0)
        dx, dz = -math.sin(ang), math.cos(ang)
        out[i] = 1.0 if world.line_blocked(a.x, a.z, a.x + dx * 2.4, a.z + dz * 2.4) else 0.0
    return out


def build_state(fs, me, op, world, tick):
    s = np.zeros(DIM, dtype=np.float32)
    dx = op.x - me.x
    dz = op.z - me.z
    dist = math.hypot(dx, dz)
    yawr = math.radians(me.yaw)
    fx, fz = -math.sin(yawr), math.cos(yawr)
    rx, rz = -fz, fx
    vel_fwd = me.vx * fx + me.vz * fz
    vel_str = me.vx * rx + me.vz * rz

    s[0] = me.hp / 20.0
    s[1] = 0.0
    s[2] = 1.0
    s[3] = me.cooldown_progress()
    s[4] = 1.0 if me.sprinting else 0.0
    s[5] = 1.0 if me.on_ground else 0.0
    s[6] = 0.0 if me.on_ground else (1.0 if me.vy > 0 else -1.0)
    s[7] = float(np.clip(me.vy, -1, 1))
    s[8] = float(np.clip(vel_fwd / 0.35, -1.5, 1.5))
    s[9] = float(np.clip(vel_str / 0.35, -1.5, 1.5))
    s[10] = fs.hurttime_emu(tick - fs.last_taken) / 10.0
    s[11] = min(1.0, max(0.0, (tick - fs.last_my_hit) / 100.0))
    s[12] = min(1.0, max(0.0, (tick - fs.last_taken) / 100.0))
    s[13] = min(1.5, fs.combo_dealt / 6.0)
    s[14] = min(1.5, fs.combo_taken / 6.0)
    s[15] = min(1.0, max(0.0, (tick - fs.my_last_attack) / 60.0))
    s[16] = min(2.0, dist / 6.0)
    bearing = math.degrees(math.atan2(-dx, dz))
    rel_yaw = wrap_deg(bearing - me.yaw)
    s[17] = rel_yaw / 180.0
    s[18] = 0.0
    s[19] = float(np.clip((op.vx * fx + op.vz * fz) / 0.35, -1.5, 1.5))
    s[20] = float(np.clip((op.vx * rx + op.vz * rz) / 0.35, -1.5, 1.5))
    s[21] = float(np.clip(op.vy, -1, 1))
    s[22] = 1.0 if op.on_ground else 0.0
    s[23] = 0.0 if op.on_ground else (1.0 if op.vy > 0 else -1.0)
    s[24] = fs.hurttime_emu(tick - fs.their_last_attack) / 10.0
    s[25] = op.hp / 20.0
    s[26] = 1.0 if op.speed > 0.24 else 0.0
    s[27] = min(1.0, max(0.0, (tick - fs.their_last_attack) / COOLDOWN_TICKS))
    s[28] = min(1.0, max(0.0, (tick - fs.their_last_attack) / 100.0))
    s[29] = min(2.0, fs.dist_ema / 6.0)
    s[30] = rel_yaw / 180.0
    s[31] = float(np.clip((dist - fs.prev_dist) * 2.0, -0.5, 0.5))
    denom = max(1.0, fs.their_hits)
    s[32] = min(1.0, fs.their_sprint_resets / max(4.0, denom))
    s[33] = 0.0
    s[34] = min(1.0, fs.their_jumps / max(4.0, denom))
    s[35] = min(1.0, fs.their_crits / denom)
    s[36] = min(1.0, fs.their_hits / 20.0)
    s[37] = min(2.0, fs.dist_ema / 3.0) / 2.0
    s[38] = fs.post_hit_jump_hits / max(1.0, fs.post_hit_jump_trials) if fs.post_hit_jump_trials >= 3 else 0.5
    s[39] = fs.post_hit_retreat_hits / max(1.0, fs.post_hit_retreat_trials) if fs.post_hit_retreat_trials >= 3 else 0.5
    s[40] = min(1.0, tick / (120.0 * 20.0))
    s[41] = min(1.0, (fs.their_hits + fs.my_hits_on_them) / 50.0)
    tb = terrain_probe(world, me)
    s[42:50] = tb
    s[50] = 0.0
    s[51] = 0.0
    s[52] = (tb[2] + tb[6]) * 0.5
    s[53] = min(1.0, tick / 2400.0)
    att = fs.hits + fs.whiffs
    s[54] = (fs.whiffs / att) if att else 0.0
    s[55] = 1.0
    # v1.0.4 additions (56..63) — mirror the Java Perception exactly
    lead = 3.0 / 20.0
    px = op.x + op.vx * lead
    pz = op.z + op.vz * lead
    pdx, pdz = px - me.x, pz - me.z
    s[56] = float(np.clip((pdx * fx + pdz * fz) / 3.0, -2.0, 2.0))
    s[57] = float(np.clip((pdx * rx + pdz * rz) / 3.0, -2.0, 2.0))
    op_bearing = math.degrees(math.atan2(-(me.x - op.x), me.z - op.z))
    s[58] = wrap_deg(op_bearing - op.yaw + (me.yaw - op.yaw)) / 180.0  # their rel yaw proxy
    s[59] = 0.0                          # their pitch (sim has none)
    s[60] = 0.0                          # their sneak (sim has none)
    s[61] = 1.0 if (tick - fs.their_last_attack) <= 1 else 0.0  # their swing telegraph
    my_move = getattr(me, "_move", (0, False))[0]
    s[62] = 1.0 if my_move in (1, 5, 6) else (-1.0 if my_move in (2, 7, 8) else 0.0)
    s[63] = 1.0 if my_move in (4, 6, 8) else (-1.0 if my_move in (3, 5, 7) else 0.0)
    fs.prev_dist = dist
    fs.dist_ema += 0.01 * (dist - fs.dist_ema)
    return s, dist, rel_yaw


# ---------------------------------------------------------------- scripted opponents

class Scripted:
    KIND = "base"

    def __init__(self, rng):
        self.rng = rng
        self.agent = None
        self.attack_now = False
        self.jump_cd = 0
        self.did_sprint_reset = False

    def spawn(self, x, z):
        self.agent = Agent(x, z)

    def step(self, me, world, tick):
        """Facing + movement. Returns True if attacking this tick."""
        self.attack_now = False
        self.did_sprint_reset = False
        self.jump_cd = max(0, self.jump_cd - 1)
        return False


class Aggressive(Scripted):
    KIND = "aggressive"

    def __init__(self, rng):
        super().__init__(rng)
        self.band = 0.9
        self.jump_reset_at = -999
        self.reset_tick = -9

    def step(self, me, world, tick):
        super().step(me, world, tick)
        a = self.agent
        dx, dz = me.x - a.x, me.z - a.z
        dist = math.hypot(dx, dz)
        auto_face(a, me, self.rng)
        sprint = dist > 1.6
        move = 1 if dist > 1.2 else 0
        if dist < 1.0:
            # real sparring partners also create space when bodies collide
            move = 2
        a.apply_action(move, sprint, False)
        # random 82-96% band like the live bot; jump reset 100-150ms after hits
        self.attack_now = dist <= REACH and a.cooldown_progress() >= self.band
        if self.attack_now:
            self.band = float(self.rng.uniform(0.82, 0.96))
        return self.attack_now

    def on_hit_me(self, tick):
        if self.rng.random() < 0.75:
            self.jump_reset_at = tick + int(self.rng.integers(2, 4))

    def maybe_jump_reset(self, me, tick):
        if tick == self.jump_reset_at and self.agent.on_ground:
            self.agent.apply_action(0, False, True)
            self.jump_reset_at = -999


class PracticeBot(Aggressive):
    """PvP-practice-bot style sparring partner: always aggressive, W-taps,
    jump resets on the 100-150ms window, never stops attacking."""

    KIND = "practicebot"

    def step(self, me, world, tick):
        Scripted.step(self, me, world, tick)
        a = self.agent
        dx, dz = me.x - a.x, me.z - a.z
        dist = math.hypot(dx, dz)
        auto_face(a, me, self.rng)
        tap = abs(tick - self.reset_tick) <= 1
        move = 1 if dist > 1.2 else 0
        if dist < 1.0:
            move = 2
        sprint = not tap and dist > 1.6
        a.apply_action(move, sprint, False)
        self.attack_now = dist <= REACH and a.cooldown_progress() >= self.band
        if self.attack_now:
            self.reset_tick = tick
            self.did_sprint_reset = True
            self.band = float(self.rng.uniform(0.82, 0.96))
        return self.attack_now


class Wtapper(Aggressive):
    KIND = "wtapper"

    def __init__(self, rng):
        super().__init__(rng)
        self.reset_until = -9

    def step(self, me, world, tick):
        Scripted.step(self, me, world, tick)
        a = self.agent
        dx, dz = me.x - a.x, me.z - a.z
        dist = math.hypot(dx, dz)
        auto_face(a, me, self.rng)
        cp = a.cooldown_progress()
        attack = dist <= REACH and cp >= 0.95
        if attack and a.sprinting:
            # v1.0.5 S-tap: sprint + hit + press S ~0.6s + release
            self.reset_until = tick + int(self.rng.integers(WTAP_MIN_TICKS, WTAP_MAX_TICKS + 1))
            self.did_sprint_reset = True
        if tick < self.reset_until:
            a.apply_action(2, False, False)   # S held, sprint dropped
        else:
            sprint = dist > 1.6
            a.apply_action(1 if dist > 1.2 else 0, sprint, False)
        self.attack_now = attack
        return attack


class CritSpammer(Scripted):
    KIND = "critspam"

    def step(self, me, world, tick):
        super().step(me, world, tick)
        a = self.agent
        dx, dz = me.x - a.x, me.z - a.z
        dist = math.hypot(dx, dz)
        auto_face(a, me, self.rng)
        jump = a.on_ground and dist < 3.6 and self.jump_cd == 0
        if jump:
            self.jump_cd = 3
        attack = dist <= REACH and (not a.on_ground) and a.vy < 0 and a.cooldown_progress() >= 0.85
        a.apply_action(1 if dist > 1.4 else 0, dist > 2.2, jump)
        self.attack_now = attack
        return attack


class Backpedaler(Scripted):
    KIND = "backpedal"

    def step(self, me, world, tick):
        super().step(me, world, tick)
        a = self.agent
        dx, dz = me.x - a.x, me.z - a.z
        dist = math.hypot(dx, dz)
        auto_face(a, me, self.rng)
        attack = dist <= REACH and a.cooldown_progress() >= 0.999
        if dist < 2.6:
            a.apply_action(2, False, False)
        elif dist > 3.0:
            a.apply_action(1, True, False)
        else:
            a.apply_action(1 if self.rng.random() < 0.5 else 0, False, False)
        self.attack_now = attack
        return attack


SCRIPTS = {"aggressive": Aggressive, "wtapper": Wtapper,
           "critspam": CritSpammer, "backpedal": Backpedaler,
           "practicebot": PracticeBot}


class Snapshot(Scripted):
    KIND = "snapshot"

    def __init__(self, rng, dqn, eps=0.03):
        super().__init__(rng)
        self.dqn = dqn
        self.eps = eps
        self.fs = FightState()
        self.prev_state = None
        self.prev_action = None
        self.world = None

    def step(self, me, world, tick):
        Scripted.step(self, me, world, tick)
        a = self.agent
        dx, dz = me.x - a.x, me.z - a.z
        dist = math.hypot(dx, dz)
        auto_face(a, me, self.rng)
        # decision every DECISION_EVERY ticks
        if tick % DECISION_EVERY == 0:
            s, d, rel = build_state(self.fs, a, me, world, tick)
            act = self.dqn.act(s, self.eps)
            move, sprint, jump, attack = action_decode(act)
            self._move = (move, sprint, jump)
            self._attack = attack
            self.prev_state = s
            self.prev_action = act
        move, sprint, jump = self._move
        a.apply_action(move, sprint, jump and a.on_ground)
        self.attack_now = self._attack
        return self._attack


def run_episode(dqn, opponent, world, rng, eps_me):
    me = Agent(float(rng.uniform(-6, 6)), float(rng.uniform(-6, 6)))
    opponent.spawn(float(rng.uniform(-6, 6)), float(rng.uniform(-6, 6)))
    op_agent = opponent.agent
    me.yaw = math.degrees(math.atan2(-(op_agent.x - me.x), op_agent.z - me.z))
    op_agent.yaw = math.degrees(math.atan2(-(me.x - op_agent.x), me.z - op_agent.z))
    fs = FightState()
    if isinstance(opponent, Snapshot):
        opponent.fs.reset()
    result = "DRAW"
    prev_s = None
    prev_a = None
    me._move = (0, False)
    me._attack = False

    for tick in range(EP_LEN):
        is_decision = (tick % DECISION_EVERY == 0)
        if is_decision:
            s, dist, rel_yaw = build_state(fs, me, op_agent, world, tick)
            if prev_s is not None:
                dqn.remember(prev_s, prev_a, fs.pending_reward, s, 0.0)
                fs.pending_reward = 0.0
            action = dqn.act(s, eps_me)
            move, sprint, jump, attack = action_decode(action)
            prev_s = s
            prev_a = action
            # ---- tactics layer (mirrors Java CombatTactics exactly) ----------
            dist_h = dist
            # backoff: too close means inside them (~1 block)
            if dist_h < BACKOFF_START:
                if not getattr(fs, "backoff_on", False):
                    fs.backoff_on = True
                    fs.backoff_wobble = 0
                    fs.backoffs += 1
            elif fs.backoff_on and (dist_h >= BACKOFF_RELEASE or dist_h > 3.0):
                fs.backoff_on = False
            if getattr(fs, "backoff_on", False):
                fs.backoff_wobble += 1
                if fs.backoff_wobble > BACKOFF_MAX_TICKS or dist_h >= BACKOFF_RELEASE:
                    fs.backoff_on = False
                else:
                    # v1.0.6 arc retreat: SA/SD mostly, one re-close step
                    move = (7, 8, 2, 8, 7, 8, 6, 7)[fs.backoff_wobble % 8]
                    sprint = False
                    jump = False
                    attack = False
            elif tick < fs.wtap_until:
                # v1.0.5 wtap = S-tap window: S pressed (maybe SA/SD), sprint off;
                # lasts ~0.6s then releases back to the policy's movement
                move = {0: 2, 1: 7, 2: 8}[fs.wtap_variant]
                sprint = False
            elif fs.combo_dealt <= 0 and STRAFE_DISCIPLINE:
                # optional strafe discipline (v1.0.5 default OFF — the policy
                # owns movement; when on, strafe-only maps to FORWARD, never idle)
                if move in (3, 4):
                    move = 1
                elif move in (5, 6):
                    move = 1
            # jump rationing: jump-reset window > crit attempt > midair > chase.
            # v1.0.5: NO technique jump can fire inside the wtap S-window.
            jump_now = False
            if fs.jump_reset_at == tick:
                if me.on_ground:
                    jump_now = True
                    fs.jump_resets += 1
                fs.jump_reset_at = -999
            elif CRIT_CHANCE >= 1.0 and tick >= fs.wtap_until and me.on_ground \
                    and 1.2 < dist <= 3.4 and tick - fs.last_crit_jump >= CRIT_CD:
                # v1.0.7 parity: chance 1.0 = DETERMINISTIC crit — self-drives
                # on its cooldown, no policy jump-bit required (matches live)
                jump_now = True
                fs.last_crit_jump = tick
            elif MIDAIR_CHANCE >= 1.0 and tick >= fs.wtap_until and me.on_ground \
                    and 1.2 < dist <= 3.4 and tick - fs.last_midair_jump >= MIDAIR_CD:
                jump_now = True
                fs.last_midair_jump = tick
            elif tick >= fs.wtap_until and jump and me.on_ground and dist <= 3.4 and dist > 1.2:
                if tick - fs.last_crit_jump >= CRIT_CD and (CRIT_CHANCE >= 1.0 or rng.random() < min(1.0, CRIT_CHANCE)):
                    jump_now = True
                    fs.last_crit_jump = tick
                elif tick - fs.last_midair_jump >= MIDAIR_CD and (MIDAIR_CHANCE >= 1.0 or rng.random() < min(1.0, MIDAIR_CHANCE)):
                    jump_now = True
                    fs.last_midair_jump = tick
            elif tick >= fs.wtap_until and jump and me.on_ground and dist > 4.0 and tick - fs.last_chase_jump >= 20:
                jump_now = True
                fs.last_chase_jump = tick
            jump = jump_now
            # attack band: click only inside this swing's personalized 82-96%
            attack = attack and dist <= REACH and me.cooldown_progress() >= fs.band_threshold
            if attack:
                fs.band_threshold = float(rng.uniform(BAND_MIN, BAND_MAX))
                fs.my_attacks += 1
            me._move = (move, sprint)
            me._jump = jump
            me._attack = attack
        else:
            move, sprint = me._move
            jump = me._jump
            attack = False

        me.apply_action(move, sprint, jump)

        # auto-facing for the learned agent (mirrors the live AimController:
        # turns toward the opponent at humanized speed with sensor noise)
        auto_face(me, op_agent, rng)

        # opponent turn (facing + movement + attack intent)
        attacked = opponent.step(me, world, tick)
        if hasattr(opponent, "maybe_jump_reset"):
            opponent.maybe_jump_reset(me, tick)

        # integrate both
        me.integrate()
        opponent.agent.integrate()

        # their jump detection
        if fs.their_air_before == 0 and not op_agent.on_ground:
            fs.their_jumps += 1
        fs.their_air_before = 0 if op_agent.on_ground else 1

        r = 0.0
        if attack:
            hit, dmg, crit, whiff = try_attack(me, op_agent, world)
            if whiff:
                fs.whiffs += 1
                r -= 0.02
            elif hit:
                fs.hits += 1
                fs.dmg_dealt += dmg
                fs.last_my_hit = tick
                fs.combo_dealt += 1
                fs.combo_taken = 0
                r += 0.2 * dmg
                if crit:
                    fs.crits += 1
                    # small nudge only — the 1.5x crit damage is already
                    # rewarded via 0.2*dmg; a big flat bonus taught the
                    # policy to crit-spam. Mirrors the Java HitWatcher.
                    r += 0.1
                if fs.combo_dealt > 2:
                    r += 0.04
                fs.my_hits_on_them += 1
                fs.post_hit_jump_trials += 1
                fs.post_hit_retreat_trials += 1
                fs.post_hit_watch = 10
                fs.dist_at_post_hit = math.hypot(op_agent.x - me.x, op_agent.z - me.z)
                # v1.0.5 S-tap: SPRINTING + HIT + press S ~0.6s + release
                if me.sprinting and rng.random() < WTAP_CHANCE:
                    rr = rng.random()
                    fs.wtap_variant = 0 if rr < WTAP_PURE_S else (1 if rr < WTAP_PURE_S + (1 - WTAP_PURE_S) / 2 else 2)
                    fs.wtap_until = tick + int(rng.integers(WTAP_MIN_TICKS, WTAP_MAX_TICKS + 1))
                    fs.wtaps += 1

        if attacked:
            hit, dmg, crit, whiff = try_attack(op_agent, me, world)
            if hit:
                fs.their_hits += 1
                fs.dmg_taken += dmg
                fs.last_taken = tick
                fs.their_last_attack = tick
                fs.combo_taken += 1
                fs.combo_dealt = 0
                r -= 0.25 * dmg
                if fs.combo_taken > 2:
                    r -= 0.04
                if crit:
                    fs.their_crits += 1
                if opponent.did_sprint_reset:
                    fs.their_sprint_resets += 1
                me.sprinting = False
                fs.taken_at = tick
                # strict jump reset: jump exactly 100-150ms (2-3 ticks) after
                # being hit — the only reflex jump there is
                fs.jump_reset_at = tick + int(rng.integers(JUMP_RESET_MIN, JUMP_RESET_MAX + 1))
                # the opponent may jump-reset our hits too
                if hasattr(opponent, "on_hit_me"):
                    opponent.on_hit_me(tick)

        # post-hit reaction probe (does the opponent jump or retreat after my hit?)
        if fs.post_hit_watch > 0:
            fs.post_hit_watch -= 1
            if not op_agent.on_ground:
                fs.post_hit_jump_hits += 1
                fs.post_hit_watch = -1
            d2 = math.hypot(op_agent.x - me.x, op_agent.z - me.z)
            if d2 > fs.dist_at_post_hit + 0.9:
                fs.post_hit_retreat_hits += 1
                fs.post_hit_watch = -1

        # v1.0.5 movement reward shaping — train the model via movements
        # (mirrors the Java movementShaping per-tick values x DECISION_EVERY)
        ddx = op_agent.x - me.x
        ddz = op_agent.z - me.z
        dist_s = math.hypot(ddx, ddz)
        my_speed = math.hypot(me.vx, me.vz)
        toward = 0.0 if dist_s < 1e-4 else (me.vx * ddx + me.vz * ddz) / dist_s
        if 2.2 <= dist_s <= 3.3:
            r += SHAPING_POCKET
        if dist_s > 4.5:
            r += SHAPING_CLOSE if toward > 0.05 else -SHAPING_CLOSE
        in_wtap = tick < fs.wtap_until
        in_backoff = getattr(fs, "backoff_on", False)
        if dist_s <= 3.4 and me.on_ground and my_speed < 0.06 and not in_wtap and not in_backoff:
            r -= SHAPING_FROZEN
        if dist_s <= 3.4 and my_speed > 0.12:
            r += SHAPING_CIRCLE
        wall_behind = world.blocked(me.x - (op_agent.x - me.x) / max(dist_s, 1e-4) * 1.5,
                                    me.z - (op_agent.z - me.z) / max(dist_s, 1e-4) * 1.5) if dist_s > 1e-4 else False
        if wall_behind and toward < -0.08:
            r -= SHAPING_WALLBACK
        # v1.0.6 shaping: too close / over-retreat / jump-while-retreating —
        # mirrors the Java movementShaping additions (retreat = velocity pointing
        # AWAY from the opponent)
        if dist_s < 1.5:
            r -= SHAPING_TOO_CLOSE
        if toward < -0.08 and dist_s > 3.2:
            r -= SHAPING_BACKFAR
        if toward < -0.08 and not me.on_ground:
            r -= SHAPING_JUMP_BACK

        fs.pending_reward += r + 0.001

        # aim supervision data (12 features -> bearing offset 3 ticks later)
        if tick % 2 == 0:
            feat = aim_features(fs, me, op_agent)
            fs.aim_buf.append((feat, tick))
        done_idx = [i for i, (f0, t0) in enumerate(fs.aim_buf) if tick - t0 == 3]
        for i in done_idx:
            f0, t0 = fs.aim_buf[i]
            dx2 = op_agent.x - me.x
            dz2 = op_agent.z - me.z
            br = math.degrees(math.atan2(-dx2, dz2))
            yaw_err = wrap_deg(br - me.yaw) / 180.0
            fs.aim_samples.append((f0, (float(np.clip(yaw_err, -1, 1)), 0.0)))
        for i in reversed(done_idx):
            fs.aim_buf.pop(i)

        if op_agent.hp <= 0:
            result = "WIN"
            fs.pending_reward += WIN_REWARD   # kills pay BIG (7.5x the old 6.0)
            break
        if me.hp <= 0:
            result = "LOSS"
            fs.pending_reward += LOSS_REWARD
            break

    # terminal transition
    if prev_s is not None:
        term = WIN_REWARD if result == "WIN" else (LOSS_REWARD if result == "LOSS" else 0.0)
        dqn.remember(prev_s, prev_a, fs.pending_reward + term, prev_s, 1.0)
    return fs, result


def aim_features(fs, me, op):
    tgt_vel = (op.vx, op.vy, op.vz)
    yawr = math.radians(me.yaw)
    fx, fz = -math.sin(yawr), math.cos(yawr)
    rx, rz = -fz, fx
    dx = op.x - me.x
    dz = op.z - me.z
    dist = math.hypot(dx, dz)
    f = np.zeros(12, dtype=np.float32)
    f[0] = np.clip((op.vx * fx + op.vz * fz) / 0.35, -1.5, 1.5)
    f[1] = np.clip((op.vx * rx + op.vz * rz) / 0.35, -1.5, 1.5)
    f[2] = float(np.clip(op.vy, -1, 1))
    f[3] = 0.0 if op.on_ground else (1.0 if op.vy > 0 else -1.0)
    f[4] = float(np.clip(dist / 6.0, 0, 2))
    bearing = math.degrees(math.atan2(-dx, dz))
    f[5] = wrap_deg(bearing - me.yaw) / 180.0
    f[6] = 0.0
    f[7] = 0.0
    f[8] = 0.0
    f[9] = 0.0
    f[10] = 1.0 if (op.vx * fx + op.vz * fz) > 0.2 else 0.0
    f[11] = 1.0
    return f


def evaluate(dqn, rng, n=25):
    out = {}
    for kind, cls in SCRIPTS.items():
        w = 0
        dmg_ratio = []
        for _ in range(n):
            world = World(rng)
            opp = cls(rng)
            fs, result = run_episode(dqn, opp, world, rng, eps_me=0.0)
            if result == "WIN":
                w += 1
            dmg_ratio.append(fs.dmg_dealt - fs.dmg_taken)
        out[kind] = (w / n, float(np.mean(dmg_ratio)))
    return out


def save_checkpoint(outdir, dqn, aim_net, ep, aim_pool, cum_seconds=0.0):
    cp = {
        "ep": ep,
        "steps": dqn.steps,
        "cum_seconds": cum_seconds,
        "q": dqn.q.state_dict(),
        "target": dqn.target.state_dict(),
        "aim": aim_net.state_dict(),
        "aim_pool": [[[float(v) for v in f], [ye, pe]] for (f, (ye, pe)) in aim_pool],
    }
    tmp = os.path.join(outdir, "checkpoint.json.tmp")
    with open(tmp, "w") as fh:
        json.dump(cp, fh)
    os.replace(tmp, os.path.join(outdir, "checkpoint.json"))


def load_checkpoint(outdir, dqn, aim_net):
    path = os.path.join(outdir, "checkpoint.json")
    if not os.path.exists(path):
        return 0, [], 0.0
    with open(path) as fh:
        cp = json.load(fh)
    dqn.q.load_state(cp["q"])
    dqn.target.load_state(cp["target"])
    dqn.q.copy_to(dqn.target)
    aim_net.load_state(cp["aim"])
    dqn.steps = int(cp["steps"])
    cum = float(cp.get("cum_seconds", 0.0))  # seconds trained in earlier chunks
    pool = [ (np.asarray(f, dtype=np.float32), (y[0], y[1])) for f, y in cp["aim_pool"] ]
    print(f"resumed from checkpoint: ep {cp['ep']}, steps {dqn.steps}, cum {cum:.0f}s", flush=True)
    return int(cp["ep"]), pool, cum


def main():
    episodes = int(sys.argv[1]) if len(sys.argv) > 1 else 12000
    outdir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "out")
    time_budget = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
    resume = len(sys.argv) > 4 and sys.argv[4] == "resume"
    expected_eps = 20000  # epsilon/lr schedule anchor for time-budgeted runs (480x480 brain, ~1h)
    os.makedirs(outdir, exist_ok=True)
    rng = np.random.default_rng(20260928)

    dqn = DQN([DIM, 480, 480, 72], capacity=120000, gamma=0.995, seed=20260928)
    aim_net = Net([12, 64, 64, 2], rng)
    n_params = sum(int(np.prod(w.shape)) for w in dqn.q.w) + sum(int(np.prod(b.shape)) for b in dqn.q.b)
    print(f"brain: {dqn.q.sizes} — {n_params:,} params ≈ {n_params * 4 / 1024 / 1024:.2f} MB float32", flush=True)

    script_pool = list(SCRIPTS.keys())
    snapshots = []
    history = []
    aim_pool = []
    ep = 0
    cum_seconds = 0.0
    if resume:
        ep, aim_pool, cum_seconds = load_checkpoint(outdir, dqn, aim_net)
    t0 = time.time()          # wall clock of THIS chunk
    last_ckpt = time.time()
    running = True
    while running:
        ep += 1
        if time_budget > 0:
            if (time.time() - t0) >= time_budget:
                break
            schedule_n = expected_eps
        else:
            if ep > episodes:
                break
            schedule_n = episodes
        # curriculum opponent selection
        use_self = len(snapshots) > 0 and rng.random() < 0.35
        if use_self:
            opp = Snapshot(rng, snapshots[int(rng.integers(0, len(snapshots)))])
        else:
            kind = script_pool[int(rng.integers(0, len(script_pool)))]
            opp = SCRIPTS[kind](rng)
        world = World(rng)
        eps_me = max(0.05, 1.0 * (1.0 - ep / max(1, schedule_n * 0.55)))
        fs, result = run_episode(dqn, opp, world, rng, eps_me)
        if len(fs.aim_samples) > 0:
            aim_pool.extend(fs.aim_samples[-256:])
            if len(aim_pool) > 20000:
                aim_pool[:] = aim_pool[-20000:]
        # training burst each episode end
        for _ in range(20):
            dqn.train(batch=32, lr=1e-3 if ep < schedule_n * 0.6 else 5e-4)
        if dqn.steps and dqn.steps % 1000 == 0:
            dqn.sync()

        history.append((ep, result, fs.dmg_dealt, fs.dmg_taken))
        if ep % 300 == 0:
            ev = evaluate(dqn, rng, n=14)
            el = time.time() - t0
            wr = {k: f"{v[0]:.2f}" for k, v in ev.items()}
            print(f"[{el:6.0f}s] ep {ep}  eps {eps_me:.2f}  steps {dqn.steps}  winrates {wr}  "
                  f"last: {result} dealt {fs.dmg_dealt:.1f} taken {fs.dmg_taken:.1f} "
                  f"wtap% {100.0 * fs.wtaps / max(1, fs.my_hits_on_them):.0f} "
                  f"jumpreset {fs.jump_resets} backoff {fs.backoffs} crit% {100.0 * fs.crits / max(1, fs.hits):.0f}", flush=True)
            history.append(("eval", ep, ev))
        if ep % 400 == 0 and ep >= 800:
            snapshots.append(dqn)
            if len(snapshots) > 4:
                snapshots.pop(0)

        # aim net supervision
        if ep % 20 == 0 and len(aim_pool) > 256:
            idx = rng.integers(0, len(aim_pool), size=min(1024, len(aim_pool)))
            xs = np.array([aim_pool[i][0] for i in idx], dtype=np.float32)
            ys = np.array([aim_pool[i][1] for i in idx], dtype=np.float32)
            for i in range(0, len(xs), 32):
                aim_net.train_batch(xs[i:i + 32], ys[i:i + 32], 1e-3)

        # periodic checkpoint (sandbox kills background processes — the 1h run
        # is driven in resumed chunks; every ~240s the full brain state lands
        # on disk so a chunk can die at ANY point without losing progress)
        if time.time() - last_ckpt >= 240:
            save_checkpoint(outdir, dqn, aim_net, ep, aim_pool, cum_seconds + time.time() - t0)
            last_ckpt = time.time()
            print("checkpoint saved", flush=True)

    # chunk exit: flush the tail progress into the checkpoint BEFORE the
    # eval/export so nothing trained this chunk is ever lost
    cum_seconds += time.time() - t0
    save_checkpoint(outdir, dqn, aim_net, ep, aim_pool, cum_seconds)
    print("checkpoint saved (chunk exit)", flush=True)

    # final eval + export
    ev = evaluate(dqn, rng, n=40)
    print("FINAL:", {k: (f"{v[0]:.2f}", f"{v[1]:.1f}") for k, v in ev.items()})

    export = {
        "schema": 1,
        "meta": {
            "trainedBy": "Super Z — self-play double-DQN (64x480x480x72, 1.13MB brain), simplified 1.9+ combat sim with live-parity tactics (82-96% attack band, W/WA/WD w-tap 90%, 100-150ms jump resets, too-close backoff, strafe discipline, crit moderation, sneak hits, mid-air hits), diamond sword + full diamond armor kit (no food)",
            "episodes": ep,
            "trainSeconds": round(cum_seconds),
            "finalEpsilon": eps_me,
            "winrates": {k: v[0] for k, v in ev.items()},
            "actions": 72,
            "stateDim": DIM,
            "paramsMB": round(n_params * 4 / 1024 / 1024, 2),
        },
        "q": {"arch": dqn.q.sizes, "layers": dqn.q.state_dict()["layers"]},
    }
    with open(os.path.join(outdir, "policy.json"), "w") as fh:
        json.dump(export, fh)
    aim_export = {
        "schema": 1,
        "arch": aim_net.sizes,
        "layers": aim_net.state_dict()["layers"],
    }
    with open(os.path.join(outdir, "aim.json"), "w") as fh:
        json.dump(aim_export, fh)

    # training report
    with open(os.path.join(outdir, "TRAINING_REPORT.md"), "w") as fh:
        fh.write("# Pre-training report\n\n")
        fh.write("Kit: diamond sword + full diamond armor, no food (no healing).\n\n")
        fh.write(f"Episodes: {ep} | DQN steps: {dqn.steps} | time: {cum_seconds:.0f}s (cumulative)\n\n")
        fh.write("## Final win rates (n=40 each)\n\n")
        fh.write("| opponent | win rate | avg dmg margin |\n|---|---|---|\n")
        for k, v in ev.items():
            fh.write(f"| {k} | {v[0]:.2f} | {v[1]:.1f} |\n")
        fh.write("\n## Eval history\n\n```\n")
        for h in history:
            if h[0] == "eval":
                fh.write(f"ep {h[1]}: {h[2]}\n")
        fh.write("```\n")
    print("exported to", outdir)


if __name__ == "__main__":
    main()
