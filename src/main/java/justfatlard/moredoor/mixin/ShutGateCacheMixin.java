package justfatlard.moredoor.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A shut gate's shapes are vanilla's whatever stands around it, so its states keep vanilla's
 * per-state cache; only an open gate's depend on its neighbours ({@link OpenGateShapeMixin}).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class ShutGateCacheMixin {
	@ModifyExpressionValue(method = "initCache",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;hasDynamicShape()Z"))
	private boolean moredoor$cacheShutGates(boolean dynamic) {
		BlockState state = (BlockState) (Object) this;
		if (dynamic && state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) return false;
		return dynamic;
	}
}
