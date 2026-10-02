package dev.z.pvpbot.ml;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Random;

/**
 * From-scratch fully-connected neural network (He init, ReLU hidden layers,
 * linear output) with manual backpropagation and the Adam optimizer.
 * No external ML libraries — every gradient is computed in this file.
 */
public final class NeuralNet {

        public final int[] sizes;
        private final float[][][] w; // w[l][out][in]
        private final float[][] b;  // b[l][out]
        // Adam moments
        private final float[][][] mW, vW;
        private final float[][] mB, vB;
        private long t = 0;
        private final Random rng;

        // guards trainBatch() + toJson() so background training and background
        // model snapshots can never interleave (forward() stays lock-free — the
        // game thread reads weights while the trainer writes them, which is a
        // benign float race: worst case one forward sees mid-update weights)
        private final Object trainLock = new Object();

        public NeuralNet(int[] sizes, long seed) {
                this.sizes = sizes.clone();
                this.rng = new Random(seed);
                int layers = sizes.length - 1;
                w = new float[layers][][];
                b = new float[layers][];
                mW = new float[layers][][];
                vW = new float[layers][][];
                mB = new float[layers][];
                vB = new float[layers][];
                for (int l = 0; l < layers; l++) {
                        int in = sizes[l], out = sizes[l + 1];
                        w[l] = new float[out][in];
                        mW[l] = new float[out][in];
                        vW[l] = new float[out][in];
                        b[l] = new float[out];
                        mB[l] = new float[out];
                        vB[l] = new float[out];
                        float std = (float) Math.sqrt(2.0 / Math.max(1, in));
                        for (int i = 0; i < out; i++) {
                                for (int j = 0; j < in; j++) {
                                        w[l][i][j] = (float) (rng.nextGaussian() * std);
                                }
                                b[l][i] = 0f;
                        }
                }
        }

        private NeuralNet(int[] sizes, float[][][] w, float[][] b) {
                this.sizes = sizes.clone();
                this.w = w;
                this.b = b;
                this.rng = new Random();
                int layers = sizes.length - 1;
                mW = new float[layers][][];
                vW = new float[layers][][];
                mB = new float[layers][];
                vB = new float[layers][];
                for (int l = 0; l < layers; l++) {
                        int out = sizes[l + 1], in = sizes[l];
                        mW[l] = new float[out][in];
                        vW[l] = new float[out][in];
                        mB[l] = new float[out];
                        vB[l] = new float[out];
                }
        }

        public int outDim() {
                return sizes[sizes.length - 1];
        }

        /** Direct (mutable) access to weights — used for target-network sync and loading. */
        public float[][][] params() {
                return w;
        }

        /** Direct (mutable) access to biases. */
        public float[][] biases() {
                return b;
        }

        public void copyFrom(NeuralNet src) {
                for (int l = 0; l < w.length; l++) {
                        for (int i = 0; i < w[l].length; i++) {
                                System.arraycopy(src.w[l][i], 0, w[l][i], 0, w[l][i].length);
                                b[l][i] = src.b[l][i];
                        }
                }
        }

        /** Forward pass. If acts != null, acts[0] is filled with x and acts[l+1] with the post-activation of layer l. */
        public float[] forward(float[] x, float[][] acts) {
                int layers = w.length;
                if (acts != null) {
                        acts[0] = x.clone();
                }
                float[] a = x;
                for (int l = 0; l < layers; l++) {
                        int out = sizes[l + 1], in = sizes[l];
                        float[] z = new float[out];
                        float[][] wl = w[l];
                        float[] bl = b[l];
                        for (int i = 0; i < out; i++) {
                                float sum = bl[i];
                                float[] wr = wl[i];
                                for (int j = 0; j < in; j++) {
                                        sum += wr[j] * a[j];
                                }
                                z[i] = (l < layers - 1) ? Math.max(0f, sum) : sum; // ReLU hidden, linear out
                        }
                        a = z;
                        if (acts != null) {
                                acts[l + 1] = a;
                        }
                }
                return a;
        }

        /**
         * One Adam step over a batch (accumulated gradients). ys are the desired
         * full output vectors (DQN caller fills un-masked entries with current Q).
         * Returns mean squared error.
         */
        public float trainBatch(float[][] xs, float[][] ys, float lr) {
                synchronized (trainLock) {
                        return trainBatchLocked(xs, ys, lr);
                }
        }

