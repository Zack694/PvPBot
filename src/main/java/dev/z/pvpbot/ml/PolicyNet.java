package dev.z.pvpbot.ml;

import dev.z.pvpbot.bot.ActionSpace;

import java.util.Random;

/**
 * v2.0 PHASE 2-b — FOUR-HEAD POLICY NETWORK (the "v2 brain").
 *
 * One fused trunk 104 -> 512 -> 512 -> 512 -> 448 -> 18 whose 18 outputs are
 * the four policy heads from the plan:
 *
 *   MOVE   [0..8]   9 Q-values — one per move combo (ActionSpace M_*)
 *   SPRINT [9..10]  2 Q-values (off / on)
 *   JUMP   [11..12] 2 Q-values (off / on)
 *   SNEAK  [13..14] 2 Q-values (off / on)   — NEW action dimension in v2
 *   AIM    [15..16] 2 continuous values — normalized yaw/pitch delta in [-1,1]
 *   CLICK  [17]     1 continuous value — click desire in [0,1]
 *
 * The trunk is SHARED: every head's loss backprops into the same
 * representation (the fused 448-bottleneck is exactly the sum of the four
 * per-head hidden layers of the plan). Untouched outputs are masked with
 * identity targets (y = yhat -> zero gradient) — the same masking pattern
 * the legacy Dqn uses, so the proven NeuralNet (Adam + Huber + grad clip)
 * trains all four heads at once.
 *
 * Learning signals:
 *   - MOVE / SPRINT / JUMP / SNEAK: Double-DQN TD on self-play replay
 *     (+ large-margin cloning on expert demonstrations, DQfD).
 *   - AIM: supervised regression toward recorded human camera deltas
 *     (human-train) or the proven tracker output (self-play distillation).
 *   - CLICK: supervised regression toward "should have clicked" labels —
 *     1.0 on ticks a click was due (band open + on target), 0.0 when the
 *     band was closed. In pure mode the click head — NOT the TriggerBot —
 *     is the intent authority, still behind every physics gate (hard band
 *     gate, click governor, on-target check).
 */
public final class PolicyNet {

        /** v2.3: OBSERVATION v4 (100 dims, Minecraft-free, simulator-parity tested). */
        public static final int IN_DIM = dev.z.pvpbot.ml.obs.ObsV4.DIM;
        /** v2.3: 100 -> 512 -> 512 -> 256 -> 18 (449k params) — offline-pretrained in the v3 simulator. */
        public static final int[] ARCH = {IN_DIM, 512, 512, 256, 18};

        public static final int MOVES = ActionSpace.MOVES;                // 9
        public static final int MOVE_OFF = 0;
        public static final int SPRINT_OFF = 9, JUMP_OFF = 11, SNEAK_OFF = 13;
        public static final int AIM_OFF = 15, CLICK_OFF = 17;

        /** Aim-head output scale: normalized [-1,1] * this = degrees per tick. */
        public float aimMaxDeg = 40f;

        // v2.0.1 FLAG HYSTERESIS — an immature head compares two Q-values that
        // differ by ~1e-4, so the raw greedy pick flickered every tick (sprint
        // stutter, crouch spam). A flag must now BEAT the held state by this
        // margin before the muscles switch.
        public float flagMargin = 0.05f;
        private boolean hSprint, hJump, hSneak;

        private final NeuralNet q;
        private final NeuralNet target;
        private final Random rng;
        private final float gamma;

        // replay storage (self-play)
        private final float[][] sBuf, s2Buf;
        private final int[] moveBuf;
        private final boolean[] sprintBuf, jumpBuf, sneakBuf;
        private final float[] aimYawBuf, aimPitBuf, clickBuf;   // NaN = no label
        private final float[] rBuf;
        private final boolean[] doneBuf;
        private int head = 0, size = 0;

        // ---- v2.0 PHASE 3-a: n-step compression + prioritized replay ----------
        // Same scheme as Dqn: (s_t, labels_t, R_t:t+n, s_t+n) with truncated
        // flushes at episode end; proportional sampling through a sum-tree with
        // the MOVE-head TD error as the priority signal; max 2 draws per slot.
        public int nStep = 3;
        private final float[][] pS, pS2;
        private final int[] pMove;
        private final boolean[] pSprint, pJump, pSneak;
        private final float[] pAimYaw, pAimPit, pClick, pR;
        private int pCount = 0;
        private final float[] tree;
        private float pMax = 1f;

