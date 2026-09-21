package justfatlard.moredoor;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
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
 * Opening and shutting a wide gate by moving it.
 *
 * <p>The blocks are lifted from where the gate stands, the squares it stood on are cleared, and
 * they are set down along the two leaves ({@link GateGroup#openSquare}). Lifting everything before
 * putting anything down matters: the leaves start on squares the gate itself is still occupying,
 * and a block written straight into one would be overwritten a moment later by the clearing.
 */
public final class GateMove {
	private GateMove() {}

	/**
	 * Levels with a gate mid-move.
	 *
	 * <p>Setting a block tells its neighbours, a gate's neighbours include the rest of the gate,
	 * and {@link GateBank#powerChanged} answers for the whole bank - so without this the first
	 * block set would start the move again from inside itself.
	 */
	private static final java.util.Set<ServerLevel> MOVING = java.util.Collections.newSetFromMap(
		new java.util.concurrent.ConcurrentHashMap<>());

	public static boolean isMoving(ServerLevel level) {
		return MOVING.contains(level);
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(GateMove::watchSignals);
	}

	/**
	 * Swing the gate, by carrying its blocks from where they are to where they belong.
	 *
	 * @return whether it moved; false when something is standing in the way of a leaf
	 */
	public static boolean set(ServerLevel level, GateGroup group, boolean open, @Nullable Entity by) {
		if (!group.pathIsClear(level, open)) return false;

		List<BlockPos> from = group.squares(!open);
		List<BlockPos> to = group.squares(open);
		List<BlockState> carried = new ArrayList<>(from.size());
		for (BlockPos square : from) {
			BlockState there = level.getBlockState(square);
			if (!(there.getBlock() instanceof FenceGateBlock)) return false;
			carried.add(there);
		}

		MOVING.add(level);
		try {
			for (BlockPos square : from) {
				level.setBlock(square, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
					Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
			int i = 0;
			for (int c = 0; c < group.width; c++) {
				for (int b = 0; b < group.height; b++) {
					BlockState was = carried.get(i);
					BlockState now = open ? group.openState(was) : group.shutState(was);
					level.setBlock(to.get(i), now, Block.UPDATE_ALL);
					i++;
				}
			}
		} finally {
			MOVING.remove(level);
		}

		GateSwings swings = GateSwings.get(level);
		swings.setOpen(group.record(), open);

		level.playSound(null, group.origin, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
			SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
		level.gameEvent(by, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, group.origin);
		return true;
	}

	/**
	 * An open gate whose signal has gone shuts itself.
	 *
	 * <p>A gate that has moved is no longer beside the lever that opened it, so the neighbour
	 * update that would shut it never arrives: it fires at the doorway, which is now air. The gate
	 * watches its own empty doorway instead, once a tick, and comes back when nothing there is
	 * calling for it any more. The same answer the mega doors reached, for the same reason.
	 *
	 * <p>Cheap in practice: it reads the home squares of gates that are open and were opened by a
	 * signal, which is usually none.
	 */
	private static void watchSignals(ServerLevel level) {
		GateSwings swings = GateSwings.get(level);
		if (swings.openGates().isEmpty()) return;

		for (GateSwings.OpenGate open : swings.openGates()) {
			GateGroup group = GateGroup.of(open, level);
			if (!wasPowered(level, group)) continue;
			boolean signal = false;
			for (BlockPos home : group.squares(false)) signal |= level.hasNeighborSignal(home);
			if (signal) continue;
			if (!set(level, group, false, null)) continue;
			for (BlockPos home : group.squares(false)) {
				BlockState there = level.getBlockState(home);
				if (there.hasProperty(FenceGateBlock.POWERED) && there.getValue(FenceGateBlock.POWERED)) {
					level.setBlock(home, there.setValue(FenceGateBlock.POWERED, false),
						Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		}
	}

	/** Whether this open gate's leaves are still carrying the mark of the signal that opened them. */
	private static boolean wasPowered(ServerLevel level, GateGroup group) {
		for (BlockPos leaf : group.squares(true)) {
			BlockState there = level.getBlockState(leaf);
			if (there.hasProperty(FenceGateBlock.POWERED) && there.getValue(FenceGateBlock.POWERED)) return true;
		}
		return false;
	}
}
