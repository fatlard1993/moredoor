package justfatlard.moredoor.mixin;

import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Occlusion back on for a solid door, whose settings are copied from a vanilla door that has it off. */
@Mixin(BlockBehaviour.Properties.class)
public interface PropertiesAccessor {
	@Accessor("canOcclude")
	void moredoor$setCanOcclude(boolean canOcclude);

	@Accessor("canOcclude")
	boolean moredoor$canOcclude();
}