        // expert storage (human-train demonstrations) — ring, never aged out
        private final float[][] eS, eS2;
        private final int[] eMove;
        private final boolean[] eSprint, eJump, eSneak;
        private final float[] eAimYaw, eAimPit, eClick;
        private final float[] eR;
        private final boolean[] eDone;
        private int eHead = 0, eSize = 0;

        private long trainSteps = 0;
        public float lastLoss = Float.NaN;
        public float aimLossEma = Float.NaN;    // HUD + assist auto-decay
        public float clickLossEma = Float.NaN;

        public PolicyNet(int capacity, int expertCapacity, float gamma, long seed) {
                this.gamma = gamma;
                this.q = new NeuralNet(ARCH, seed);
                this.target = new NeuralNet(ARCH, seed + 7919);
                this.rng = new Random(seed + 104729);
                int cap = Math.max(capacity, 4096);
                this.sBuf = new float[cap][];
                this.s2Buf = new float[cap][];
                this.moveBuf = new int[cap];
                this.sprintBuf = new boolean[cap];
                this.jumpBuf = new boolean[cap];
                this.sneakBuf = new boolean[cap];
                this.aimYawBuf = new float[cap];
                this.aimPitBuf = new float[cap];
                this.clickBuf = new float[cap];
                this.rBuf = new float[cap];
                this.doneBuf = new boolean[cap];
                int ec = Math.max(expertCapacity, 1);
                this.eS = new float[ec][];
                this.eS2 = new float[ec][];
                this.eMove = new int[ec];
                this.eSprint = new boolean[ec];
                this.eJump = new boolean[ec];
                this.eSneak = new boolean[ec];
                this.eAimYaw = new float[ec];
                this.eAimPit = new float[ec];
                this.eClick = new float[ec];
                this.eR = new float[ec];
                this.eDone = new boolean[ec];
                // PHASE 3-a: n-step pending ring + priority tree
                int n = Math.max(1, nStep);
                this.pS = new float[n][];
                this.pS2 = new float[n][];
                this.pMove = new int[n];
                this.pSprint = new boolean[n];
                this.pJump = new boolean[n];
                this.pSneak = new boolean[n];
                this.pAimYaw = new float[n];
                this.pAimPit = new float[n];
                this.pClick = new float[n];
                this.pR = new float[n];
                this.tree = new float[2 * Math.max(cap, 8)];
                syncTarget();
        }

        // -------------------------------------------------- n-step + priority

