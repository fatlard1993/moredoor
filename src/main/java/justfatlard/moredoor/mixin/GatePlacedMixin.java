package justfatlard.moredoor.mixin;

import justfatlard.moredoor.GateSwings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A gate set down against others joins them, and the whole gate is hung the way the rest of it
 * agrees on ({@link GateSwings#adopt}).
 *
 * <p>The hinge is kept per gate, and a gate opens from the hinge its gates share. A new gate would
 * otherwise come with none of its own, and leave the gate it joined disagreeing with itself.
 */
@Mixin(Block.class)
public abstract class GatePlacedMixin {

	@Inject(method = "setPlacedBy", at = @At("HEAD"))
	private void moredoor$joinTheGateBeside(Level level, BlockPos pos, BlockState state, LivingEntity by,
			ItemStack stack, CallbackInfo callback) {
		if (level instanceof ServerLevel server && state.getBlock() instanceof FenceGateBlock) {
			GateSwings.get(server).adopt(server, pos, state);
		}
	}
}
