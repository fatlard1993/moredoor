package justfatlard.moredoor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * The menu behind a sneaking right-click on a trapdoor: which edge it hangs from, and
 * whether it sits at the top of its block or the bottom.
 *
 * <p>Both are things a trapdoor already has and neither can be changed once it is placed.
 * Vanilla decides the edge from where you were standing and the half from where on the
 * block you clicked, and if it guesses wrong the only fix is to break it and try the
 * approach again - which for a hatch of six means breaking six and getting all six right.
 *
 * <p>So it is set here instead, for the whole hatch at once. The edges are shown as the
 * player sees them rather than as compass directions, the same way the door menu shows its
 * corners: the near edge is the one by your feet.
 */
public final class TrapMenu {
	private TrapMenu() {}

	public static final String TYPE = "moredoor:trapdoor";

	private static final String NEAR = "hatch_near";
	private static final String FAR = "hatch_far";
	private static final String LEFT = "hatch_left";
	private static final String RIGHT = "hatch_right";
	private static final String[] EDGES = {LEFT, FAR, RIGHT, NEAR};

	private static final String TOP = "hatch_top";
	private static final String BOTTOM = "hatch_bottom";
	private static final String[] HALVES = {TOP, BOTTOM};

	private static final int BUTTON = 24;
	private static final int GAP = 4;
	private static final int WIDTH = 176;
	private static final int EDGE_Y = 8;
	private static final int HALF_Y = EDGE_Y + BUTTON + GAP;
	private static final int HEIGHT = HALF_Y + BUTTON + 8;

	/** What a player has open: the trapdoor, and the way they were looking at it. */
	private record Target(BlockPos pos, Direction toward, String screenId) {}

	private static final Map<UUID, Target> target = new ConcurrentHashMap<>();

	public static void register() {
		for (String edge : EDGES) {
			PandoricalApi.screens().onAction(TYPE, edge, (player, data) -> chooseEdge(player, edge));
		}
		for (String half : HALVES) {
			PandoricalApi.screens().onAction(TYPE, half, (player, data) -> chooseHalf(player, half));
		}
		PandoricalApi.screens().onClose(TYPE, player -> target.remove(player.getUUID()));
	}

	public static void open(ServerPlayer player, BlockPos pos, BlockState state) {
		Direction toward = player.getDirection();

		ScreenBuilder screen = new ScreenBuilder(TYPE).size(WIDTH, HEIGHT).title("Trapdoor");
		screen.panel("frame", 0, 0, WIDTH, HEIGHT, Map.of());

		String litEdge = edgeIdOf(state.getValue(TrapDoorBlock.FACING), toward);
		int x = (WIDTH - EDGES.length * BUTTON - (EDGES.length - 1) * GAP) / 2;
		for (String edge : EDGES) {
			screen.button(edge, x, EDGE_Y, BUTTON, BUTTON, props(edge, edge.equals(litEdge)));
			x += BUTTON + GAP;
		}

		String litHalf = state.getValue(TrapDoorBlock.HALF) == Half.TOP ? TOP : BOTTOM;
		x = (WIDTH - HALVES.length * BUTTON - (HALVES.length - 1) * GAP) / 2;
		for (String half : HALVES) {
			screen.button(half, x, HALF_Y, BUTTON, BUTTON, props(half, half.equals(litHalf)));
			x += BUTTON + GAP;
		}

		target.put(player.getUUID(), new Target(pos, toward, screen.screenId()));
		PandoricalApi.screens().open(player, screen.build());
	}

	private static Map<String, String> props(String id, boolean lit) {
		return Map.of(
			ComponentType.PROP_ICON, "moredoor-justfatlard:swing/" + id + (lit ? "_lit" : ""),
			ComponentType.PROP_STYLE, lit ? "pressed" : "default",
			ComponentType.PROP_TOOLTIP_KEY, "moredoor-justfatlard.trapdoor.set." + id);
	}

	/**
	 * A trapdoor hangs from the edge its facing points away from: a trapdoor facing north
	 * swings down from the south edge. Named here as the player sees it, with the near edge
	 * the one by their feet.
	 */
	private static String edgeIdOf(Direction facing, Direction toward) {
		Direction hangs = facing.getOpposite();
		if (hangs == toward) return FAR;
		if (hangs == toward.getOpposite()) return NEAR;
		return hangs == toward.getClockWise() ? RIGHT : LEFT;
	}

	private static Direction facingFor(String edge, Direction toward) {
		Direction hangs = switch (edge) {
			case FAR -> toward;
			case NEAR -> toward.getOpposite();
			case RIGHT -> toward.getClockWise();
			default -> toward.getCounterClockWise();
		};
		return hangs.getOpposite();
	}

	private static void chooseEdge(ServerPlayer player, String edge) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		apply(player, now, facingFor(edge, now.toward()), null);
	}

	private static void chooseHalf(ServerPlayer player, String half) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		apply(player, now, null, half.equals(TOP) ? Half.TOP : Half.BOTTOM);
	}

	/**
	 * Re-hang the whole hatch, closed.
	 *
	 * <p>Closed because an open trapdoor stands where its new edge is about to be, and a
	 * hatch that changed edge while open would be half in the floor and half out of it.
	 */
	private static void apply(ServerPlayer player, Target now, Direction facing, Half half) {
		ServerLevel level = player.level();
		BlockState state = level.getBlockState(now.pos());
		if (!(state.getBlock() instanceof TrapDoorBlock)) return;

		Set<BlockPos> bank = TrapBank.bankOf(level, now.pos(), state);
		for (BlockPos each : bank) {
			BlockState there = level.getBlockState(each);
			if (!(there.getBlock() instanceof TrapDoorBlock)) continue;
			there = there.setValue(TrapDoorBlock.OPEN, false);
			if (facing != null) there = there.setValue(TrapDoorBlock.FACING, facing);
			if (half != null) there = there.setValue(TrapDoorBlock.HALF, half);
			level.setBlock(each, there, Block.UPDATE_ALL);
		}

		level.playSound(null, now.pos(), SoundEvents.ITEM_FRAME_ROTATE_ITEM,
			SoundSource.BLOCKS, 0.8F, 1.0F);

		BlockState after = level.getBlockState(now.pos());
		String litEdge = edgeIdOf(after.getValue(TrapDoorBlock.FACING), now.toward());
		String litHalf = after.getValue(TrapDoorBlock.HALF) == Half.TOP ? TOP : BOTTOM;
		player.sendOverlayMessage(Component.translatable(
			"moredoor-justfatlard.trapdoor.set." + (facing != null ? litEdge : litHalf)));

		List<ComponentUpdate> updates = new ArrayList<>();
		for (String edge : EDGES) updates.add(new ComponentUpdate(edge, props(edge, edge.equals(litEdge))));
		for (String h : HALVES) updates.add(new ComponentUpdate(h, props(h, h.equals(litHalf))));
		PandoricalApi.screens().update(player, now.screenId(), updates);
	}
}
