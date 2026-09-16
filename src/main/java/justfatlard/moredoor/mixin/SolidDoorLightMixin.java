package justfatlard.moredoor.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A door or trapdoor that occludes stops light through the face it covers, the way a slab stops
 * it through its half. Light passes a block only where neither side's shape covers the face it
 * crosses, and asks the shape at all only for a block that says to. Vanilla has occlusion off on
 * every door, so this is moredoor's solid ones and nothing of vanilla's.
 *
 * <p>Read off the settings rather than a list of blocks: the answer is taken while the block's
 * states are built, inside its constructor, before a list could hold it.
 *
 * <p>On vanilla's own classes rather than a subclass: everything else this mod does to a door
 * it does to vanilla's, and it tells its own doors from other mods' by the exact class.
 */
@Mixin({DoorBlock.class, TrapDoorBlock.class})
public abstract class SolidDoorLightMixin extends Block {
	private SolidDoorLightMixin(Properties properties) {
		super(properties);
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return ((PropertiesAccessor) this.properties).moredoor$canOcclude() || super.useShapeForLightOcclusion(state);
	}
}
