package dev.z.pvpbot.ml;

import com.google.gson.JsonObject;

import java.util.Random;

/**
 * Double Deep-Q-Network with an experience replay buffer, a target network
 * AND a dedicated expert buffer (DQfD-style imitation learning).
 *
 * Imitation: every (state, action) pair recorded while the USER fights
 * (human-train) — and the rich 64-dim observation that goes with it — is kept
 * in a separate expert buffer that NEVER ages out. Every training step mixes
 * a slice of expert transitions into the batch and applies a large-margin
 * cloning loss on the demonstrated action, so the policy keeps imitating
 * good human play even while it continues RL self-play. Demonstrations are
 * backed by their TD target too, exactly like DQfD.
 */
public final class Dqn {

        private final NeuralNet q;
        private final NeuralNet target;
        private final Random rng;
        private final int outDim;
        private final float gamma;
        private final int[] sizes;

        // replay storage
        private final float[][] sBuf;
        private final float[][] s2Buf;
        private final int[] aBuf;
        private final float[] rBuf;
        private final boolean[] doneBuf;
        private int head = 0, size = 0;

        // ---- v2.0 PHASE 3-a: N-STEP RETURN COMPRESSION -------------------------
        // Transitions are stored as (s_t, a_t, R_t:t+n, s_t+n, done) with
        // R = r_t + γr_t+1 + … + γ^{n-1} r_t+n-1. N-step returns propagate
        // reward much faster through the replay than 1-step TD (a win signal
        // reaches the whole winning sequence in one update) while the Double
        // DQN target keeps the bias bounded. Episode ends flush the pending
        // ring with truncated returns (correct, shorter horizons).
        public int nStep = 3;
        private final float[][] pS, pS2;
        private final int[] pA;
        private final float[] pR;
        private int pCount = 0;

        // ---- v2.0 PHASE 3-a: PRIORITIZED REPLAY (proportional, sum-tree) ------
        // Each transition carries a priority p = |TD error| + ε, sampled
        // proportionally through a bottom-up segment tree (O(log N) sample +
        // update). New transitions enter at max priority (classic PER). The
        // per-sample importance weights are approximated by capping every
        // transition at 2 draws per batch — with the v1.0.11 Huber gradient
        // clamp the sampling bias stays bounded and no NeuralNet surgery is
        // needed for per-sample loss weights.
        private final float[] tree;      // size 2*cap, leaves at [cap, 2*cap)
        private float pMax = 1f;

        // expert (imitation) storage — ring buffer, never decayed
        private final float[][] eS, eS2;
        private final int[] eA;
        private final float[] eR;
        private final boolean[] eDone;
        private int eHead = 0, eSize = 0;
        private float margin = 0.8f;

        private long trainSteps = 0;

        public Dqn(int[] sizes, int capacity, float gamma, long seed) {
                this(sizes, capacity, gamma, seed, 0);
        }

        public Dqn(int[] sizes, int capacity, float gamma, long seed, int expertCapacity) {
                this.sizes = sizes.clone();
                this.q = new NeuralNet(sizes, seed);
                this.target = new NeuralNet(sizes, seed + 7919);
                this.rng = new Random(seed + 104729);
                this.outDim = sizes[sizes.length - 1];
                this.gamma = gamma;
                this.sBuf = new float[capacity][];
                this.s2Buf = new float[capacity][];
                this.aBuf = new int[capacity];
                this.rBuf = new float[capacity];
                this.doneBuf = new boolean[capacity];
                int ec = Math.max(expertCapacity, 1);
                this.eS = new float[ec][];
                this.eS2 = new float[ec][];
                this.eA = new int[ec];
                this.eR = new float[ec];
                this.eDone = new boolean[ec];
                // PHASE 3-a: n-step pending ring + priority tree
                int n = Math.max(1, nStep);
                this.pS = new float[n][];
                this.pS2 = new float[n][];
                this.pA = new int[n];
                this.pR = new float[n];
                this.tree = new float[2 * Math.max(capacity, 8)];
                syncTarget();
        }

        // -------------------------------------------------- n-step + priority

        private void storeNStep(float[] s, int a, float r, float[] s2, boolean done) {
                int idx = head;
                sBuf[idx] = s;
                aBuf[idx] = a;
                rBuf[idx] = r;
                s2Buf[idx] = s2;
                doneBuf[idx] = done;
                head = (head + 1) % sBuf.length;
                if (size < sBuf.length) {
                        size++;
                }
                treeUpdate(idx, pMax); // classic PER: new = max priority
        }

        private void treeUpdate(int idx, float p) {
                int cap = sBuf.length;
                int i = idx + cap;
                tree[i] = Math.max(p, 1e-3f);
                i >>= 1;
                while (i >= 1) {
                        float nv = tree[2 * i] + tree[2 * i + 1];
                        if (tree[i] == nv) {
                                break;
                        }
                        tree[i] = nv;
                        i >>= 1;
                }
                pMax = Math.max(pMax, tree[idx + cap]);
        }

