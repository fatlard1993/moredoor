package justfatlard.moredoor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jspecify.annotations.Nullable;

/**
 * Opening and shutting a wide gate by moving it, the way {@link DoorSwing} moves a mega door.
 *
 * <p>A block can turn about its own edge and nothing else, so every column of the gate goes to
 * the square its leaf really swings to ({@link GateGroup#openSquare}), all at once: a hinge has no
 * halfway that blocks can hold. A square it would land on that is taken stops it before it moves,
 * and the block in the way knocks and sheds dust. Something standing inside the arc the leaf turns
 * through, or in the square its tip reaches into, is found once the gate has swung: it knocks
 * against it and comes back.
 */
public final class GateMove {
	private GateMove() {}

	/** Told to clients, no shape updates: squares in mid-move must not be judged half a gate. */
	private static final int MOVE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	/** How long a gate that hit something stays against it before it comes back. */
	private static final int HIT_TICKS = 6;

	/** Gates that knocked against something, waiting to come back. */
	private static final List<Bounce> bouncing = new ArrayList<>();
	private record Bounce(ServerLevel level, GateGroup group, long at) {}

	/** Levels with a gate mid-move: setting its blocks must not set the gate off again. */
	private static final Set<ServerLevel> MOVING = java.util.Collections.newSetFromMap(
		new java.util.concurrent.ConcurrentHashMap<>());

	public static boolean isMoving(ServerLevel level) {
		return MOVING.contains(level);
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			List<Bounce> due = new ArrayList<>();
			bouncing.removeIf(bounce -> bounce.level() == level && level.getGameTime() >= bounce.at() && due.add(bounce));
			for (Bounce bounce : due) set(level, bounce.group(), false, null, true);
			watchSignals(level);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Bounce bounce : bouncing) set(bounce.level(), bounce.group(), false, null, true);
			bouncing.clear();
		});
	}

	/**
	 * Swing the gate, by carrying its blocks from where they are to where they belong.
	 *
	 * @return whether it moved; false when a square it would land on is taken
	 */
	public static boolean set(ServerLevel level, GateGroup group, boolean open, @Nullable Entity by, boolean sound) {
		GateSwings swings = GateSwings.get(level);
		List<BlockPos> from = group.squares(!open);
		List<BlockPos> to = group.squares(open);
		Set<BlockPos> own = new HashSet<>(from);
		for (BlockPos dest : to) {
			if (own.contains(dest)) continue;
			if (level.isOutsideBuildHeight(dest) || !level.getBlockState(dest).canBeReplaced()) {
				// Shutting into a square somebody has built in waits, quietly, for another try.
				if (open) struck(level, dest);
				return false;
			}
		}
		List<BlockState> carried = new ArrayList<>(from.size());
		for (BlockPos square : from) {
			BlockState there = level.getBlockState(square);
			if (!there.is(group.block)) return false;
			carried.add(there);
		}

		if (!open) swings.clearOpen(level, group.record(), false);
		MOVING.add(level);
		try {
			for (int i = 0; i < from.size(); i++) {
				if (!from.get(i).equals(to.get(i))) {
					level.setBlock(from.get(i), level.getFluidState(from.get(i)).createLegacyBlock(), MOVE);
				}
			}
			for (int i = 0; i < to.size(); i++) {
				BlockState now = open ? group.openState(carried.get(i)) : group.shutState(carried.get(i));
				boolean stays = from.get(i).equals(to.get(i));
				level.setBlock(to.get(i), now, stays ? Block.UPDATE_ALL : MOVE);
				if (!stays) swings.move(level, from.get(i), to.get(i));
			}
		} finally {
			MOVING.remove(level);
		}
		if (open) {
			List<GateSwing> sides = new ArrayList<>(to.size());
			for (int i = 0; i < to.size(); i++) sides.add(group.sideOf(i));
			swings.markOpen(level, group.record(), to, sides);
		} else {
			for (BlockPos square : to) swings.remark(level, square);
		}

		if (sound) {
			level.playSound(null, group.origin, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
				SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
		}
		level.gameEvent(by, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, group.origin);

		// Swung, and now asked what it went over: it knocks against the first thing and comes back.
		if (open) {
			Set<BlockPos> standing = new HashSet<>(to);
			for (BlockPos over : group.sweeps()) {
				if (standing.contains(over)) continue;
				if (level.isOutsideBuildHeight(over) || !level.getBlockState(over).canBeReplaced()) {
					struck(level, over);
					bouncing.add(new Bounce(level, group, level.getGameTime() + HIT_TICKS));
					break;
				}
			}
		}
		return true;
	}

	/** The knock and the chips off the block, and nothing in words, as a door does. */
	private static void struck(ServerLevel level, BlockPos at) {
		BlockState wall = level.getBlockState(at);
		level.playSound(null, at, wall.getSoundType().getHitSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
		if (!wall.isAir()) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, wall),
				at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.0);
		}
	}

	/**
	 * An open gate whose signal has gone shuts itself.
	 *
	 * <p>A gate that has moved is no longer beside the lever that opened it, so the neighbour
	 * update that would shut it fires at its empty doorway. The gate watches that doorway instead,
	 * once a tick, and comes back when nothing there is calling for it. Cheap in practice: it reads
	 * the doorways of open gates whose squares still say powered, which is usually none.
	 */
	private static void watchSignals(ServerLevel level) {
		GateSwings swings = GateSwings.get(level);
		for (GateSwings.OpenGate open : swings.openGates()) {
			GateGroup group = GateGroup.of(open);
			boolean powered = false;
			for (BlockPos square : group.squares(true)) {
				BlockState there = level.getBlockState(square);
				powered |= there.hasProperty(FenceGateBlock.POWERED) && there.getValue(FenceGateBlock.POWERED);
			}
			if (!powered) continue;
			boolean signal = false;
			for (BlockPos home : group.squares(false)) signal |= level.hasNeighborSignal(home);
			if (signal || !set(level, group, false, null, true)) continue;
			for (BlockPos home : group.squares(false)) {
				BlockState there = level.getBlockState(home);
				if (there.hasProperty(FenceGateBlock.POWERED) && there.getValue(FenceGateBlock.POWERED)) {
					level.setBlock(home, there.setValue(FenceGateBlock.POWERED, false), MOVE);
				}
			}
		}
	}
}
