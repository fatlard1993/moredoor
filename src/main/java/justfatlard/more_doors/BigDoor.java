package justfatlard.more_doors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import justfatlard.pandorical.api.BlockEntry;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.RelPos;
import justfatlard.pandorical.api.StructurePose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * A bank of doors, swinging as one object rather than as a row of blocks.
 *
 * <p>Six door blocks all flipping their {@code open} property on the same tick is six doors doing
 * the same thing at the same moment; it is not a gate swinging. This lifts the whole bank out of
 * the world for the length of the swing, hands it to Pandorical as one structure, turns it, and
 * sets it back down open.
 *
 * <p><b>The blocks are described relative to the hinge.</b> That is the entire trick and it needs
 * nothing from the structure API that big-boats did not already need: a structure turns about its
 * own origin, so putting the origin on the hinge post is what makes a yaw change read as a door
 * swinging rather than a gate sliding sideways. A boat does the same thing about its helm.
 *
 * <p>While it swings the doorway is genuinely open - the real blocks are gone and the structure is
 * a picture. Walking through a gate a tick before it finishes is a smaller lie than a gate that
 * looks open and stops you.
 */
public final class BigDoor {
	private BigDoor() {}

	/** Below this it is a door, and vanilla's own instant swing looks better than a production. */
	public static final int LEAVES_FOR_A_GATE = 2;

	/**
	 * How long a swing takes, per block of gate measured out from the hinge.
	 *
	 * <p>Scaled rather than fixed, because the far edge of a wide gate travels much further than
	 * the far edge of a narrow one. Turning both in the same ten ticks means the big gate's edge is
	 * moving several times faster, and a heavy thing that whips is the one cue that reads as fake
	 * however good the model is. Holding the edge to a constant speed is what makes size feel like
	 * mass: a double door still claps shut, and a gatehouse takes its time.
	 */
	private static final int TICKS_PER_BLOCK = 5;

	/** A double door, and the pace everything else is measured against. */
	private static final int MIN_SWING_TICKS = 10;

	/**
	 * Even a castle gate stops being weighty and starts being a wait.
	 *
	 * <p>The doorway is standing open for the whole swing, so this is also how long the mod is
	 * willing to leave a hole where a locked gate used to be.
	 */
	private static final int MAX_SWING_TICKS = 50;

	private static final float QUARTER_TURN = 90F;

	private record Swing(String id, Entity anchor, BlockPos hinge, float from, float to,
			Map<BlockPos, BlockState> settled, int started, int ticks) {}

	private static final Map<String, Swing> active = new HashMap<>();
	private static int nextId = 0;