        /** Sample a slot proportionally to priority; empty slots never match. */
        private int treeSample() {
                float target = rng.nextFloat() * tree[1];
                int cap = sBuf.length;
                int i = 1;
                while (i < cap) {
                        if (target < tree[2 * i]) {
                                i = 2 * i;
                        } else {
                                target -= tree[2 * i];
                                i = 2 * i + 1;
                        }
                }
                return Math.min(i - cap, cap - 1);
        }

        public void setImitationMargin(float m) {
                margin = m;
        }

        public void remember(float[] s, int a, float r, float[] s2, boolean done) {
                // PHASE 3-a: compress into n-step returns via the pending ring
                pS[pCount] = s;
                pA[pCount] = a;
                pR[pCount] = r;
                pS2[pCount] = s2;
                pCount++;
                if (done) {
                        // episode end: flush ALL pending with truncated returns
                        for (int i = 0; i < pCount; i++) {
                                float R = 0f, g = 1f;
                                for (int j = i; j < pCount; j++) {
                                        R += g * pR[j];
                                        g *= gamma;
                                }
                                storeNStep(pS[i], pA[i], R, pS2[pCount - 1], true);
                        }
                        pCount = 0;
                } else if (pCount >= nStep) {
                        float R = 0f, g = 1f;
                        for (int j = 0; j < nStep; j++) {
                                R += g * pR[j];
                                g *= gamma;
                        }
                        storeNStep(pS[0], pA[0], R, pS2[nStep - 1], false);
                        for (int i = 1; i < pCount; i++) {
                                pS[i - 1] = pS[i];
                                pA[i - 1] = pA[i];
                                pR[i - 1] = pR[i];
                                pS2[i - 1] = pS2[i];
                        }
                        pCount--;
                }
        }

        /** Store a demonstrated (expert) transition for imitation learning. */
        public void rememberExpert(float[] s, int a, float r, float[] s2, boolean done) {
                int idx = eHead;
                eS[idx] = s;
                eA[idx] = a;
                eR[idx] = r;
                eS2[idx] = s2;
                eDone[idx] = done;
                eHead = (eHead + 1) % eS.length;
                if (eSize < eS.length) {
                        eSize++;
                }
        }

        public int expertSize() {
                return eSize;
        }

        /** v2.2.0: wipe the expert ring (IL reload boundary) — weights untouched. */
        public void clearExpert() {
                eHead = 0;
                eSize = 0;
                java.util.Arrays.fill(eS, null);
                java.util.Arrays.fill(eS2, null);
        }

        /**
         * One gradient step. Up to {@code expertFrac} of the batch is drawn
         * from the expert buffer with margin-cloned targets (DQfD), the rest
         * is ordinary TD on the self-play replay. Returns loss (NaN if not enough data).
         */
        public float trainStep(int batch, float lr, float expertFrac) {
                if (size < Math.max(batch, 512)) {
                        return Float.NaN;
                }
                int nExpert = eSize >= 64 && expertFrac > 0f
                                ? Math.min(batch - 1, Math.max(1, Math.round(batch * expertFrac)))
                                : 0;
                int nTd = batch - nExpert;
                float[][] xs = new float[batch][];
                float[][] ys = new float[batch][];
                int[] actIdx = new int[batch];
                int[] slots = new int[batch];
                boolean[] isExpert = new boolean[batch];
                int k = 0;
                int drawnN = 0;
                for (int i = 0; i < nTd; i++, k++) {
                        // PHASE 3-a: priority-proportional sampling, max 2 draws/slot
                        int idx = 0;
                        for (int t = 0; t < 5; t++) {
                                idx = treeSample();
                                int times = 0;
                                for (int d = 0; d < drawnN; d++) {
                                        if (slots[d] == idx) times++;
                                }
                                if (times < 2) break;
                        }
                        slots[drawnN++] = idx;
                        xs[k] = sBuf[idx];
                        actIdx[k] = aBuf[idx];
                        isExpert[k] = false;
                        float[] err = new float[1];
                        ys[k] = tdTarget(sBuf[idx], aBuf[idx], rBuf[idx], s2Buf[idx], doneBuf[idx], 0f, err, gammaN());
                        treeUpdate(idx, err[0] + 1e-3f);
                }
                for (int i = 0; i < nExpert; i++, k++) {
                        int idx = rng.nextInt(eSize);
                        xs[k] = eS[idx];
                        actIdx[k] = eA[idx];
                        isExpert[k] = true;
                        float[] err = new float[1];
                        ys[k] = tdTarget(eS[idx], eA[idx], eR[idx], eS2[idx], eDone[idx], margin, err, gamma);
                }
                trainSteps++;
                return q.trainBatch(xs, ys, lr);
        }

