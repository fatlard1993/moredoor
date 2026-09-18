package justfatlard.moredoor.mixin;

import justfatlard.moredoor.GateBank;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redstone opens a double gate as one gate, the way a click already did.
 *
 * <p>Clicking has gone through {@link GateBank} since gates could be banked at all, so two leaves
 * across a track open together by hand and opened one at a time on a signal. This is the other
 * half of that: the same bank, the same rule, reached from a lever instead of a hand.
 *
 * <p>A single gate is left entirely to vanilla - there is nothing to join it to, and its own
 * handling is the one that keeps sounds and game events right for everything else that listens.
 */
@Mixin(FenceGateBlock.class)
public abstract class GateRedstoneMixin {

	@Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true)
	private void moreDoors$signalReachesTheWholeGate(BlockState state, Level level, BlockPos pos, Block block,
			@Nullable Orientation orientation, boolean movedByPiston, CallbackInfo callback) {
		if (!(level instanceof ServerLevel server)) return;
		if (GateBank.powerChanged(server, pos, state)) callback.cancel();
	}
}
