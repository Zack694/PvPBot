"""v3 trainer for the four-head brain (pure mode).

  python3 train.py --run runs/main --minutes 25            # fresh / resume
  python3 train.py --run runs/main --minutes 25 --export    # also write .pbm

Architecture (Ape-X style, CPU):
  K actor processes x E duels each -> n-step transitions -> learner (PER,
  double-DQN on MOVE + flag heads, supervised AIM/CLICK) -> weights back to
  the actors through shared memory. One evaluator process scores the greedy
  brain against a FIXED benchmark set; the best-scoring weights are kept.
  Self-play league: snapshots every N updates become opponents.

The sandbox kills background processes, so runs are chunked: every chunk
resumes model + target + optimizer + replay + league from --run.
"""
import os
# one BLAS thread per process: K actors + learner would otherwise oversubscribe
for _v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_v, "1")
import argparse
import json
import math
import queue as pyqueue
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import torch  # noqa: E402
import torch.multiprocessing as mp  # noqa: E402

from net import ARCH, MOVES, SPRINT_OFF, JUMP_OFF, SNEAK_OFF, AIM_OFF, CLICK_OFF  # noqa: E402

ARCH_V1 = [64, 480, 480, 72]
from net import build_torch, torch_to_numpy, numpy_to_torch, np_forward, decide, save_pbm, load_pbm  # noqa: E402
from replay import Replay  # noqa: E402

# v2.3.2 adaptive curriculum: base weights, scaled up for opponent types the
# brain currently loses to (per-actor win-rate EMA)
CURRICULUM = {None: 3.0, "practice": 2.0, "crit": 1.0, "critpro": 1.5, "combo": 1.5, "kiter": 1.0, "jitter": 1.0}
PRESETS = list(CURRICULUM.keys())
BENCH = [("scripted", None, 101), ("scripted", None, 202), ("scripted", "practice", 303),
         ("scripted", "practice", 404), ("scripted", "crit", 505), ("scripted", "kiter", 606),
         ("scripted", "jitter", 707), ("scripted", None, 808),
         ("scripted", "critpro", 909), ("scripted", "combo", 1010)]
BENCH_SIG = "v2.3.2-10"


def default_cfg():
    return dict(actors=4, envs=16, nstep=3, gamma=0.995, batch=512, lr=2.5e-4, tau=0.005,
                replay=2_000_000, warmup=60_000, max_ratio=6.0, publish_every=50,
                league_every=8000, league_max=24, eval_every=240, eval_rounds=6,
                selfplay=0.45, aim_w=0.5, click_w=0.5, huber=1.0, grad_clip=10.0)


# =================================================================== actor
class NStep:
    def __init__(self, n, gamma):
        self.n, self.g = n, gamma
        self.buf = []

    def push(self, s, act, lab, r, s2, done, trunc, out):
        self.buf.append((s, act, lab, r))
        if done or trunc:
            k = len(self.buf)
            for i in range(k):
                R, g = 0.0, 1.0
                for j in range(i, k):
                    R += g * self.buf[j][3]
                    g *= self.g
                out.append((self.buf[i][0], self.buf[i][1], self.buf[i][2], R, s2, 0.0 if done else g))
            self.buf = []
            return
        if len(self.buf) >= self.n:
            R, g = 0.0, 1.0
            for j in range(self.n):
                R += g * self.buf[j][3]
                g *= self.g
            out.append((self.buf[0][0], self.buf[0][1], self.buf[0][2], R, s2, g))
            self.buf.pop(0)


def pack(items, dim=100):
    n = len(items)
    b = {"s": np.zeros((n, dim), np.float32), "s2": np.zeros((n, dim), np.float32),
         "move": np.zeros(n, np.int8), "sprint": np.zeros(n, np.int8), "jump": np.zeros(n, np.int8),
         "sneak": np.zeros(n, np.int8), "aim": np.zeros((n, 2), np.float32), "click": np.zeros(n, np.float32),
         "R": np.zeros(n, np.float32), "gN": np.zeros(n, np.float32)}
    for i, (s, act, lab, R, s2, g) in enumerate(items):
        b["s"][i] = s
        b["s2"][i] = s2
        if lab is None:          # v1: one 72-way action index
            b["move"][i] = act
            b["R"][i] = R
            b["gN"][i] = g
            continue
        b["move"][i] = act[0]
        b["sprint"][i] = 1 if act[1] else 0
        b["jump"][i] = 1 if act[2] else 0
        b["sneak"][i] = 1 if act[3] else 0
        b["aim"][i] = (lab[0], lab[1])
        b["click"][i] = lab[2]
        b["R"][i] = R
        b["gN"][i] = g
    return b


