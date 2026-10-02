"""Numpy double-DQN — same architecture/layout as the Java NeuralNet."""
import numpy as np


class Net:
    def __init__(self, sizes, rng):
        self.sizes = list(sizes)
        self.L = len(sizes) - 1
        self.w = []
        self.b = []
        for l in range(self.L):
            fan_in = sizes[l]
            std = np.sqrt(2.0 / max(1, fan_in))
            self.w.append(rng.normal(0, std, size=(sizes[l + 1], sizes[l])).astype(np.float32))
            self.b.append(np.zeros(sizes[l + 1], dtype=np.float32))
        # Adam
        self.mw = [np.zeros_like(w) for w in self.w]
        self.vw = [np.zeros_like(w) for w in self.w]
        self.mb = [np.zeros_like(b) for b in self.b]
        self.vb = [np.zeros_like(b) for b in self.b]
        self.t = 0

    def forward(self, x):
        a = x
        acts = [a]
        for l in range(self.L):
            z = a @ self.w[l].T + self.b[l]
            if l < self.L - 1:
                z = np.maximum(0.0, z)
            a = z
            acts.append(a)
        return a, acts

    def train_batch(self, xs, ys, lr):
        n = len(xs)
        x = np.asarray(xs, dtype=np.float32)
        y = np.asarray(ys, dtype=np.float32)
        pred, acts = self.forward(x)
        err = pred - y
        loss = float(np.mean(err ** 2))
        # backward
        grads_w = [np.zeros_like(w) for w in self.w]
        grads_b = [np.zeros_like(b) for b in self.b]
        delta = err
        for l in range(self.L - 1, -1, -1):
            grads_w[l] = delta.T @ acts[l] / n
            grads_b[l] = delta.mean(axis=0)
            if l > 0:
                delta = (delta @ self.w[l]) * (acts[l] > 0)
        # Adam
        self.t += 1
        b1, b2, eps = 0.9, 0.999, 1e-8
        c1 = 1.0 - b1 ** self.t
        c2 = 1.0 - b2 ** self.t
        for l in range(self.L):
            self.mw[l] = b1 * self.mw[l] + (1 - b1) * grads_w[l]
            self.vw[l] = b2 * self.vw[l] + (1 - b2) * grads_w[l] ** 2
            self.w[l] -= lr * (self.mw[l] / c1) / (np.sqrt(self.vw[l] / c2) + eps)
            self.mb[l] = b1 * self.mb[l] + (1 - b1) * grads_b[l]
            self.vb[l] = b2 * self.vb[l] + (1 - b2) * grads_b[l] ** 2
            self.b[l] -= lr * (self.mb[l] / c1) / (np.sqrt(self.vb[l] / c2) + eps)
        return loss

    def copy_to(self, other):
        for l in range(self.L):
            other.w[l] = self.w[l].copy()
            other.b[l] = self.b[l].copy()

    def state_dict(self):
        return {"arch": self.sizes,
                "layers": [{"w": self.w[l].tolist(), "b": self.b[l].tolist()} for l in range(self.L)]}

    def load_state(self, sd):
        assert sd["arch"] == self.sizes, f"arch mismatch {sd['arch']} vs {self.sizes}"
        for l in range(self.L):
            self.w[l] = np.asarray(sd["layers"][l]["w"], dtype=np.float32)
            self.b[l] = np.asarray(sd["layers"][l]["b"], dtype=np.float32)


class Replay:
    def __init__(self, capacity, sdim, rng):
        self.cap = capacity
        self.s = np.zeros((capacity, sdim), dtype=np.float32)
        self.s2 = np.zeros((capacity, sdim), dtype=np.float32)
        self.a = np.zeros(capacity, dtype=np.int64)
        self.r = np.zeros(capacity, dtype=np.float32)
        self.d = np.zeros(capacity, dtype=np.float32)
        self.head = 0
        self.size = 0
        self.rng = rng

    def add(self, s, a, r, s2, d):
        i = self.head
        self.s[i] = s
        self.a[i] = a
        self.r[i] = r
        self.s2[i] = s2
        self.d[i] = d
        self.head = (self.head + 1) % self.cap
        self.size = min(self.size + 1, self.cap)

    def sample(self, n):
        idx = self.rng.integers(0, self.size, size=n)
        return self.s[idx], self.a[idx], self.r[idx], self.s2[idx], self.d[idx]


class DQN:
    def __init__(self, sizes, capacity=200000, gamma=0.995, seed=0):
        self.rng = np.random.default_rng(seed)
        self.q = Net(sizes, self.rng)
        self.target = Net(sizes, self.rng)
        self.q.copy_to(self.target)
        self.gamma = gamma
        self.buf = Replay(capacity, sizes[0], self.rng)
        self.n_out = sizes[-1]
        self.steps = 0

    def act(self, s, eps):
        if self.rng.random() < eps:
            return int(self.rng.integers(0, self.n_out))
        qv, _ = self.q.forward(s[None, :])
        return int(np.argmax(qv[0]))

    def remember(self, s, a, r, s2, done):
        self.buf.add(s, a, r, s2, done)

    def train(self, batch=32, lr=1e-3):
        if self.buf.size < max(1000, batch):
            return None
        s, a, r, s2, d = self.buf.sample(batch)
        q2on, _ = self.q.forward(s2)
        best_a = np.argmax(q2on, axis=1)
        q2t, _ = self.target.forward(s2)
        tv = q2t[np.arange(batch), best_a]
        y_target = r + self.gamma * (1.0 - d) * tv
        qs, _ = self.q.forward(s)
        y = qs.copy()
        y[np.arange(batch), a] = y_target
        loss = self.q.train_batch(s, y, lr)
        self.steps += 1
        return loss

    def sync(self):
        self.q.copy_to(self.target)
