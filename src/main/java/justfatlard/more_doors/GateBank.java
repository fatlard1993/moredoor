package justfatlard.more_doors;

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
		if (hand != InteractionHand.MAIN_HAND || player.isSecondaryUseActive()) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof FenceGateBlock)) return InteractionResult.PASS;
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
