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
 * right post. Left and right are the player's, from the side they opened the menu on. A gate
 * stacked on another is one tall gate, and is hung as one.
 *
 * <p>A gate with a gate beside it opens from the middle and nothing else: the pair is drawn
 * as one wide gate whose halves swing from the outer posts, and a single leaf two blocks long
 * is longer than a block model can be. The single-leaf pictures are there but not pressable.
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

	/** What a player has open: the gate, the side they see it from, whether it has a gate beside it. */
	private record Target(BlockPos pos, Direction toward, boolean pair, String screenId) {}

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
		boolean pair = GateBank.joins(level.getBlockState(pos.relative(facing.getClockWise())), state)
			|| GateBank.joins(level.getBlockState(pos.relative(facing.getCounterClockWise())), state);

		ScreenBuilder screen = new ScreenBuilder(TYPE).size(WIDTH, HEIGHT).title("Gate");
		screen.panel("frame", 0, 0, WIDTH, HEIGHT, Map.of());
		GateSwing lit = seen(GateSwings.get(level).settingOf(pos), toward, facing);
		int x = (WIDTH - 3 * BUTTON - 2 * GAP) / 2;
		for (String id : BUTTONS) {
			screen.button(id, x, ROW_Y, BUTTON, BUTTON, props(id, lit, pair));
			x += BUTTON + GAP;
		}
		target.put(player.getUUID(), new Target(pos, toward, pair, screen.screenId()));
		PandoricalApi.screens().open(player, screen.build());
	}

	/** The way from the player to the gate along its own axis: the side they see it from. */
	private static Direction toward(ServerPlayer player, BlockPos pos, Direction facing) {
		double along = (pos.getX() + 0.5 - player.getX()) * facing.getStepX()
			+ (pos.getZ() + 0.5 - player.getZ()) * facing.getStepZ();
		return along >= 0 ? facing : facing.getOpposite();
	}

	/** The gate's own hinge side as the player sees it: the same from behind, mirrored from in front. */
	private static GateSwing seen(GateSwing own, Direction toward, Direction facing) {
		return toward == facing ? own : own.mirrored();
	}

	private static String idOf(GateSwing seen) {
		return seen == GateSwing.LEFT ? LEFT : seen == GateSwing.RIGHT ? RIGHT : DOUBLE;
	}

	/** The picture, lit when it is the answer; a single leaf is not on offer for a gate in a pair. */
	private static Map<String, String> props(String id, GateSwing lit, boolean pair) {
		boolean isLit = id.equals(idOf(lit));
		boolean allowed = !pair || id.equals(DOUBLE);
		return Map.of(
			ComponentType.PROP_ICON, "moredoor-justfatlard:swing/" + id + (isLit ? "_lit" : ""),
			ComponentType.PROP_STYLE, isLit ? "pressed" : "default",
			ComponentType.PROP_ENABLED, String.valueOf(allowed),
			ComponentType.PROP_TOOLTIP_KEY, allowed
				? "moredoor-justfatlard.gate.set." + id : "moredoor-justfatlard.gate.pair");
	}

	private static void choose(ServerPlayer player, GateSwing seen) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		ServerLevel level = player.level();
		BlockState state = level.getBlockState(now.pos());
		if (!(state.getBlock() instanceof FenceGateBlock)) return;
		if (now.pair() && seen != GateSwing.DOUBLE) return;
		Direction facing = state.getValue(FenceGateBlock.FACING);
		GateSwing own = seen(seen, now.toward(), facing);

		// The whole gate: every storey of a tall one, hung the same way.
		GateSwings swings = GateSwings.get(level);
		for (BlockPos gate : GateBank.gatesOf(level, now.pos(), state)) swings.set(level, gate, own);
		level.playSound(null, now.pos(), SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.8F, 1.0F);
		player.sendOverlayMessage(Component.translatable("moredoor-justfatlard.gate.set." + idOf(seen)));

		List<ComponentUpdate> updates = new ArrayList<>();
		for (String id : BUTTONS) updates.add(new ComponentUpdate(id, props(id, seen, now.pair())));
		PandoricalApi.screens().update(player, now.screenId(), updates);
	}
}
