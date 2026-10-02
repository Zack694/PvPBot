package dev.z.pvpbot.ml;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.3: the bundled pretrained brain loads through the SAME path the mod uses
 * (ModelStore.readPolicyNet on the jar resource), has the running architecture,
 * carries its offline step count, and its Java forward pass reproduces the
 * trainer's numpy outputs on simulator observations.
 */
public class BundledBrainTest {

        @Test
        public void bundledBrainLoadsAndMatchesTrainer() throws Exception {
                var raw = BundledBrainTest.class.getResourceAsStream("/assets/pvpbot/model/brain_v2.pbm");
                assertNotNull(raw, "bundled brain missing");
                PolicyNet p = ModelStore.readPolicyNet(raw);
                assertEquals(PolicyNet.ARCH.length - 1, p.archSummary().split("x").length - 1);
                assertEquals(java.util.Arrays.toString(PolicyNet.ARCH).replace(", ", "x").replace("[", "").replace("]", ""),
                                p.archSummary());

                var in = BundledBrainTest.class.getResourceAsStream("/brain_v2_expected.json");
                assertNotNull(in, "expected outputs missing");
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(root.get("steps").getAsLong(), p.getTrainSteps(), "offline step count not carried");
                JsonArray obs = root.getAsJsonArray("obs"), out = root.getAsJsonArray("out");
                double worst = 0;
                for (int i = 0; i < obs.size(); i++) {
                        JsonArray o = obs.get(i).getAsJsonArray();
                        float[] s = new float[o.size()];
                        for (int k = 0; k < s.length; k++) s[k] = o.get(k).getAsFloat();
                        float[] got = p.forward(s);
                        JsonArray want = out.get(i).getAsJsonArray();
                        for (int k = 0; k < got.length; k++) {
                                double d = Math.abs(got[k] - want.get(k).getAsDouble());
                                worst = Math.max(worst, d);
                                assertTrue(d < 1e-3, "head[" + k + "] sample " + i + ": java " + got[k] + " vs trainer " + want.get(k));
                        }
                }
                System.out.printf("bundled brain OK: %s, %d offline steps, forward parity worst |d| %.2e%n",
                                p.archSummary(), p.getTrainSteps(), worst);
        }
}
