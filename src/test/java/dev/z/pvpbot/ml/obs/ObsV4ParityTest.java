package dev.z.pvpbot.ml.obs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays the simulator recording (pretrain/v3/make_fixture.py) through the
 * Java ObsV4 and demands the same observation vectors the Python mirror
 * produced. If this fails, an offline-trained brain would see different
 * numbers in-game than it learned on — fix obs.py and ObsV4.java together.
 */
public class ObsV4ParityTest {

        private static final double TOL = 2e-4;

        @Test
        public void javaMatchesPythonSimulator() throws Exception {
                var in = ObsV4ParityTest.class.getResourceAsStream("/obs_v4_fixture.json");
                assertNotNull(in, "fixture missing — run pretrain/v3/make_fixture.py");
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(ObsV4.DIM, root.get("dim").getAsInt(), "fixture DIM differs from ObsV4.DIM");
                ObsV4 obs = new ObsV4();
                int ticks = 0;
                double worst = 0;
                int worstIdx = -1, worstTick = -1;
                for (JsonElement e : root.getAsJsonArray("ops")) {
                        JsonObject op = e.getAsJsonObject();
                        String kind = op.get("op").getAsString();
                        if (kind.equals("opp")) {
                                obs.resetOpponent();
                                continue;
                        }
                        if (kind.equals("ep")) {
                                obs.resetEpisode();
                                continue;
                        }
                        CombatFrame f = frame(op.getAsJsonObject("f"));
                        obs.tick(f);
                        float[] got = obs.build();
                        JsonArray want = op.getAsJsonArray("obs");
                        assertEquals(want.size(), got.length);
                        for (int i = 0; i < got.length; i++) {
                                double d = Math.abs(got[i] - want.get(i).getAsDouble());
                                if (d > worst) {
                                        worst = d;
                                        worstIdx = i;
                                        worstTick = ticks;
                                }
                                assertTrue(d <= TOL, "obs[" + i + "] tick " + ticks + ": java=" + got[i]
                                                + " python=" + want.get(i).getAsDouble());
                        }
                        ticks++;
                }
                assertTrue(ticks > 100, "fixture too short");
                System.out.printf("ObsV4 parity OK over %d ticks (worst |d| %.2e at obs[%d] tick %d)%n",
                                ticks, worst, worstIdx, worstTick);
        }

        private static CombatFrame frame(JsonObject j) {
                CombatFrame f = new CombatFrame();
                fighter(j.getAsJsonObject("me"), f.me);
                fighter(j.getAsJsonObject("them"), f.them);
                f.myCharge = j.get("my_charge").getAsFloat();
                f.food = j.get("food").getAsFloat();
                f.myMove = j.get("my_move").getAsInt();
                f.myJumpHeld = j.get("my_jump_held").getAsBoolean();
                JsonArray tr = j.getAsJsonArray("terrain");
                for (int i = 0; i < 8; i++) {
                        f.terrain[i] = tr.get(i).getAsFloat();
                }
                f.dropAhead = j.get("drop_ahead").getAsFloat();
                f.ceilingLow = j.get("ceiling_low").getAsFloat();
                f.los = j.get("los").getAsBoolean();
                f.crosshairOnTarget = j.get("crosshair_on_target").getAsBoolean();
                f.iSwung = j.get("i_swung").getAsBoolean();
                f.iHitThem = j.get("i_hit_them").getAsBoolean();
                f.dmgDealt = j.get("dmg_dealt").getAsFloat();
                f.iCrit = j.get("i_crit").getAsBoolean();
                f.iWasHit = j.get("i_was_hit").getAsBoolean();
                f.dmgTaken = j.get("dmg_taken").getAsFloat();
                return f;
        }

        private static void fighter(JsonObject j, FighterFrame f) {
                f.x = j.get("x").getAsDouble();
                f.y = j.get("y").getAsDouble();
                f.z = j.get("z").getAsDouble();
                f.yaw = j.get("yaw").getAsFloat();
                f.pitch = j.get("pitch").getAsFloat();
                f.health = j.get("health").getAsFloat();
                f.absorption = j.get("absorption").getAsFloat();
                f.width = j.get("width").getAsFloat();
                f.height = j.get("height").getAsFloat();
                f.onGround = j.get("on_ground").getAsBoolean();
                f.sprinting = j.get("sprinting").getAsBoolean();
                f.sneaking = j.get("sneaking").getAsBoolean();
                f.swinging = j.get("swinging").getAsBoolean();
                f.usingItem = j.get("using_item").getAsBoolean();
                f.hurtTime = j.get("hurt_time").getAsInt();
        }
}
