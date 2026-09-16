package justfatlard.moredoor.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A fence meeting a door joins onto it.
 *
 * <p>Vanilla fences connect to walls, to other fences and to anything with a solid face, and a
 * door has none of those - so a fence line running into a gate stops one block short with a post
 * standing on its own, and the gap reads as a mistake because it is one.
 *
 * <p>Only fences. A wall meeting a door already looks like a wall meeting a door; it is the fence's
 * thin post that needs the arm to reach across.
 */
@Mixin(FenceBlock.class)
public class FenceConnectionMixin {

	@Inject(method = "connectsTo", at = @At("HEAD"), cancellable = true)
	private void moreDoors$joinDoors(BlockState state, boolean solidFace, Direction direction,
			CallbackInfoReturnable<Boolean> cir) {
		if (state.getBlock() instanceof DoorBlock) cir.setReturnValue(true);
	}
}
