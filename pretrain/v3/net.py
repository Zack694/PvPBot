"""Four-head brain: torch model, numpy inference, PolicyNet.act mirror, .pbm IO.

Output layout (identical to dev.z.pvpbot.ml.PolicyNet):
  MOVE [0..8] Q   SPRINT [9..10] Q   JUMP [11..12] Q   SNEAK [13..14] Q
  AIM [15..16] regression in [-1,1]   CLICK [17] regression in [0,1]
"""
import struct
import time

import numpy as np

ARCH = [100, 512, 512, 256, 18]
MOVES = 9
SPRINT_OFF, JUMP_OFF, SNEAK_OFF, AIM_OFF, CLICK_OFF = 9, 11, 13, 15, 17
FLAG_MARGIN = 0.05


def build_torch(arch=ARCH):
    import torch.nn as nn
    layers = []
    for i in range(len(arch) - 1):
        lin = nn.Linear(arch[i], arch[i + 1])
        nn.init.kaiming_normal_(lin.weight, nonlinearity="relu")
        nn.init.zeros_(lin.bias)
        layers.append(lin)
        if i < len(arch) - 2:
            layers.append(nn.ReLU())
    return nn.Sequential(*layers)


def torch_to_numpy(model):
    ws, bs = [], []
    for m in model:
        if hasattr(m, "weight"):
            ws.append(m.weight.detach().cpu().numpy().astype(np.float32).copy())
            bs.append(m.bias.detach().cpu().numpy().astype(np.float32).copy())
    return ws, bs


def numpy_to_torch(model, ws, bs):
    import torch
    i = 0
    with torch.no_grad():
        for m in model:
            if hasattr(m, "weight"):
                m.weight.copy_(torch.from_numpy(ws[i]))
                m.bias.copy_(torch.from_numpy(bs[i]))
                i += 1


def np_forward(ws, bs, x):
    a = x
    n = len(ws)
    for i in range(n):
        a = a @ ws[i].T + bs[i]
        if i < n - 1:
            np.maximum(a, 0.0, out=a)
    return a


def decide(o, eps, held, rng, aim_max=40.0):
    """Mirror of PolicyNet.act (incl. v2.0.1 sticky flags). held = [sprint, jump, sneak] (mutated)."""
    if rng.random() < eps:
        move = int(rng.integers(0, MOVES))
    else:
        move = int(np.argmax(o[0:MOVES]))
    flags = []
    for k, off in enumerate((SPRINT_OFF, JUMP_OFF, SNEAK_OFF)):
        e = eps * 0.5
        if rng.random() < e:
            raw = bool(rng.random() < 0.5)
        else:
            raw = bool(o[off + 1] > o[off])
        h = held[k]
        if raw != h:
            on, off_v = o[off + 1], o[off]
            if raw and on - off_v < FLAG_MARGIN:
                raw = h
            elif (not raw) and off_v - on < FLAG_MARGIN:
                raw = h
        held[k] = raw
        flags.append(raw)
    ny = _c(float(o[AIM_OFF]), -1.0, 1.0)
    npi = _c(float(o[AIM_OFF + 1]), -1.0, 1.0)
    if eps > 0:
        ny += float(rng.normal()) * eps * 0.03
        npi += float(rng.normal()) * eps * 0.02
    return {
        "move": move, "sprint": flags[0], "jump": flags[1], "sneak": flags[2],
        "sneak_margin": float(o[SNEAK_OFF + 1] - o[SNEAK_OFF]),
        "aim_y": _c(ny, -1.0, 1.0) * aim_max, "aim_p": _c(npi, -1.0, 1.0) * aim_max,
        "click": _c(float(o[CLICK_OFF]), 0.0, 1.0),
    }


def _c(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


# ------------------------------------------------------------------ .pbm IO
MAGIC = b"PVPBMDL"
KIND_V2POLICY = 3


def _write_utf(fh, s):
    b = s.encode("utf-8")
    fh.write(struct.pack(">H", len(b)))
    fh.write(b)


def save_pbm(path, ws, bs, name, train_steps, episodes=0, wins=0, losses=0, draws=0):
    sizes = [ws[0].shape[1]] + [w.shape[0] for w in ws]
    with open(path, "wb") as fh:
        fh.write(MAGIC)
        fh.write(struct.pack(">b", 2))
        fh.write(struct.pack(">b", KIND_V2POLICY))
        _write_utf(fh, name)
        fh.write(struct.pack(">q", int(time.time() * 1000)))
        fh.write(struct.pack(">iiii", episodes, wins, losses, draws))
        fh.write(struct.pack(">q", int(train_steps)))
        # PVB2 net blob
        fh.write(b"PVB2")
        fh.write(struct.pack(">h", 1))
        fh.write(struct.pack(">i", len(sizes)))
        for s in sizes:
            fh.write(struct.pack(">i", s))
        for w, b in zip(ws, bs):
            fh.write(np.ascontiguousarray(w, dtype="<f4").tobytes())
            fh.write(np.ascontiguousarray(b, dtype="<f4").tobytes())


def load_pbm(path):
    with open(path, "rb") as fh:
        data = fh.read()
    p = 0
    assert data[:7] == MAGIC, "bad magic"
    p = 7
    ver, kind = struct.unpack(">bb", data[p:p + 2]); p += 2
    (ln,) = struct.unpack(">H", data[p:p + 2]); p += 2
    name = data[p:p + ln].decode("utf-8"); p += ln
    created, = struct.unpack(">q", data[p:p + 8]); p += 8
    ep, w_, l_, d_ = struct.unpack(">iiii", data[p:p + 16]); p += 16
    steps, = struct.unpack(">q", data[p:p + 8]); p += 8
    assert data[p:p + 4] == b"PVB2"; p += 4
    p += 2
    (n,) = struct.unpack(">i", data[p:p + 4]); p += 4
    sizes = list(struct.unpack(">" + "i" * n, data[p:p + 4 * n])); p += 4 * n
    ws, bs = [], []
    for i in range(n - 1):
        cnt = sizes[i + 1] * sizes[i]
        w = np.frombuffer(data, dtype="<f4", count=cnt, offset=p).reshape(sizes[i + 1], sizes[i]).astype(np.float32)
        p += cnt * 4
        b = np.frombuffer(data, dtype="<f4", count=sizes[i + 1], offset=p).astype(np.float32)
        p += sizes[i + 1] * 4
        ws.append(w)
        bs.append(b)
    return {"name": name, "steps": steps, "sizes": sizes, "ws": ws, "bs": bs, "kind": kind}