def decide_any(q, eps, side, rng):
    """v2: four-head decision dict. v1: epsilon-greedy index over 72 actions."""
    if side.kind == "v1":
        if rng.random() < eps:
            return int(rng.integers(0, len(q)))
        return int(np.argmax(q))
    return decide(q, eps, side.held, rng)


def list_league(league_dir):
    try:
        return sorted(f for f in os.listdir(league_dir) if f.endswith(".pbm"))
    except FileNotFoundError:
        return []


def actor_main(aid, shared, version, q, stop, cfg, seed, league_dir):
    torch.set_num_threads(1)
    from env import Match
    from bots import LearnerCfg
    rng = np.random.default_rng(seed)
    E = cfg["envs"]
    N = cfg["actors"] * E
    local_v = -1
    ws = bs = None
    league_cache = {}
    slots = []
    for j in range(E):
        i = aid * E + j
        eps = 0.4 ** (1 + 7.0 * i / max(1, N - 1))
        slots.append({"eps": eps, "m": None, "ns": {}, "opp_w": None})
    out = []
    stats = []
    win_ema = {}
    last_flush = time.time()

    def new_match(slot):
        lc = LearnerCfg()
        lc.band_min = float(rng.uniform(0.78, 0.9))
        lc.band_max = float(min(1.0, lc.band_min + rng.uniform(0.06, 0.18)))
        lc.react_min = 1
        lc.react_max = int(rng.integers(1, 4))
        brain = cfg.get("brain", "v2")
        if rng.random() < cfg["selfplay"]:
            m = Match(rng, opponent="policy", learner_cfg=lc, brain=brain)
            files = list_league(league_dir)
            if files and rng.random() < 0.6:
                fn = files[int(rng.integers(0, len(files)))]
                if fn not in league_cache:
                    try:
                        d = load_pbm(os.path.join(league_dir, fn))
                        league_cache[fn] = (d["ws"], d["bs"])
                    except Exception:
                        league_cache[fn] = None
                slot["opp_w"] = league_cache.get(fn)
            else:
                slot["opp_w"] = None   # latest weights
        else:
            wts = np.asarray([CURRICULUM[k] * (1.3 - win_ema.get(k, 0.5)) for k in PRESETS])
            pr = PRESETS[int(rng.choice(len(PRESETS), p=wts / wts.sum()))]
            m = Match(rng, opponent="scripted", preset=pr, learner_cfg=lc, brain=brain)
            slot["opp_w"] = None
        slot["m"] = m
        slot["ns"] = {"a": NStep(cfg["nstep"], cfg["gamma"]), "b": NStep(cfg["nstep"], cfg["gamma"])}

    for s in slots:
        new_match(s)

    while not stop.is_set():
        if version.value != local_v:
            local_v = version.value
            arrs = [p.detach().numpy().copy() for p in shared]
            ws, bs = arrs[0::2], arrs[1::2]
        # observe
        obs_list = []
        for si, s in enumerate(slots):
            o = s["m"].observe()
            for name, vec in o.items():
                obs_list.append((si, name, vec))
        # batched forward: latest weights for side a (+ latest-opponent b), league weights per slot
        heads = [dict() for _ in slots]
        lat_idx = [k for k, (si, name, _) in enumerate(obs_list) if name == "a" or slots[si]["opp_w"] is None]
        if lat_idx:
            X = np.asarray([obs_list[k][2] for k in lat_idx], np.float32)
            Q = np_forward(ws, bs, X)
            for qi, k in enumerate(lat_idx):
                si, name, _ = obs_list[k]
                side = slots[si]["m"].a if name == "a" else slots[si]["m"].b
                eps = slots[si]["eps"] if name == "a" else 0.02
                heads[si][name] = decide_any(Q[qi], eps, side, rng)
        for k, (si, name, vec) in enumerate(obs_list):
            if name in heads[si]:
                continue
            ow = slots[si]["opp_w"]
            qv = np_forward(ow[0], ow[1], np.asarray(vec, np.float32)[None, :])[0]
            heads[si][name] = decide_any(qv, 0.02, slots[si]["m"].b, rng)
        # act
        for si, s in enumerate(slots):
            m = s["m"]
            trans, rr = m.act(heads[si])
            for (name, st, act, lab, r, s2, done, trunc) in trans:
                if name == "b" and s["opp_w"] is not None:
                    continue   # league snapshots: keep data from current-ish policies only
                s["ns"][name].push(st, act, lab, r, s2, done, trunc, out)
            if rr is not None:
                res, sa, sb = rr
                if m.opponent != "policy":
                    k = m.preset
                    win_ema[k] = win_ema.get(k, 0.5) + 0.05 * ((1.0 if res == "WIN" else 0.0) - win_ema.get(k, 0.5))
                stats.append((m.opponent if m.opponent == "policy" else (m.preset or "scripted"), res,
                              sa["dealt"], sa["taken"], sa["hits"], sa["swings"]))
            if m.result is not None:
                new_match(s)
        if len(out) >= 1024 or (out and time.time() - last_flush > 2.0):
            try:
                q.put(("data", pack(out, cfg.get("dim", 100))), timeout=5)
            except pyqueue.Full:
                pass
            out = []
            if stats:
                q.put(("stats", stats))
                stats = []
            last_flush = time.time()


