package dev.z.pvpbot.bot;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Spatial awareness: the bot senses walls, floor drops and ceilings around
 * itself through voxel raycasts and feeds them to the policy, letting it
 * learn terrain play (corner control, ledge awareness) through rewards.
 */
public final class TerrainSense {

	/** 8 horizontal probes around the facing: 0 = forward, 2 = right, 4 = back, 6 = left. */
	public final float[] blocked = new float[8];
	public float floorDropAhead;
	public float ceilingLow;
	public float wallTightness;

	private PlayerEntity self;
	private World world;
	private long lastComputeTick = -1;

	public void sense(World world, PlayerEntity player, long tick) {
		if (world == null || player == null) return;
		this.world = world;
		this.self = player;
		if (tick - lastComputeTick < 3) return; // recompute at ~6.7Hz
		lastComputeTick = tick;
		Vec3d eye = player.getEyePos();
		for (int i = 0; i < 8; i++) {
			double rel = i * 45.0; // 0 = forward, 2 = right, 4 = back, 6 = left
			double ang = Math.toRadians(player.getYaw() + rel);
			double dx = -Math.sin(ang), dz = Math.cos(ang);
			blocked[i] = probe(eye, eye.add(dx * 2.4, 0, dz * 2.4));
		}
		double fwdAng = Math.toRadians(player.getYaw());
		Vec3d ahead = eye.add(-Math.sin(fwdAng) * 2.0, 0, Math.cos(fwdAng) * 2.0);
		floorDropAhead = 1f - probeDown(ahead);
		ceilingLow = 1f - probeUp(eye);
		wallTightness = (blocked[2] + blocked[6]) * 0.5f;
	}

	private float probe(Vec3d from, Vec3d to) {
		BlockHitResult hit = world.raycast(new RaycastContext(from, to,
				RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, self));
		return hit.getType() == HitResult.Type.BLOCK ? 1f : 0f;
	}

	private float probeDown(Vec3d from) {
		BlockHitResult hit = world.raycast(new RaycastContext(from, from.add(0, -2.5, 0),
				RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, self));
		return hit.getType() == HitResult.Type.BLOCK ? 1f : 0f;
	}

	private float probeUp(Vec3d from) {
		BlockHitResult hit = world.raycast(new RaycastContext(from, from.add(0, 2.5, 0),
				RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, self));
		return hit.getType() == HitResult.Type.BLOCK ? 0f : 1f;
	}
}
