package justfatlard.moredoor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Trapdoors laid together open together.
 *
 * <p>A hatch in a floor is rarely one trapdoor. It is four in a square, or a row across a
 * stairwell, or a bank of shutters down a wall, and every one of them is a separate click -
 * which is the same complaint doors and fence gates already had answered. Trapdoors of the
 * same kind, hung the same way, touching each other, are one hatch and take one click.
 *
 * <p>Same block and same half hold a hatch together, and facing on the same line: the
 * same rule doors keep. Which way a trapdoor faces is decided by where its placer stood, so
 * a pair of cellar doors meeting in the middle faces two opposite ways, and so does a row
 * laid by somebody walking round it. Both are one hatch to whoever built them. A trapdoor at
 * right angles is hung across a different gap, and is left out.
 *
 * <p>Opening spans the pair; re-hanging does not. {@link #bankOf} keeps the stricter same
 * facing for the menu, which sets one edge for the whole hatch and would fold a pair of
 * cellar doors into a single slope.
 *
 * <p>Walked through all six neighbours, not four. A hatch in a floor spreads flat; shutters
 * on a wall spread up. Both are the same shape of thing to whoever built them.
 */
public final class TrapBank {
	private TrapBank() {}

	/** Enough for any hatch somebody built on purpose, and a stop against a floor of them. */
	private static final int MOST = 64;

	public static void register() {
		UseBlockCallback.EVENT.register(TrapBank::onUse);
	}

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand,
			BlockHitResult hit) {
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof TrapDoorBlock trapdoor)) return InteractionResult.PASS;
		if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;

		// A sneaking empty hand asks how the hatch hangs, the way it does on a door and a gate.
		if (player.isSecondaryUseActive()) {
			if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
			if (player instanceof ServerPlayer opener) TrapMenu.open(opener, pos, state);
			return InteractionResult.SUCCESS;
		}

		// An iron trapdoor is a redstone trapdoor; a hand is not the way it opens.
		BlockSetType type = ((justfatlard.moredoor.mixin.TrapDoorTypeAccessor) trapdoor).moredoor$type();
		if (!type.canOpenByHand()) return InteractionResult.PASS;

		Set<BlockPos> bank = hatchOf(server, pos, state);
		if (bank.size() < 2) return InteractionResult.PASS;

		boolean opening = !state.getValue(TrapDoorBlock.OPEN);
		for (BlockPos each : bank) {
			BlockState there = server.getBlockState(each);
			server.setBlock(each, there.setValue(TrapDoorBlock.OPEN, opening), Block.UPDATE_ALL);
		}
		// One sound for one hatch. Every trapdoor playing its own is a clatter, and the
		// pitch wobble vanilla gives each one turns a bank of six into a dropped tray.
		server.playSound(null, pos, opening
			? type.trapdoorOpen() : type.trapdoorClose(),
			SoundSource.BLOCKS, 1.0F, server.getRandom().nextFloat() * 0.1F + 0.9F);
		server.gameEvent(player, opening ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
		return InteractionResult.SUCCESS;
	}

	/** Every trapdoor that opens with this one, including it. */
	public static Set<BlockPos> hatchOf(Level level, BlockPos pos, BlockState like) {
		return walk(level, pos, like, TrapBank::opensWith);
	}

	/**
	 * Every trapdoor of this kind touching this one, however it happens to be hung.
	 *
	 * <p>Looser than both the others on purpose. {@link #hatchOf} already requires the same half
	 * and the same axis, so a shutter turned the wrong way or sitting in the other half of its
	 * block is not merely in a different hatch - it is invisible to the walk entirely, and there
	 * is no way to ask for it to be brought into line. This is what "make these one hatch" has to
	 * start from.
	 */
	public static Set<BlockPos> touchingOf(Level level, BlockPos pos, BlockState like) {
		return walk(level, pos, like, (other, ignored) ->
			other.getBlock() == like.getBlock() && other.getBlock() instanceof TrapDoorBlock);
	}

	/** Every trapdoor hung the same way and touching this one, including it. */
	public static Set<BlockPos> bankOf(Level level, BlockPos pos, BlockState like) {
		return walk(level, pos, like, TrapBank::joins);
	}

	private static Set<BlockPos> walk(Level level, BlockPos pos, BlockState like,
			java.util.function.BiPredicate<BlockState, BlockState> belongs) {
		Set<BlockPos> found = new LinkedHashSet<>();
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> pending = new ArrayDeque<>();
		found.add(pos);
		seen.add(pos);
		pending.add(pos);

		while (!pending.isEmpty() && found.size() < MOST) {
			BlockPos at = pending.poll();
			for (Direction step : Direction.values()) {
				BlockPos next = at.relative(step);
				if (!seen.add(next) || !belongs.test(level.getBlockState(next), like)) continue;
				found.add(next);
				pending.add(next);
			}
		}
		return found;
	}

	/** The same trapdoor, hung the same way: one hatch rather than two that happen to touch. */
	public static boolean joins(BlockState other, BlockState like) {
		return opensWith(other, like)
			&& other.getValue(TrapDoorBlock.FACING) == like.getValue(TrapDoorBlock.FACING);
	}

	/** The same trapdoor across the same gap, from either side of it. */
	private static boolean opensWith(BlockState other, BlockState like) {
		return other.getBlock() == like.getBlock()
			&& other.getBlock() instanceof TrapDoorBlock
			&& other.getValue(TrapDoorBlock.HALF) == like.getValue(TrapDoorBlock.HALF)
			&& other.getValue(TrapDoorBlock.FACING).getAxis() == like.getValue(TrapDoorBlock.FACING).getAxis();
	}
}