        private float trainBatchLocked(float[][] xs, float[][] ys, float lr) {
                int layers = w.length;
                float[][][] gW = newGrad(w);
                float[][] gB = newGradB(b);
                float loss = 0f;
                int n = xs.length;
                // scratch
                float[][] acts = new float[w.length + 1][];
                for (int s = 0; s < n; s++) {
                        float[] yhat = forward(xs[s], acts);
                        float[] delta = new float[yhat.length];
                        for (int i = 0; i < yhat.length; i++) {
                                float d = yhat[i] - ys[s][i];
                                // v1.0.11 HUBER LOSS — raw MSE let the big terminal
                                // rewards (win +45 / loss -10) produce huge TD errors
                                // whose squared gradients exploded the weights (the
                                // "loss keeps climbing" drift). Huber is quadratic
                                // within +-1 (identical fine-grain learning) and LINEAR
                                // beyond it: every sample's gradient is bounded to [-1,1],
                                // so a terminal transition can no longer dominate a batch.
                                float ad = Math.abs(d);
                                loss += ad <= 1f ? 0.5f * d * d : ad - 0.5f;
                                delta[i] = Math.max(-1f, Math.min(1f, d));
                        }
                        // backward
                        for (int l = layers - 1; l >= 0; l--) {
                                float[] aPrev = acts[l];
                                int out = sizes[l + 1], in = sizes[l];
                                float[] deltaPrev = new float[in];
                                float[][] wl = w[l];
                                float[][] gwl = gW[l];
                                float[] gbl = gB[l];
                                for (int i = 0; i < out; i++) {
                                        float di = delta[i];
                                        if (di == 0f) {
                                                continue;
                                        }
                                        gbl[i] += di;
                                        float[] wr = wl[i];
                                        float[] gr = gwl[i];
                                        for (int j = 0; j < in; j++) {
                                                gr[j] += di * aPrev[j];
                                                deltaPrev[j] += di * wr[j];
                                        }
                                }
                                if (l > 0) {
                                        for (int j = 0; j < in; j++) {
                                                if (aPrev[j] <= 0f) {
                                                        deltaPrev[j] = 0f; // ReLU derivative
                                                }
                                        }
                                }
                                delta = deltaPrev;
                        }
                }
                loss /= (n * outDim());
                // v1.0.11 GLOBAL GRADIENT-NORM CLIP (max norm 5) — the second
                // guard beside Huber: even a pathological batch can never move
                // the weights by more than a healthy Adam step.
                float gnorm = 0f;
                for (float[][] gl : gW) {
                        for (float[] gr : gl) {
                                for (float g : gr) gnorm += g * g;
                        }
                }
                for (float[] gb : gB) {
                        for (float g : gb) gnorm += g * g;
                }
                gnorm = (float) Math.sqrt(gnorm);
                float clip = gnorm > 5f ? 5f / gnorm : 1f;
                // Adam step
                t++;
                float b1 = 0.9f, b2 = 0.999f, eps = 1e-8f;
                float bc1 = (float) (1.0 - Math.pow(b1, t));
                float bc2 = (float) (1.0 - Math.pow(b2, t));
                float scale = 1f / n;
                for (int l = 0; l < layers; l++) {
                        int out = sizes[l + 1], in = sizes[l];
                        for (int i = 0; i < out; i++) {
                                float[] wr = w[l][i], gr = gW[l][i], mr = mW[l][i], vr = vW[l][i];
                                for (int j = 0; j < in; j++) {
                                        float g = gr[j] * scale * clip;
                                        mr[j] = b1 * mr[j] + (1 - b1) * g;
                                        vr[j] = b2 * vr[j] + (1 - b2) * g * g;
                                        wr[j] -= (float) (lr * (mr[j] / bc1) / (Math.sqrt(vr[j] / bc2) + eps));
                                }
                                float gb = gB[l][i] * scale * clip;
                                mB[l][i] = b1 * mB[l][i] + (1 - b1) * gb;
                                vB[l][i] = b2 * vB[l][i] + (1 - b2) * gb * gb;
                                b[l][i] -= (float) (lr * (mB[l][i] / bc1) / (Math.sqrt(vB[l][i] / bc2) + eps));
                        }
                }
                return loss;
        }

        private float[][][] newGrad(float[][][] w) {
                float[][][] g = new float[w.length][][];
                for (int l = 0; l < w.length; l++) {
                        g[l] = new float[w[l].length][w[l][0].length];
                }
                return g;
        }

        private float[][] newGradB(float[][] b) {
                float[][] g = new float[b.length][];
                for (int l = 0; l < b.length; l++) {
                        g[l] = new float[b[l].length];
                }
                return g;
        }

        // ------------------------------------------------------------------ IO

        public JsonObject toJson() {
                // consistent snapshot even while the background trainer runs
                synchronized (trainLock) {
                        return toJsonLocked();
                }
        }

        private JsonObject toJsonLocked() {
                JsonObject root = new JsonObject();
                JsonArray arch = new JsonArray();
                for (int s : sizes) {
                        arch.add(s);
                }
                root.add("arch", arch);
                JsonArray layers = new JsonArray();
                for (int l = 0; l < w.length; l++) {
                        JsonObject layer = new JsonObject();
                        layer.add("w", matrix(w[l]));
                        layer.add("b", vec(b[l]));
                        layers.add(layer);
                }
                root.add("layers", layers);
                return root;
        }

