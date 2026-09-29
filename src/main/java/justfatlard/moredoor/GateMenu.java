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
 * <p>Every gate is hung on its own, a gate beside another included. The pair used to be offered
 * nothing but the middle, on the grounds that it is drawn as one wide gate whose leaf would be two
 * blocks long and longer than a block model can be - but nothing draws it that way. {@link
 * GateGroup} is the half-built answer to that and says so itself, and {@link GateMove} is reached
 * only by a redstone close, so two gates side by side stay two one-block gates that happen to open
 * together. Each can therefore hang whichever way it likes. Put the restriction back with the
 * merged model, not before it.
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

	/** What a player has open: the gate and the side they see it from. */
	private record Target(BlockPos pos, Direction toward, String screenId) {}

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
		GateSwing lit = seen(GateSwings.get(level).settingOf(pos), toward, facing);
		int x = (WIDTH - 3 * BUTTON - 2 * GAP) / 2;
		for (String id : BUTTONS) {
			screen.button(id, x, ROW_Y, BUTTON, BUTTON, props(id, lit));
			x += BUTTON + GAP;
		}
		target.put(player.getUUID(), new Target(pos, toward, screen.screenId()));
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

	/** The picture, lit when it is the answer it already has. */
	private static Map<String, String> props(String id, GateSwing lit) {
		boolean isLit = id.equals(idOf(lit));
		return Map.of(
			ComponentType.PROP_ICON, "moredoor-justfatlard:swing/" + id + (isLit ? "_lit" : ""),
			ComponentType.PROP_STYLE, isLit ? "pressed" : "default",
			ComponentType.PROP_TOOLTIP_KEY, "moredoor-justfatlard.gate.set." + id);
	}

	/** This gate's own column: the blocks stacked on it, without the gates standing beside it. */
	private static List<BlockPos> storeys(ServerLevel level, BlockPos pos, BlockState state) {
		List<BlockPos> column = new ArrayList<>();
		column.add(pos);
		for (Direction step : new Direction[] {Direction.UP, Direction.DOWN}) {
			for (BlockPos at = pos.relative(step); GateBank.joins(level.getBlockState(at), state);
					at = at.relative(step)) {
				column.add(at);
			}
		}
		return column;
	}

	private static void choose(ServerPlayer player, GateSwing seen) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		ServerLevel level = player.level();
		BlockState state = level.getBlockState(now.pos());
		if (!(state.getBlock() instanceof FenceGateBlock)) return;
		Direction facing = state.getValue(FenceGateBlock.FACING);
		GateSwing own = seen(seen, now.toward(), facing);

		// Every storey of this gate, and only this one. GateBank.gatesOf walks along the line as
		// well as up it, which is right for opening a bank together and wrong here: a gate beside
		// this one is its own gate and keeps its own hinge, or a pair could never meet in the middle.
		GateSwings swings = GateSwings.get(level);
		for (BlockPos gate : storeys(level, now.pos(), state)) swings.set(level, gate, own);
		level.playSound(null, now.pos(), SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.8F, 1.0F);
		player.sendOverlayMessage(Component.translatable("moredoor-justfatlard.gate.set." + idOf(seen)));

		List<ComponentUpdate> updates = new ArrayList<>();
		for (String id : BUTTONS) updates.add(new ComponentUpdate(id, props(id, seen)));
		PandoricalApi.screens().update(player, now.screenId(), updates);
	}
}
