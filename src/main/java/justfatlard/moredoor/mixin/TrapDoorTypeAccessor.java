package justfatlard.moredoor.mixin;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * A trapdoor's set type, which it keeps to itself.
 *
 * <p>Two things a hatch needs come from there and nowhere else: whether a hand may open it at
 * all - an iron trapdoor is a redstone trapdoor - and which sounds it makes, so a bank of six
 * can play one note between them instead of six.
 */
@Mixin(TrapDoorBlock.class)
public interface TrapDoorTypeAccessor {
	@Invoker("getType")
	BlockSetType moredoor$type();
}
