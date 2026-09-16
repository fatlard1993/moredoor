package justfatlard.moredoor.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;

/**
 * The pictures for the readme and the mod page: the six doors a wood offers, side by side in one
 * wall, and a close look at the depth they are built with.
 *
 * <p>Built with commands rather than the mod's own registry, so the scene is written the way a
 * builder would write it and nothing here has to follow a rename. Run it under xvfb-run; the
 * frames land in build/run/clientGameTest/screenshots.
 */
public final class Showcase implements FabricClientGameTest {

	private static final int WIDTH = 1920;
	private static final int HEIGHT = 1080;

	/** Solid first, because it is what the plain recipe gives, then the openings widening. */
	private static final String[] DOORS = {
		"oak_door", "oak_classic_door", "oak_glass_door",
		"oak_barred_door", "oak_full_glass_door", "oak_full_barred_door",
	};

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			context.getInput().pressKey(options -> options.keyToggleGui);
			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("gamerule doWeatherCycle false");
			server.runCommand("gamemode spectator @a");
			server.runCommand("time set noon");

			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			int y = origin.getY();
			int z = origin.getZ() - 8;
			int left = origin.getX() - 6;

			// A wall with a doorway every other block, and a door standing in each.
			server.runCommand("fill %d %d %d %d %d %d minecraft:stone_bricks"
				.formatted(left - 2, y, z, left + DOORS.length * 2, y + 3, z));
			server.runCommand("fill %d %d %d %d %d %d minecraft:smooth_stone"
				.formatted(left - 4, y - 1, z + 1, left + DOORS.length * 2 + 2, y - 1, z + 18));
			for (int i = 0; i < DOORS.length; i++) {
				int x = left + i * 2;
				String door = "more-doors-justfatlard:" + DOORS[i];
				server.runCommand("setblock %d %d %d %s[facing=south,half=lower,hinge=left,open=false]"
					.formatted(x, y, z, door));
				server.runCommand("setblock %d %d %d %s[facing=south,half=upper,hinge=left,open=false]"
					.formatted(x, y + 1, z, door));
			}

			// A second pass with every one shut: a door placed beside others comes up swung,
			// which is the bank logic doing its job and not what this picture is of.
			context.waitTicks(10);
			for (int i = 0; i < DOORS.length; i++) {
				int x = left + i * 2;
				String door = "more-doors-justfatlard:" + DOORS[i];
				server.runCommand("setblock %d %d %d %s[facing=south,half=lower,hinge=left,open=false]"
					.formatted(x, y, z, door));
				server.runCommand("setblock %d %d %d %s[facing=south,half=upper,hinge=left,open=false]"
					.formatted(x, y + 1, z, door));
			}

			// Far enough back that every door is seen through its own doorway rather than edge-on:
			// from close up, the jambs hide the ones at the ends.
			stand(server, left + (DOORS.length - 1.0), y + 0.6, z + 11, 180, 1);
			context.waitTicks(20);
			shoot(context, "doors");

			// And the depth, which is the other half of the point: a low sun across the face, so the
			// frame stands proud of the panel and the handle casts a shadow.
			server.runCommand("time set 1200");
			int door = left + 4;
			stand(server, door + 2.4, y + 0.5, z + 3.0, 208, 4);
			context.waitTicks(30);
			shoot(context, "door-depth");

		}
	}

	/** Put the camera here, looking this way. The y is the feet: the camera sees 1.62 above it. */
	private void stand(TestServerContext server, double x, double y, double z, int yaw, int pitch) {
		server.runCommand("tp @a %.2f %.2f %.2f %d %d".formatted(x, y, z, yaw, pitch));
	}

	private void shoot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name)
			.withSize(WIDTH, HEIGHT)
			.disableCounterPrefix());
	}
}
