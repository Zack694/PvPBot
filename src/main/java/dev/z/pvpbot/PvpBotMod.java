package dev.z.pvpbot;

import dev.z.pvpbot.bot.FocusMode;
import dev.z.pvpbot.cmd.PvpBotCommands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PvPBot — a from-scratch, self-learning sword PvP AI.
 * Client mod: takes over the local player with input-level control
 * (virtual WASD + left click + real mouse events). No packets.
 */
public class PvpBotMod implements ClientModInitializer {

        public static final Logger LOGGER = LoggerFactory.getLogger("pvpbot");

        private static KeyBinding clickGuiKey;
        private static KeyBinding focusKey;

        @Override
        public void onInitializeClient() {
                PvpBot bot = PvpBot.init();
                LOGGER.info("PvPBot initialized — policy arch {} (~{} KB of weights), aim arch 64x64",
                                bot.dqn().qArchSummary(), paramKb());

                // v2.1.0: vision dataset recorder (auto-labeled player crops)
                dev.z.pvpbot.bot.VisionRecorder.get().init();

                // v2.0: crash recovery for Focus Mode FIRST — if the last session
                // died while focused, restore the user's video/audio settings now.
                FocusMode.recoverIfCrashed(MinecraftClient.getInstance());

                // ---- v2.0 PHASE 1 keybinds ----
                // 1.21.11: keybind categories are KeyBinding.Category records —
                // create our own so the Controls screen shows a "PvPBot" group.
                dev.z.pvpbot.ui.CategoryHolder.CATEGORY = KeyBinding.Category.create(
                                net.minecraft.util.Identifier.of("pvpbot", "main"));
                clickGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                                "key.pvpbot.clickgui", InputUtil.Type.KEYSYM,
                                GLFW.GLFW_KEY_RIGHT_CONTROL, dev.z.pvpbot.ui.CategoryHolder.CATEGORY));
                focusKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                                "key.pvpbot.focus", InputUtil.Type.KEYSYM,
                                GLFW.GLFW_KEY_UNKNOWN, dev.z.pvpbot.ui.CategoryHolder.CATEGORY));

                ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                        bot.controller().tick(mc);
                        // Right Control → ClickGUI (big Train/Stop + Human-Train/Stop buttons)
                        while (clickGuiKey.wasPressed()) {
                                if (mc.currentScreen == null && mc.player != null) {
                                        mc.setScreen(new dev.z.pvpbot.ui.PvpBotClickGui(null));
                                }
                        }
                        // Focus (Eco) Mode bind — user-configurable in Controls
                        while (focusKey.wasPressed()) {
                                FocusMode.toggle(mc);
                        }
                });

                // 60Hz+ aim: the aim loop runs every render frame, dt-compensated
                WorldRenderEvents.START_MAIN.register(ctx -> bot.controller().frameTick(MinecraftClient.getInstance()));

                // big-text round detection from chat/system lines too (title banner
                // is polled inside the controller tick)
                ClientReceiveMessageEvents.GAME.register((text, overlay) ->
                                bot.controller().maybeChatResult(text));

                // HUD, then the Focus Mode black overlay LAST so it covers everything
                HudRenderCallback.EVENT.register((context, tickCounter) -> {
                        bot.hud().render(context, MinecraftClient.getInstance());
                        if (FocusMode.isActive()) {
                                renderFocusOverlay(context, MinecraftClient.getInstance());
                        }
                });

                ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
                        // never leave virtual keys pressed across worlds
                        bot.controller().stop();
                });

                PvpBotCommands.register();
                LOGGER.info("PvPBot ready — Right Control opens the ClickGUI — /pvpbot train | human-train | pause | resume | start | stop | status | config | save | wipe | hud | explore | memory | focus | help");
        }

        private static void renderFocusOverlay(net.minecraft.client.gui.DrawContext ctx, MinecraftClient mc) {
                int w = mc.getWindow().getScaledWidth();
                int h = mc.getWindow().getScaledHeight();
                ctx.fill(0, 0, w, h, 0xFF000000);
                // faint one-line hint so the state is never a mystery
                ctx.drawText(mc.textRenderer, Text.literal("PvPBot Focus Mode — training continues (bind or Right Control > HOME to exit)"),
                                6, h - 12, 0xFF202820, false);
        }

        private static long paramKb() {
                int[] s = PvpBot.POLICY_ARCH;
                long p = 0;
                for (int i = 0; i + 1 < s.length; i++) p += (long) s[i] * s[i + 1] + s[i + 1];
                return p * 4 / 1024;
        }
}
