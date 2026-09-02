package justfatlard.more_doors;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Every wood the game already makes a door out of.
 *
 * <p>Read out of the block registry rather than listed, so a mod that adds a wood set with a door
 * in it gets the same five variants without an entry here. Having planks is what makes something a
 * wood for this purpose, which is also what excludes the metals: iron and copper doors keep their
 * vanilla behaviour, because a redstone latch and an oxidising surface are not styling.
 */
public record DoorMaterials(String name, Block door, Block trapdoor, String texture) {

	public static List<DoorMaterials> all() {
		List<DoorMaterials> found = new ArrayList<>();

		for (Block block : BuiltInRegistries.BLOCK) {
			String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
			if (!path.endsWith("_door") || path.startsWith("more_doors")) continue;

			String material = path.substring(0, path.length() - "_door".length());
			Block trapdoor = byName(material + "_trapdoor");
			if (trapdoor == null) continue;

			// Woods only. A metal door is not a wood door with a different colour - iron opens on
			// redstone and copper oxidises, and neither behaviour survives being restyled. They
			// stay vanilla's.
			if (byName(material + "_planks") == null) continue;

			found.add(new DoorMaterials(material, block, trapdoor, textureFor(material)));
		}
		return List.copyOf(found);
	}

	/** The block whose face stands in for this wood. Its planks, which is what a door is made of. */
	private static String textureFor(String material) {
		return "block/" + material + "_planks";
	}

	private static Block byName(String name) {
		Block block = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name));

		return block == null || block == Blocks.AIR ? null : block;
	}
}
