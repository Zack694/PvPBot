package dev.z.pvpbot.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the vanilla left-click pipeline (MinecraftClient#doAttack) and the
 * vanilla spam-guard counter. The bot triggers exactly what a physical mouse
 * click triggers — no packets are forged anywhere in this mod.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {

	@Invoker("doAttack")
	boolean pvpbot$invokeDoAttack();

	@Accessor("attackCooldown")
	int pvpbot$getAttackCooldown();
}