        /** Legacy single-mode step (plain TD). */
        public float trainStep(int batch, float lr) {
                return trainStep(batch, lr, 0f);
        }

        /**
         * v2.3 N-STEP DISCOUNT FIX — a non-terminal replay sample holds an
         * n-step return R_t:t+n and the state n steps later, so it must
         * bootstrap with gamma^n. The old code used gamma for every sample,
         * i.e. it valued the future as if it were 1 step away (3-step
         * returns overestimated the tail by ~1%/step).
         */
        private float gammaN() {
                return (float) Math.pow(gamma, Math.max(1, nStep));
        }

        private float[] tdTarget(float[] s, int a, float r, float[] s2, boolean done, float expertMargin,
                                 float[] errOut, float discount) {
                float[] qsa = q.forward(s, null);
                float[] y = qsa.clone();
                if (done) {
                        y[a] = r;
                } else {
                        // double DQN: online net picks action, target net evaluates it
                        float[] q2on = q.forward(s2, null);
                        int bestA = argmax(q2on);
                        float[] q2t = target.forward(s2, null);
                        float tv = q2t[bestA];
                        y[a] = r + discount * tv;
                }
                if (expertMargin > 0f) {
                        // large-margin cloning: the demonstrated action must stay
                        // at least `expertMargin` above every other action's Q
                        float bestOther = Float.NEGATIVE_INFINITY;
                        for (int i = 0; i < y.length; i++) {
                                if (i != a && qsa[i] > bestOther) bestOther = qsa[i];
                        }
                        if (bestOther != Float.NEGATIVE_INFINITY) {
                                y[a] = Math.max(y[a], bestOther + expertMargin);
                        }
                }
                errOut[0] = Math.abs(y[a] - qsa[a]); // PHASE 3-a: priority signal
                return y;
        }

        public int act(float[] s, float epsilon) {
                if (rng.nextFloat() < epsilon) {
                        return rng.nextInt(outDim);
                }
                float[] qs = q.forward(s, null);
                return argmax(qs);
        }

        /**
         * v1.0.6: pick from PRE-COMPUTED Q-values (the state was already run
         * through the net this tick for the DecisionMind's q-agreement vote —
         * avoids a second 480x480 forward pass per decision).
         */
        public int actFromQ(float[] qs, float epsilon) {
                if (rng.nextFloat() < epsilon) {
                        return rng.nextInt(outDim);
                }
                return argmax(qs);
        }

        public float[] qValues(float[] s) {
                return q.forward(s, null);
        }

        public void syncTarget() {
                target.copyFrom(q);
        }

        public long getTrainSteps() {
                return trainSteps;
        }

        public int bufferSize() {
                return size;
        }

        public int bufferCapacity() {
                return sBuf.length;
        }

        private int argmax(float[] v) {
                int best = 0;
                float bv = v[0];
                for (int i = 1; i < v.length; i++) {
                        if (v[i] > bv) {
                                bv = v[i];
                                best = i;
                        }
                }
                return best;
        }

        public JsonObject toJson() {
                JsonObject root = new JsonObject();
                root.add("q", q.toJson());
                return root;
        }

        // ------------------------------------------------------------- v2.0

        /**
         * v2.0 PHASE 2-a HOT-SWAP — replace the online + target weights with
         * {@code src} while KEEPING the replay buffer and expert demos.
         * All references to this Dqn (controller, trainer, HUD) stay valid:
         * only the weights change, mid-episode or not. A torn float during the
         * copy is the same benign race the trainer already tolerates.
         */
        public void loadWeights(NeuralNet src) {
                q.copyFrom(src);
                syncTarget();
        }

        /** Binary snapshot of the Q network (the "policy brain"). */
        public void saveBinary(java.io.DataOutputStream out) throws java.io.IOException {
                q.saveBinary(out);
        }

        public int[] qSizes() {
                return q.sizes.clone();
        }

        public String qArchSummary() {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < q.sizes.length; i++) {
                        if (i > 0) sb.append('x');
                        sb.append(q.sizes[i]);
                }
                return sb.toString();
        }

        public static Dqn fromJson(JsonObject root, int capacity, float gamma, long seed, int expertCapacity,
                                   int[] expectedSizes) {
                NeuralNet net = NeuralNet.fromJson(root.getAsJsonObject("q"));
                if (expectedSizes != null && !java.util.Arrays.equals(net.sizes, expectedSizes)) {
                        // old-architecture brain — force fallback to the bundled model
                        throw new IllegalArgumentException("arch mismatch: saved "
                                        + java.util.Arrays.toString(net.sizes) + " != expected "
                                        + java.util.Arrays.toString(expectedSizes));
                }
                Dqn d = new Dqn(net.sizes, capacity, gamma, seed, expertCapacity);
                d.q.copyFrom(net);
                d.syncTarget();
                return d;
        }
}
