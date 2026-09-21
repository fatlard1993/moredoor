package justfatlard.moredoor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A bank of fence gates, read as one gate with two leaves, and told where every block of it stands
 * whether it is open or shut.
 *
 * <p>A wide gate used to open the way vanilla opens a single one: the blocks stayed where they were
 * and the client drew a leaf stretched out past them. Nothing carried the hitbox out with it, so
 * the drawn leaf was a ghost and the doorway kept a selection box across thin air. This group is
 * the other answer, and the one the mega doors in this mod already take: when the gate opens its
 * blocks <em>move</em>, and a block that is really there brings its collision, its light and the
 * way through along with it.
 *
 * <p>Only the overhang moves. A leaf one block long turns about its own edge and stays inside its
 * own square, which is why single and double gates never needed any of this; a leaf longer than
 * that hangs off the end, and those squares are the ones carried out to where they are drawn.
 *
 * <p><b>Not yet wired up.</b> Moving the blocks is half the job: an overhang square set down out
 * there still draws as a whole open gate rather than as more leaf, so it needs a model of its own
 * before a wide gate can open this way without a seam in it.
 *
 * <p><b>Two leaves, always.</b> A gate wide enough to be a bank is a double gate: the menu offers
 * nothing else ({@code moredoor-justfatlard.gate.pair}), and the two halves hinge on the two end
 * posts and swing out the same side. So the left half turns about the left end and the right half
 * about the right, which are the two mappings {@link DoorGroup} already makes for a door hung on
 * one side or the other.
 */
public final class GateGroup {
	/** The gate's own line: the axis it stands along, which is across the way it faces. */
	public final Direction along;
	/** The way the shut gate faces, and the way its leaves swing when it opens. */
	public final Direction facing;
	/** The lowest block of the leftmost column, with the gate shut. */
	public final BlockPos origin;
	public final int width;
	public final int height;
	public final String block;

	private GateGroup(Direction along, Direction facing, BlockPos origin, int width, int height, String block) {
		this.along = along;
		this.facing = facing;
		this.origin = origin;
		this.width = width;
		this.height = height;
		this.block = block;
	}

	/**
	 * The gate this block belongs to, shut, or null where the blocks do not make a rectangle.
	 *
	 * <p>A bank is found by flooding ({@link GateBank#gatesOf}); this asks the stricter question,
	 * because a gate that is not a rectangle has no sensible place to put its leaves.
	 */
	public static GateGroup at(ServerLevel level, BlockPos pos, BlockState state) {
		if (!(state.getBlock() instanceof FenceGateBlock)) return null;
		Set<BlockPos> bank = GateBank.gatesOf(level, pos, state);
		if (bank.isEmpty()) return null;

		Direction facing = state.getValue(FenceGateBlock.FACING);
		Direction along = facing.getClockWise();

		int minAlong = Integer.MAX_VALUE;
		int maxAlong = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (BlockPos gate : bank) {
			int c = alongCoordinate(gate, along);
			minAlong = Math.min(minAlong, c);
			maxAlong = Math.max(maxAlong, c);
			minY = Math.min(minY, gate.getY());
			maxY = Math.max(maxY, gate.getY());
		}
		int width = maxAlong - minAlong + 1;
		int height = maxY - minY + 1;
		if (width * height != bank.size()) return null;

		BlockPos origin = null;
		for (BlockPos gate : bank) {
			if (alongCoordinate(gate, along) == minAlong && gate.getY() == minY) origin = gate;
		}
		if (origin == null) return null;

		String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
			.getKey(state.getBlock()).toString();
		return new GateGroup(along, facing, origin, width, height, block);
	}

	/** Rebuilt from what was written down when the gate opened. */
	public static GateGroup of(GateSwings.OpenGate open, ServerLevel level) {
		Direction facing = open.facing();
		return new GateGroup(facing.getClockWise(), facing, BlockPos.of(open.origin()),
			open.width(), open.height(), open.block());
	}

	public GateSwings.OpenGate record() {
		return new GateSwings.OpenGate(block, facing, origin.asLong(), width, height);
	}

	private static int alongCoordinate(BlockPos pos, Direction along) {
		return along.getAxis() == Direction.Axis.X ? pos.getX() : pos.getZ();
	}

	/** Where column {@code c}, storey {@code b} stands with the gate shut. */
	public BlockPos shutSquare(int c, int b) {
		return origin.relative(along, c).above(b);
	}

	/**
	 * Where column {@code c}, storey {@code b} ends up with the gate open.
	 *
	 * <p>The left half turns about the left end post and the right half about the right, both
	 * swinging out the way the gate faces. The block sitting on a hinge does not move at all,
	 * which is what a hinge is.
	 */
	public BlockPos openSquare(int c, int b) {
		return hingesLeft(c)
			? origin.relative(facing, c).above(b)
			: origin.relative(along, width - 1).relative(facing, width - 1 - c).above(b);
	}

	/** Which of the two leaves this column belongs to. An odd gate gives the middle block to the right. */
	public boolean hingesLeft(int c) {
		return c < width / 2;
	}

	public BlockPos square(int c, int b, boolean open) {
		return open ? openSquare(c, b) : shutSquare(c, b);
	}

	public List<BlockPos> squares(boolean open) {
		List<BlockPos> all = new ArrayList<>(width * height);
		for (int c = 0; c < width; c++) {
			for (int b = 0; b < height; b++) all.add(square(c, b, open));
		}
		return all;
	}

	/**
	 * The state a square takes when the gate opens: open, facing the way it always did.
	 *
	 * <p>It is tempting to lay a leaf down as a <em>shut</em> gate turned a quarter, so that it
	 * carries a panel and a collision box out with it. It does not work: vanilla builds the shut
	 * shape as {@code Block.cube(16, 16, 4)}, which is centred, so a leaf laid down that way is a
	 * slab through the middle of the square rather than a panel against its face - and a gate two
	 * wide, where both columns are their own hinge and nothing moves at all, would simply stop
	 * being a doorway. Open is what a leaf is.
	 */
	public BlockState openState(BlockState shut) {
		return shut.setValue(FenceGateBlock.OPEN, true);
	}

	/** The state a square takes when the gate shuts again. */
	public BlockState shutState(BlockState open) {
		return open.setValue(FenceGateBlock.OPEN, false);
	}

	/**
	 * Whether every square the leaves would swing into is free to take them.
	 *
	 * <p>Squares the gate already stands on count as free: a leaf that ends up where another
	 * started is the gate moving through itself, which is fine, and the hinge squares never move
	 * at all.
	 */
	public boolean pathIsClear(BlockGetter level, boolean opening) {
		List<BlockPos> leaving = squares(!opening);
		for (BlockPos square : squares(opening)) {
			if (leaving.contains(square)) continue;
			if (!level.getBlockState(square).canBeReplaced()) return false;
		}
		return true;
	}
}
