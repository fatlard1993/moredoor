package justfatlard.moredoor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
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
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * The menu behind a sneaking right-click on a door: how it moves.
 *
 * <p>Two rows of pictures, one of them lit. The first row is the block seen from above with the
 * player at the bottom of it: a leaf along the near edge or the far one, hung from its left corner
 * or its right - the four corners a door can hang from. The second is a leaf sliding left, right,
 * up or down. Press another and the door is re-hung that way, the whole door, every leaf.
 *
 * <p>Near and far are the player's: the same corner is a different picture from the other side
 * of the door, and the pictures are drawn for whichever side the player opened the menu from.
 *
 * <p>Beside the slides, one more picture moves the door to the other face of its block: a slide
 * pulled to the far edge instead of the near, a hinged leaf swung the other way about the same
 * corner.
 *
 * <p>Below, when they apply: a button that cuts this leaf loose from the door it is part of, and
 * one that makes one door of every leaf standing together here. Both can apply at once - a leaf
 * can be part of a door and have leaves beside it that are not - and each shows on its own.
 */
public final class SwingMenu {
	private SwingMenu() {}

	public static final String TYPE = "moredoor:swing";
	private static final String DISCONNECT = "disconnect";
	private static final String AUTOCONNECT = "autoconnect";
	private static final String FLIP = "face_flip";
	private static final String LOCK = "lock";

	private static final int BUTTON = 24;
	private static final int GAP = 4;
	private static final int WIDTH = 176;
	private static final int HINGE_Y = 8;
	private static final int SLIDE_Y = HINGE_Y + BUTTON + GAP;
	private static final int LINK_Y = SLIDE_Y + BUTTON + 8;
	private static final int HEIGHT_ALONE = SLIDE_Y + BUTTON + 8;
	private static final int HEIGHT_LINKED = LINK_Y + 20 + 8;
	private static final int ROW = 20;
	private static final int MAX_LEAVES = 64;

	/** One of the four corners a hinged leaf can hang from, as the player sees the block. */
	private enum Corner {
		NEAR_LEFT(false, false), NEAR_RIGHT(false, true), FAR_LEFT(true, false), FAR_RIGHT(true, true);

		final boolean far;
		final boolean right;

		Corner(boolean far, boolean right) {
			this.far = far;
			this.right = right;
		}

		String id() {
			return "hinge_" + name().toLowerCase();
		}

		/** The block's facing for a leaf on this corner, seen from {@code toward}. */
		Direction facing(Direction toward) {
			return far ? toward.getOpposite() : toward;
		}

		/**
		 * The block's hinge for a leaf on this corner. A leaf's left is its facing's left, and a
		 * leaf on the far edge faces away, so its left is the player's right.
		 */
		DoorHingeSide hinge() {
			return right != far ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
		}

		static Corner of(BlockState state, Direction toward) {
			boolean far = state.getValue(DoorBlock.FACING) != toward;
			boolean leafRight = state.getValue(DoorBlock.HINGE) == DoorHingeSide.RIGHT;
			return far ? (leafRight ? FAR_LEFT : FAR_RIGHT) : (leafRight ? NEAR_RIGHT : NEAR_LEFT);
		}
	}

	private static final Swing[] SLIDES = {Swing.SLIDE_LEFT, Swing.SLIDE_RIGHT, Swing.SLIDE_UP, Swing.SLIDE_DOWN};

	/** What a player has open: the door, the side they see it from, and the screen. */
	private record Target(BlockPos foot, Direction toward, String screenId) {}

	private static final Map<UUID, Target> target = new ConcurrentHashMap<>();

	public static void register() {
		for (Corner corner : Corner.values()) {
			PandoricalApi.screens().onAction(TYPE, corner.id(), (player, data) -> chooseCorner(player, corner));
		}
		for (Swing slide : SLIDES) {
			PandoricalApi.screens().onAction(TYPE, slide.name().toLowerCase(), (player, data) -> apply(player, slide, null));
		}
		PandoricalApi.screens().onAction(TYPE, FLIP, (player, data) -> flipFace(player));
		PandoricalApi.screens().onAction(TYPE, DISCONNECT, (player, data) -> disconnect(player));
		PandoricalApi.screens().onAction(TYPE, AUTOCONNECT, (player, data) -> autoconnect(player));
		PandoricalApi.screens().onAction(TYPE, LOCK, (player, data) -> cycleLock(player));
		PandoricalApi.screens().onClose(TYPE, player -> target.remove(player.getUUID()));
	}

