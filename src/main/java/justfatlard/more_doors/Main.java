package justfatlard.more_doors;

import justfatlard.pandorical.api.BlockRegistration;
import justfatlard.pandorical.api.ItemRegistration;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main implements ModInitializer {
	public static final String MOD_ID = "more-doors-justfatlard";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		MoreDoors.register();
		DoorInteraction.register();

		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
			(dispatcher, registry, environment) -> DoorCommands.register(dispatcher));

		if (PandoricalApi.isAvailable()) {
			syncClientAssets();
		}

		// Gates in flight. Cheap when none are: the tick returns on an empty map.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(
			BigDoor::tick);

		// Nothing is allowed to stop while a gate is still a hole with its blocks in memory.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.UNLOAD.register(
			(server, world) -> BigDoor.landAll(world));

		System.out.println("[" + MOD_ID + "] Loaded");
	}

	/**
	 * Hand the client everything it needs to draw all this.
	 *
	 * <p>The eight door shapes are vanilla's own, rebuilt with depth and served above vanilla's
	 * copy. Overriding those rather than shipping sixty-six of our own is what gives the game's
	 * twenty-two doors depth too, and what keeps a resource pack's door retexture working on
	 * every one of them.
	 */
	private static void syncClientAssets() {
		for (String name : new String[] {"door_bottom_left", "door_bottom_left_open",
				"door_bottom_right", "door_bottom_right_open", "door_top_left",
				"door_top_left_open", "door_top_right", "door_top_right_open"}) {
			shipOverride("minecraft/models/block/" + name + ".json");
		}

		for (var door : MoreDoors.DOORS.entrySet()) {
			PandoricalApi.content().registerBlock(MOD_ID + ":" + door.getKey(),
				new BlockRegistration().baseBlock("minecraft:oak_door")
					.model(MOD_ID + ":block/" + door.getKey() + "_bottom_left"));
			PandoricalApi.content().registerItem(MOD_ID + ":" + door.getKey(),
				new ItemRegistration().model(MOD_ID + ":item/" + door.getKey()));
		}
		for (var trap : MoreDoors.TRAPDOORS.entrySet()) {
			PandoricalApi.content().registerBlock(MOD_ID + ":" + trap.getKey(),
				new BlockRegistration().baseBlock("minecraft:oak_trapdoor")
					.model(MOD_ID + ":block/" + trap.getKey() + "_bottom"));
			PandoricalApi.content().registerItem(MOD_ID + ":" + trap.getKey(),
				new ItemRegistration().model(MOD_ID + ":item/" + trap.getKey()));
		}

		PandoricalApi.content().registerModAssets(MOD_ID);
	}

	/** One of our minecraft-namespace overrides, which registerModAssets does not scan for. */
	private static void shipOverride(String path) {
		try (java.io.InputStream in = Main.class.getClassLoader().getResourceAsStream("assets/" + path)) {
			if (in == null) {
				LOGGER.error("[{}] Missing bundled asset {}; doors stay flat", MOD_ID, path);
				return;
			}
			PandoricalApi.content().registerAsset(path, in.readAllBytes());
		} catch (java.io.IOException e) {
			LOGGER.error("[{}] Could not read bundled asset {}: {}", MOD_ID, path, e.getMessage());
		}
	}
}
