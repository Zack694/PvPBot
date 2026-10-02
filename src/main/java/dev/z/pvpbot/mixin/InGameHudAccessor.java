package dev.z.pvpbot.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the BIG on-screen text (title / subtitle) — the banner duel servers
 *  print "VICTORY" or "ROUND LOST" into. */
@Mixin(InGameHud.class)
public interface InGameHudAccessor {

        @Accessor("title")
        Text pvpbot$getTitle();

        @Accessor("subtitle")
        Text pvpbot$getSubtitle();
}