	public static void open(ServerPlayer player, BlockPos pos, BlockState state) {
		ServerLevel level = player.level();
		BlockPos foot = DoorBank.footOf(pos, state);
		BlockState footState = level.getBlockState(foot);
		Direction toward = toward(player, foot, footState);
		boolean linked = touching(level, foot, footState);
		int height = linked ? HEIGHT_LINKED : HEIGHT_ALONE;
		boolean joined = joined(level, foot);
		boolean loose = looseNeighbours(level, foot);

		// Always there, whatever the door is: a menu that only grows a lock once the door is
		// already locked is a menu with no way to lock a door.
		DoorLocks.Lock lock = DoorLocks.get(level).lockAt(foot);
		int lockY = linked ? LINK_Y + ROW + GAP : LINK_Y;
		height = lockY + ROW + 8;

		ScreenBuilder screen = new ScreenBuilder(TYPE).size(WIDTH, height).title("Door");
		screen.panel("frame", 0, 0, WIDTH, height, Map.of());

		int x0 = (WIDTH - 5 * BUTTON - 4 * GAP) / 2;
		Corner lit = litCorner(level, foot, toward);
		Swing now = DoorSwings.get(level).settingOf(foot, footState);
		int x = x0;
		for (Corner corner : Corner.values()) {
			screen.button(corner.id(), x, HINGE_Y, BUTTON, BUTTON, buttonProps(corner.id(), corner == lit));
			x += BUTTON + GAP;
		}
		x = x0;
		for (Swing slide : SLIDES) {
			screen.button(slide.name().toLowerCase(), x, SLIDE_Y, BUTTON, BUTTON,
				buttonProps(slide.name().toLowerCase(), slide == now));
			x += BUTTON + GAP;
		}
		screen.button(FLIP, x, SLIDE_Y, BUTTON, BUTTON, buttonProps(FLIP, false));

		// Only when there is something beside this leaf to be joined to or cut from; each button
		// on its own, side by side when both apply.
		if (linked) {
			int half = (WIDTH - 16 - GAP) / 2;
			screen.button(DISCONNECT, 8, LINK_Y, half, 20, linkProps("moredoor-justfatlard.swing.disconnect", joined));
			screen.button(AUTOCONNECT, 8 + half + GAP, LINK_Y, half, 20, linkProps("moredoor-justfatlard.swing.autoconnect", loose));
		}

		screen.button(LOCK, 8, lockY, WIDTH - 16, ROW, lockProps(lock));

		target.put(player.getUUID(), new Target(foot, toward, screen.screenId()));
		PandoricalApi.screens().open(player, screen.build());
	}

	/**
	 * The way from the player to the door along the door's own axis: the side they see it from.
	 * Read from where they stand, not where they look, so a glance along the wall does not
	 * turn the pictures round.
	 */
	private static Direction toward(ServerPlayer player, BlockPos foot, BlockState state) {
		Direction facing = state.getValue(DoorBlock.FACING);
		double along = (foot.getX() + 0.5 - player.getX()) * facing.getStepX()
			+ (foot.getZ() + 0.5 - player.getZ()) * facing.getStepZ();
		return along >= 0 ? facing : facing.getOpposite();
	}

	/** The corner lit in the top row: the leaf's own, unless it slides, when none is. */
	private static Corner litCorner(ServerLevel level, BlockPos foot, Direction toward) {
		BlockState state = level.getBlockState(foot);
		if (!(state.getBlock() instanceof DoorBlock)) return null;
		if (DoorSwings.get(level).settingOf(foot, state).isSlide()) return null;
		return Corner.of(state, toward);
	}

