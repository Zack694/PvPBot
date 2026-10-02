package dev.z.pvpbot.bot;

import dev.z.pvpbot.mixin.InGameHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;

/**
 * Round-result detection from the BIG TEXT ON THE SCREEN (title / subtitle)
 * and from short system chat lines — exactly what the user asked for:
 * "ROUND LOST" or similar ⇒ LOSS, "VICTORY" or similar ⇒ WIN.
 *
 * Results are routed through BotController.settleRound() which enforces the
 * 5-second debounce: the FIRST result inside any 5s window settles the round;
 * every duplicate ("ROUND LOST" spam, death + text, text + despawn) is
 * cancelled.
 */
public final class RoundWatcher {

        // matched against lowercased plain text, contains-style
        // v2.3: first-person phrases only. "winner" / "wins the round" matched
        // lines about the OPPONENT ("Steve wins the round" = our LOSS) and
        // "defeat" matched "You defeated Steve" (= our WIN). LOSS is checked
        // first so "You lost! Steve wins the duel" can no longer read as a WIN.
        private static final String[] WIN_KEYS = {
                        "victory", "you win", "you won", "won the duel", "won the round",
                        "you are the winner", "1st place", "you placed 1st", "you defeated"
        };
        private static final String[] LOSS_KEYS = {
                        "round lost", "you lose", "you lost", "defeated by", "defeat!", "lost the duel", "lost the round",
                        "you died", "you were eliminated", "game over", "you placed 2nd", "2nd place"
        };

        // v2.3: titles stay on screen for fade-in + stay + fade-out ticks; a long
        // title outlived the 5 s debounce and settled the NEXT round as well.
        // A title only counts once until it changes.
        private static String lastTitle = "";

        private RoundWatcher() {}

        /** Called every client tick while the bot is active: read the title banner. */
        public static void poll(MinecraftClient mc, BotController controller) {
                if (!controller.inEpisode()) return;
                if (mc.inGameHud instanceof InGameHudAccessor acc) {
                        if (acc.pvpbot$getTitle() == null && acc.pvpbot$getSubtitle() == null) {
                                lastTitle = ""; // banner gone: the next one counts again
                        }
                        match(acc.pvpbot$getTitle(), controller);
                        if (!controller.inEpisode()) return; // settled by the title already
                        match(acc.pvpbot$getSubtitle(), controller);
                }
        }

        /** Chat/system line hook (registered in PvpBotMod). */
        public static void onChat(Text message, BotController controller) {
                if (!controller.inEpisode()) return;
                String s = plain(message);
                if (s.length() > 100) return; // big-text rules, not regular chat
                matchText(s, controller);
        }

        private static void match(Text t, BotController controller) {
                if (t == null) return;
                String s = plain(t);
                if (s.isEmpty() || s.length() > 60) return;
                if (s.equals(lastTitle)) return; // same banner still showing
                lastTitle = s;
                matchText(s, controller);
        }

        private static void matchText(String s, BotController controller) {
                for (String k : LOSS_KEYS) {
                        if (s.contains(k)) {
                                controller.settleRound("LOSS");
                                return;
                        }
                }
                for (String k : WIN_KEYS) {
                        if (s.contains(k)) {
                                controller.settleRound("WIN");
                                return;
                        }
                }
        }

        private static String plain(Text t) {
                try {
                        return t.getString().toLowerCase().trim();
                } catch (Throwable ignored) {
                        return "";
                }
        }
}
