package justfatlard.moredoor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Moving a door, whatever its size and however it hangs.
 *
 * <p>A block can turn about its own edge and nothing else, so a door is moved when it swings: each
 * square goes to the block it would really sweep to. Hung from a side, the far leaves stand out
 * from the hinge column. Sliding, it goes along its own plane by its own size. A door one leaf
 * wide on a side hinge swings in place, exactly as it always did.
 *
 * <p>Not all at once. A door the size of a wall would open in a blink if every square jumped in
 * the same tick, and that reads as a trick, not a door. So a hinged door sweeps out one column
 * at a time from the hinge, the outermost coming back first when it closes, and a sliding door
 * travels one block at a time; the bigger the door, the longer it takes, which is what weight
 * looks like. A door that meets something on the way stops there, the struck block sounds and
 * sheds dust, and the door comes back the way it went.
 */
public final class DoorSwing {
	private DoorSwing() {}

	/** Told to clients, no shape updates: squares in mid-move must not be judged half a door. */
	private static final int MOVE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	/** Ticks between one column and the next of a swinging door. */
	private static final int SWING_TICKS = 3;
	/** Ticks between one block and the next of a sliding door. */
	private static final int SLIDE_TICKS = 2;
	/** How long a door that hit something stays against it before it comes back. */
	private static final int HIT_TICKS = 6;

	private static final List<Motion> moving = new ArrayList<>();

