package justfatlard.moredoor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentUpdate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The menu behind a sneaking right-click on a fence gate: how it opens.
 *
 * <p>Three pictures, one lit: the gate from above with the player at the bottom, opening as
 * one leaf from the left post, as the two leaves the game gives it, or as one leaf from the
 * right post. Left and right are the player's, from the side they opened the menu on.
 *
 * <p>Gates beside and on top of one another are one gate, and are hung as one: every gate of it
 * carries the same hinge, and a wide one swings its blocks out as a single leaf from its end post,
 * or as two leaves meeting in the middle ({@link GateGroup}).
 */
public final class GateMenu {
	private GateMenu() {}

	public static final String TYPE = "moredoor:gate";
	private static final String LEFT = "gate_left";
	private static final String DOUBLE = "gate_double";
	private static final String RIGHT = "gate_right";
	private static final String[] BUTTONS = {LEFT, DOUBLE, RIGHT};

	private static final int BUTTON = 24;
	private static final int GAP = 4;
	private static final int WIDTH = 176;
	private static final int ROW_Y = 8;
	private static final int HEIGHT = ROW_Y + BUTTON + 8;

	/** What a player has open: the gate, the level it is in, and the side they see it from. */
	private record Target(BlockPos pos, ServerLevel level, Direction toward, String screenId) {}

	private static final Map<UUID, Target> target = new ConcurrentHashMap<>();

	public static void register() {
		PandoricalApi.screens().onAction(TYPE, LEFT, (player, data) -> choose(player, GateSwing.LEFT));
		PandoricalApi.screens().onAction(TYPE, DOUBLE, (player, data) -> choose(player, GateSwing.DOUBLE));
		PandoricalApi.screens().onAction(TYPE, RIGHT, (player, data) -> choose(player, GateSwing.RIGHT));
		PandoricalApi.screens().onClose(TYPE, player -> target.remove(player.getUUID()));
	}

	public static void open(ServerPlayer player, BlockPos pos, BlockState state) {
		ServerLevel level = player.level();
		Direction facing = state.getValue(FenceGateBlock.FACING);
		Direction toward = toward(player, pos, facing);

		ScreenBuilder screen = new ScreenBuilder(TYPE).size(WIDTH, HEIGHT).title("Gate");
		screen.panel("frame", 0, 0, WIDTH, HEIGHT, Map.of());
		GateSwing lit = seen(GateSwings.get(level).agreedBy(level, GateBank.gatesOf(level, pos, state), facing), toward, facing);
		int x = (WIDTH - 3 * BUTTON - 2 * GAP) / 2;
		for (String id : BUTTONS) {
			screen.button(id, x, ROW_Y, BUTTON, BUTTON, props(id, lit));
			x += BUTTON + GAP;
		}
		target.put(player.getUUID(), new Target(pos, level, toward, screen.screenId()));
		PandoricalApi.screens().open(player, screen.build());
	}

	/** The way from the player to the gate along its own axis: the side they see it from. */
	private static Direction toward(ServerPlayer player, BlockPos pos, Direction facing) {
		double along = (pos.getX() + 0.5 - player.getX()) * facing.getStepX()
			+ (pos.getZ() + 0.5 - player.getZ()) * facing.getStepZ();
		return along >= 0 ? facing : facing.getOpposite();
	}

	/**
	 * The gate's own hinge side as the player sees it: the same from behind, mirrored from in
	 * front. Mirroring twice is no change, so the same turn takes the player's side to the gate's.
	 */
	private static GateSwing seen(GateSwing own, Direction toward, Direction facing) {
		return toward == facing ? own : own.mirrored();
	}

	private static String idOf(GateSwing seen) {
		return seen == GateSwing.LEFT ? LEFT : seen == GateSwing.RIGHT ? RIGHT : DOUBLE;
	}

	/** The picture, lit when it is the answer it already has. */
	private static Map<String, String> props(String id, GateSwing lit) {
		boolean isLit = id.equals(idOf(lit));
		return Map.of(
			ComponentType.PROP_ICON, "moredoor-justfatlard:swing/" + id + (isLit ? "_lit" : ""),
			ComponentType.PROP_STYLE, isLit ? "pressed" : "default",
			ComponentType.PROP_TOOLTIP_KEY, "moredoor-justfatlard.gate.set." + id);
	}

	private static void choose(ServerPlayer player, GateSwing seen) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		ServerLevel level = player.level();
		if (level != now.level() || !player.isWithinBlockInteractionRange(now.pos(), 1.0)) return;
		BlockState state = level.getBlockState(now.pos());
		if (!(state.getBlock() instanceof FenceGateBlock)) return;
		GateSwings swings = GateSwings.get(level);
		// An open wide gate's blocks stand where its leaves are; it is hung again once it is shut.
		if (swings.openAt(now.pos()) != null) {
			player.sendOverlayMessage(Component.translatable("moredoor-justfatlard.gate.shut_first"));
			return;
		}
		Direction facing = state.getValue(FenceGateBlock.FACING);
		GateSwing own = seen(seen, now.toward(), facing);

		swings.hang(level, GateBank.gatesOf(level, now.pos(), state), facing, own);
		level.playSound(null, now.pos(), SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.8F, 1.0F);
		player.sendOverlayMessage(Component.translatable("moredoor-justfatlard.gate.set." + idOf(seen)));

		List<ComponentUpdate> updates = new ArrayList<>();
		for (String id : BUTTONS) updates.add(new ComponentUpdate(id, props(id, seen)));
		PandoricalApi.screens().update(player, now.screenId(), updates);
	}
}