	/**
	 * Start a bank swinging, if it is big enough to be worth it and anybody can see it.
	 *
	 * @return whether the swing was taken on; false means the caller should do it the plain way
	 */
	public static boolean swing(ServerLevel level, Set<BlockPos> leaves, boolean opening) {
		if (leaves.size() < LEAVES_FOR_A_GATE) return false;
		if (!PandoricalApi.isAvailable()) return false;

		BlockState front = level.getBlockState(leaves.iterator().next());
		if (!(front.getBlock() instanceof DoorBlock)) return false;

		// The side the hinge is on decides both which end is the post and which way it opens.
		Direction toward = front.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT
			? front.getValue(DoorBlock.FACING).getCounterClockWise()
			: front.getValue(DoorBlock.FACING).getClockWise();

		BlockPos hinge = furthest(leaves, toward);

		List<BlockEntry> blocks = new ArrayList<>();
		Map<BlockPos, BlockState> settled = new HashMap<>();

		for (BlockPos foot : leaves) {
			for (BlockPos half : new BlockPos[] {foot, foot.above()}) {
				BlockState state = level.getBlockState(half);
				if (!(state.getBlock() instanceof DoorBlock)) continue;

				blocks.add(new BlockEntry(relative(hinge, half), state));
				// What it will be once it lands: the same door, the other way round.
				settled.put(half, state.setValue(DoorBlock.OPEN, opening));
			}
		}
		if (blocks.isEmpty()) return false;

		Entity anchor = anchorAt(level, hinge);
		if (anchor == null) return false;

		// It opens away from its post, so the direction follows the hinge side.
		float sweep = front.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT
			? QUARTER_TURN : -QUARTER_TURN;

		float from = opening ? 0F : sweep;
		float to = opening ? sweep : 0F;

		String id = Main.MOD_ID + ":gate/" + (nextId++);
		PandoricalApi.structures().spawn(anchor, id, blocks, poseAt(hinge, from));

		// Out of the world for the duration: the picture is now the door.
		for (BlockPos half : settled.keySet()) {
			level.setBlock(half, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}

		active.put(id, new Swing(id, anchor, hinge, from, to, settled, (int) level.getGameTime(),
			ticksFor(spanFrom(hinge, leaves, toward))));
		return true;
	}

	/** Advance every swing in flight, and set down the ones that have finished. */
	public static void tick(ServerLevel level) {
		if (active.isEmpty()) return;

		int now = (int) level.getGameTime();
		List<String> done = new ArrayList<>();

		for (Swing swing : List.copyOf(active.values())) {
			int elapsed = now - swing.started();
			if (elapsed >= swing.ticks()) {
				done.add(swing.id());
				continue;
			}

			float through = elapsed / (float) swing.ticks();
			PandoricalApi.structures().updatePose(swing.id(),
				poseAt(swing.hinge(), swing.from() + (swing.to() - swing.from()) * eased(through)));
		}

		for (String id : done) land(level, active.remove(id));
	}

	/** Put the real door back, open or shut, and take the picture away. */
	private static void land(ServerLevel level, Swing swing) {
		if (swing == null) return;

		PandoricalApi.structures().despawn(swing.id());
		swing.anchor().discard();

		swing.settled().forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_ALL));
	}

	/** Slow at both ends. A door on a hinge has mass; a linear swing reads as a slide. */
	private static float eased(float through) {
		return through * through * (3F - 2F * through);
	}

	/**
	 * How wide the gate is, measured out from its hinge post.
	 *
	 * <p>Width, not leaf count: a bank can be tall as well as wide, and stacking doors upward adds
	 * nothing to how far the outer edge has to travel. Two doors wide and three high swings like a
	 * double door, because that is what it is.
	 */
	private static int spanFrom(BlockPos hinge, Set<BlockPos> leaves, Direction toward) {
		int post = along(hinge, toward);
		int span = 1;

		for (BlockPos leaf : leaves) {
			span = Math.max(span, post - along(leaf, toward) + 1);
		}
		return span;
	}

	/** The pace for a gate of that width, kept between a clap and a wait. */
	private static int ticksFor(int span) {
		return Math.clamp(span * TICKS_PER_BLOCK, MIN_SWING_TICKS, MAX_SWING_TICKS);
	}

	/** The leaf at the far end in some direction - the hinge end, when given the hinge side. */
	private static BlockPos furthest(Set<BlockPos> leaves, Direction toward) {
		BlockPos furthest = leaves.iterator().next();

		for (BlockPos leaf : leaves) {
			if (along(leaf, toward) > along(furthest, toward)) furthest = leaf;
		}
		return furthest;
	}

	private static int along(BlockPos pos, Direction direction) {
		return pos.getX() * direction.getStepX() + pos.getZ() * direction.getStepZ();
	}

	private static RelPos relative(BlockPos hinge, BlockPos block) {
		return new RelPos(block.getX() - hinge.getX(), block.getY() - hinge.getY(),
			block.getZ() - hinge.getZ());
	}

	private static StructurePose poseAt(BlockPos hinge, float yaw) {
		return new StructurePose(hinge.getX() + 0.5, hinge.getY(), hinge.getZ() + 0.5, yaw);
	}

	/**
	 * Something for the structure to hang off.
	 *
	 * <p>A structure's visibility follows a real entity's tracking, so a gate needs one even
	 * though a gate is not an entity. An item display holding nothing is tracked, draws nothing,
	 * and is gone again in half a second.
	 */
	private static Entity anchorAt(ServerLevel level, BlockPos hinge) {
		Display.ItemDisplay anchor = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
		anchor.setPos(hinge.getX() + 0.5, hinge.getY(), hinge.getZ() + 0.5);

		return level.addFreshEntity(anchor) ? anchor : null;
	}

	/**
	 * Set every swing down where it stands, wherever it had got to.
	 *
	 * <p>A gate mid-swing is a hole in the world with its blocks held in memory. If the server
	 * stops there, the blocks are gone and the hole is permanent. This is the guarantee that the
	 * doorway is a door again by the time anything can stop watching it - shutdown, or the level
	 * unloading out from under it.
	 */
	public static void landAll(ServerLevel level) {
		if (active.isEmpty()) return;

		for (Swing swing : List.copyOf(active.values())) {
			active.remove(swing.id());
			land(level, swing);
		}
	}

	/** A door that is mid-swing is not there to be interacted with. */
	public static boolean isSwinging(BlockPos pos) {
		for (Swing swing : active.values()) {
			if (swing.settled().containsKey(pos)) return true;
		}
		return false;
	}
}