# =================================================================== evaluator
def evaluate_weights(ws, bs, rounds_per=6, seed0=0, brain="v2"):
    from env import Match
    res = {}
    for kind, preset, seed in BENCH:
        rng = np.random.default_rng(seed + seed0)
        w = l = d = 0
        dealt = taken = 0.0
        r_done = 0
        while r_done < rounds_per:
            m = Match(rng, opponent=kind, preset=preset, rounds=rounds_per - r_done, brain=brain)
            while m.result is None:
                o = m.observe()
                heads = {}
                for name, vec in o.items():
                    qv = np_forward(ws, bs, np.asarray(vec, np.float32)[None, :])[0]
                    heads[name] = decide_any(qv, 0.0, m.a, rng)
                _, rr = m.act(heads)
                if rr is not None:
                    r_done += 1
                    res_, sa, sb = rr
                    w += res_ == "WIN"
                    l += res_ == "LOSS"
                    d += res_ == "DRAW"
                    dealt += sa["dealt"]
                    taken += sa["taken"]
        key = f"{preset or 'scripted'}-{seed}"
        res[key] = {"w": w, "l": l, "d": d, "dealt": round(dealt, 1), "taken": round(taken, 1)}
    wins = sum(v["w"] for v in res.values())
    total = sum(v["w"] + v["l"] + v["d"] for v in res.values())
    margin = sum(v["dealt"] - v["taken"] for v in res.values()) / max(1, total)
    return {"winrate": wins / max(1, total), "margin": margin, "detail": res}


def eval_main(shared, version, q, stop, cfg):
    torch.set_num_threads(1)
    last = -1
    k = 0
    while not stop.is_set():
        v = version.value
        if v == last or v < 1:
            time.sleep(2)
            continue
        arrs = [p.detach().numpy().copy() for p in shared]
        ws, bs = arrs[0::2], arrs[1::2]
        r = evaluate_weights(ws, bs, cfg["eval_rounds"], seed0=0, brain=cfg.get("brain", "v2"))
        r["version"] = v
        q.put(("eval", r, ws, bs))
        last = v
        k += 1
        for _ in range(int(cfg["eval_every"])):
            if stop.is_set():
                break
            time.sleep(1)


# =================================================================== learner
def huber(x, d):
    a = x.abs()
    return torch.where(a <= d, 0.5 * x * x, d * (a - 0.5 * d))


def train_step_v1(model, target, opt, rb, cfg, beta):
    idx, b, w = rb.sample(cfg["batch"], beta)
    s = torch.from_numpy(b["s"])
    s2 = torch.from_numpy(b["s2"])
    a = torch.from_numpy(b["move"].astype(np.int64))
    R = torch.from_numpy(b["R"])
    gN = torch.from_numpy(b["gN"])
    W = torch.from_numpy(w)
    ar = torch.arange(len(R))
    with torch.no_grad():
        a2 = model(s2).argmax(1)
        y = R + gN * target(s2)[ar, a2]
    td = model(s)[ar, a] - y
    loss = (W * huber(td, cfg["huber"])).mean()
    opt.zero_grad(set_to_none=True)
    loss.backward()
    torch.nn.utils.clip_grad_norm_(model.parameters(), cfg["grad_clip"])
    opt.step()
    with torch.no_grad():
        for pt, p in zip(target.parameters(), model.parameters()):
            pt.mul_(1 - cfg["tau"]).add_(p, alpha=cfg["tau"])
    rb.update_priorities(idx, td.detach().numpy())
    return float(loss.item()), 0.0, 0.0, float(td.abs().mean().item())


