package justfatlard.moredoor.mixin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import justfatlard.pandorical.BlockMarkLookup;
import justfatlard.pandorical.FenceGateLeaves;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An open gate is outlined where its leaves are, and a wide gate's leaf is solid.
 *
 * <p>Vanilla's {@code getShape} reads the gate's facing and whether it is in a wall, and nothing
 * else - never whether it is open. So an open gate keeps the outline of a shut one: a box sixteen
 * wide straight across a doorway you can walk through. Here a lone open gate is outlined as it is
 * drawn: vanilla's open model, or both posts and one leaf when it hangs from one of them.
 *
 * <p>A wide gate opens by moving its blocks out along its leaves, and each square is outlined and
 * collides as the piece of leaf it draws ({@link FenceGateLeaves}), within its own square: the tip
 * reaches seven pixels into the next, which this square cannot be aimed at through. Its collision
 * stands as tall as a fence's, as a shut gate's does; a lone open gate keeps vanilla's none.
 */
@Mixin(FenceGateBlock.class)
public abstract class OpenGateShapeMixin {
	/**
	 * Pieces of the open model, in model pixels: {@code x0, y0, z0, x1, y1, z1}.
	 *
	 * <p>Model space has the gate facing south, which is the rotation the blockstate files leave
	 * unturned, so z runs the way the gate faces and x runs along it: the gate's own left is the
	 * post at x 14.
	 */
	@Unique
	private static final double[][] MOREDOOR$POSTS = {{0, 5, 7, 2, 16, 9}, {14, 5, 7, 16, 16, 9}};
	/** Vanilla's own stubs: half a gate's leaf each, ending in their end posts. */
	@Unique
	private static final double[][] MOREDOOR$STUBS = {
		{0, 6, 13, 2, 15, 15}, {14, 6, 13, 16, 15, 15},
		{0, 6, 9, 2, 9, 13}, {0, 12, 9, 2, 15, 13},
		{14, 6, 9, 16, 9, 13}, {14, 12, 9, 16, 15, 13},
	};

	/** A gate in a wall sits three pixels lower, the same drop vanilla gives its occlusion shape. */
	@Unique
	private static final double MOREDOOR$WALL_DROP = 3.0;
	/** A fence's height, and a shut gate's collision. */
	@Unique
	private static final double MOREDOOR$FENCE_HEIGHT = 24.0;

	@Unique
	private static final Map<String, VoxelShape> MOREDOOR$SHAPES = new ConcurrentHashMap<>();

	/**
	 * A wide gate's collision depends on the squares around it, so vanilla must not cache one
	 * per block state: with a cache it never asks.
	 */
	public boolean hasDynamicShape() {
		return true;
	}

	@Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
	private void moredoor$outlineTheOpenLeaves(BlockState state, BlockGetter level, BlockPos pos,
			CollisionContext context, CallbackInfoReturnable<VoxelShape> back) {
		if (!state.getValue(FenceGateBlock.OPEN)) return;
		back.setReturnValue(moredoor$shape(state, level, pos, false));
	}

	@Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
	private void moredoor$wideLeavesAreSolid(BlockState state, BlockGetter level, BlockPos pos,
			CollisionContext context, CallbackInfoReturnable<VoxelShape> back) {
		if (!state.getValue(FenceGateBlock.OPEN)) return;
		VoxelShape solid = moredoor$shape(state, level, pos, true);
		if (solid != null) back.setReturnValue(solid);
	}

	/** The outline, or with {@code solid} the collision of a wide gate's piece (null for none of ours). */
	@Unique
	private static VoxelShape moredoor$shape(BlockState state, BlockGetter level, BlockPos pos, boolean solid) {
		BiPredicate<BlockPos, String> marks = level instanceof LevelReader reader
			? (at, mark) -> BlockMarkLookup.has(reader, at, mark) : (at, mark) -> false;
		String piece = FenceGateLeaves.piece(level, pos, state, marks);
		String side = FenceGateLeaves.side(marks, pos);
		if (solid && piece == null) return null;
		Direction facing = state.getValue(FenceGateBlock.FACING);
		boolean wall = state.getValue(FenceGateBlock.IN_WALL);
		String key = facing + "/" + wall + "/" + piece + "/" + side + "/" + solid;
		return MOREDOOR$SHAPES.computeIfAbsent(key, k -> moredoor$build(facing, wall, piece, side, solid));
	}

	@Unique
	private static VoxelShape moredoor$build(Direction facing, boolean wall, String piece, String side, boolean solid) {
		double lift = wall && !solid ? -MOREDOOR$WALL_DROP : 0.0;
		if (piece == null) {
			VoxelShape shape = moredoor$add(Shapes.empty(), facing, lift, MOREDOOR$POSTS);
			if (side == null) return moredoor$add(shape, facing, lift, MOREDOOR$STUBS).optimize();
			return moredoor$add(shape, facing, lift, moredoor$leaf(side, 9, 16, false)).optimize();
		}
		boolean hinge = piece.startsWith("hinge");
		double x0 = side.equals("left") ? 14 : 0;
		double[][] boxes = solid
			? new double[][] {{x0, 0, hinge ? 7 : 0, x0 + 2, MOREDOOR$FENCE_HEIGHT, 16}}
			: moredoor$leaf(side, hinge ? 9 : 0, 16, hinge);
		return moredoor$add(Shapes.empty(), facing, lift, boxes).optimize();
	}

	/** One side's leaf rails from z0 to z1, and its post when {@code post}. */
	@Unique
	private static double[][] moredoor$leaf(String side, double z0, double z1, boolean post) {
		double x0 = side.equals("left") ? 14 : 0;
		double[][] rails = {{x0, 6, z0, x0 + 2, 9, z1}, {x0, 12, z0, x0 + 2, 15, z1}};
		if (!post) return rails;
		return new double[][] {rails[0], rails[1], {x0, 5, 7, x0 + 2, 16, 9}};
	}

	@Unique
	private static VoxelShape moredoor$add(VoxelShape shape, Direction facing, double lift, double[][] boxes) {
		Direction along = facing.getCounterClockWise();
		for (double[] box : boxes) shape = Shapes.or(shape, moredoor$turn(facing, along, box, lift));
		return shape;
	}

	@Unique
	private static VoxelShape moredoor$turn(Direction facing, Direction along, double[] box, double lift) {
		double[] one = moredoor$world(facing, along, box[0], box[2]);
		double[] other = moredoor$world(facing, along, box[3], box[5]);
		return Shapes.box(
			Math.min(one[0], other[0]), (box[1] + lift) / 16.0, Math.min(one[1], other[1]),
			Math.max(one[0], other[0]), (box[4] + lift) / 16.0, Math.max(one[1], other[1]));
	}

	/** One model corner, turned about the middle of the block onto the world's own axes. */
	@Unique
	private static double[] moredoor$world(Direction facing, Direction along, double x, double z) {
		double outX = 8.0 + (x - 8.0) * along.getStepX() + (z - 8.0) * facing.getStepX();
		double outZ = 8.0 + (x - 8.0) * along.getStepZ() + (z - 8.0) * facing.getStepZ();
		return new double[] {outX / 16.0, outZ / 16.0};
	}
}