	/** The picture, lit when it is the answer, with the words for it under the pointer. */
	private static Map<String, String> buttonProps(String id, boolean lit) {
		return Map.of(
			ComponentType.PROP_ICON, "moredoor-justfatlard:swing/" + id + (lit ? "_lit" : ""),
			ComponentType.PROP_STYLE, lit ? "pressed" : "default",
			ComponentType.PROP_TOOLTIP_KEY, "moredoor-justfatlard.swing.set." + id);
	}

	/** The lock, reading as what the door is now; the tooltip says what pressing it does next. */
	private static Map<String, String> lockProps(DoorLocks.Lock lock) {
		String state = lock == null ? "off" : lock.isPublic() ? "anyone" : "on";
		return Map.of(
			ComponentType.PROP_LABEL_KEY, "moredoor-justfatlard.swing.lock_" + state,
			ComponentType.PROP_STYLE, lock == null ? "default" : "pressed",
			ComponentType.PROP_TOOLTIP_KEY, "moredoor-justfatlard.swing.lock_" + state + "_tip");
	}

	/**
	 * Round the three things a door's lock can be: nobody's, yours, or yours and open to all.
	 *
	 * <p>One button through all three, which is what chest-utils does with a chest, deliberately:
	 * the two locks are the same idea and a player who has met one should not have to learn the
	 * other. A door left open is still yours - anybody walks through it, nobody else breaks it,
	 * re-hangs it or takes the lock off.
	 */
	private static void cycleLock(ServerPlayer player) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		ServerLevel level = player.level();
		BlockPos foot = now.foot();
		BlockState state = level.getBlockState(foot);
		if (!(state.getBlock() instanceof DoorBlock)) return;

		DoorLocks locks = DoorLocks.get(level);
		// Asked fresh rather than trusted from when the menu opened: somebody else may have locked
		// it since, and an open door's menu opens for anybody.
		if (locks.refuses(player, level, foot, state, DoorLocks.Use.LOCK)) {
			player.sendOverlayMessage(Component.translatable("moredoor-justfatlard.locked.by",
				locks.lockedBy(level, foot, state)));
			return;
		}

		DoorLocks.Lock was = locks.lockAt(foot);
		String said;
		if (was == null) {
			locks.lock(player, level, foot, state);
			said = "moredoor-justfatlard.swing.lock_on_said";
		} else if (!was.isPublic()) {
			locks.publish(level, foot, state, true);
			said = "moredoor-justfatlard.swing.lock_anyone_said";
		} else {
			locks.unlock(level, foot, state);
			said = "moredoor-justfatlard.swing.lock_off_said";
		}

