package dev.z.pvpbot.bot;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

/**
 * Auto-detects which kit the character is wearing and adjusts the assumed
 * damage model (used for hit/miss classification and opponent armor
 * estimation).
 */
public final class KitDetect {

	public enum Kit {
		NETHERITE(20, 12, "netherite"),
		DIAMOND(20, 8, "diamond"),
		IRON(15, 2, "iron"),
		LEATHER(5, 0, "leather"),
		NONE(0, 0, "unarmored");

		public final int armorPoints;
		public final int toughness;
		public final String label;

		Kit(int armorPoints, int toughness, String label) {
			this.armorPoints = armorPoints;
			this.toughness = toughness;
			this.label = label;
		}
	}

	public static Kit detect(ClientPlayerEntity player) {
		int armor = player.getArmor();
		if (armor >= 19) return Kit.NETHERITE;
		if (armor >= 17) return Kit.DIAMOND;
		if (armor >= 13) return Kit.IRON;
		if (armor >= 3) return Kit.LEATHER;
		return Kit.NONE;
	}

	public static String describeWorn(ClientPlayerEntity player) {
		StringBuilder sb = new StringBuilder();
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (EquipmentSlot slot : slots) {
			ItemStack st = player.getEquippedStack(slot);
			if (st != null && !st.isEmpty()) {
				if (sb.length() > 0) sb.append(", ");
				sb.append(st.getItem().toString());
			}
		}
		return sb.length() == 0 ? "no armor" : sb.toString();
	}

	/**
	 * Vanilla armor damage reduction for the sim-calibrated damage model.
	 */
	public static float reduce(float damage, int armorPoints, int toughness) {
		float a = armorPoints;
		float capped = Math.min(20f, Math.max(a / 5f, a - damage / (2f + toughness / 4f)));
		return damage * (1f - capped / 25f);
	}
}
