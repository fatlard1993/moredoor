package justfatlard.more_doors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;

/**
 * The doors this mod adds: three variants of every material the game already had one of.
 *
 * <p>Plain {@link DoorBlock}s and {@link TrapDoorBlock}s, deliberately. Everything this mod does to
 * a door - locking it, swinging it with its neighbours, turning it in place - is done to vanilla's
 * own class so that vanilla's own doors get it too. A subclass here would have bought nothing and
 * split every behaviour into two cases.
 *
 * <p>What the variant actually changes is the settings: a barred door is not solid, does not stop
 * light and does not muffle, and the same is true of a glazed one except that it does stop the
 * wind. That is the whole of {@link DoorVariant#solid} and it is worth more than it looks - a
 * vanilla iron door with a grille in it blocks light as if it were a wall.
 */
public final class MoreDoors {
	private MoreDoors() {}

	/** Every door this mod registered, by block id path. */
	public static final Map<String, Block> DOORS = new LinkedHashMap<>();
	/** Every trapdoor, likewise. */
	public static final Map<String, Block> TRAPDOORS = new LinkedHashMap<>();

	/** The materials found at registration time, kept so the rest of the mod can walk them. */
	public static List<DoorMaterials> materials = List.of();

	public static void register() {
		materials = DoorMaterials.all();
		List<Block> registered = new ArrayList<>();

		for (DoorMaterials material : materials) {
			for (DoorVariant variant : DoorVariant.values()) {
				registered.add(door(material, variant));
				registered.add(trapdoor(material, variant));
			}
		}
		Main.LOGGER.info("[{}] Registered {} doors and trapdoors across {} materials",
			Main.MOD_ID, registered.size(), materials.size());
	}

	private static Block door(DoorMaterials material, DoorVariant variant) {
		String name = variant.doorName(material.name());
		ResourceKey<Block> key = blockKey(name);

		DoorBlock block = new DoorBlock(setTypeFor(material),
			settings(material, variant).setId(key));

		DOORS.put(name, place(name, key, block));
		return block;
	}

	private static Block trapdoor(DoorMaterials material, DoorVariant variant) {
		String name = variant.trapdoorName(material.name());
		ResourceKey<Block> key = blockKey(name);

		TrapDoorBlock block = new TrapDoorBlock(setTypeFor(material),
			settings(material, variant).setId(key));

		TRAPDOORS.put(name, place(name, key, block));
		return block;
	}

	/**
	 * Settings copied from the material's own vanilla door, then corrected for the variant.
	 *
	 * <p>Copying is what keeps a copper door oxidising and an iron door needing a redstone signal:
	 * those live in the block's settings and in its {@link BlockSetType}, and rewriting them by
	 * hand here would be a second copy of vanilla's table to keep in step.
	 */
	private static BlockBehaviour.Properties settings(DoorMaterials material, DoorVariant variant) {
		BlockBehaviour.Properties settings = BlockBehaviour.Properties.ofFullCopy(material.door());

		if (variant.solid) return settings;

		// Seeing through it is the point, so it must not occlude and must not be treated as a
		// solid face by anything asking. Light follows occlusion on its own; saying anything
		// about light level here would be saying how much it *emits*, which is none either way.
		return settings.noOcclusion()
			.isViewBlocking((state, level, pos, box) -> false)
			.isSuffocating((state, level, pos) -> false);
	}

	/**
	 * The wood's own set type, taken from the door vanilla already makes out of it.
	 *
	 * <p>The set type carries the sounds - a bamboo door does not close like an oak one, and the
	 * two nether woods differ again - so reading it off the vanilla block keeps every variant
	 * sounding like the wood it is made of, without a second copy of vanilla's table here.
	 */
	private static BlockSetType setTypeFor(DoorMaterials material) {
		return material.door() instanceof DoorBlock door ? door.type() : BlockSetType.OAK;
	}

	private static ResourceKey<Block> blockKey(String name) {
		return ResourceKey.create(Registries.BLOCK,
			Identifier.fromNamespaceAndPath(Main.MOD_ID, name));
	}

	private static Block place(String name, ResourceKey<Block> key, Block block) {
		Identifier id = Identifier.fromNamespaceAndPath(Main.MOD_ID, name);

		Registry.register(BuiltInRegistries.BLOCK, id, block);
		Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block,
			new Item.Properties()
				.setId(ResourceKey.create(Registries.ITEM, id))
				.useBlockDescriptionPrefix()));

		return block;
	}
}
