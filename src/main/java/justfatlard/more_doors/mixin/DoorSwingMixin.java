package justfatlard.more_doors.mixin;

import justfatlard.more_doors.DoorBank;
import justfatlard.more_doors.DoorGroup;
import justfatlard.more_doors.DoorInteraction;
import justfatlard.more_doors.DoorSwing;
import justfatlard.more_doors.DoorSwings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A door block is a square of a door.
 *
 * <p>Vanilla decides per block what is decided per door here. How a leaf hangs comes from the
 * leaves it is placed against, so a door grows the way it was started rather than vanilla's rule
 * of hanging every second leaf the other way. And opening goes through the whole door, moving its
 * squares where they go, whoever asked: a villager, a zombie, a lever, all reach a door here.
 */
@Mixin(DoorBlock.class)
public abstract class DoorSwingMixin {

	@Inject(method = "setOpen", at = @At("HEAD"), cancellable = true)
	private void moreDoors$swingTheWholeDoor(Entity by, Level level, BlockState state, BlockPos pos, boolean open,
			CallbackInfo ci) {
		if (!(level instanceof ServerLevel serverLevel)) return;
		if (DoorSwing.isMoving(serverLevel, pos)) {
			ci.cancel();
			return;
		}
		DoorGroup group = DoorGroup.at(serverLevel, pos);
		if (group == null || (group.width == 1 && group.swing.isSideHinge())) return;
		DoorSwing.set(serverLevel, by, group, open, true);
		ci.cancel();
	}

	@Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true)
	private void moreDoors$redstoneReachesTheDoor(BlockState state, Level level, BlockPos pos, Block block,
			Orientation orientation, boolean movedByPiston, CallbackInfo ci) {
		if (!(level instanceof ServerLevel serverLevel)) return;
		if (DoorSwing.isMoving(serverLevel, pos)) {
			ci.cancel();
			return;
		}
		// A signal reaches every door a click would: the whole bank, a double door's other leaf
		// included. A leaf on its own, hinged, is vanilla's to turn.
		DoorGroup group = DoorGroup.at(serverLevel, pos);
		boolean alone = DoorBank.leavesOf(serverLevel, pos, state).size() <= 1;
		if (alone && (group == null || (group.width == 1 && group.swing.isSideHinge()))) return;

		BlockPos other = pos.relative(state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN);
		boolean powered = level.hasNeighborSignal(pos) || level.hasNeighborSignal(other);
		if (powered == state.getValue(DoorBlock.POWERED)) {
			ci.cancel();
			return;
		}
		// Without shape updates: those are queued while a neighbour update is running, and the
		// door is about to move inside this one. Run afterwards they would find each square's
		// other half gone from beside it and dissolve the door. Both halves are set here anyway.
		for (BlockPos half : new BlockPos[] {pos, other}) {
			BlockState there = level.getBlockState(half);
			if (there.getBlock() instanceof DoorBlock) {
				level.setBlock(half, there.setValue(DoorBlock.POWERED, powered), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}
		boolean open = group != null ? group.open : state.getValue(DoorBlock.OPEN);
		if (powered != open) DoorInteraction.swingBank(serverLevel, null, pos, level.getBlockState(pos), powered);
		ci.cancel();
	}

	/**
	 * A leaf placed against a door faces and hangs the way the door does, so a door grows the way
	 * it was started, whichever way the builder happened to be looking. Placed sneaking, it takes
	 * vanilla's own choice instead, which beside one door is the other side: a double door.
	 */
	@Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true)
	private void moreDoors$hangLikeTheNeighbours(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
		BlockState placed = cir.getReturnValue();
		if (placed == null || !(placed.getBlock() instanceof DoorBlock)) return;
		if (context.getPlayer() != null && context.getPlayer().isSecondaryUseActive()) return;
		BlockState near = neighbourLeaf(context.getLevel(), context.getClickedPos(), placed);
		if (near == null) return;
		cir.setReturnValue(placed
			.setValue(DoorBlock.FACING, near.getValue(DoorBlock.FACING))
			.setValue(DoorBlock.HINGE, near.getValue(DoorBlock.HINGE)));
	}

	@Inject(method = "setPlacedBy", at = @At("TAIL"))
	private void moreDoors$moveLikeTheNeighbours(Level level, BlockPos pos, BlockState state, LivingEntity by,
			ItemStack stack, CallbackInfo ci) {
		if (!(level instanceof ServerLevel serverLevel)) return;
		BlockPos foot = DoorBank.footOf(pos, state);
		BlockPos nearFoot = neighbourFoot(level, foot, state);
		if (nearFoot == null) return;
		DoorSwings swings = DoorSwings.get(serverLevel);
		swings.setSetting(serverLevel, foot, swings.settingOf(nearFoot, level.getBlockState(nearFoot)));
	}

	private static BlockState neighbourLeaf(Level level, BlockPos pos, BlockState like) {
		BlockPos foot = neighbourFoot(level, pos, like);
		return foot == null ? null : level.getBlockState(foot);
	}

	/**
	 * The lower half of a same door standing against this one, on any side or below, facing
	 * whichever way: it is the neighbour that decides the facing, not the other way round.
	 */
	private static BlockPos neighbourFoot(Level level, BlockPos foot, BlockState like) {
		for (BlockPos near : new BlockPos[] {foot.north(), foot.south(), foot.east(), foot.west(), foot.below(2)}) {
			BlockState there = level.getBlockState(near);
			if (there.is(like.getBlock()) && there.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) return near;
		}
		return null;
	}
}
