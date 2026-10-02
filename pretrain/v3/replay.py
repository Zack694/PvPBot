"""Prioritized replay (proportional, sum-tree) with vectorized sampling."""
import numpy as np


class SumTree:
    def __init__(self, cap):
        self.cap = 1
        while self.cap < cap:
            self.cap *= 2
        self.tree = np.zeros(2 * self.cap, dtype=np.float64)

    def update(self, idx, pri):
        idx = np.asarray(idx, dtype=np.int64) + self.cap
        self.tree[idx] = pri
        idx = np.unique(idx // 2)
        while True:
            self.tree[idx] = self.tree[2 * idx] + self.tree[2 * idx + 1]
            if idx.size == 1 and idx[0] == 1:
                break
            idx = np.unique(idx // 2)

    def total(self):
        return self.tree[1]

    def sample(self, vals):
        i = np.ones(len(vals), dtype=np.int64)
        v = vals.copy()
        while i[0] < self.cap:
            left = 2 * i
            lv = self.tree[left]
            go_right = v >= lv
            v = np.where(go_right, v - lv, v)
            i = np.where(go_right, left + 1, left)
        return i - self.cap

    def get(self, idx):
        return self.tree[np.asarray(idx) + self.cap]


class Replay:
    FIELDS = (("s", (100,), np.float32), ("s2", (100,), np.float32), ("move", (), np.int8),
              ("sprint", (), np.int8), ("jump", (), np.int8), ("sneak", (), np.int8),
              ("aim", (2,), np.float32), ("click", (), np.float32), ("R", (), np.float32),
              ("gN", (), np.float32))

    def __init__(self, cap, alpha=0.6, seed=0):
        self.cap = cap
        self.alpha = alpha
        self.data = {k: np.zeros((cap,) + shp, dtype=dt) for k, shp, dt in self.FIELDS}
        self.tree = SumTree(cap)
        self.head = 0
        self.size = 0
        self.max_p = 1.0
        self.rng = np.random.default_rng(seed)
        self.added = 0

    def add_batch(self, batch):
        n = len(batch["R"])
        idx = (self.head + np.arange(n)) % self.cap
        for k in self.data:
            self.data[k][idx] = batch[k]
        self.tree.update(idx, np.full(n, self.max_p ** self.alpha))
        self.head = int((self.head + n) % self.cap)
        self.size = int(min(self.cap, self.size + n))
        self.added += n

    def sample(self, n, beta):
        tot = self.tree.total()
        seg = tot / n
        vals = (np.arange(n) + self.rng.random(n)) * seg
        idx = self.tree.sample(vals)
        idx = np.clip(idx, 0, self.size - 1)
        p = self.tree.get(idx) / max(tot, 1e-12)
        w = (self.size * np.maximum(p, 1e-12)) ** (-beta)
        w = w / w.max()
        out = {k: v[idx] for k, v in self.data.items()}
        return idx, out, w.astype(np.float32)

    def update_priorities(self, idx, err):
        p = (np.abs(err) + 1e-3)
        self.max_p = max(self.max_p, float(p.max()))
        self.tree.update(idx, p ** self.alpha)

    # ---- persistence (resume across sandbox chunks)
    def save(self, path):
        np.savez(path, head=self.head, size=self.size, max_p=self.max_p, added=self.added,
                 pri=self.tree.tree[self.tree.cap:self.tree.cap + self.cap], **self.data)

    def load(self, path):
        z = np.load(path)
        n = min(int(z["size"]), self.cap)
        for k in self.data:
            self.data[k][:n] = z[k][:n]
        self.size = n
        self.head = int(z["head"]) % self.cap
        self.max_p = float(z["max_p"])
        self.added = int(z["added"])
        pri = z["pri"][:n]
        self.tree.update(np.arange(n), pri)
