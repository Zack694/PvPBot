package dev.z.pvpbot.ml;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.z.pvpbot.BotConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * v2.0 PHASE 3-b — CHECKPOINT LEAGUE.
 *
 * Every {@code /pvpbot eval} run produces a scorecard (W/L/D, damage
 * balance, hit accuracy) AND a snapshot of the exact brain that earned it.
 * The pairing is stored in config/pvpbot/models/league.json so the user can
 * later hot-swap the historically best brain back in:
 *
 *   /pvpbot league          — print the ranking
 *   /pvpbot model load X    — swap a league entry's snapshot in
 *
 * Ranking: wins DESC, then damage balance (dealt - taken) DESC. Capped at
 * 50 entries (oldest evicted). Never throws on a broken file — it is reset.
 */
public final class League {

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final int CAP = 50;

        public static final class Entry {
                public String name;
                public String file;
                public String kind;      // "v1" | "v2"
                public int wins, losses, draws, episodes;
                public float dmgDealt, dmgTaken;
                public String at;

                public String line(int rank) {
                        float diff = dmgDealt - dmgTaken;
                        float acc = (wins + losses + draws) > 0
                                        ? 100f * wins / (wins + losses + draws)
                                        : 0f;
                        return String.format("#%d  %-24s %s  W/L/D %d/%d/%d (%.0f%% win)  dmg %+,.1f",
                                        rank, name, kind, wins, losses, draws, acc, diff);
                }
        }

        private League() {
        }

        private static Path file() {
                return BotConfig.dir().resolve("models").resolve("league.json");
        }

        public static synchronized void record(String name, String fileName, String kind,
                                               int wins, int losses, int draws,
                                               float dmgDealt, float dmgTaken, int episodes) {
                try {
                        List<Entry> list = read();
                        Entry e = new Entry();
                        e.name = name;
                        e.file = fileName;
                        e.kind = kind;
                        e.wins = wins;
                        e.losses = losses;
                        e.draws = draws;
                        e.dmgDealt = dmgDealt;
                        e.dmgTaken = dmgTaken;
                        e.episodes = episodes;
                        e.at = java.time.Instant.now().toString();
                        list.add(e);
                        list.sort(Comparator
                                        .comparingInt((Entry x) -> x.wins).reversed()
                                        .thenComparing(x -> x.dmgDealt - x.dmgTaken, Comparator.reverseOrder()));
                        while (list.size() > CAP) {
                                list.remove(list.size() - 1);
                        }
                        write(list);
                } catch (Exception ignored) {
                }
        }

        public static synchronized List<Entry> top() {
                try {
                        return read();
                } catch (Exception e) {
                        return new ArrayList<>();
                }
        }

        private static List<Entry> read() throws IOException {
                List<Entry> out = new ArrayList<>();
                Path p = file();
                if (!Files.exists(p)) {
                        return out;
                }
                JsonObject root = GSON.fromJson(Files.readString(p), JsonObject.class);
                if (root == null || !root.has("entries")) {
                        return out;
                }
                JsonArray arr = root.getAsJsonArray("entries");
                for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        Entry e = new Entry();
                        e.name = o.has("name") ? o.get("name").getAsString() : "?";
                        e.file = o.has("file") ? o.get("file").getAsString() : "";
                        e.kind = o.has("kind") ? o.get("kind").getAsString() : "v1";
                        e.wins = o.has("wins") ? o.get("wins").getAsInt() : 0;
                        e.losses = o.has("losses") ? o.get("losses").getAsInt() : 0;
                        e.draws = o.has("draws") ? o.get("draws").getAsInt() : 0;
                        e.episodes = o.has("episodes") ? o.get("episodes").getAsInt() : 0;
                        e.dmgDealt = o.has("dmgDealt") ? o.get("dmgDealt").getAsFloat() : 0f;
                        e.dmgTaken = o.has("dmgTaken") ? o.get("dmgTaken").getAsFloat() : 0f;
                        e.at = o.has("at") ? o.get("at").getAsString() : "";
                        out.add(e);
                }
                return out;
        }

        private static void write(List<Entry> list) throws IOException {
                Files.createDirectories(file().getParent());
                JsonObject root = new JsonObject();
                JsonArray arr = new JsonArray();
                for (Entry e : list) {
                        JsonObject o = new JsonObject();
                        o.addProperty("name", e.name);
                        o.addProperty("file", e.file);
                        o.addProperty("kind", e.kind);
                        o.addProperty("wins", e.wins);
                        o.addProperty("losses", e.losses);
                        o.addProperty("draws", e.draws);
                        o.addProperty("episodes", e.episodes);
                        o.addProperty("dmgDealt", e.dmgDealt);
                        o.addProperty("dmgTaken", e.dmgTaken);
                        o.addProperty("at", e.at);
                        arr.add(o);
                }
                root.add("entries", arr);
                Files.writeString(file(), GSON.toJson(root));
        }
}