def train_step(model, target, opt, rb, cfg, beta):
    if cfg.get("brain", "v2") == "v1":
        return train_step_v1(model, target, opt, rb, cfg, beta)
    idx, b, w = rb.sample(cfg["batch"], beta)
    s = torch.from_numpy(b["s"])
    s2 = torch.from_numpy(b["s2"])
    move = torch.from_numpy(b["move"].astype(np.int64))
    flags = [torch.from_numpy(b[k].astype(np.int64)) for k in ("sprint", "jump", "sneak")]
    R = torch.from_numpy(b["R"])
    gN = torch.from_numpy(b["gN"])
    aim = torch.from_numpy(b["aim"])
    click = torch.from_numpy(b["click"])
    W = torch.from_numpy(w)
    ar = torch.arange(len(R))
    with torch.no_grad():
        q2o = model(s2)
        q2t = target(s2)
        a2 = q2o[:, 0:MOVES].argmax(1)
        y_move = R + gN * q2t[ar, a2]
        y_flags = []
        for off in (SPRINT_OFF, JUMP_OFF, SNEAK_OFF):
            b2 = q2o[:, off:off + 2].argmax(1)
            y_flags.append(R + gN * q2t[ar, off + b2])
    q = model(s)
    td = q[ar, move] - y_move
    loss = (W * huber(td, cfg["huber"])).mean()
    for k, off in enumerate((SPRINT_OFF, JUMP_OFF, SNEAK_OFF)):
        tdf = q[ar, off + flags[k]] - y_flags[k]
        loss = loss + 0.5 * (W * huber(tdf, cfg["huber"])).mean()
    aim_l = ((q[:, AIM_OFF:AIM_OFF + 2] - aim.clamp(-0.98, 0.98)) ** 2).mean()
    click_l = ((q[:, CLICK_OFF] - click) ** 2).mean()
    loss = loss + cfg["aim_w"] * aim_l + cfg["click_w"] * click_l
    opt.zero_grad(set_to_none=True)
    loss.backward()
    torch.nn.utils.clip_grad_norm_(model.parameters(), cfg["grad_clip"])
    opt.step()
    with torch.no_grad():
        for pt, p in zip(target.parameters(), model.parameters()):
            pt.mul_(1 - cfg["tau"]).add_(p, alpha=cfg["tau"])
    rb.update_priorities(idx, td.detach().numpy())
    return float(loss.item()), float(aim_l.item()), float(click_l.item()), float(td.abs().mean().item())


