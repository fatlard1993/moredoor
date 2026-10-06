package justfatlard.moredoor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A wide fence gate: a rectangle of gates read as one gate, and where each of its blocks stands
 * open or shut.
 *
 * <p>Squares are counted {@code c} columns from the gate's own left end (its counter-clockwise
 * side, looking the way it faces) and {@code b} blocks up. Open, a gate hung from one post swings
 * every column out from that post, the way the mega doors do: column {@code c} stands {@code c}
 * blocks out in front of the hinge. Hung as two leaves, the left half swings from the left post and
 * the right half, which takes the middle column of an odd gate, from the right. The hinge column
 * itself only turns.
 *
 * <p>A row wider than {@link #MAX_WIDTH} is several gates side by side, counted from its own left
 * end, so the leaves of a long fence stay a sensible length.
 */
public final class GateGroup {
	public static final int MAX_WIDTH = 17;

	public final Block block;
	public final Direction facing;
	/** The bottom of the leftmost column, shut. */
	public final BlockPos origin;
	public final int width;
	public final int height;
	public final GateSwing swing;

	private GateGroup(Block block, Direction facing, BlockPos origin, int width, int height, GateSwing swing) {
		this.block = block;
		this.facing = facing;
		this.origin = origin;
		this.width = width;
		this.height = height;
		this.swing = swing;
	}

	public static GateGroup of(GateSwings.OpenGate gate) {
		Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(gate.block()));
		return new GateGroup(block, gate.facing(), BlockPos.of(gate.origin()), gate.width(), gate.height(), gate.swing());
	}

	public GateSwings.OpenGate record() {
		return new GateSwings.OpenGate(BuiltInRegistries.BLOCK.getKey(block).toString(), facing, origin.asLong(),
			width, height, swing);
	}

	/**
	 * Every gate of this shut bank, as gates of at most {@link #MAX_WIDTH}, read facing
	 * {@code facing}; empty when the bank is not a rectangle, which has no sensible way to swing.
	 */
	public static List<GateGroup> allOf(ServerLevel level, BlockPos pos, BlockState state, Direction facing) {
		if (!moves(state.getBlock())) return List.of();
		Set<BlockPos> bank = GateBank.gatesOf(level, pos, state);
		Direction right = facing.getClockWise();
		int minC = Integer.MAX_VALUE, maxC = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		for (BlockPos gate : bank) {
			int c = along(gate, right);
			minC = Math.min(minC, c);
			maxC = Math.max(maxC, c);
			minY = Math.min(minY, gate.getY());
			maxY = Math.max(maxY, gate.getY());
		}
		int width = maxC - minC + 1, height = maxY - minY + 1;
		if (width * height != bank.size()) return List.of();

		BlockPos left = pos.relative(right, minC - along(pos, right)).atY(minY);
		GateSwings swings = GateSwings.get(level);
		List<GateGroup> groups = new ArrayList<>();
		for (int start = 0; start < width; start += MAX_WIDTH) {
			int part = Math.min(MAX_WIDTH, width - start);
			BlockPos origin = left.relative(right, start);
			List<BlockPos> gates = new ArrayList<>(part * height);
			for (int c = 0; c < part; c++) {
				for (int b = 0; b < height; b++) gates.add(origin.relative(right, c).above(b));
			}
			groups.add(new GateGroup(state.getBlock(), facing, origin, part, height, swings.agreedBy(level, gates, facing)));
		}
		return groups;
	}

	/**
	 * Whether this gate swings wide by moving. Only the gates the client has a wide gate's pieces
	 * for, which generate_gate_models.py cuts from the game's own: another mod's gate would stand
	 * out along its leaf drawn as a row of ordinary open gates, so it opens in place.
	 */
	public static boolean moves(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft");
	}

	/** How far along {@code step}'s axis this block stands, counted the way {@code step} points. */
	private static int along(BlockPos pos, Direction step) {
		return pos.getX() * step.getStepX() + pos.getZ() * step.getStepZ();
	}

	public BlockPos shutSquare(int c, int b) {
		return origin.relative(facing.getClockWise(), c).above(b);
	}

	/** Whether column {@code c} swings from the left post. */
	public boolean hingesLeft(int c) {
		return switch (swing) {
			case LEFT -> true;
			case RIGHT -> false;
			case DOUBLE -> c < width / 2;
		};
	}

	/** How many blocks out from its hinge column {@code c} stands open. */
	public int out(int c) {
		return hingesLeft(c) ? c : width - 1 - c;
	}

	/** How many columns the leaf column {@code c} belongs to is made of. */
	public int leafLength(int c) {
		if (swing != GateSwing.DOUBLE) return width;
		return hingesLeft(c) ? width / 2 : width - width / 2;
	}

	public BlockPos hinge(int c, int b) {
		return hingesLeft(c) ? shutSquare(0, b) : shutSquare(width - 1, b);
	}

	public BlockPos openSquare(int c, int b) {
		return hinge(c, b).relative(facing, out(c));
	}

	public BlockPos square(int c, int b, boolean open) {
		return open ? openSquare(c, b) : shutSquare(c, b);
	}

	/** Every square, column by column, bottom to top within each. */
	public List<BlockPos> squares(boolean open) {
		List<BlockPos> all = new ArrayList<>(width * height);
		for (int c = 0; c < width; c++) {
			for (int b = 0; b < height; b++) all.add(square(c, b, open));
		}
		return all;
	}

	/** The post the leaf of square {@code i} (in {@link #squares} order) hangs from. */
	public GateSwing sideOf(int i) {
		return hingesLeft(i / height) ? GateSwing.LEFT : GateSwing.RIGHT;
	}

	/**
	 * Squares the leaves pass through or reach into without standing on: the inside of the quarter
	 * circle each turns through, and the square its tip reaches seven pixels into.
	 */
	public List<BlockPos> sweeps() {
		List<BlockPos> swept = new ArrayList<>();
		for (boolean left : new boolean[] {true, false}) {
			int c = left ? 0 : width - 1;
			if (hingesLeft(c) != left) continue;
			int reach = leafLength(c);
			Direction back = left ? facing.getClockWise() : facing.getCounterClockWise();
			for (int b = 0; b < height; b++) {
				BlockPos hinge = hinge(c, b);
				swept.add(hinge.relative(facing, reach));
				for (int a = 1; a <= reach; a++) {
					for (int f = 1; f <= reach; f++) {
						if (a * a + f * f <= reach * reach) swept.add(hinge.relative(back, a).relative(facing, f));
					}
				}
			}
		}
		return swept;
	}

	public BlockState openState(BlockState shut) {
		return shut.setValue(FenceGateBlock.OPEN, true).setValue(FenceGateBlock.FACING, facing);
	}

	public BlockState shutState(BlockState open) {
		return open.setValue(FenceGateBlock.OPEN, false);
	}
}
