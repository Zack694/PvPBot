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
        private static final String[] WIN_KEYS = {
                        "victory", "you win", "you won", "winner", "won the duel", "won the round",
                        "wins the duel", "wins the round", "you are the winner", "1st place", "you placed 1st"
        };
        private static final String[] LOSS_KEYS = {
                        "round lost", "you lose", "you lost", "defeat", "lost the duel", "lost the round",
                        "you died", "eliminated", "game over", "you placed 2nd", "2nd place"
        };

        private RoundWatcher() {}

        /** Called every client tick while the bot is active: read the title banner. */
        public static void poll(MinecraftClient mc, BotController controller) {
                if (!controller.inEpisode()) return;
                if (mc.inGameHud instanceof InGameHudAccessor acc) {
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
                matchText(s, controller);
        }

        private static void matchText(String s, BotController controller) {
                for (String k : WIN_KEYS) {
                        if (s.contains(k)) {
                                controller.settleRound("WIN");
                                return;
                        }
                }
                for (String k : LOSS_KEYS) {
                        if (s.contains(k)) {
                                controller.settleRound("LOSS");
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
