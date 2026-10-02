package dev.z.pvpbot.ml;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** v2.3.2: the bundled classic brain loads through Dqn.fromJson with the running v1 architecture. */
public class BundledV1BrainTest {

        @Test
        public void bundledV1BrainLoads() {
                var in = BundledV1BrainTest.class.getResourceAsStream("/assets/pvpbot/model/policy.json");
                assertNotNull(in, "bundled v1 brain missing");
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                Dqn d = Dqn.fromJson(root, 4096, 0.995f, 1L, 1024, new int[]{64, 480, 480, 72});
                float[] s = new float[64];
                s[55] = 1f;
                float[] q = d.qValues(s);
                for (float v : q) {
                        if (!Float.isFinite(v)) throw new AssertionError("non-finite Q");
                }
                System.out.println("bundled v1 brain OK: " + d.qArchSummary());
        }
}
