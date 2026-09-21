package justfatlard.moredoor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * One door, however many squares it is built from.
 *
 * <p>A door block is two squares with their joining and their hinge encoded in its state. Squares
 * of one kind, hung the same way, standing in a full rectangle, are one door. The rectangle is
 * counted in squares: {@code c} columns along the wall from its low end, {@code b} blocks up from
 * the bottom. A closed door is read from the world. An open door is read from what was remembered
 * when it opened, because a slid door's squares may be standing a room away looking closed.
 */
public final class DoorGroup {
	private static final int MAX_LEAVES = 64;

	public final Block block;
	public final Direction facing;
	public final DoorHingeSide hinge;
	public final Swing swing;
	/** The lower half of the bottom leaf at the wall's low end, in the closed frame. */
	public final BlockPos origin;
	/** In leaves. */
	public final int width;
	public final int height;
	public final boolean open;

	private DoorGroup(Block block, Direction facing, DoorHingeSide hinge, Swing swing, BlockPos origin,
			int width, int height, boolean open) {
		this.block = block;
		this.facing = facing;
		this.hinge = hinge;
		this.swing = swing;
		this.origin = origin;
		this.width = width;
		this.height = height;
		this.open = open;
	}

	/** The door this block is part of, or null if it is not one or its squares do not fill a rectangle. */
	public static DoorGroup at(ServerLevel level, BlockPos pos) {
		DoorSwings swings = DoorSwings.get(level);
		DoorSwings.OpenDoor remembered = swings.openAt(pos);
		if (remembered != null) return of(remembered);

		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof DoorBlock)) return null;
		BlockPos foot = DoorBank.footOf(pos, state);
		BlockState like = level.getBlockState(foot);
		if (!(like.getBlock() instanceof DoorBlock) || like.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) return null;
		if (like.getValue(DoorBlock.OPEN)) return null;

		Direction facing = like.getValue(DoorBlock.FACING);
		Swing swing = swings.settingOf(foot, like);
		Direction along = along(facing);

		Set<BlockPos> found = new HashSet<>();
		Deque<BlockPos> pending = new ArrayDeque<>();
		found.add(foot);
		// A leaf cut loose is a door of its own, whatever stands beside it.
		if (!swings.isDetached(foot)) pending.add(foot);
		while (!pending.isEmpty() && found.size() < MAX_LEAVES) {
			BlockPos at = pending.poll();
			for (BlockPos next : new BlockPos[] {at.relative(along), at.relative(along.getOpposite()),
					at.above(2), at.below(2)}) {
				if (found.contains(next)) continue;
				BlockState there = level.getBlockState(next);
				if (!joins(there, like) || swings.settingOf(next, there) != swing) continue;
				// A slid door's leaves look closed where they stand; they belong to that door.
				if (swings.openAt(next) != null || swings.isDetached(next)) continue;
				found.add(next);
				pending.add(next);
			}
		}

		int sMin = Integer.MAX_VALUE, sMax = Integer.MIN_VALUE, rMin = Integer.MAX_VALUE, rMax = Integer.MIN_VALUE;
		for (BlockPos leaf : found) {
			int s = (leaf.getX() - foot.getX()) * along.getStepX() + (leaf.getZ() - foot.getZ()) * along.getStepZ();
			int r = Math.floorDiv(leaf.getY() - foot.getY(), 2);
			sMin = Math.min(sMin, s); sMax = Math.max(sMax, s);
			rMin = Math.min(rMin, r); rMax = Math.max(rMax, r);
		}
		int width = sMax - sMin + 1, height = rMax - rMin + 1;
		if (found.size() != width * height) return null;

		BlockPos origin = foot.relative(along, sMin).above(2 * rMin);
		return new DoorGroup(like.getBlock(), facing, like.getValue(DoorBlock.HINGE), swing, origin, width, height, false);
	}

	public static DoorGroup of(DoorSwings.OpenDoor door) {
		Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(door.block()));
		return new DoorGroup(block, door.facing(), door.hinge(), door.swing(), BlockPos.of(door.origin()),
			door.width(), door.height(), true);
	}

	public DoorSwings.OpenDoor record() {
		return new DoorSwings.OpenDoor(BuiltInRegistries.BLOCK.getKey(block).toString(), facing, hinge, swing,
			origin.asLong(), width, height);
	}

	/** Same door block, closed, facing the same way, hung on the same side. */
	public static boolean joins(BlockState state, BlockState like) {
		return state.is(like.getBlock())
			&& state.hasProperty(DoorBlock.HALF)
			&& state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
			&& state.getValue(DoorBlock.FACING) == like.getValue(DoorBlock.FACING)
			&& state.getValue(DoorBlock.HINGE) == like.getValue(DoorBlock.HINGE)
			&& !state.getValue(DoorBlock.OPEN);
	}

	/** Along the wall from its low end, which is where a left hinge hangs. */
	public static Direction along(Direction facing) {
		return lowEnd(facing).getOpposite();
	}

	/** The end of a closed door that a left hinge hangs from, which is the model's low-z end. */
	public static Direction lowEnd(Direction facing) {
		return switch (facing) {
			case EAST -> Direction.NORTH;
			case SOUTH -> Direction.EAST;
			case WEST -> Direction.SOUTH;
			default -> Direction.WEST;
		};
	}

	public int blocksTall() {
		return height * 2;
	}

	/** Where square (c, b) stands with the door closed. */
	public BlockPos closedSquare(int c, int b) {
		return origin.relative(along(facing), c).above(b);
	}

	/** Where square (c, b) ends up with the door open, by how it swings. */
	public BlockPos openSquare(int c, int b) {
		Direction along = along(facing);
		int tall = blocksTall();
		return switch (swing) {
			case LEFT -> origin.relative(facing, c).above(b);
			case RIGHT -> origin.relative(along, width - 1).relative(facing, width - 1 - c).above(b);
			case SLIDE_LEFT -> origin.relative(along, c - width).above(b);
			case SLIDE_RIGHT -> origin.relative(along, c + width).above(b);
			case SLIDE_UP -> origin.relative(along, c).above(b + tall);
			case SLIDE_DOWN -> origin.relative(along, c).above(b - tall);
		};
	}

	public BlockPos square(int c, int b, boolean open) {
		return open ? openSquare(c, b) : closedSquare(c, b);
	}

	/** Every square, in the given state. */
	public List<BlockPos> squares(boolean open) {
		List<BlockPos> all = new ArrayList<>(width * blocksTall());
		for (int c = 0; c < width; c++) {
			for (int b = 0; b < blocksTall(); b++) {
				all.add(square(c, b, open));
			}
		}
		return all;
	}

	/** Every lower half, with the door closed. */
	public List<BlockPos> feet() {
		return feet(false);
	}

	/** Every lower half, with the door open: the same leaves as {@link #feet()}, in the same order. */
	public List<BlockPos> openFeet() {
		return feet(true);
	}

	private List<BlockPos> feet(boolean open) {
		List<BlockPos> feet = new ArrayList<>(width * height);
		for (int r = 0; r < height; r++) {
			for (int c = 0; c < width; c++) {
				feet.add(square(c, 2 * r, open));
			}
		}
		return feet;
	}

	/** The block state square (c, b) takes when the door opens: swung open on a hinge, unchanged on a slide. */
	public BlockState openState(int c, int b, BlockState closed) {
		return swing.isSlide() ? closed : closed.setValue(DoorBlock.OPEN, true);
	}

	/** The block state square (c, b) takes when the door closes again. */
	public BlockState closedState(int c, int b, BlockState opened) {
		return swing.isSlide() ? opened : opened.setValue(DoorBlock.OPEN, false);
	}

}
