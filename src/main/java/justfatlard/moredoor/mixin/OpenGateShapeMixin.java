package justfatlard.moredoor.mixin;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
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
 * An open gate is outlined where its leaves actually are.
 *
 * <p>Vanilla's {@code getShape} reads the gate's facing and whether it is in a wall, and nothing
 * else - never whether it is open. So an open gate keeps the outline of a shut one: a box sixteen
 * wide straight across a doorway you can walk through. You cannot click past it, cannot place a
 * block behind it, and the highlight hangs in the air where the gate plainly is not.
 *
 * <p>The boxes below are vanilla's own {@code template_fence_gate_open} model, so the outline is
 * the drawing rather than a guess at it: two posts, and the two leaves swung back against them.
 *
 * <p>Collision is left alone deliberately. An open gate is empty to walk through and should stay
 * that way; a leaf long enough to be worth bumping into is a wide gate's, and that one is made of
 * blocks that have moved, which is a different fix in a different place.
 */
@Mixin(FenceGateBlock.class)
public abstract class OpenGateShapeMixin {
	/**
	 * The open model, in model pixels: {@code x0, y0, z0, x1, y1, z1}.
	 *
	 * <p>Model space has the gate facing south, which is the rotation the blockstate files leave
	 * unturned, so z runs the way the gate faces and x runs along it.
	 */
	@Unique
	private static final double[][] MOREDOOR$OPEN_BOXES = {
		{0, 5, 7, 2, 16, 9}, {14, 5, 7, 16, 16, 9},
		{0, 6, 13, 2, 15, 15}, {14, 6, 13, 16, 15, 15},
		{0, 6, 9, 2, 9, 13}, {0, 12, 9, 2, 15, 13},
		{14, 6, 9, 16, 9, 13}, {14, 12, 9, 16, 15, 13},
	};

	/** A gate in a wall sits three pixels lower, the same drop vanilla gives its occlusion shape. */
	@Unique
	private static final double MOREDOOR$WALL_DROP = 3.0 / 16.0;

	@Unique
	private static final Map<Direction, VoxelShape> MOREDOOR$OPEN = moredoor$shapes(0.0);

	@Unique
	private static final Map<Direction, VoxelShape> MOREDOOR$OPEN_IN_WALL = moredoor$shapes(-MOREDOOR$WALL_DROP);

	@Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
	private void moredoor$outlineTheOpenLeaves(BlockState state, BlockGetter level, BlockPos pos,
			CollisionContext context, CallbackInfoReturnable<VoxelShape> back) {
		if (!state.getValue(FenceGateBlock.OPEN)) return;
		Direction facing = state.getValue(FenceGateBlock.FACING);
		back.setReturnValue(state.getValue(FenceGateBlock.IN_WALL)
			? MOREDOOR$OPEN_IN_WALL.get(facing) : MOREDOOR$OPEN.get(facing));
	}

	@Unique
	private static Map<Direction, VoxelShape> moredoor$shapes(double lift) {
		Map<Direction, VoxelShape> byFacing = new EnumMap<>(Direction.class);
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			// Model x runs along the gate and model z the way it faces, so the pair (facing,
			// counter-clockwise of it) is the turn that carries the model onto the world.
			Direction along = facing.getCounterClockWise();
			VoxelShape shape = Shapes.empty();
			for (double[] box : MOREDOOR$OPEN_BOXES) {
				shape = Shapes.or(shape, moredoor$turn(facing, along, box, lift));
			}
			byFacing.put(facing, shape.optimize());
		}
		return byFacing;
	}

	@Unique
	private static VoxelShape moredoor$turn(Direction facing, Direction along, double[] box, double lift) {
		double[] one = moredoor$world(facing, along, box[0], box[2]);
		double[] other = moredoor$world(facing, along, box[3], box[5]);
		return Shapes.box(
			Math.min(one[0], other[0]), box[1] / 16.0 + lift, Math.min(one[1], other[1]),
			Math.max(one[0], other[0]), box[4] / 16.0 + lift, Math.max(one[1], other[1]));
	}

	/** One model corner, turned about the middle of the block onto the world's own axes. */
	@Unique
	private static double[] moredoor$world(Direction facing, Direction along, double x, double z) {
		double outX = 8.0 + (x - 8.0) * along.getStepX() + (z - 8.0) * facing.getStepX();
		double outZ = 8.0 + (x - 8.0) * along.getStepZ() + (z - 8.0) * facing.getStepZ();
		return new double[] {outX / 16.0, outZ / 16.0};
	}
}
