package dev.z.pvpbot.ui;

import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.text.Text;

/**
 * Cloth Config API screen (the "Advanced Config via Cloth Config" the user
 * asked for). This class is ONLY referenced when cloth-config2 is detected
 * on the mod loader — without it nothing here is ever class-loaded and the
 * built-in PvpBotConfigScreen is used instead.
 *
 * v1.0.10: EVERY entry now carries a detailed hover tooltip (user: "add
 * Detailed Descriptions when you Hover a settings Name in the config so I
 * can easily Know what Each setting does fully"). The tooltip texts live in
 * {@link BotTooltips} so the built-in screen shows the SAME descriptions.
 */
public final class ClothConfigBridge {

        public static net.minecraft.client.gui.screen.Screen build(net.minecraft.client.gui.screen.Screen parent) {
                BotConfig cfg = PvpBot.get().config();
                ConfigBuilder b = ConfigBuilder.create()
                                .setParentScreen(parent)
                                .setTitle(Text.literal("PvPBot Advanced Config"));
                b.setSavingRunnable(cfg::save);
                ConfigEntryBuilder eb = b.entryBuilder();

                ConfigCategory combat = b.getOrCreateCategory(Text.literal("Combat"));
                combat.addEntry(eb.startBooleanToggle(Text.literal("TriggerBot (click on crosshair)"), cfg.triggerBot)
                                .setTooltip(BotTooltips.TRIGGERBOT)
                                .setSaveConsumer(v -> cfg.triggerBot = v).build());
                combat.addEntry(eb.startFloatField(Text.literal("Attack band min (0-1)"), cfg.attackCooldownMin)
                                .setTooltip(BotTooltips.BAND_MIN)
                                .setMin(0.5f).setMax(1.0f).setSaveConsumer(v -> cfg.attackCooldownMin = v).build());
                combat.addEntry(eb.startFloatField(Text.literal("Attack band max (0-1)"), cfg.attackCooldownMax)
                                .setTooltip(BotTooltips.BAND_MAX)
                                .setMin(0.5f).setMax(1.0f).setSaveConsumer(v -> cfg.attackCooldownMax = v).build());
                combat.addEntry(eb.startBooleanToggle(Text.literal("WTap (S-tap) enabled"), cfg.wtapEnabled)
                                .setTooltip(BotTooltips.WTAP_ENABLED)
                                .setSaveConsumer(v -> cfg.wtapEnabled = v).build());
                combat.addEntry(eb.startBooleanToggle(Text.literal("Sprint hits only (never sweep hits)"), cfg.sprintHitOnly)
                                .setTooltip(BotTooltips.SPRINT_HIT_ONLY)
                                .setSaveConsumer(v -> cfg.sprintHitOnly = v).build());
                combat.addEntry(eb.startFloatField(Text.literal("WTap chance (0-1, 1.0 = always)"), cfg.wtapChance)
                                .setTooltip(BotTooltips.WTAP_CHANCE)
                                .setMin(0f).setMax(1f).setSaveConsumer(v -> cfg.wtapChance = v).build());
                combat.addEntry(eb.startFloatField(Text.literal("WTap pure-S share"), cfg.wtapPureWChance)
                                .setTooltip(BotTooltips.WTAP_PURE_S)
                                .setMin(0f).setMax(1f).setSaveConsumer(v -> cfg.wtapPureWChance = v).build());
                combat.addEntry(eb.startIntField(Text.literal("WTap S-hold min ms"), cfg.wtapMinMs)
                                .setTooltip(BotTooltips.WTAP_MIN_MS)
                                .setMin(100).setMax(1000).setSaveConsumer(v -> cfg.wtapMinMs = v).build());
                combat.addEntry(eb.startIntField(Text.literal("WTap S-hold max ms"), cfg.wtapMaxMs)
                                .setTooltip(BotTooltips.WTAP_MAX_MS)
                                .setMin(100).setMax(1200).setSaveConsumer(v -> cfg.wtapMaxMs = v).build());
                combat.addEntry(eb.startIntField(Text.literal("Jump reset min ms"), cfg.jumpResetMinMs)
                                .setTooltip(BotTooltips.JUMP_RESET_MIN_MS)
                                .setMin(60).setMax(300).setSaveConsumer(v -> cfg.jumpResetMinMs = v).build());
                combat.addEntry(eb.startIntField(Text.literal("Jump reset max ms"), cfg.jumpResetMaxMs)
                                .setTooltip(BotTooltips.JUMP_RESET_MAX_MS)
                                .setMin(60).setMax(400).setSaveConsumer(v -> cfg.jumpResetMaxMs = v).build());
                combat.addEntry(eb.startBooleanToggle(Text.literal("Jump reset"), cfg.jumpResetEnabled)
                                .setTooltip(BotTooltips.JUMP_RESET)
                                .setSaveConsumer(v -> cfg.jumpResetEnabled = v).build());
                combat.addEntry(eb.startFloatField(Text.literal("Sneak hit chance (1.0 = EVERY hit)"), cfg.sneakHitChance)
                                .setTooltip(BotTooltips.SNEAK_HIT)
                                .setMin(0f).setMax(3f).setSaveConsumer(v -> cfg.sneakHitChance = v).build());
                combat.addEntry(eb.startFloatField(Text.literal("Sneak+jump hit chance (1.0 = every sneak)"), cfg.sneakJumpHitChance)
                                .setTooltip(BotTooltips.SNEAK_JUMP_HIT)
                                .setMin(0f).setMax(3f).setSaveConsumer(v -> cfg.sneakJumpHitChance = v).build());
                combat.addEntry(eb.startFloatField(Text.literal("Crit attempt chance (1.0 = auto-crit on cooldown)"), cfg.critAttemptChance)
                                .setTooltip(BotTooltips.CRIT_CHANCE)
                                .setMin(0f).setMax(3f).setSaveConsumer(v -> cfg.critAttemptChance = v).build());
                combat.addEntry(eb.startFloatField(Text.literal("MidAir hit chance (1.0 = auto on cooldown)"), cfg.midAirChance)
                                .setTooltip(BotTooltips.MIDAIR_CHANCE)
                                .setMin(0f).setMax(3f).setSaveConsumer(v -> cfg.midAirChance = v).build());
                combat.addEntry(eb.startFloatField(Text.literal("Backoff below (0-3 blocks)"), cfg.tooCloseDist)
                                .setTooltip(BotTooltips.BACKOFF_BELOW)
                                .setMin(0f).setMax(3f).setSaveConsumer(v -> cfg.tooCloseDist = v).build());
                combat.addEntry(eb.startFloatField(Text.literal("Backoff release (0-3 blocks)"), cfg.backoffReleaseDist)
                                .setTooltip(BotTooltips.BACKOFF_RELEASE)
                                .setMin(0f).setMax(3f).setSaveConsumer(v -> cfg.backoffReleaseDist = v).build());
                combat.addEntry(eb.startIntField(Text.literal("Backoff max duration (ticks)"), cfg.maxBackoffTicks)
                                .setTooltip(BotTooltips.BACKOFF_MAX)
                                .setMin(6).setMax(100).setSaveConsumer(v -> cfg.maxBackoffTicks = v).build());
                combat.addEntry(eb.startBooleanToggle(Text.literal("Strafe discipline (legacy, off = NN moves)"), cfg.strafeDiscipline)
                                .setTooltip(BotTooltips.STRAFE_DISCIPLINE)
                                .setSaveConsumer(v -> cfg.strafeDiscipline = v).build());

                ConfigCategory aim = b.getOrCreateCategory(Text.literal("Aim"));
                aim.addEntry(eb.startIntSlider(Text.literal("Aim zone (0 Head / 1 Eyes / 2 Neck / 3 Chest)"), cfg.aimZone, 0, 3)
                                .setTooltip(BotTooltips.AIM_ZONE)
                                .setSaveConsumer(v -> cfg.aimZone = v).build());
                aim.addEntry(eb.startBooleanToggle(Text.literal("Aim assist blend"), cfg.aimAssistEnabled)
                                .setTooltip(BotTooltips.AIM_ASSIST)
                                .setSaveConsumer(v -> cfg.aimAssistEnabled = v).build());
                aim.addEntry(eb.startFloatField(Text.literal("Aim assist strength (0-3, clamped 0-1)"), cfg.aimAssistStrength)
                                .setTooltip(BotTooltips.AIM_ASSIST_STRENGTH)
                                .setMin(0f).setMax(3f).setSaveConsumer(v -> cfg.aimAssistStrength = v).build());
                aim.addEntry(eb.startBooleanToggle(Text.literal("Aim prediction (velocity + acceleration)"), cfg.aimPredict)
                                .setTooltip(BotTooltips.AIM_PREDICT)
                                .setSaveConsumer(v -> cfg.aimPredict = v).build());
                aim.addEntry(eb.startBooleanToggle(Text.literal("Frame aim (60Hz+)"), cfg.frameAim)
                                .setTooltip(BotTooltips.FRAME_AIM)
                                .setSaveConsumer(v -> cfg.frameAim = v).build());
                aim.addEntry(eb.startFloatField(Text.literal("Aim smooth min (0-3, sane 0-1)"), cfg.aimSmoothMin)
                                .setTooltip(BotTooltips.SMOOTH_MIN)
                                .setMin(0f).setMax(3f).setSaveConsumer(v -> cfg.aimSmoothMin = Math.min(0.95f, v)).build());
                aim.addEntry(eb.startFloatField(Text.literal("Aim smooth max (0-3, sane 0-1)"), cfg.aimSmoothMax)
                                .setTooltip(BotTooltips.SMOOTH_MAX)
                                .setMin(0f).setMax(3f).setSaveConsumer(v -> cfg.aimSmoothMax = Math.min(0.98f, v)).build());
                aim.addEntry(eb.startIntField(Text.literal("Aim lead ticks (0-10)"), cfg.aimLeadTicks)
                                .setTooltip(BotTooltips.AIM_LEAD)
                                .setMin(0).setMax(10).setSaveConsumer(v -> cfg.aimLeadTicks = v).build());
                aim.addEntry(eb.startFloatField(Text.literal("Turn cap deg/tick (0-180)"), cfg.aimMaxTurnDeg)
                                .setTooltip(BotTooltips.TURN_CAP)
                                .setMin(5f).setMax(180f).setSaveConsumer(v -> cfg.aimMaxTurnDeg = v).build());
                aim.addEntry(eb.startFloatField(Text.literal("Aim noise deg (0-3)"), cfg.aimNoiseDeg)
                                .setTooltip(BotTooltips.AIM_NOISE)
                                .setMin(0f).setMax(3f).setSaveConsumer(v -> cfg.aimNoiseDeg = v).build());

                ConfigCategory mind = b.getOrCreateCategory(Text.literal("Decision Mind"));
                mind.addEntry(eb.startBooleanToggle(Text.literal("Decision thinking (intents)"), cfg.decisionMindEnabled)
                                .setTooltip(BotTooltips.DECISION_MIND)
                                .setSaveConsumer(v -> cfg.decisionMindEnabled = v).build());
                mind.addEntry(eb.startFloatField(Text.literal("Intent bias on DQN (0-1)"), cfg.decisionBias)
                                .setTooltip(BotTooltips.MIND_BIAS)
                                .setMin(0f).setMax(1f).setSaveConsumer(v -> cfg.decisionBias = v).build());
                mind.addEntry(eb.startFloatField(Text.literal("Innovation (try new strats) 0-0.3"), cfg.innovationChance)
                                .setTooltip(BotTooltips.INNOVATION)
                                .setMin(0f).setMax(0.3f).setSaveConsumer(v -> cfg.innovationChance = v).build());
                mind.addEntry(eb.startIntField(Text.literal("Deliberate every N ticks"), cfg.mindDeliberateTicks)
                                .setTooltip(BotTooltips.DELIBERATE_TICKS)
                                .setMin(2).setMax(40).setSaveConsumer(v -> cfg.mindDeliberateTicks = v).build());
                mind.addEntry(eb.startBooleanToggle(Text.literal("Model decides techniques (sneak/jump/tap votes)"), cfg.modelTechniques)
                                .setTooltip(BotTooltips.MODEL_TECHNIQUES)
                                .setSaveConsumer(v -> cfg.modelTechniques = v).build());
                mind.addEntry(eb.startBooleanToggle(Text.literal("Combo strafe (orbit while comboing)"), cfg.comboStrafe)
                                .setTooltip(BotTooltips.COMBO_STRAFE)
                                .setSaveConsumer(v -> cfg.comboStrafe = v).build());
                mind.addEntry(eb.startBooleanToggle(Text.literal("Adaptive style (per-opponent profiles)"), cfg.adaptiveStyle)
                                .setTooltip(BotTooltips.ADAPTIVE_STYLE)
                                .setSaveConsumer(v -> cfg.adaptiveStyle = v).build());

                ConfigCategory learn = b.getOrCreateCategory(Text.literal("Learning"));
                learn.addEntry(eb.startBooleanToggle(Text.literal("Imitation learning (DQfD)"), cfg.imitationEnabled)
                                .setTooltip(BotTooltips.IMITATION)
                                .setSaveConsumer(v -> cfg.imitationEnabled = v).build());
                learn.addEntry(eb.startFloatField(Text.literal("Imitation batch ratio"), cfg.imitationRatio)
                                .setTooltip(BotTooltips.IMITATION_RATIO)
                                .setMin(0f).setMax(1f).setSaveConsumer(v -> cfg.imitationRatio = Math.min(0.6f, v)).build());
                learn.addEntry(eb.startFloatField(Text.literal("Kill reward (0-300)"), cfg.winReward)
                                .setTooltip(BotTooltips.KILL_REWARD)
                                .setMin(0f).setMax(300f).setSaveConsumer(v -> cfg.winReward = v).build());
                learn.addEntry(eb.startFloatField(Text.literal("Loss penalty (0-300)"), cfg.lossReward)
                                .setTooltip(BotTooltips.LOSS_REWARD)
                                .setMin(-300f).setMax(0f).setSaveConsumer(v -> cfg.lossReward = v).build());
                learn.addEntry(eb.startBooleanToggle(Text.literal("Round text detection"), cfg.roundTextDetection)
                                .setTooltip(BotTooltips.ROUND_TEXT)
                                .setSaveConsumer(v -> cfg.roundTextDetection = v).build());
                learn.addEntry(eb.startIntField(Text.literal("Round debounce ms"), cfg.roundDebounceMs)
                                .setTooltip(BotTooltips.ROUND_DEBOUNCE)
                                .setMin(500).setMax(30000).setSaveConsumer(v -> cfg.roundDebounceMs = v).build());

                ConfigCategory hud = b.getOrCreateCategory(Text.literal("HUD"));
                hud.addEntry(eb.startBooleanToggle(Text.literal("HUD"), cfg.hudEnabled)
                                .setTooltip(BotTooltips.HUD)
                                .setSaveConsumer(v -> cfg.hudEnabled = v).build());
                hud.addEntry(eb.startBooleanToggle(Text.literal("Keystrokes (WASD+LMB+Space+Shift)"), cfg.keystrokesEnabled)
                                .setTooltip(BotTooltips.KEYSTROKES)
                                .setSaveConsumer(v -> cfg.keystrokesEnabled = v).build());
                hud.addEntry(eb.startBooleanToggle(Text.literal("Thought line (mind's deliberation)"), cfg.thoughtHudEnabled)
                                .setTooltip(BotTooltips.THOUGHT_HUD)
                                .setSaveConsumer(v -> cfg.thoughtHudEnabled = v).build());
                hud.addEntry(eb.startBooleanToggle(Text.literal("Trade log"), cfg.tradeLogEnabled)
                                .setTooltip(BotTooltips.TRADE_LOG)
                                .setSaveConsumer(v -> cfg.tradeLogEnabled = v).build());
                hud.addEntry(eb.startBooleanToggle(Text.literal("Training stats"), cfg.trainingStatsEnabled)
                                .setTooltip(BotTooltips.TRAINING_STATS)
                                .setSaveConsumer(v -> cfg.trainingStatsEnabled = v).build());
                hud.addEntry(eb.startBooleanToggle(Text.literal("Combo meter"), cfg.comboMeterEnabled)
                                .setTooltip(BotTooltips.COMBO_METER)
                                .setSaveConsumer(v -> cfg.comboMeterEnabled = v).build());

                return b.build();
        }

        private ClothConfigBridge() {}
}
