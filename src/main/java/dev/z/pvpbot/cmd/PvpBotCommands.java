package dev.z.pvpbot.cmd;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.z.pvpbot.PvpBot;
import dev.z.pvpbot.ml.League;
import dev.z.pvpbot.ml.ModelStore;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/** /pvpbot client-side commands — work on any server, nothing is sent to it. */
public final class PvpBotCommands {

        public static void register() {
                ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                                ClientCommandManager.literal("pvpbot")
                                                .then(ClientCommandManager.literal("start").executes(ctx -> {
                                                        PvpBot.get().controller().start();
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("train").executes(ctx -> {
                                                        PvpBot.get().controller().startTraining();
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("human-train").executes(ctx -> {
                                                        PvpBot.get().controller().startHumanTrain();
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("eval")
                                                                .executes(ctx -> {
                                                                        PvpBot.get().controller().startEval(PvpBot.get().config().evalEpisodes);
                                                                        return 1;
                                                                })
                                                                .then(ClientCommandManager.argument("episodes", IntegerArgumentType.integer(1, 500))
                                                                                .executes(ctx -> {
                                                                                        PvpBot.get().controller().startEval(IntegerArgumentType.getInteger(ctx, "episodes"));
                                                                                        return 1;
                                                                                })))
                                                .then(ClientCommandManager.literal("league").executes(ctx -> {
                                                        var list = League.top();
                                                        if (list.isEmpty()) {
                                                                send(ctx.getSource(), "league is empty — /pvpbot eval <n> to rank the current brain");
                                                        } else {
                                                                send(ctx.getSource(), "CHECKPOINT LEAGUE (models/league.json):");
                                                                int rank = 1;
                                                                for (var e : list) {
                                                                        send(ctx.getSource(), e.line(rank++));
                                                                }
                                                        }
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("pause").executes(ctx -> {
                                                        PvpBot.get().controller().pause();
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("resume").executes(ctx -> {
                                                        PvpBot.get().controller().resume();
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("continue").executes(ctx -> {
                                                        PvpBot.get().controller().resume();
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                                                        PvpBot.get().controller().stop();
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                                                        PvpBot.get().controller().toggle();
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("status").executes(ctx -> {
                                                        var c = PvpBot.get().controller();
                                                        var src = ctx.getSource();
                                                        String mode = c.isHumanTraining() ? "HUMAN-TRAIN (watching)"
                                                                        : c.isTrainingSession() ? "TRAINING SESSION" : "engage";
                                                        String pause = c.isPaused() ? "  [PAUSED — /pvpbot resume]" : "";
                                                        send(src, "state: " + c.state() + "  mode: " + mode + pause);
                                                        send(src, String.format("episodes %d  W/L/D %d/%d/%d  phase %s",
                                                                        c.episodesDone, c.wins, c.losses, c.draws, c.curriculumPhase()));
                                                        if (c.isTrainingSession()) {
                                                                send(src, c.sessionSummary());
                                                        }
                                                        send(src, String.format("epsilon %.3f  lr %.4f  buffer %d/%d  steps %d",
                                                                        c.epsilon(), c.currentLr(), PvpBot.get().dqn().bufferSize(),
                                                                        PvpBot.get().dqn().bufferCapacity(), PvpBot.get().dqn().getTrainSteps()));
                                                        var t = c.selector().target();
                                                        send(src, "target: " + (t != null ? t.getName().getString() : "none"));
                                                        send(src, "mind: " + c.mind.intent().tag + (c.mind.thought().isEmpty() ? ""
                                                                        : " — \"" + c.mind.thought() + "\""));
                                                        send(src, "memory: " + (c.memory.opponentName.isEmpty() ? "empty" : c.memory.summary()));
                                                        send(src, "style: " + c.adapt.summary());
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("save").executes(ctx -> {
                                                        PvpBot.get().saveModelAsync();
                                                        send(ctx.getSource(), "model save queued (background) — config/pvpbot/model/");
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("model")
                                                                .then(ClientCommandManager.literal("save")
                                                                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                                                                                .executes(ctx -> {
                                                                                                        String name = StringArgumentType.getString(ctx, "name");
                                                                                                        var src = ctx.getSource();
                                                                                                        final PvpBot bot = PvpBot.get();
                                                                                                        PvpBot.worker().execute(() -> {
                                                                                                                try {
                                                                                                                        String fn = ModelStore.saveSnapshot(name, bot);
                                                                                                                        send(src, "snapshot saved → config/pvpbot/models/" + fn + " (hot-swappable now)");
                                                                                                                } catch (Exception e) {
                                                                                                                        send(src, "snapshot failed: " + e.getMessage());
                                                                                                                }
                                                                                                        });
                                                                                                        send(src, "saving snapshot \"" + name + "\" (background)…");
                                                                                                        return 1;
                                                                                                })))
                                                                .then(ClientCommandManager.literal("list").executes(ctx -> {
                                                                        var src = ctx.getSource();
                                                                        String active = PvpBot.get().activeModelName();
                                                                        send(src, "active: " + (active != null ? active : "autosave (model/policy.json)"));
                                                                        var list = ModelStore.list();
                                                                        if (list.isEmpty()) {
                                                                                send(src, "models/ is empty — /pvpbot model save <name> to snapshot the current brain");
                                                                        } else {
                                                                                for (var m : list) {
                                                                                        send(src, (m.fileName.equals(active) ? "* " : "  ") + m.statsLine());
                                                                                }
                                                                        }
                                                                        return 1;
                                                                }))
                                                                .then(ClientCommandManager.literal("load")
                                                                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                                                                                .executes(ctx -> {
                                                                                                        String name = StringArgumentType.getString(ctx, "name");
                                                                                                        var src = ctx.getSource();
                                                                                                        final PvpBot bot = PvpBot.get();
                                                                                                        // exact file name, or stem with .pbm/.json suffixes
                                                                                                        java.nio.file.Path dir = ModelStore.dir();
                                                                                                        java.nio.file.Path p = null;
                                                                                                        for (String cand : new String[]{name, name + ".pbm", name + ".json"}) {
                                                                                                                java.nio.file.Path c = dir.resolve(cand);
                                                                                                                if (java.nio.file.Files.exists(c)) { p = c; break; }
                                                                                                        }
                                                                                                        final java.nio.file.Path file = p;
                                                                                                        if (file == null) {
                                                                                                                send(src, "no model named \"" + name + "\" in config/pvpbot/models/ — /pvpbot model list");
                                                                                                                return 1;
                                                                                                        }
                                                                                                        PvpBot.worker().execute(() -> {
                                                                                                                try {
                                                                                                                        String d = ModelStore.loadInto(file, bot);
                                                                                                                        send(src, "hot-swapped \"" + d + "\" into the live brain (replay + demos kept)");
                                                                                                                } catch (Exception e) {
                                                                                                                        send(src, "load failed: " + e.getMessage());
                                                                                                                }
                                                                                                        });
                                                                                                        send(src, "loading " + file.getFileName() + " (background)…");
                                                                                                        return 1;
                                                                                                }))))
                                                .then(ClientCommandManager.literal("v2")
                                                                .executes(ctx -> {
                                                                        send(ctx.getSource(), PvpBot.get().controller().v2StatusLine());
                                                                        return 1;
                                                                })
                                                                .then(ClientCommandManager.literal("on").executes(ctx -> {
                                                                        PvpBot.get().controller().setPureMode(true);
                                                                        send(ctx.getSource(), "PURE MODE — the v2 four-head brain (move/flags/aim/click) is now the only authority. Physics gates stay.");
                                                                        return 1;
                                                                }))
                                                                .then(ClientCommandManager.literal("off").executes(ctx -> {
                                                                        PvpBot.get().controller().setPureMode(false);
                                                                        send(ctx.getSource(), "Pure mode off — v1 stack (TriggerBot + techniques) restored.");
                                                                        return 1;
                                                                }))
                                                                .then(ClientCommandManager.literal("save")
                                                                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                                                                                .executes(ctx -> {
                                                                                                        String name = StringArgumentType.getString(ctx, "name");
                                                                                                        var src = ctx.getSource();
                                                                                                        final PvpBot bot = PvpBot.get();
                                                                                                        PvpBot.worker().execute(() -> {
                                                                                                                try {
                                                                                                                        String fn = ModelStore.saveSnapshotV2(name, bot);
                                                                                                                        send(src, "v2 snapshot saved → config/pvpbot/models/" + fn + " (hot-swappable now)");
                                                                                                                } catch (Exception e) {
                                                                                                                        send(src, "v2 snapshot failed: " + e.getMessage());
                                                                                                                }
                                                                                                        });
                                                                                                        send(src, "saving v2 snapshot \"" + name + "\" (background)…");
                                                                                                        return 1;
                                                                                                })))
                                                                // v2.1.0 TRAINING-WHEELS GATES — the aim head and the sneak
                                                                // muscle stay benched until the user explicitly promotes
                                                                // them AND the brain has proven training volume.
                                                                .then(ClientCommandManager.literal("aimhead")
                                                                                .then(ClientCommandManager.literal("on").executes(ctx -> {
                                                                                        PvpBot.get().config().pureAimHead = true;
                                                                                        PvpBot.get().config().save();
                                                                                        send(ctx.getSource(), "aim head opt-in ON — it joins the blend ONLY once it has " + PvpBot.get().config().pureAimHeadMinSteps
                                                                                                        + " training steps AND aimLoss <= " + PvpBot.get().config().pureAimHeadMaxLoss + " (check /pvpbot v2).");
                                                                                        return 1;
                                                                                }))
                                                                                .then(ClientCommandManager.literal("off").executes(ctx -> {
                                                                                        PvpBot.get().config().pureAimHead = false;
                                                                                        PvpBot.get().config().save();
                                                                                        send(ctx.getSource(), "aim head back on the bench — the proven tracker owns the aim (first-model law).");
                                                                                        return 1;
                                                                                })))
                                                                .then(ClientCommandManager.literal("sneak")
                                                                                .then(ClientCommandManager.literal("on").executes(ctx -> {
                                                                                        PvpBot.get().config().pureSneak = true;
                                                                                        PvpBot.get().config().save();
                                                                                        send(ctx.getSource(), "sneak muscle opt-in ON — fires only after " + PvpBot.get().config().pureSneakMinSteps
                                                                                                        + " training steps, a decisive head margin, grounded + in range, max 8 ticks then cooldown.");
                                                                                        return 1;
                                                                                }))
                                                                                .then(ClientCommandManager.literal("off").executes(ctx -> {
                                                                                        PvpBot.get().config().pureSneak = false;
                                                                                        PvpBot.get().config().save();
                                                                                        send(ctx.getSource(), "sneak muscle HARD-DISABLED — the bot can never hold shift again until you opt back in.");
                                                                                        return 1;
                                                                                })))
                                                .then(ClientCommandManager.literal("vision")
                                                                .executes(ctx -> {
                                                                        send(ctx.getSource(), dev.z.pvpbot.bot.VisionRecorder.get().status());
                                                                        return 1;
                                                                })
                                                                .then(ClientCommandManager.literal("on").executes(ctx -> {
                                                                        dev.z.pvpbot.bot.VisionRecorder.setRecording(true);
                                                                        send(ctx.getSource(), "vision recorder ON — auto-labeled player crops + negatives save to <game dir>/pvpbot-vision/ (manifest.jsonl has the labels).");
                                                                        return 1;
                                                                }))
                                                                .then(ClientCommandManager.literal("off").executes(ctx -> {
                                                                        dev.z.pvpbot.bot.VisionRecorder.setRecording(false);
                                                                        send(ctx.getSource(), "vision recorder off.");
                                                                        return 1;
                                                                }))))
                                                // v2.2.0 — IMITATION LEARNING FROM VIDEO (the "IL Model"):
                                                // load the extractor's *.jsonl sessions into BOTH brains'
                                                // expert rings, train bursts on them, full status reporting.
                                                // Data folder: .minecraft/config/pvpbot/il/
                                                .then(ClientCommandManager.literal("il")
                                                                .executes(ctx -> {
                                                                        send(ctx.getSource(), dev.z.pvpbot.ml.ILStore.get().statusLine());
                                                                        return 1;
                                                                })
                                                                .then(ClientCommandManager.literal("status").executes(ctx -> {
                                                                        send(ctx.getSource(), dev.z.pvpbot.ml.ILStore.get().statusLine());
                                                                        return 1;
                                                                }))
                                                                .then(ClientCommandManager.literal("load").executes(ctx -> {
                                                                        var src = ctx.getSource();
                                                                        final PvpBot bot = PvpBot.get();
                                                                        send(src, "scanning config/pvpbot/il/ for extractor sessions…");
                                                                        PvpBot.worker().execute(() -> send(src, dev.z.pvpbot.ml.ILStore.get().loadAll(bot)));
                                                                        return 1;
                                                                }))
                                                                .then(ClientCommandManager.literal("train")
                                                                                .executes(ctx -> {
                                                                                        dev.z.pvpbot.ml.ILStore il = dev.z.pvpbot.ml.ILStore.get();
                                                                                        if (!il.isLoaded()) {
                                                                                                send(ctx.getSource(), il.statusLine() + "  — run /pvpbot il load first.");
                                                                                        } else {
                                                                                                il.train(PvpBot.get(), 60);
                                                                                                send(ctx.getSource(), "IL training: 60 bursts on the background worker (demo-heavy ratio 0.9). Status: " + il.statusLine());
                                                                                        }
                                                                                        return 1;
                                                                                })
                                                                                .then(ClientCommandManager.argument("bursts", IntegerArgumentType.integer(1, 400))
                                                                                                .executes(ctx -> {
                                                                                                        int bursts = IntegerArgumentType.getInteger(ctx, "bursts");
                                                                                                        dev.z.pvpbot.ml.ILStore il = dev.z.pvpbot.ml.ILStore.get();
                                                                                                        if (!il.isLoaded()) {
                                                                                                                send(ctx.getSource(), il.statusLine() + "  — run /pvpbot il load first.");
                                                                                                        } else {
                                                                                                                il.train(PvpBot.get(), bursts);
                                                                                                                send(ctx.getSource(), "IL training: " + bursts + " bursts queued. " + il.statusLine());
                                                                                                        }
                                                                                                        return 1;
                                                                                                })))
                                                                .then(ClientCommandManager.literal("clear").executes(ctx -> {
                                                                        dev.z.pvpbot.ml.ILStore.get().clear(PvpBot.get());
                                                                        send(ctx.getSource(), "IL expert demos cleared from both brains (trained weights untouched). Re-load with /pvpbot il load.");
                                                                        return 1;
                                                                })))
                                                // v2.2.0 — the anti-wobble master dial (same slider the config
                                                // screen shows): 0 = raw tracking, 1 = maximum calm.
                                                .then(ClientCommandManager.literal("antiwobble")
                                                                .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0f, 1f))
                                                                                .executes(ctx -> {
                                                                                        float v = FloatArgumentType.getFloat(ctx, "value");
                                                                                        PvpBot.get().config().antiWobble = v;
                                                                                        PvpBot.get().config().save();
                                                                                        send(ctx.getSource(), String.format("anti-wobble set to %.2f — error EMA, deadzone, wander sway, smoothing rhythm and the oscillation damper all scale with it.", v));
                                                                                        return 1;
                                                                                })))
                                                // v2.2.0 — PURE ATTACK LAW toggle: the pure brain clicks the
                                                // instant the crosshair can hit (band + governors still apply).
                                                .then(ClientCommandManager.literal("pureattack")
                                                                .then(ClientCommandManager.literal("on").executes(ctx -> {
                                                                        PvpBot.get().config().pureImmediateAttack = true;
                                                                        PvpBot.get().config().save();
                                                                        send(ctx.getSource(), "pure immediate attack ON — the pure brain clicks the moment the crosshair is on the hitbox (hard band + governors still pace it). No TriggerBot needed.");
                                                                        return 1;
                                                                }))
                                                                .then(ClientCommandManager.literal("off").executes(ctx -> {
                                                                        PvpBot.get().config().pureImmediateAttack = false;
                                                                        PvpBot.get().config().save();
                                                                        send(ctx.getSource(), "pure immediate attack OFF — the trained click head is the intent authority again (mature heads only).");
                                                                        return 1;
                                                                })))
                                                .then(ClientCommandManager.literal("wipe").executes(ctx -> {
                                                        PvpBot.wipe();
                                                        send(ctx.getSource(), "brain wiped — fresh network loaded. Training starts from zero.");
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("hud")
                                                                .executes(ctx -> {
                                                                        // v1.0.11: /pvpbot hud opens the layout editor
                                                                        var mc = net.minecraft.client.MinecraftClient.getInstance();
                                                                        mc.send(() -> mc.setScreen(new dev.z.pvpbot.ui.PvpBotHudEditScreen(null)));
                                                                        send(ctx.getSource(), "HUD layout editor open — drag to move, scroll to resize.");
                                                                        return 1;
                                                                })
                                                                .then(ClientCommandManager.literal("on").executes(ctx -> {
                                                                        PvpBot.get().config().hudEnabled = true;
                                                                        PvpBot.get().config().save();
                                                                        return 1;
                                                                })).then(ClientCommandManager.literal("off").executes(ctx -> {
                                                                        PvpBot.get().config().hudEnabled = false;
                                                                        PvpBot.get().config().save();
                                                                        return 1;
                                                                }))
                                                                .then(ClientCommandManager.literal("edit").executes(ctx -> {
                                                                        var mc = net.minecraft.client.MinecraftClient.getInstance();
                                                                        mc.send(() -> mc.setScreen(new dev.z.pvpbot.ui.PvpBotHudEditScreen(null)));
                                                                        send(ctx.getSource(), "HUD layout editor open — drag to move, scroll to resize.");
                                                                        return 1;
                                                                })))
                                                .then(ClientCommandManager.literal("explore")
                                                                .then(ClientCommandManager.argument("epsilon", FloatArgumentType.floatArg(0f, 1f)).executes(ctx -> {
                                                                        float e = FloatArgumentType.getFloat(ctx, "epsilon");
                                                                        PvpBot.get().config().epsilonOverride = e;
                                                                        send(ctx.getSource(), String.format("epsilon override set to %.2f (use /pvpbot explore auto to clear)", e));
                                                                        return 1;
                                                                }))
                                                                .then(ClientCommandManager.literal("auto").executes(ctx -> {
                                                                        PvpBot.get().config().epsilonOverride = null;
                                                                        send(ctx.getSource(), "epsilon back to curriculum schedule");
                                                                        return 1;
                                                                })))
                                                .then(ClientCommandManager.literal("memory").executes(ctx -> {
                                                        var c = PvpBot.get().controller();
                                                        send(ctx.getSource(), c.memory.opponentName.isEmpty()
                                                                        ? "no opponent in memory yet"
                                                                        : c.memory.summary());
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("config").executes(ctx -> {
                                                        var mc = net.minecraft.client.MinecraftClient.getInstance();
                                                        // v2.2.1: ALWAYS the built-in smooth-scroll config
                                                        // screen. The old branch opened the Cloth Config
                                                        // bridge whenever cloth-config2 was installed —
                                                        // that screen has fixed pages and NO scrolling,
                                                        // which is exactly the "All Configs/Settings not
                                                        // scrollable" report. One screen, wheel + drag +
                                                        // keyboard scrolling, every setting, same tooltips.
                                                        mc.send(() -> mc.setScreen(
                                                                        new dev.z.pvpbot.ui.PvpBotConfigScreen(null, 1)));
                                                        send(ctx.getSource(), "opened advanced settings (scroll: wheel / drag / arrow keys)");
                                                        return 1;
                                                }))
                                                .then(ClientCommandManager.literal("help").executes(ctx -> {
                                                        send(ctx.getSource(), "train | human-train | eval <n> | league | pause | resume/continue | start | stop | toggle | status | config | save | model save/list/load | v2 [on|off|save|aimhead|sneak] | il [status|load|train <bursts>|clear] | antiwobble <0-1> | pureattack on/off | vision on/off | wipe | hud [edit] | explore <0-1>|auto | memory  — v2.2: aim calm + anti-wobble dial, pure immediate attack, retreat governor, IL-from-video loader (also in Right Control GUI)");
                                                        return 1;
                                                }))
                ));
        }

        private static void send(FabricClientCommandSource src, String msg) {
                src.sendFeedback(Text.literal("[PvPBot] " + msg));
        }
}
