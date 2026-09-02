package justfatlard.more_doors;

import java.util.Set;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.BlockHitResult;

/** Everything that happens when somebody reaches for a door. */
public final class DoorInteraction {
	private DoorInteraction() {}

	public static void register() {
		UseBlockCallback.EVENT.register(DoorInteraction::onUseBlock);

		// A locked door is no more breakable than it is openable. Without this the lock is a
		// suggestion: anybody refused at the handle simply takes the door off its hinges.
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (!(level instanceof ServerLevel serverLevel)) return true;
			if (!(state.getBlock() instanceof DoorBlock)) return true;
			if (!(player instanceof ServerPlayer breaker)) return true;

			DoorLocks locks = DoorLocks.get(serverLevel);
			if (!locks.refuses(breaker, serverLevel, pos, state)) return true;

			refuse(serverLevel, breaker, locks, pos, state);
			return false;
		});

		// And a door that is gone is not locked any more.
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel serverLevel && state.getBlock() instanceof DoorBlock) {
				DoorLocks.get(serverLevel).forget(DoorBank.footOf(pos, state));
			}
		});
	}

	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
			BlockHitResult hit) {
		if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
		if (!(player instanceof ServerPlayer opener)) return InteractionResult.PASS;

		BlockPos pos = hit.getBlockPos();
		BlockState state = serverLevel.getBlockState(pos);
		if (!(state.getBlock() instanceof DoorBlock door)) return InteractionResult.PASS;

		// Mid-swing the door is a picture and its blocks are gone; there is nothing here to grab.
		if (BigDoor.isSwinging(DoorBank.footOf(pos, state))) return InteractionResult.SUCCESS;

		DoorLocks locks = DoorLocks.get(serverLevel);
		if (locks.refuses(opener, serverLevel, pos, state)) {
			refuse(serverLevel, opener, locks, pos, state);
			return InteractionResult.SUCCESS;
		}

		ItemStack held = player.getItemInHand(hand);

		if (held.is(Items.IRON_INGOT) && !player.isSecondaryUseActive()) {
			return fitLock(serverLevel, opener, locks, pos, state, held);
		}

		if (player.isSecondaryUseActive() && turnsWith(held, door)) {
			return turn(serverLevel, opener, pos, state);
		}

		// A door that does not open by hand still does not, and a sneaking player is reaching
		// past the door for whatever is in their hand.
		if (!door.type().canOpenByHand() || player.isSecondaryUseActive()) return InteractionResult.PASS;

		swingBank(serverLevel, opener, pos, state, door);
		return InteractionResult.SUCCESS;
	}

	/** An iron ingot makes a door somebody's. Visually nothing changes; that is the point. */
	private static InteractionResult fitLock(ServerLevel level, ServerPlayer owner, DoorLocks locks,
			BlockPos pos, BlockState state, ItemStack ingot) {
		if (locks.lockAt(DoorBank.footOf(pos, state)) != null) return InteractionResult.PASS;

		locks.lock(owner, level, pos, state);
		ingot.consume(1, owner);

		level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 1.4F);
		owner.sendSystemMessage(Component.translatable("more-doors-justfatlard.locked"));

		return InteractionResult.SUCCESS;
	}

	/** The whole bank swings, because the whole bank is one door. */
	private static void swingBank(ServerLevel level, ServerPlayer opener, BlockPos pos,
			BlockState state, DoorBlock door) {
		boolean opening = !state.getValue(DoorBlock.OPEN);
		Set<BlockPos> bank = DoorBank.leavesOf(level, pos, state);

		// A gate is lifted out and turned as one object. A single door is not: vanilla's own
		// instant swing looks better than a production, and costs nothing.
		if (BigDoor.swing(level, bank, opening)) {
			level.playSound(null, pos, opening ? door.type().doorOpen() : door.type().doorClose(),
				SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
			return;
		}

		for (BlockPos leaf : bank) {
			BlockState leafState = level.getBlockState(leaf);
			if (!(leafState.getBlock() instanceof DoorBlock leafDoor)) continue;

			leafDoor.setOpen(opener, level, leafState, leaf, opening);
		}

		level.playSound(null, pos, opening ? door.type().doorOpen() : door.type().doorClose(),
			SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
	}

	/**
	 * Turn a door in place: hinge first, then the way it faces.
	 *
	 * <p>Four clicks brings it back where it started, so it is a thing you can fiddle with rather
	 * than a decision. Placing a door the wrong way round is the commonest thing that happens
	 * when you place a door.
	 */
	private static InteractionResult turn(ServerLevel level, ServerPlayer player, BlockPos pos,
			BlockState state) {
		BlockPos foot = DoorBank.footOf(pos, state);
		BlockState lower = level.getBlockState(foot);
		if (!(lower.getBlock() instanceof DoorBlock)) return InteractionResult.PASS;

		BlockState turned = lower.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT
			? lower.setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT)
			: lower.setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
				.setValue(DoorBlock.FACING, lower.getValue(DoorBlock.FACING).getClockWise());

		level.setBlock(foot, turned, Block.UPDATE_ALL);
		level.setBlock(foot.above(), level.getBlockState(foot.above())
			.setValue(DoorBlock.HINGE, turned.getValue(DoorBlock.HINGE))
			.setValue(DoorBlock.FACING, turned.getValue(DoorBlock.FACING)), Block.UPDATE_ALL);

		level.playSound(null, foot, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.8F, 1.0F);
		return InteractionResult.SUCCESS;
	}

	/** An axe turns wood and a pickaxe turns metal, because that is what you would reach for. */
	private static boolean turnsWith(ItemStack held, DoorBlock door) {
		boolean metal = !door.type().canOpenByHand();

		// Asked by tag, not by class: the tool classes were folded away and what a pickaxe is
		// is now a thing the data says, which also means a modded axe counts.
		return held.is(metal ? ItemTags.PICKAXES : ItemTags.AXES);
	}

	private static void refuse(ServerLevel level, ServerPlayer player, DoorLocks locks,
			BlockPos pos, BlockState state) {
		String owner = locks.lockedBy(level, pos, state);

		player.sendOverlayMessage(owner == null
			? Component.translatable("more-doors-justfatlard.locked.refused")
			: Component.translatable("more-doors-justfatlard.locked.by", owner));
		level.playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 1.0F, 1.0F);
	}
}
