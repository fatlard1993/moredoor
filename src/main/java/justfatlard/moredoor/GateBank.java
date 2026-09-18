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

/**
 * Fence gates side by side open as one gate.
 *
 * <p>A double gate is two gates everybody already reads as one, and vanilla makes you open each.
 * Gates touching along their line, the same block, facing the same way or its opposite, are a
 * bank: use any one and they all take the same state, facing away from you the way a single
 * gate does. Pandorical clients also draw a pair as one wide gate; see the joined models.
 */
public final class GateBank {
	private GateBank() {}

	private static final int MAX_GATES = 64;

	public static void register() {
		UseBlockCallback.EVENT.register(GateBank::onUse);
	}

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
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

		Set<BlockPos> bank = gatesOf(server, pos, state);
		if (bank.size() < 2) return InteractionResult.PASS;

		boolean opening = !state.getValue(FenceGateBlock.OPEN);
		// Vanilla's own rule: a gate opens away from whoever opened it.
		Direction away = player.getDirection();
		for (BlockPos gate : bank) {
			BlockState each = server.getBlockState(gate);
			if (opening && each.getValue(FenceGateBlock.FACING) == away.getOpposite()) {
				each = each.setValue(FenceGateBlock.FACING, away);
			}
			server.setBlock(gate, each.setValue(FenceGateBlock.OPEN, opening), Block.UPDATE_ALL);
		}
		server.playSound(null, pos, opening ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
			SoundSource.BLOCKS, 1.0F, server.getRandom().nextFloat() * 0.1F + 0.9F);
		server.gameEvent(player, opening ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
		return InteractionResult.SUCCESS;
	}

	/**
	 * A signal reaches every gate a click would.
	 *
	 * <p>Vanilla powers a block, and a double gate is two blocks: a lever beside one leaf opened
	 * that leaf and left the other shut, which is the one thing nobody building a gate across a
	 * cart track wants. The bank answers together instead - powered when anything touching any of
	 * it is powered, so a signal at either end works, and closed again when the last of it goes
	 * quiet.
	 *
	 * <p>Facing is left alone. A click turns a gate away from whoever opened it; a lever is not
	 * standing anywhere, so the gate swings the way it already hangs.
	 *
	 * @return true when the bank has been dealt with and vanilla should not also act
	 */
	public static boolean powerChanged(ServerLevel level, BlockPos pos, BlockState state) {
		Set<BlockPos> bank = gatesOf(level, pos, state);
		if (bank.size() < 2) return false;

		boolean powered = false;
		for (BlockPos gate : bank) powered |= level.hasNeighborSignal(gate);
		if (powered == state.getValue(FenceGateBlock.POWERED)) return true;

		boolean opening = powered != state.getValue(FenceGateBlock.OPEN);
		for (BlockPos gate : bank) {
			BlockState each = level.getBlockState(gate);
			if (!joins(each, state)) continue;
			level.setBlock(gate, each.setValue(FenceGateBlock.POWERED, powered)
				.setValue(FenceGateBlock.OPEN, powered), Block.UPDATE_ALL);
		}
		if (opening) {
			level.playSound(null, pos, powered ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
				SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
			level.gameEvent(null, powered ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
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
				found.add(next);
				pending.add(next);
			}
		}
		return found;
	}

	/** The same gate, on the same line: facing along it either way. */
	public static boolean joins(BlockState other, BlockState like) {
		return other.getBlock() == like.getBlock()
			&& other.getBlock() instanceof FenceGateBlock
			&& other.getValue(FenceGateBlock.FACING).getAxis() == like.getValue(FenceGateBlock.FACING).getAxis();
	}
}
