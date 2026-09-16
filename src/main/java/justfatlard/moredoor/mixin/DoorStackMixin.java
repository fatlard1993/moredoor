package justfatlard.moredoor.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A door stands on a door.
 *
 * <p>Vanilla wants a sturdy top face under a door, and a door's top is not one, so a bank could
 * be as wide as you liked and never taller than two blocks. A bank is collected upward as well
 * as sideways already; this lets it be built that way: sneak and place a door on the top of
 * another and it takes.
 */
@Mixin(DoorBlock.class)
public abstract class DoorStackMixin {
	@Inject(method = "canSurvive", at = @At("HEAD"), cancellable = true)
	private void moreDoors$standsOnADoor(BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) return;
		// A swung leaf hangs from the door, not from a floor.
		if (justfatlard.moredoor.DoorSwing.hangs(level, pos, state)) {
			cir.setReturnValue(true);
			return;
		}
		BlockState below = level.getBlockState(pos.below());
		if (below.getBlock() instanceof DoorBlock && below.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
			cir.setReturnValue(true);
		}
	}
}
