package dev.z.pvpbot.ui;

import net.minecraft.client.option.KeyBinding;

/**
 * v2.0 PHASE 1 — shared holder for the mod's keybind category.
 *
 * MC 1.21.11 turned keybind categories from plain strings into
 * {@link KeyBinding.Category} records that must be created once and shared by
 * every binding that should appear under the same Controls-screen group. The
 * field is assigned during client init (PvpBotMod#onInitializeClient) before
 * any KeyBinding is constructed, and read by the ClickGUI when it registers
 * its own extra binds.
 */
public final class CategoryHolder {

        public static volatile net.minecraft.client.option.KeyBinding.Category CATEGORY;

        private CategoryHolder() {
        }
}
