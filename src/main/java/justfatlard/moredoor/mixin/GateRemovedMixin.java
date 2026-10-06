package justfatlard.moredoor.mixin;

import justfatlard.moredoor.GateMove;
import justfatlard.moredoor.GateSwings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A gate gone is forgotten, however it went: broken, burnt, blown up, pushed by a piston, or
 * replaced by a command. Its hinge would otherwise wait on the empty square for the next gate set
 * there, and a wide gate it was part of would be remembered whole with a piece missing.
 */
@Mixin(BlockBehaviour.class)
public abstract class GateRemovedMixin {

	@Inject(method = "affectNeighborsAfterRemoval", at = @At("HEAD"))
	private void moredoor$forgetTheGate(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston,
			CallbackInfo callback) {
		if (state.getBlock() instanceof FenceGateBlock && !GateMove.isMoving(level)) {
			GateSwings.get(level).forget(level, pos);
		}
	}
}