        private void storeNStep(float[] s, int move, boolean sprint, boolean jump, boolean sneak,
                                float aimYawN, float aimPitN, float clickN,
                                float r, float[] s2, boolean done) {
                int idx = head;
                sBuf[idx] = s;
                moveBuf[idx] = move;
                sprintBuf[idx] = sprint;
                jumpBuf[idx] = jump;
                sneakBuf[idx] = sneak;
                aimYawBuf[idx] = aimYawN;
                aimPitBuf[idx] = aimPitN;
                clickBuf[idx] = clickN;
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

        // -------------------------------------------------------------- decision

        public static final class Decision {
                public int move;                 // ActionSpace.M_*
                public boolean sprint, jump, sneak;
                public float aimYawDeg, aimPitDeg; // head intent, degrees per tick
                public float clickDesire;        // [0,1]
                public float sneakMargin;        // v2.0.2: q[sneak-on] - q[sneak-off] (execution gate)
        }

        /**
         * Pick one full four-head decision. Epsilon randomizes the MOVE pick
         * (and weakly the flags); aim gets a small exploratory jitter scaled
         * by epsilon; the click threshold is deterministic at 0.5.
         */
        public Decision act(float[] s, float epsilon) {
                float[] o = q.forward(s, null);
                Decision d = new Decision();
                // move
                if (rng.nextFloat() < epsilon) {
                        d.move = rng.nextInt(MOVES);
                } else {
                        d.move = argmaxRange(o, MOVE_OFF, MOVES);
                }
                // flags (greedy, weak epsilon noise) — v2.0.1: sticky picks,
                // see flagMargin. Held state lives here so EVERY caller
                // (pure mode, eval) gets the same stabilized muscles.
                d.sprint = flagSticky(o, SPRINT_OFF, epsilon * 0.5f, hSprint);
                d.jump = flagSticky(o, JUMP_OFF, epsilon * 0.5f, hJump);
                d.sneak = flagSticky(o, SNEAK_OFF, epsilon * 0.5f, hSneak);
                hSprint = d.sprint;
                hJump = d.jump;
                hSneak = d.sneak;
                d.sneakMargin = o[SNEAK_OFF + 1] - o[SNEAK_OFF]; // v2.0.2 execution gate
                // aim — v2.0.1: exploration jitter halved-plus (0.08/0.05 ->
                // 0.03/0.02): at epsilon 1.0 the old jitter injected up to
                // +/-3.2 deg/tick of pure noise into the aim budget, feeding
                // the very oscillation the square fix removes.
                float ny = Math.max(-1f, Math.min(1f, o[AIM_OFF]));
                float np = Math.max(-1f, Math.min(1f, o[AIM_OFF + 1]));
                ny += (float) (rng.nextGaussian() * epsilon * 0.03);
                np += (float) (rng.nextGaussian() * epsilon * 0.02);
                d.aimYawDeg = Math.max(-1f, Math.min(1f, ny)) * aimMaxDeg;
                d.aimPitDeg = Math.max(-1f, Math.min(1f, np)) * aimMaxDeg;
                // click
                d.clickDesire = Math.max(0f, Math.min(1f, o[CLICK_OFF]));
                return d;
        }

        private boolean flagOn(float[] o, int off, float eps) {
                if (rng.nextFloat() < eps) {
                        return rng.nextBoolean();
                }
                return o[off + 1] > o[off];
        }

        /** v2.0.1: greedy flag pick with hysteresis against the held state. */
        private boolean flagSticky(float[] o, int off, float eps, boolean held) {
                boolean raw = flagOn(o, off, eps);
                if (raw == held) {
                        return held;
                }
                float on = o[off + 1], offV = o[off];
                if (raw && on - offV < flagMargin) {
                        return held; // not confident enough to switch ON
                }
                if (!raw && offV - on < flagMargin) {
                        return held; // not confident enough to switch OFF
                }
                return raw;
        }

        // -------------------------------------------------------------- memory

        public void remember(float[] s, int move, boolean sprint, boolean jump, boolean sneak,
                             float aimYawN, float aimPitN, float clickN,
                             float r, float[] s2, boolean done) {
                // PHASE 3-a: compress into n-step returns (labels ride with the
                // SOURCE state of each step; only reward/next-state compress)
                pS[pCount] = s;
                pMove[pCount] = move;
                pSprint[pCount] = sprint;
                pJump[pCount] = jump;
                pSneak[pCount] = sneak;
                pAimYaw[pCount] = aimYawN;
                pAimPit[pCount] = aimPitN;
                pClick[pCount] = clickN;
                pR[pCount] = r;
                pS2[pCount] = s2;
                pCount++;
                if (done) {
                        for (int i = 0; i < pCount; i++) {
                                float R = 0f, g = 1f;
                                for (int j = i; j < pCount; j++) {
                                        R += g * pR[j];
                                        g *= gamma;
                                }
                                storeNStep(pS[i], pMove[i], pSprint[i], pJump[i], pSneak[i],
                                                pAimYaw[i], pAimPit[i], pClick[i],
                                                R, pS2[pCount - 1], true);
                        }
                        pCount = 0;
                } else if (pCount >= nStep) {
                        float R = 0f, g = 1f;
                        for (int j = 0; j < nStep; j++) {
                                R += g * pR[j];
                                g *= gamma;
                        }
                        storeNStep(pS[0], pMove[0], pSprint[0], pJump[0], pSneak[0],
                                        pAimYaw[0], pAimPit[0], pClick[0],
                                        R, pS2[nStep - 1], false);
                        for (int i = 1; i < pCount; i++) {
                                pS[i - 1] = pS[i];
                                pMove[i - 1] = pMove[i];
                                pSprint[i - 1] = pSprint[i];
                                pJump[i - 1] = pJump[i];
                                pSneak[i - 1] = pSneak[i];
                                pAimYaw[i - 1] = pAimYaw[i];
                                pAimPit[i - 1] = pAimPit[i];
                                pClick[i - 1] = pClick[i];
                                pR[i - 1] = pR[i];
                                pS2[i - 1] = pS2[i];
                        }
                        pCount--;
                }
        }

        /** Store a full human demonstration (all four heads labeled). */
        public void rememberExpert(float[] s, int move, boolean sprint, boolean jump, boolean sneak,
                                   float aimYawN, float aimPitN, float clickN,
                                   float r, float[] s2, boolean done) {
                int idx = eHead;
                eS[idx] = s;
                eMove[idx] = move;
                eSprint[idx] = sprint;
                eJump[idx] = jump;
                eSneak[idx] = sneak;
                eAimYaw[idx] = aimYawN;
                eAimPit[idx] = aimPitN;
                eClick[idx] = clickN;
                eR[idx] = r;
                eS2[idx] = s2;
                eDone[idx] = done;
                eHead = (eHead + 1) % eS.length;
                if (eSize < eS.length) {
                        eSize++;
                }
        }

        public int bufferSize() {
                return size;
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

        public long getTrainSteps() {
                return trainSteps;
        }

        // -------------------------------------------------------------- training

        /**
         * One gradient step over a mixed batch (TD self-play + expert demos
         * with margin cloning). Returns Huber loss (NaN while the buffer
         * warms up). Mirrors {@link Dqn#trainStep}.
         */
        public float trainStep(int batch, float lr, float expertFrac, float margin) {
                if (size < Math.max(batch, 512)) {
                        return Float.NaN;
                }
                int nExpert = eSize >= 64 && expertFrac > 0f
                                ? Math.min(batch - 1, Math.max(1, Math.round(batch * expertFrac)))
                                : 0;
                int nTd = batch - nExpert;
                float[][] xs = new float[batch][];
                float[][] ys = new float[batch][];
                int k = 0;
                statAimSq = 0f;
                statAimN = 0;
                statClickSq = 0f;
                statClickN = 0;
                int[] slots = new int[batch];
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
                        float[] err = new float[1];
                        ys[k] = targetVector(sBuf[idx], moveBuf[idx], sprintBuf[idx], jumpBuf[idx], sneakBuf[idx],
                                        aimYawBuf[idx], aimPitBuf[idx], clickBuf[idx],
                                        rBuf[idx], s2Buf[idx], doneBuf[idx], 0f, err, gammaN());
                        treeUpdate(idx, err[0] + 1e-3f);
                }
                float aimSq = statAimSq, aimN = statAimN, clickSq = statClickSq, clickN = statClickN;
                for (int i = 0; i < nExpert; i++, k++) {
                        int idx = rng.nextInt(eSize);
                        xs[k] = eS[idx];
                        float[] err = new float[1];
                        ys[k] = targetVector(eS[idx], eMove[idx], eSprint[idx], eJump[idx], eSneak[idx],
                                        eAimYaw[idx], eAimPit[idx], eClick[idx],
                                        eR[idx], eS2[idx], eDone[idx], margin, err, gamma);
                }
                aimSq += statAimSq;
                aimN += statAimN;
                clickSq += statClickSq;
                clickN += statClickN;
                trainSteps++;
                float loss = q.trainBatch(xs, ys, lr);
                lastLoss = loss;
                if (aimN > 0) {
                        float al = aimSq / aimN;
                        aimLossEma = Float.isNaN(aimLossEma) ? al : aimLossEma + 0.02f * (al - aimLossEma);
                }
                if (clickN > 0) {
                        float cl = clickSq / clickN;
                        clickLossEma = Float.isNaN(clickLossEma) ? cl : clickLossEma + 0.02f * (cl - clickLossEma);
                }
                return loss;
        }

        private float sq(float v) {
                return v * v;
        }

        // per-trainStep supervised-error accumulators (filled by targetVector)
        private float statAimSq = 0f;
        private int statAimN = 0;
        private float statClickSq = 0f;
        private int statClickN = 0;

        /**
         * Build the 18-dim supervised target for one transition. Untouched
         * outputs keep the online value (identity mask -> zero gradient).
         */
        private float[] targetVector(float[] s, int move, boolean sprint, boolean jump, boolean sneak,
                                     float aimYawN, float aimPitN, float clickN,
                                     float r, float[] s2, boolean done, float expertMargin,
                                     float[] errOut, float discount) {
                float[] yhat = q.forward(s, null);
                float[] y = yhat.clone();

                float[] q2t = null;
                if (!done) {
                        q2t = target.forward(s2, null);
                }

                // MOVE: double-DQN (online picks, target evaluates) / terminal / margin
                int mi = MOVE_OFF + move;
                if (done) {
                        y[mi] = r;
                } else {
                        float[] q2on = q.forward(s2, null);
                        int best = argmaxRange(q2on, MOVE_OFF, MOVES);
                        y[mi] = r + discount * q2t[MOVE_OFF + best];
                }
                if (expertMargin > 0f) {
                        float bestOther = Float.NEGATIVE_INFINITY;
                        for (int i = 0; i < MOVES; i++) {
                                if (i != move && yhat[MOVE_OFF + i] > bestOther) {
                                        bestOther = yhat[MOVE_OFF + i];
                                }
                        }
                        if (bestOther != Float.NEGATIVE_INFINITY) {
                                y[mi] = Math.max(y[mi], bestOther + expertMargin);
                        }
                }

                // FLAGS: per-flag Bellman (max over the two bits of the target net)
                y = flagTd(y, q2t, r, discount, done, SPRINT_OFF, sprint, expertMargin);
                y = flagTd(y, q2t, r, discount, done, JUMP_OFF, jump, expertMargin);
                y = flagTd(y, q2t, r, discount, done, SNEAK_OFF, sneak, expertMargin);

                // AIM: supervised regression (identity mask when unlabeled)
                if (!Float.isNaN(aimYawN)) {
                        y[AIM_OFF] = Math.max(-0.98f, Math.min(0.98f, aimYawN));
                        y[AIM_OFF + 1] = Math.max(-0.98f, Math.min(0.98f, aimPitN));
                        statAimSq += sq(y[AIM_OFF] - yhat[AIM_OFF]) + sq(y[AIM_OFF + 1] - yhat[AIM_OFF + 1]);
                        statAimN++;
                }

                // CLICK: supervised regression toward the should-have-clicked label
                if (!Float.isNaN(clickN)) {
                        y[CLICK_OFF] = Math.max(0f, Math.min(1f, clickN));
                        statClickSq += sq(y[CLICK_OFF] - yhat[CLICK_OFF]);
                        statClickN++;
                }
                errOut[0] = Math.abs(y[MOVE_OFF + move] - yhat[MOVE_OFF + move]); // PER priority
                return y;
        }

        private float[] flagTd(float[] y, float[] q2t, float r, float discount, boolean done,
                               int off, boolean bit, float expertMargin) {
                int idx = off + (bit ? 1 : 0);
                if (done) {
                        y[idx] = r;
                } else if (q2t != null) {
                        y[idx] = r + discount * Math.max(q2t[off], q2t[off + 1]);
                }
                if (expertMargin > 0f) {
                        int other = off + (bit ? 0 : 1);
                        y[idx] = Math.max(y[idx], y[other] + expertMargin * 0.5f);
                }
                return y;
        }

        public void syncTarget() {
                target.copyFrom(q);
        }

        /** v2.3: replay samples hold n-step returns -> bootstrap with gamma^n (was gamma). */
        private float gammaN() {
                return (float) Math.pow(gamma, Math.max(1, nStep));
        }

        /**
         * v2.3: carry the training volume of a loaded brain (the .pbm header's
         * step count). Exploration decays with it, so a pretrained brain starts
         * near the stable epsilon instead of 45% random moves.
         */
        public void setTrainSteps(long steps) {
                trainSteps = Math.max(0L, steps);
        }

        // -------------------------------------------------------------- hot-swap + IO

        /** v2 hot-swap: replace weights, KEEP replay + expert buffers. */
        public void loadWeights(PolicyNet src) {
                q.copyFrom(src.q);
                syncTarget();
        }

        public void saveBinary(java.io.DataOutputStream out) throws java.io.IOException {
                q.saveBinary(out);
        }

        public static PolicyNet loadBinary(java.io.DataInputStream in) throws java.io.IOException {
                NeuralNet net = NeuralNet.loadBinary(in);
                if (!java.util.Arrays.equals(net.sizes, ARCH)) {
                        throw new java.io.IOException("arch mismatch: file "
                                        + java.util.Arrays.toString(net.sizes) + " != v2.3 "
                                        + java.util.Arrays.toString(ARCH)
                                        + " (v2.3 brains read the 100-dim ObsV4 input; older v2 brains must be retrained)");
                }
                PolicyNet p = new PolicyNet(4096, 1, 0.995f, 20260101L);
                p.q.copyFrom(net);
                p.syncTarget();
                return p;
        }

        public String archSummary() {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < q.sizes.length; i++) {
                        if (i > 0) {
                                sb.append('x');
                        }
                        sb.append(q.sizes[i]);
                }
                return sb.toString();
        }

        private static int argmaxRange(float[] v, int off, int len) {
                int best = off;
                float bv = v[off];
                for (int i = 1; i < len; i++) {
                        if (v[off + i] > bv) {
                                bv = v[off + i];
                                best = off + i;
                        }
                }
                return best - off;
        }
}
