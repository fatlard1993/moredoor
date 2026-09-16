package justfatlard.moredoor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * The doors that are really one door.
 *
 * <p>A double door is two doors that everybody already treats as one, and vanilla makes you open
 * both. Widen that a little and a gatehouse is a bank of six that swing together - which is what a
 * gate is, and what no amount of clicking one leaf at a time will feel like.
 *
 * <p>Connected means: touching side to side, standing on the same line, and the same block. Same
 * block matters - an oak door beside an iron one is two doors that happen to be adjacent, and
 * swinging the iron one because somebody opened the oak one would be a way through a locked gate.
 *
 * <p>The same line, not the same facing. Which way a door faces is decided by where its placer
 * happened to be standing, so a pair built from opposite sides of the same doorway - the ordinary
 * way two people build one - faces two ways and was two doors to everything here. They fill one
 * gap in one wall and everybody reads them as one door, so they open as one and each swings its
 * own way, which is what a pair of doors does. A door in the wall at right angles to this one is
 * still a different door, because it is closing a different gap.
 *
 * <p>Geometry is not decided here. {@link DoorGroup} keeps its own stricter rule - same facing and
 * same hinge - because a leaf that slides has to know which way is along the wall, and a pair
 * that disagrees about facing has no single answer to that. Opening spans the pair; sliding does
 * not have to.
 */
public final class DoorBank {
	private DoorBank() {}

	/** A gate this wide is a wall with a hinge; past here something has gone wrong. */
	private static final int MAX_LEAVES = 64;

	/**
	 * Every door leaf joined to this one, including it.
	 *
	 * <p>Returned as lower halves only. A door is two blocks and every caller here wants to act on
	 * doors rather than on halves, so the upper half is the lower one's business.
	 */
	public static Set<BlockPos> leavesOf(LevelAccessor level, BlockPos pos, BlockState state) {
		if (!(state.getBlock() instanceof DoorBlock)) return Set.of();

		BlockPos foot = footOf(pos, state);
		// An upper half over something that is not its lower half is half a door, and half a
		// door is nobody's bank; walking from it read a facing off air.
		if (!(level.getBlockState(foot).getBlock() instanceof DoorBlock)) return Set.of();
		// A leaf cut loose opens on its own, and nobody's opening reaches it.
		DoorSwings swings = level instanceof net.minecraft.server.level.ServerLevel server ? DoorSwings.get(server) : null;
		if (swings != null && swings.isDetached(foot)) return Set.of(foot);
		Set<BlockPos> found = new LinkedHashSet<>();
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> pending = new ArrayDeque<>();

		found.add(foot);
		seen.add(foot);
		pending.add(foot);

		while (!pending.isEmpty() && found.size() < MAX_LEAVES) {
			BlockPos at = pending.poll();
			BlockState here = level.getBlockState(at);

			// Along the door's own face, and up: a gate is as wide and as tall as it is built.
			for (Direction step : sidewaysFrom(here)) {
				for (BlockPos next : new BlockPos[] {at.relative(step), at.relative(step).above(2),
						at.relative(step).below(2)}) {
					if (!seen.add(next)) continue;
					if (!joins(level, next, here) || (swings != null && swings.isDetached(next))) continue;

					found.add(next);
					pending.add(next);
				}
			}

			for (BlockPos next : new BlockPos[] {at.above(2), at.below(2)}) {
				if (!seen.add(next)) continue;
				if (!joins(level, next, here) || (swings != null && swings.isDetached(next))) continue;

				found.add(next);
				pending.add(next);
			}
		}
		return found;
	}

	/** Whether the door at this spot is the same door, built the same way, as that one. */
	private static boolean joins(LevelAccessor level, BlockPos pos, BlockState like) {
		BlockState state = level.getBlockState(pos);

		return state.getBlock() == like.getBlock()
			&& state.hasProperty(DoorBlock.HALF)
			&& state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
			&& state.getValue(DoorBlock.FACING).getAxis()
				== like.getValue(DoorBlock.FACING).getAxis();
	}

	/** The two ways along a door's face, which is across whichever way it is facing. */
	private static Direction[] sidewaysFrom(BlockState state) {
		Direction facing = state.getValue(DoorBlock.FACING);

		return new Direction[] {facing.getClockWise(), facing.getCounterClockWise()};
	}

	/** The lower half of whichever door this position belongs to. */
	public static BlockPos footOf(BlockPos pos, BlockState state) {
		return state.hasProperty(DoorBlock.HALF)
				&& state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
			? pos.below()
			: pos;
	}
}