def export_v1_json(path, ws, bs, state):
    """Dqn.fromJson format: {"q": {"arch": [...], "layers": [{"w": rows, "b": vec}]}}."""
    sizes = [int(ws[0].shape[1])] + [int(w.shape[0]) for w in ws]
    out = {"schema": 1,
           "meta": {"trainedBy": "PvPBot v2.3 v3-simulator fine-tune (vanilla 1.21 physics, latency, randomized opponents, self-play league, PER double-DQN)",
                    "trainSteps": int(state["steps"]), "trainMinutes": round(state["seconds"] / 60.0, 1)},
           "q": {"arch": sizes, "layers": [{"w": [[float(x) for x in row] for row in w], "b": [float(x) for x in b]}
                                          for w, b in zip(ws, bs)]}}
    with open(path + ".tmp", "w") as fh:
        json.dump(out, fh, separators=(",", ":"))
    os.replace(path + ".tmp", path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default=os.path.join(HERE, "..", "runs", "main"))
    ap.add_argument("--minutes", type=float, default=25.0)
    ap.add_argument("--actors", type=int, default=None)
    ap.add_argument("--lr", type=float, default=None)
    ap.add_argument("--export", action="store_true")
    ap.add_argument("--no-replay-save", action="store_true")
    ap.add_argument("--brain", choices=["v1", "v2"], default=None)
    args = ap.parse_args()
    run = os.path.abspath(args.run)
    os.makedirs(run, exist_ok=True)
    league_dir = os.path.join(run, "league")
    os.makedirs(league_dir, exist_ok=True)
    cfg = default_cfg()
    cfg_path = os.path.join(run, "cfg.json")
    if os.path.exists(cfg_path):
        cfg.update(json.load(open(cfg_path)))
    if args.actors:
        cfg["actors"] = args.actors
    if args.lr:
        cfg["lr"] = args.lr
    if args.brain:
        cfg["brain"] = args.brain
    cfg.setdefault("brain", "v2")
    cfg["dim"] = 64 if cfg["brain"] == "v1" else 100
    json.dump(cfg, open(cfg_path, "w"), indent=1)
    arch = ARCH_V1 if cfg["brain"] == "v1" else ARCH
    torch.set_num_threads(3)
    torch.manual_seed(int(time.time()))

    model = build_torch(arch)
    target = build_torch(arch)
    opt = torch.optim.Adam(model.parameters(), lr=cfg["lr"])
    state = {"steps": 0, "added": 0, "seconds": 0.0, "best": -1e9, "chunks": 0, "rounds": 0, "bench_sig": BENCH_SIG}
    ck = os.path.join(run, "ckpt.pt")
    if os.path.exists(ck):
        c = torch.load(ck, weights_only=False)
        model.load_state_dict(c["model"])
        target.load_state_dict(c["target"])
        opt.load_state_dict(c["opt"])
        for g in opt.param_groups:
            g["lr"] = cfg["lr"]
        state.update(c["state"])
        if state.get("bench_sig") != BENCH_SIG:
            state["best"] = -1e9   # benchmark set changed: scores are not comparable
            state["bench_sig"] = BENCH_SIG
        print(f"[resume] steps {state['steps']} added {state['added']} trained {state['seconds'] / 60:.1f} min", flush=True)
    else:
        if cfg["brain"] == "v1":
            # fine-tune the shipped classic brain instead of starting from scratch
            d = json.load(open(os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "pvpbot", "model", "policy.json")))["q"]
            numpy_to_torch(model, [np.asarray(l["w"], np.float32) for l in d["layers"]],
                           [np.asarray(l["b"], np.float32) for l in d["layers"]])
            print("[init] v1 from bundled policy.json", flush=True)
        target.load_state_dict(model.state_dict())
    rb = Replay(cfg["replay"], seed=int(time.time()), dim=cfg["dim"])
    rp = os.path.join(run, "replay.npz")
    if os.path.exists(rp):
        t0 = time.time()
        rb.load(rp)
        print(f"[resume] replay {rb.size} in {time.time() - t0:.0f}s", flush=True)

    shared_model = build_torch(arch)
    shared_model.load_state_dict(model.state_dict())
    shared_model.share_memory()
    shared = list(shared_model.parameters())
    ctx = mp.get_context("fork")
    version = ctx.Value("i", 1)
    q = ctx.Queue(maxsize=64)
    stop = ctx.Event()
    procs = []
    base_seed = int(time.time()) % 100000
    for aid in range(cfg["actors"]):
        p = ctx.Process(target=actor_main, args=(aid, shared, version, q, stop, cfg, base_seed + 17 * aid, league_dir), daemon=True)
        p.start()
        procs.append(p)
    ev = ctx.Process(target=eval_main, args=(shared, version, q, stop, cfg), daemon=True)
    ev.start()
    procs.append(ev)

    log = open(os.path.join(run, "log.txt"), "a")
    evlog = open(os.path.join(run, "evals.jsonl"), "a")
    t_start = time.time()
    budget = args.minutes * 60.0
    trained_samples_chunk = 0
    added_chunk = 0
    last_log = time.time()
    loss_ema = aim_ema = click_ema = td_ema = None
    roll = []
    try:
        while time.time() - t_start < budget:
            # drain
            drained = 0
            while True:
                try:
                    msg = q.get_nowait()
                except pyqueue.Empty:
                    break
                drained += 1
                if msg[0] == "data":
                    rb.add_batch(msg[1])
                    added_chunk += len(msg[1]["R"])
                    state["added"] += len(msg[1]["R"])
                elif msg[0] == "stats":
                    roll.extend(msg[1])
                    state["rounds"] += len(msg[1])
                    roll = roll[-600:]
                elif msg[0] == "eval":
                    r, ews, ebs = msg[1], msg[2], msg[3]
                    r["steps"] = state["steps"]
                    r["minutes"] = round((state["seconds"] + time.time() - t_start) / 60.0, 1)
                    score = r["winrate"] + 0.02 * r["margin"]
                    r["score"] = round(score, 4)
                    evlog.write(json.dumps(r) + "\n")
                    evlog.flush()
                    summary = " ".join(f"{k.split('-')[0][:5]}:{v['w']}/{v['w'] + v['l'] + v['d']}" for k, v in r["detail"].items())
                    print(f"[eval] steps {state['steps']} winrate {r['winrate']:.2f} margin {r['margin']:+.2f} | {summary}", flush=True)
                    if score > state["best"]:
                        state["best"] = score
                        save_pbm(os.path.join(run, "best.pbm"), ews, ebs, "pvpbot-v2.3-best", state["steps"])
                        if cfg["brain"] == "v1":
                            export_v1_json(os.path.join(run, "best_v1.json"), ews, ebs, state)
                        print(f"[eval] NEW BEST score {score:.3f}", flush=True)
                if drained > 200:
                    break
            if rb.size < cfg["warmup"]:
                time.sleep(0.05)
                continue
            if added_chunk > 0 and trained_samples_chunk / max(1, added_chunk) > cfg["max_ratio"]:
                time.sleep(0.01)
                continue
            beta = min(1.0, 0.4 + 0.6 * state["steps"] / 300000.0)
            loss, al, cl, tdm = train_step(model, target, opt, rb, cfg, beta)
            state["steps"] += 1
            trained_samples_chunk += cfg["batch"]
            a = 0.02
            loss_ema = loss if loss_ema is None else loss_ema + a * (loss - loss_ema)
            aim_ema = al if aim_ema is None else aim_ema + a * (al - aim_ema)
            click_ema = cl if click_ema is None else click_ema + a * (cl - click_ema)
            td_ema = tdm if td_ema is None else td_ema + a * (tdm - td_ema)
            if state["steps"] % cfg["publish_every"] == 0:
                with torch.no_grad():
                    for ps, pm in zip(shared_model.parameters(), model.parameters()):
                        ps.copy_(pm)
                version.value += 1
            if state["steps"] % cfg["league_every"] == 0:
                ws, bs = torch_to_numpy(model)
                save_pbm(os.path.join(league_dir, f"snap-{state['steps']:08d}.pbm"), ws, bs, "league", state["steps"])
                files = list_league(league_dir)
                for f in files[:-cfg["league_max"]]:
                    os.remove(os.path.join(league_dir, f))
            if time.time() - last_log > 30:
                last_log = time.time()
                el = state["seconds"] + time.time() - t_start
                by = {}
                for (opp, res, dealt, taken, hits, swings) in roll:
                    e = by.setdefault(opp, [0, 0, 0.0])
                    e[0] += 1
                    e[1] += res == "WIN"
                    e[2] += dealt - taken
                rs = " ".join(f"{k[:6]}:{v[1] / v[0]:.2f}({v[2] / v[0]:+.1f})" for k, v in sorted(by.items()))
                line = (f"t {el / 60:6.1f}m steps {state['steps']:7d} buf {rb.size:7d} added {state['added']:9d} "
                        f"loss {loss_ema:.4f} td {td_ema:.3f} aim {aim_ema:.4f} click {click_ema:.4f} | {rs}")
                print(line, flush=True)
                log.write(line + "\n")
                log.flush()
    finally:
        stop.set()
        state["seconds"] += time.time() - t_start
        state["chunks"] += 1
        torch.save({"model": model.state_dict(), "target": target.state_dict(), "opt": opt.state_dict(),
                    "state": state}, ck + ".tmp")
        os.replace(ck + ".tmp", ck)
        ws, bs = torch_to_numpy(model)
        save_pbm(os.path.join(run, "latest.pbm"), ws, bs, "pvpbot-v2.3-latest", state["steps"])
        if cfg["brain"] == "v1":
            export_v1_json(os.path.join(run, "policy_v1.json"), ws, bs, state)
        if not args.no_replay_save:
            t0 = time.time()
            rb.save(rp + ".tmp.npz")
            os.replace(rp + ".tmp.npz", rp)
            print(f"[save] replay {rb.size} in {time.time() - t0:.0f}s", flush=True)
        for p in procs:
            p.join(timeout=5)
            if p.is_alive():
                p.terminate()
        print(f"[done] steps {state['steps']} total {state['seconds'] / 60:.1f} min, rounds {state['rounds']}", flush=True)


if __name__ == "__main__":
    main()
