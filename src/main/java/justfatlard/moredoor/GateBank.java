package justfatlard.moredoor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Fence gates side by side open as one gate.
 *
 * <p>A double gate is two gates everybody already reads as one, and vanilla makes you open each.
 * Gates touching along their line or stacked, the same block, facing the same way or its opposite,
 * shut or open alike, are a bank: use any one and the whole bank answers, facing away from you the
 * way a single gate does. A bank more than one gate wide is a wide gate, and opens by moving its
 * blocks out along its leaves ({@link GateMove}); a single column turns in place.
 */
public final class GateBank {
	private GateBank() {}

	private static final int MAX_GATES = 256;

	public static void register() {
		UseBlockCallback.EVENT.register(GateBank::onUse);
	}

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (hand != InteractionHand.MAIN_HAND || player.isSpectator()) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof FenceGateBlock)) return InteractionResult.PASS;
		// A sneaking empty hand asks how the gate opens, the way it does on a door.
		if (player.isSecondaryUseActive()) {
			if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
			if (player instanceof net.minecraft.server.level.ServerPlayer opener) GateMenu.open(opener, pos, state);
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;

		GateSwings swings = GateSwings.get(server);
		GateSwings.OpenGate standing = swings.openAt(pos);
		if (standing != null) {
			GateMove.set(server, GateGroup.of(standing), false, player, true);
			return InteractionResult.SUCCESS;
		}
		Set<BlockPos> bank = gatesOf(server, pos, state);
		// A gate on its own, hung as vanilla hangs it, is vanilla's to open.
		if (bank.size() < 2 && swings.settingOf(pos) == GateSwing.DOUBLE) return InteractionResult.PASS;

		boolean opening = !state.getValue(FenceGateBlock.OPEN);
		Direction facing = state.getValue(FenceGateBlock.FACING);
		// Vanilla's own rule: a gate opens away from whoever opened it.
		Direction away = player.getDirection();
		if (opening && facing == away.getOpposite()) facing = away;
		swing(server, pos, state, bank, opening, facing, player);
		return InteractionResult.SUCCESS;
	}

	/**
	 * Open or shut a whole bank, every gate of it facing {@code facing}: wide gates by moving
	 * them, single columns and banks that are no rectangle by turning in place.
	 */
	private static void swing(ServerLevel level, BlockPos pos, BlockState state, Set<BlockPos> bank, boolean opening,
			Direction facing, @Nullable Player by) {
		GateSwings swings = GateSwings.get(level);
		for (BlockPos gate : bank) {
			BlockState each = level.getBlockState(gate);
			if (each.getValue(FenceGateBlock.FACING) == facing) continue;
			// Turned round, its left is the other post: the hinge follows so the leaf does not.
			swings.followTurn(level, gate);
			level.setBlock(gate, each.setValue(FenceGateBlock.FACING, facing), Block.UPDATE_CLIENTS);
		}
		List<GateGroup> groups = opening ? GateGroup.allOf(level, pos, state, facing) : List.of();
		Set<BlockPos> moved = new HashSet<>();
		boolean sounded = false;
		for (GateGroup group : groups) {
			if (group.width < 2) continue;
			if (GateMove.set(level, group, true, by, !sounded)) sounded = true;
			moved.addAll(group.squares(false));
		}
		boolean turned = false;
		for (BlockPos gate : bank) {
			if (moved.contains(gate)) continue;
			BlockState each = level.getBlockState(gate);
			if (!each.is(state.getBlock()) || each.getValue(FenceGateBlock.OPEN) == opening) continue;
			level.setBlock(gate, each.setValue(FenceGateBlock.OPEN, opening), Block.UPDATE_ALL);
			turned = true;
		}
		if (turned && !sounded) {
			level.playSound(null, pos, opening ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
				SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
			level.gameEvent(by, opening ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
		}
	}

	/**
	 * A signal reaches every gate a click would.
	 *
	 * <p>Vanilla powers a block, and a double gate is two blocks: a lever beside one leaf opened
	 * that leaf and left the other shut, which is the one thing nobody building a gate across a
	 * cart track wants. The bank answers together instead - powered when anything touching any of
	 * it is powered, so a signal at either end works, and closed again when the last of it goes
	 * quiet. A wide gate that has swung out is no longer beside its lever; {@link GateMove} watches
	 * its doorway for it.
	 *
	 * <p>Facing is the clicked gate's. A click turns a gate away from whoever opened it; a lever is
	 * not standing anywhere, so the gate swings the way it already hangs.
	 *
	 * @return true when the bank has been dealt with and vanilla should not also act
	 */
	public static boolean powerChanged(ServerLevel level, BlockPos pos, BlockState state) {
		if (GateMove.isMoving(level) || GateSwings.get(level).openAt(pos) != null) return true;
		Set<BlockPos> bank = gatesOf(level, pos, state);
		if (bank.size() < 2 && GateSwings.get(level).settingOf(pos) == GateSwing.DOUBLE) return false;

		boolean powered = false;
		for (BlockPos gate : bank) powered |= level.hasNeighborSignal(gate);
		if (powered == state.getValue(FenceGateBlock.POWERED)) return true;

		for (BlockPos gate : bank) {
			BlockState each = level.getBlockState(gate);
			level.setBlock(gate, each.setValue(FenceGateBlock.POWERED, powered), Block.UPDATE_CLIENTS);
		}
		if (powered != state.getValue(FenceGateBlock.OPEN)) {
			swing(level, pos, level.getBlockState(pos), bank, powered, state.getValue(FenceGateBlock.FACING), null);
		}
		return true;
	}

	/** Every gate joined to this one along its line, including it. */
	public static Set<BlockPos> gatesOf(Level level, BlockPos pos, BlockState state) {
		Set<BlockPos> found = new LinkedHashSet<>();
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> pending = new ArrayDeque<>();
		found.add(pos);
		seen.add(pos);
		pending.add(pos);
		// Along the line of the gate, and up and down it: a gate on a gate is the same gate, taller.
		Direction.Axis line = state.getValue(FenceGateBlock.FACING).getClockWise().getAxis();
		while (!pending.isEmpty() && found.size() < MAX_GATES) {
			BlockPos at = pending.poll();
			for (Direction step : Direction.values()) {
				if (step.getAxis() != line && !step.getAxis().isVertical()) continue;
				BlockPos next = at.relative(step);
				if (!seen.add(next) || !joins(level.getBlockState(next), state)) continue;
				// A wide gate standing open is its own, wherever its squares have swung to.
				if (level instanceof ServerLevel server && GateSwings.get(server).openAt(next) != null) continue;
				found.add(next);
				pending.add(next);
			}
		}
		return found;
	}

	/** The same gate, on the same line, open or shut alike: facing along it either way. */
	public static boolean joins(BlockState other, BlockState like) {
		return other.getBlock() == like.getBlock()
			&& other.getBlock() instanceof FenceGateBlock
			&& other.getValue(FenceGateBlock.FACING).getAxis() == like.getValue(FenceGateBlock.FACING).getAxis()
			&& other.getValue(FenceGateBlock.OPEN) == like.getValue(FenceGateBlock.OPEN);
	}
}