        public static NeuralNet fromJson(JsonObject root) {
                JsonArray arch = root.getAsJsonArray("arch");
                int[] sizes = new int[arch.size()];
                for (int i = 0; i < sizes.length; i++) {
                        sizes[i] = arch.get(i).getAsInt();
                }
                JsonArray layers = root.getAsJsonArray("layers");
                float[][][] w = new float[layers.size()][][];
                float[][] b = new float[layers.size()][];
                for (int l = 0; l < layers.size(); l++) {
                        JsonObject layer = layers.get(l).getAsJsonObject();
                        w[l] = matrix(layer.getAsJsonArray("w"));
                        b[l] = vec(layer.getAsJsonArray("b"));
                }
                return new NeuralNet(sizes, w, b);
        }

        private static JsonArray matrix(float[][] m) {
                JsonArray rows = new JsonArray();
                for (float[] row : m) {
                        rows.add(vec(row));
                }
                return rows;
        }

        private static JsonArray vec(float[] v) {
                JsonArray arr = new JsonArray(v.length);
                for (float x : v) {
                        arr.add(x);
                }
                return arr;
        }

        private static float[][] matrix(JsonArray rows) {
                float[][] m = new float[rows.size()][];
                for (int i = 0; i < m.length; i++) {
                        m[i] = vec(rows.get(i).getAsJsonArray());
                }
                return m;
        }

        private static float[] vec(JsonArray arr) {
                float[] v = new float[arr.size()];
                for (int i = 0; i < v.length; i++) {
                        v[i] = arr.get(i).getAsFloat();
                }
                return v;
        }

        // ------------------------------------------------------------ binary v2
        //
        // v2.0 PHASE 2 — BINARY MODEL FORMAT (user: "why is the model a JSON
        // file?"). The v1 JSON format spent 6.4 MB of text describing ~1.13 MB
        // of weights: every float as a decimal string parsed by Gson, a save
        // cost of 100-300ms and a load that stuttered startup. The binary
        // format stores the SAME weights as little-endian float32 —
        //
        //   magic  "PVB2" (4 bytes)
        //   short  format version (1)
        //   int    layer-count (= sizes.length)
        //   int[]  sizes
        //   per layer: float32 weights [out][in] row-major, then float32 biases [out]
        //
        // ~1.0 MB on disk (about a sixth of the pretty JSON), written in one pass with no string building, and
        // loaded with a single InputStream. The legacy JSON loader is kept so
        // every v1 model still opens.

        static final byte[] BIN_MAGIC = {'P', 'V', 'B', '2'};
        static final short BIN_VERSION = 1;

        public void saveBinary(java.io.DataOutputStream out) throws java.io.IOException {
                // consistent snapshot even while the background trainer runs
                synchronized (trainLock) {
                        out.write(BIN_MAGIC);
                        out.writeShort(BIN_VERSION);
                        out.writeInt(sizes.length);
                        for (int s : sizes) {
                                out.writeInt(s);
                        }
                        for (int l = 0; l < w.length; l++) {
                                for (int i = 0; i < w[l].length; i++) {
                                        float[] row = w[l][i];
                                        for (int j = 0; j < row.length; j++) {
                                                writeFloatLE(out, row[j]);
                                        }
                                }
                                for (int i = 0; i < b[l].length; i++) {
                                        writeFloatLE(out, b[l][i]);
                                }
                        }
                }
        }

        public static NeuralNet loadBinary(java.io.DataInputStream in) throws java.io.IOException {
                byte[] magic = new byte[BIN_MAGIC.length];
                in.readFully(magic);
                if (!java.util.Arrays.equals(magic, BIN_MAGIC)) {
                        throw new java.io.IOException("bad magic — not a PVB2 binary net");
                }
                short ver = in.readShort();
                if (ver != BIN_VERSION) {
                        throw new java.io.IOException("unsupported PVB2 version " + ver);
                }
                int n = in.readInt();
                if (n < 2 || n > 16) {
                        throw new java.io.IOException("implausible layer count " + n);
                }
                int[] sizes = new int[n];
                for (int i = 0; i < n; i++) {
                        sizes[i] = in.readInt();
                        if (sizes[i] < 1 || sizes[i] > 8192) {
                                throw new java.io.IOException("implausible layer size " + sizes[i]);
                        }
                }
                int layers = n - 1;
                float[][][] w = new float[layers][][];
                float[][] b = new float[layers][];
                for (int l = 0; l < layers; l++) {
                        int outN = sizes[l + 1], inN = sizes[l];
                        w[l] = new float[outN][inN];
                        b[l] = new float[outN];
                        for (int i = 0; i < outN; i++) {
                                for (int j = 0; j < inN; j++) {
                                        w[l][i][j] = readFloatLE(in);
                                }
                        }
                        for (int i = 0; i < outN; i++) {
                                b[l][i] = readFloatLE(in);
                        }
                }
                return new NeuralNet(sizes, w, b);
        }

        private static void writeFloatLE(java.io.DataOutputStream out, float v) throws java.io.IOException {
                out.writeInt(Integer.reverseBytes(Float.floatToIntBits(v)));
        }

        private static float readFloatLE(java.io.DataInputStream in) throws java.io.IOException {
                return Float.intBitsToFloat(Integer.reverseBytes(in.readInt()));
        }
}