	public static void init() {
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			moving.removeIf(m -> m.level == level && m.tick());
			watchSignals(level);
		});
		// A door caught mid-swing when the server stops would be saved bent; every one finishes
		// where it was going, now, without the wait.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Motion motion : moving) motion.finishNow();
			moving.clear();
		});
	}

	/** Whether this square is part of a door on the move, and so not to be touched. */
	public static boolean isMoving(ServerLevel level, BlockPos pos) {
		for (Motion motion : moving) {
			if (motion.level == level && motion.holds(pos)) return true;
		}
		return false;
	}

	/**
	 * Open or close a door.
	 *
	 * @return whether the door set off; false when it could not move at all
	 */
	public static boolean set(ServerLevel level, Entity by, DoorGroup group, boolean open, boolean sound) {
		if (group.open == open) return true;
		// Already on its way, from any of its squares: a slid door's origin is empty air.
		for (BlockPos square : group.squares(group.open)) {
			if (isMoving(level, square)) return true;
		}

		if (group.width == 1 && group.swing.isSideHinge()) {
			for (BlockPos foot : group.feet()) {
				for (BlockPos half : new BlockPos[] {foot, foot.above()}) {
					BlockState there = level.getBlockState(half);
					if (there.getBlock() instanceof DoorBlock) {
						level.setBlock(half, there.setValue(DoorBlock.OPEN, open), Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
					}
				}
			}
			swingSound(level, group, open, sound);
			level.gameEvent(by, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, group.origin);
			return true;
		}

		Motion motion = new Motion(level, by, group, open, sound);
		if (!motion.step()) return false;
		swingSound(level, group, open, sound);
		level.gameEvent(by, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, group.origin);
		moving.add(motion);
		return true;
	}

	/**
	 * A slid door opened by a signal has moved away from whatever sent it, and nobody tells air
	 * that the signal has gone. So every open door whose squares still say powered looks at
	 * home for itself, once a tick; finding no signal there or beside it, it comes back.
	 *
	 * <p>Cheap in practice: it runs for doors that are open, sliding and still marked powered,
	 * which is usually none, and reads the home squares only. A signal changing beside the
	 * squares the door stands on reaches it the ordinary way, through the neighbour update.
	 */
	private static void watchSignals(ServerLevel level) {
		DoorSwings swings = DoorSwings.get(level);
		for (DoorSwings.OpenDoor record : swings.openDoors()) {
			if (!record.swing().isSlide()) continue;
			DoorGroup group = DoorGroup.of(record);
			List<BlockPos> standing = group.squares(true);
			if (isMoving(level, standing.get(0))) continue;
			boolean powered = false;
			for (BlockPos square : standing) {
				BlockState state = level.getBlockState(square);
				if (state.hasProperty(DoorBlock.POWERED) && state.getValue(DoorBlock.POWERED)) powered = true;
			}
			if (!powered) continue;
			boolean signal = false;
			for (BlockPos square : group.squares(false)) signal |= level.hasNeighborSignal(square);
			if (signal) continue;
			for (BlockPos square : standing) {
				BlockState state = level.getBlockState(square);
				if (state.hasProperty(DoorBlock.POWERED)) {
					level.setBlock(square, state.setValue(DoorBlock.POWERED, false), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
			set(level, null, group, false, true);
		}
	}

	private static void swingSound(ServerLevel level, DoorGroup group, boolean open, boolean sound) {
		if (!sound) return;
		var type = ((DoorBlock) group.block).type();
		level.playSound(null, group.origin, open ? type.doorOpen() : type.doorClose(), SoundSource.BLOCKS,
			1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
	}

	/**
	 * Whether a door half is held up by the door it moved with.
	 *
	 * <p>A swung or slid leaf may stand over nothing, and vanilla would drop it for having no
	 * floor. A square of a door that is open, or on its way, is held by the door.
	 */
	public static boolean hangs(LevelReader level, BlockPos pos, BlockState state) {
		return level instanceof ServerLevel server && DoorSwings.get(server).openAt(pos) != null;
	}

	/**
	 * One door on its way from closed to open or back, a frame at a time.
	 *
	 * <p>A frame is where every square stands at one moment. Hinged, frame {@code k} has the
	 * columns within {@code k} of the hinge swung out; sliding, it has the whole door {@code k}
	 * blocks along. Opening runs the frames forward and closing runs them back, so a closing door
	 * brings its outermost column in first, the way it went out last.
	 */
	private static final class Motion {
		final ServerLevel level;
		private final DoorGroup group;
		private final boolean toOpen;
		private final boolean sound;
		/** Per frame, per square (column-major, {@code c * tall + b}): where it stands. */
		private final List<BlockPos[]> frames = new ArrayList<>();
		/**
		 * Per frame, per square: whether it is swung. Kept apart from where it stands because the
		 * hinge column stands in the same block open or closed, and only its state tells.
		 */
		private final List<boolean[]> swung = new ArrayList<>();
		private int at;
		private boolean returning;
		/**
		 * Squares the leaf passes over without ever coming to rest on: the inside of the quarter
		 * circle it turns through. Empty for a slide, which travels along its own line and sweeps
		 * nothing else.
		 */
		private final List<BlockPos> arc = new ArrayList<>();
		/** Whoever set it going, to be told when it cannot get there; null when a signal did. */
		private final Entity by;
		private int wait;

		Motion(ServerLevel level, Entity by, DoorGroup group, boolean toOpen, boolean sound) {
			this.level = level;
			this.by = by;
			this.group = group;
			this.toOpen = toOpen;
			this.sound = sound;
			build();
		}

		private int squares() {
			return group.width * group.blocksTall();
		}

		private void build() {
			int tall = group.blocksTall();
			if (group.swing.isSlide()) {
				int steps = switch (group.swing) {
					case SLIDE_UP, SLIDE_DOWN -> tall;
					default -> group.width;
				};
				Direction dir = switch (group.swing) {
					case SLIDE_UP -> Direction.UP;
					case SLIDE_DOWN -> Direction.DOWN;
					case SLIDE_LEFT -> DoorGroup.along(group.facing).getOpposite();
					default -> DoorGroup.along(group.facing);
				};
				for (int k = 0; k <= steps; k++) {
					BlockPos[] frame = new BlockPos[squares()];
					boolean[] out = new boolean[squares()];
					for (int c = 0; c < group.width; c++) {
						for (int b = 0; b < tall; b++) {
							frame[c * tall + b] = group.closedSquare(c, b).relative(dir, k);
							out[c * tall + b] = k > 0;
						}
					}
					frames.add(frame);
					swung.add(out);
				}
			} else {
				// Two frames, shut and swung, and nothing between them.
				//
				// A hinge has no halfway that blocks can hold: part way round, the leaf lies across
				// the corners of its squares. What used to stand in for it was swinging the columns
				// nearest the hinge and leaving the rest where they were - so a door three wide that
				// met something on its way opened as two leaves joined together with the third still
				// in the jamb. That is not a door part way open, it is a door in two pieces, and no
				// ordering of the columns fixes it. The whole door goes, or none of it does.
				for (int k = 0; k <= 1; k++) {
					BlockPos[] frame = new BlockPos[squares()];
					boolean[] out = new boolean[squares()];
					for (int c = 0; c < group.width; c++) {
						for (int b = 0; b < tall; b++) {
							frame[c * tall + b] = k > 0 ? group.openSquare(c, b) : group.closedSquare(c, b);
							out[c * tall + b] = k > 0;
						}
					}
					frames.add(frame);
					swung.add(out);
				}
			}
			// What the leaf sweeps on its way round, which is not where it ends up.
			//
			// A door turning about its hinge covers the quarter circle of its own width. Only the
			// far edge of that lands anywhere: everything inside is passed over, and nothing was
			// ever asked about it - so a block sitting in the middle of the arc was swept straight
			// through. It went unnoticed while a blocked door stopped partway and looked stuck; a
			// door that now goes in one move either clears its arc or does not turn.
			if (!group.swing.isSlide() && group.width > 1) {
				Direction along = DoorGroup.along(group.facing);
				boolean left = group.swing == Swing.LEFT;
				BlockPos hinge = left ? group.origin : group.origin.relative(along, group.width - 1);
				Direction back = left ? along : along.getOpposite();
				int reach = group.width - 1;
				for (int a = 1; a <= reach; a++) {
					for (int f = 1; f <= reach; f++) {
						if (a * a + f * f > reach * reach) continue;
						for (int b = 0; b < tall; b++) {
							arc.add(hinge.relative(back, a).relative(group.facing, f).above(b));
						}
					}
				}
			}

			// Closing is opening run backwards: the frames are read from the far end.
			if (!toOpen) {
				java.util.Collections.reverse(frames);
				java.util.Collections.reverse(swung);
			}
			// The door starts where it stands, which is frame zero; the first step is to frame one.
			at = 0;
		}

		private BlockPos[] current() {
			return frames.get(at);
		}

		boolean holds(BlockPos pos) {
			for (BlockPos square : current()) {
				if (square.equals(pos)) return true;
			}
			return false;
		}

		/** Advance one frame the way we are going; false if the way is blocked or there is no frame left. */
		boolean step() {
			int next = returning ? at - 1 : at + 1;
			if (next < 0 || next >= frames.size()) return false;
			BlockPos[] from = current();
			BlockPos[] to = frames.get(next);
			boolean[] out = swung.get(next);

			Set<BlockPos> own = new HashSet<>(List.of(from));
			for (BlockPos dest : to) {
				if (own.contains(dest)) continue;
				if (level.isOutsideBuildHeight(dest) || !level.getBlockState(dest).canBeReplaced()) {
					// On the way out that is a hit, and says so. On the way back it is somebody
					// building in the doorway; the door waits, quietly, and tries again.
					if (!returning) struck(dest);
					return false;
				}
			}

			// Lifted out whole, then set down, so no square lands on another's old spot.
			BlockState[] lifted = new BlockState[from.length];
			for (int i = 0; i < from.length; i++) {
				lifted[i] = level.getBlockState(from[i]);
				if (!from[i].equals(to[i])) level.setBlock(from[i], level.getFluidState(from[i]).createLegacyBlock(), MOVE);
			}
			DoorLocks locks = DoorLocks.get(level);
			int tall = group.blocksTall();
			for (int i = 0; i < to.length; i++) {
				if (!(lifted[i].getBlock() instanceof DoorBlock)) continue;
				int c = i / tall, b = i % tall;
				BlockState state = out[i] ? group.openState(c, b, lifted[i]) : group.closedState(c, b, lifted[i]);
				// A square that stays put and only turns is told to its neighbours, as vanilla tells
				// a door that opened; one that travelled is not, until it has arrived.
				level.setBlock(to[i], state, from[i].equals(to[i]) ? Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS : MOVE);
				// Every foot takes its marks with it, on every row; the lock rides the bottom one.
				if (b % 2 == 0 && !from[i].equals(to[i])) {
					if (b == 0) locks.move(from[i], to[i]);
					DoorSwings.get(level).moveMarks(level, from[i], to[i]);
				}
			}
			at = next;
			remember();

			// Swung, and now asked what it went over. The door opens whatever is in its arc - it
			// has to, or there is nothing to see - and then finds the thing it caught on, knocks
			// against it and comes back. Opening only: a door on its way home that brushes
			// something is already going where it should.
			if (toOpen && !returning && at == frames.size() - 1) {
				BlockPos caught = sweptOn();
				if (caught != null) {
					struck(caught);
					returning = true;
					wait = HIT_TICKS;
				}
			}
			return true;
		}

		/** Where the door is now, written down, so its squares are its squares wherever they stand. */
		private void remember() {
			DoorSwings swings = DoorSwings.get(level);
			swings.clearOpen(group.record());
			boolean closedAtRest = at == (toOpen ? 0 : frames.size() - 1);
			if (!closedAtRest) swings.markOpen(group.record(), List.of(current()));
		}

		private void struck(BlockPos dest) {
			// The knock and the chips off the block, and nothing in words. A door stopped against
			// something is as often somebody's mechanism - a slide held shut to close itself again -
			// as it is a door in trouble, and a mechanism that narrates itself every cycle is worse
			// than one that says nothing.
			BlockState wall = level.getBlockState(dest);
			level.playSound(null, dest, wall.getSoundType().getHitSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
			if (!wall.isAir()) {
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, wall),
					dest.getX() + 0.5, dest.getY() + 0.5, dest.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.0);
			}
		}

		/** Whatever the leaf passed over on its way round and cannot stay clear of, or null. */
		private BlockPos sweptOn() {
			Set<BlockPos> standing = new HashSet<>(List.of(current()));
			for (BlockPos over : arc) {
				if (standing.contains(over)) continue;
				if (level.isOutsideBuildHeight(over) || !level.getBlockState(over).canBeReplaced()) return over;
			}
			return null;
		}

		/** @return whether the door has come to rest */
		boolean tick() {
			if (wait-- > 0) return false;
			wait = group.swing.isSlide() ? SLIDE_TICKS : SWING_TICKS;
			boolean lastFrame = returning ? at == 0 : at == frames.size() - 1;
			if (lastFrame) {
				if (returning) swingSound(level, group, !toOpen, sound);
				return true;
			}
			if (step()) return false;
			if (returning) return at == 0;   // could not even come back: rest where we are
			// Blocked on the way out: hold against it a moment, then come back.
			returning = true;
			wait = HIT_TICKS;
			return false;
		}

		/** Straight to the end, for when there are no more ticks. */
		void finishNow() {
			while (step()) {
				// each step moves a frame; stopping on a block leaves the door where it got to
			}
		}
	}
}