		level.playSound(null, foot, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.6F,
			locks.lockAt(foot) == null ? 0.8F : 1.4F);
		player.sendOverlayMessage(Component.translatable(said));
		PandoricalApi.screens().update(player, now.screenId(),
			List.of(new ComponentUpdate(LOCK, lockProps(locks.lockAt(foot)))));
	}

	private static Map<String, String> linkProps(String labelKey, boolean shown) {
		return Map.of(ComponentType.PROP_LABEL_KEY, labelKey, ComponentType.PROP_VISIBLE, String.valueOf(shown));
	}

	/** Whether this leaf is part of a door with any other leaf: joined, not merely beside. */
	private static boolean joined(ServerLevel level, BlockPos foot) {
		DoorGroup group = DoorGroup.at(level, foot);
		return group != null && group.width * group.height > 1;
	}

	/** Whether any leaf standing together with this one is not part of its door. */
	private static boolean looseNeighbours(ServerLevel level, BlockPos foot) {
		DoorGroup group = DoorGroup.at(level, foot);
		Set<BlockPos> mine = new java.util.HashSet<>(group != null ? group.feet() : List.of(foot));
		for (BlockPos leaf : contiguous(level, foot, null)) {
			if (!mine.contains(leaf)) return true;
		}
		return false;
	}

	/**
	 * The lower halves of every same door standing against this one along the given line, or
	 * above or below it, whichever way they face or hang: a leaf put in turned a quarter round
	 * is still a leaf in the row.
	 */
	private static List<BlockPos> neighbours(ServerLevel level, BlockPos foot, BlockState state, Direction along) {
		List<BlockPos> found = new ArrayList<>();
		if (!(state.getBlock() instanceof DoorBlock)) return found;
		for (BlockPos near : new BlockPos[] {foot.relative(along), foot.relative(along.getOpposite()), foot.above(2), foot.below(2)}) {
			BlockState there = level.getBlockState(near);
			if (there.is(state.getBlock()) && there.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) found.add(near);
		}
		return found;
	}

	/** Whether another leaf of the same door block stands against this one. */
	private static boolean touching(ServerLevel level, BlockPos foot, BlockState state) {
		if (!(state.getBlock() instanceof DoorBlock)) return false;
		return !neighbours(level, foot, state, state.getValue(DoorBlock.FACING).getClockWise()).isEmpty();
	}

	/**
	 * Every leaf reachable from this one through leaves standing against each other, this one
	 * first: in a row facing the given way, or, given no way, on any side at all.
	 */
	private static Set<BlockPos> contiguous(ServerLevel level, BlockPos foot, Direction facing) {
		Set<BlockPos> found = new LinkedHashSet<>();
		Deque<BlockPos> pending = new ArrayDeque<>();
		found.add(foot);
		pending.add(foot);
		while (!pending.isEmpty() && found.size() < MAX_LEAVES) {
			BlockPos at = pending.poll();
			BlockState state = level.getBlockState(at);
			List<BlockPos> next = facing != null ? neighbours(level, at, state, facing.getClockWise())
				: new ArrayList<>();
			if (facing == null) {
				next.addAll(neighbours(level, at, state, Direction.NORTH));
				next.addAll(neighbours(level, at, state, Direction.EAST));
			}
			for (BlockPos near : next) {
				if (found.add(near)) pending.add(near);
			}
		}
		return found;
	}

	/** The door moved to the other face of its block: a slide pulled to the far edge, a hinge swung the other way about its corner. */
	private static void flipFace(ServerPlayer player) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		ServerLevel level = player.level();
		BlockState state = level.getBlockState(now.foot());
		if (!(state.getBlock() instanceof DoorBlock)) return;
		Swing swing = DoorSwings.get(level).settingOf(now.foot(), state);
		Direction facing = state.getValue(DoorBlock.FACING).getOpposite();
		// The same corner from the other face is the other hinge side.
		if (swing.isSideHinge()) swing = swing == Swing.LEFT ? Swing.RIGHT : Swing.LEFT;
		apply(player, swing, facing);
	}

	private static void chooseCorner(ServerPlayer player, Corner corner) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		apply(player, Swing.of(corner.hinge()), corner.facing(now.toward()));
	}

	/** Re-light both rows for what the door is now. */
	private static void refresh(ServerPlayer player) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		ServerLevel level = player.level();
		Corner lit = litCorner(level, now.foot(), now.toward());
		Swing swing = DoorSwings.get(level).settingOf(now.foot(), level.getBlockState(now.foot()));
		List<ComponentUpdate> updates = new ArrayList<>();
		for (Corner corner : Corner.values()) {
			updates.add(new ComponentUpdate(corner.id(), buttonProps(corner.id(), corner == lit)));
		}
		for (Swing slide : SLIDES) {
			updates.add(new ComponentUpdate(slide.name().toLowerCase(), buttonProps(slide.name().toLowerCase(), slide == swing)));
		}
		if (touching(level, now.foot(), level.getBlockState(now.foot()))) {
			updates.add(new ComponentUpdate(DISCONNECT, linkProps("moredoor-justfatlard.swing.disconnect", joined(level, now.foot()))));
			updates.add(new ComponentUpdate(AUTOCONNECT, linkProps("moredoor-justfatlard.swing.autoconnect", looseNeighbours(level, now.foot()))));
		}
		PandoricalApi.screens().update(player, now.screenId(), updates);
	}

	/** Cut this leaf loose from the door it is part of. */
	private static void disconnect(ServerPlayer player) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		ServerLevel level = player.level();
		BlockPos foot = now.foot();
		BlockState state = level.getBlockState(foot);
		if (!(state.getBlock() instanceof DoorBlock)) return;
		if (DoorLocks.get(level).refuses(player, level, foot, state, DoorLocks.Use.ALTER)) return;
		if (!joined(level, foot)) return;

		// Not while open: a slid door would never find its way back.
		if (!settle(player, foot)) return;
		DoorSwings.get(level).setDetached(level, foot, true);
		level.playSound(null, foot, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.8F, 0.8F);
		player.sendOverlayMessage(Component.translatable("moredoor-justfatlard.swing.disconnected"));
		refresh(player);
	}

	/**
	 * Make one door of every leaf standing together here: all closed, all turned and hung like
	 * the biggest door among them, none cut loose.
	 */
	private static void autoconnect(ServerPlayer player) {
		Target now = target.get(player.getUUID());
		if (now == null) return;
		ServerLevel level = player.level();
		BlockPos foot = now.foot();
		BlockState state = level.getBlockState(foot);
		if (!(state.getBlock() instanceof DoorBlock)) return;
		DoorLocks locks = DoorLocks.get(level);
		if (locks.refuses(player, level, foot, state, DoorLocks.Use.ALTER)) return;
		DoorSwings swings = DoorSwings.get(level);

		// Hung the way the biggest door among them hangs, which is the one worth joining.
		// Everything touching is looked at to find that door, whichever way any of it faces; then
		// the row is read along its line.
		Swing like = swings.settingOf(foot, state);
		Direction facing = state.getValue(DoorBlock.FACING);
		DoorHingeSide hinge = state.getValue(DoorBlock.HINGE);
		Set<BlockPos> touching = contiguous(level, foot, null);
		int biggest = 1;
		for (BlockPos leaf : touching) {
			DoorGroup group = DoorGroup.at(level, leaf);
			int size = group != null ? group.width * group.height : 1;
			if (size > biggest) {
				biggest = size;
				like = group.swing;
				facing = group.facing;
				hinge = group.hinge;
			}
		}
		// Nothing standing here is a door yet, which is what one leaf hung the wrong way does to a
		// whole wall of them: they fill a rectangle, so every one of them is in the group, and one
		// odd hinge means the rectangle never closes. There is no bigger door to copy then, so they
		// follow the way most of them already hang rather than the one that happened to be clicked
		// - which would otherwise let a click on the odd leaf turn the whole wall around.
		if (biggest == 1) {
			hinge = commonHinge(level, touching, hinge);
			if (like.isSideHinge()) like = Swing.of(hinge);
		}
		Set<BlockPos> leaves = contiguous(level, foot, facing);
		for (BlockPos leaf : leaves) {
			if (locks.refuses(player, level, leaf, level.getBlockState(leaf), DoorLocks.Use.ALTER)) {
				player.sendOverlayMessage(Component.translatable("moredoor-justfatlard.swing.autoconnect_locked"));
				return;
			}
		}
		for (BlockPos leaf : leaves) {
			if (!settle(player, leaf)) return;
		}
		for (BlockPos leaf : leaves) {
			swings.setDetached(level, leaf, false);
			hang(level, leaf, like, facing, hinge);
		}
		level.playSound(null, foot, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.8F, 1.2F);
		DoorGroup made = DoorGroup.at(level, foot);
		boolean whole = made != null && made.width * made.height == leaves.size();
		player.sendOverlayMessage(Component.translatable(whole
			? "moredoor-justfatlard.swing.autoconnected" : "moredoor-justfatlard.swing.autoconnect_apart"));
		refresh(player);
	}

	/**
	 * Close the door this leaf belongs to, however it was opened. An open door's squares are out
	 * where they went, and how they come back is the one thing that must not change while they
	 * are.
	 *
	 * @return false if it could not close, which the player has been told
	 */
	private static boolean settle(ServerPlayer player, BlockPos foot) {
		ServerLevel level = player.level();
		BlockState state = level.getBlockState(foot);
		if (!(state.getBlock() instanceof DoorBlock)) return true;
		DoorGroup standing = DoorGroup.at(level, foot);
		if (standing != null && standing.open) {
			if (!DoorSwing.set(level, player, standing, false, true)) {
				player.sendOverlayMessage(Component.translatable("moredoor-justfatlard.swing.blocked"));
				refresh(player);
				return false;
			}
		} else if (state.getValue(DoorBlock.OPEN)) {
			// A door one leaf wide, open the vanilla way, closes the vanilla way.
			for (BlockPos half : new BlockPos[] {foot, foot.above()}) {
				BlockState there = level.getBlockState(half);
				if (there.getBlock() instanceof DoorBlock) {
					level.setBlock(half, there.setValue(DoorBlock.OPEN, false), Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
				}
			}
		}
		return true;
	}

	/** Hang one leaf this way, leaving the side it hangs from alone. */
	public static void hang(ServerLevel level, BlockPos leaf, Swing swing, Direction facing) {
		hang(level, leaf, swing, facing, null);
	}

	/**
	 * Hang one leaf this way: the slide remembered, the side hinge written into its blocks, and
	 * the leaf turned to face the given way if one is given.
	 *
	 * <p>A side hinge is the swing, so it writes itself. A slide is not, and its leaves still carry
	 * a hinge: it decides nothing about how the door moves, but {@link DoorGroup#joins} reads it,
	 * because two leaves hung on opposite sides are mirrored and do not make one door. So a slide
	 * is given the hinge to settle on, and one odd leaf in a slid wall becomes something the
	 * autoconnect button can put right. Null leaves it as it stands, for a caller with no opinion.
	 */
	public static void hang(ServerLevel level, BlockPos leaf, Swing swing, Direction facing,
			DoorHingeSide hinge) {
		DoorSwings.get(level).setSetting(level, leaf, swing);
		for (BlockPos half : new BlockPos[] {leaf, leaf.above()}) {
			BlockState there = level.getBlockState(half);
			if (!(there.getBlock() instanceof DoorBlock)) continue;
			if (facing != null) there = there.setValue(DoorBlock.FACING, facing);
			if (swing.isSideHinge()) {
				there = there.setValue(DoorBlock.HINGE, swing == Swing.RIGHT ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT);
			} else if (hinge != null) {
				there = there.setValue(DoorBlock.HINGE, hinge);
			}
			level.setBlock(half, there, Block.UPDATE_ALL);
		}
	}

	/** The side most of these leaves hang from, or the given fallback where they are evenly split. */
	private static DoorHingeSide commonHinge(ServerLevel level, Set<BlockPos> leaves, DoorHingeSide fallback) {
		int right = 0;
		int left = 0;
		for (BlockPos leaf : leaves) {
			BlockState there = level.getBlockState(leaf);
			if (!there.hasProperty(DoorBlock.HINGE)) continue;
			if (there.getValue(DoorBlock.HINGE) == DoorHingeSide.RIGHT) right++;
			else left++;
		}
		return right == left ? fallback : right > left ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
	}

	private static void apply(ServerPlayer player, Swing swing, Direction facing) {
		Target chosen = target.get(player.getUUID());
		if (chosen == null) return;
		ServerLevel level = player.level();
		BlockPos foot = chosen.foot();
		BlockState state = level.getBlockState(foot);
		if (!(state.getBlock() instanceof DoorBlock)) return;
		if (DoorLocks.get(level).refuses(player, level, foot, state, DoorLocks.Use.ALTER)) return;
		if (!settle(player, foot)) return;

		// The whole door, always: a leaf that should not follow is cut loose first.
		DoorGroup group = DoorGroup.at(level, foot);
		Iterable<BlockPos> feet = group != null ? group.feet() : List.of(foot);
		DoorHingeSide hinge = group != null ? group.hinge : state.getValue(DoorBlock.HINGE);
		for (BlockPos leaf : feet) hang(level, leaf, swing, facing, hinge);
		level.playSound(null, foot, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.8F, 1.0F);
		String said = swing.isSideHinge()
			? Corner.of(level.getBlockState(foot), chosen.toward()).id() : swing.name().toLowerCase();
		player.sendOverlayMessage(Component.translatable("moredoor-justfatlard.swing.set." + said));
		refresh(player);
	}
}
