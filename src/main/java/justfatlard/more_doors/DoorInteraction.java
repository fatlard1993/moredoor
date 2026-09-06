package justfatlard.more_doors;


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
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.BlockHitResult;

/** Everything that happens when somebody reaches for a door. */
public final class DoorInteraction {
	private DoorInteraction() {}

	public static void register() {
		UseBlockCallback.EVENT.register(DoorInteraction::onUseBlock);
		GateBank.register();
		SwingMenu.register();

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

		// And a door that is gone is not locked any more, nor hung any way, nor open.
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!(level instanceof ServerLevel serverLevel)) return;
			DoorSwings swings = DoorSwings.get(serverLevel);
			DoorSwings.OpenDoor open = swings.openAt(pos);
			if (open != null) swings.clearOpen(open);
			if (state.getBlock() instanceof DoorBlock) {
				BlockPos foot = DoorBank.footOf(pos, state);
				DoorLocks.get(serverLevel).forget(foot);
				swings.forgetSetting(serverLevel, foot);
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
		// Only doors that are doors. A subclass has a use of its own - the amethyst door is a way
		// into somebody's geode - and swinging it here would answer the click before it could.
		if (door.getClass() != DoorBlock.class) return InteractionResult.PASS;

		// Mid-bounce, the door is nobody's to touch.
		if (DoorSwing.isMoving(serverLevel, pos)) return InteractionResult.SUCCESS;

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

		// A sneaking empty hand asks how the door hangs. Main hand only: the same click reaches
		// here once per hand, and the menu should open once.
		if (player.isSecondaryUseActive() && held.isEmpty() && hand == InteractionHand.MAIN_HAND) {
			SwingMenu.open(opener, pos, state);
			return InteractionResult.SUCCESS;
		}

		// A door that does not open by hand still does not, and a sneaking player is reaching
		// past the door for whatever is in their hand.
		if (!door.type().canOpenByHand() || player.isSecondaryUseActive()) return InteractionResult.PASS;

		swingBank(serverLevel, opener, pos, state, !isOpen(serverLevel, pos, state));
		// A vanilla client, or one that guessed, may have predicted placing what it holds. Its
		// stack did not change here; say so, or the hotbar shows one fewer until it next syncs.
		if (!player.getItemInHand(hand).isEmpty()) opener.containerMenu.sendAllDataToRemote();
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

	/**
	 * The whole bank opens, because the whole bank is one door.
	 *
	 * <p>The clicked leaf goes through vanilla's own open, sound and all; its neighbours change
	 * state with it, silently, so a bank of six is one door sound and not six on top of each
	 * other. An earlier version lifted the bank out of the world and turned it as one structure;
	 * it turned about the wrong axis, and a door that spins is worse than a door that flips.
	 * Nothing here is different from vanilla except that the neighbours come along.
	 */
	/**
	 * Whether this door stands open: by the door, not by the block. A slid door's blocks say
	 * closed while the door stands open a room away, and the record is what knows.
	 */
	public static boolean isOpen(ServerLevel level, BlockPos pos, BlockState state) {
		DoorGroup group = DoorGroup.at(level, DoorBank.footOf(pos, state));
		return group != null ? group.open : state.getValue(DoorBlock.OPEN);
	}

	/** Open or close every door that moves with this one; the opener, if there is one, hears about a slide that cannot. */
	public static void swingBank(ServerLevel level, ServerPlayer opener, BlockPos pos, BlockState state, boolean opening) {
		BlockPos clicked = DoorBank.footOf(pos, state);

		// The bank is every door that opens with this one; each of those is a door of its own,
		// swung whole. The one clicked makes the sound; the rest come along quietly. A leaf that
		// belongs to no rectangle - a stray beside a proper door - just turns where it stands.
		java.util.Set<BlockPos> done = new java.util.HashSet<>();
		java.util.List<BlockPos> order = new java.util.ArrayList<>();
		order.add(clicked);
		order.addAll(DoorBank.leavesOf(level, pos, state));
		boolean sound = true;
		for (BlockPos leaf : order) {
			if (done.contains(leaf)) continue;
			BlockState leafState = level.getBlockState(leaf);
			if (!(leafState.getBlock() instanceof DoorBlock)) continue;
			DoorGroup group = DoorGroup.at(level, leaf);
			if (group == null) {
				done.add(leaf);
				if (leafState.getValue(DoorBlock.OPEN) == opening) continue;
				level.setBlock(leaf, leafState.setValue(DoorBlock.OPEN, opening), Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
				BlockState upper = level.getBlockState(leaf.above());
				if (upper.getBlock() instanceof DoorBlock) {
					level.setBlock(leaf.above(), upper.setValue(DoorBlock.OPEN, opening), Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
				}
				level.gameEvent(opener, opening ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, leaf);
				continue;
			}
			// Every square the door has now, not the feet it would have closed: a slid door's
			// leaves stand a room from home, and the walker lists those, not home.
			done.addAll(group.squares(group.open));
			done.addAll(group.feet());
			if (!DoorSwing.set(level, opener, group, opening, sound) && group.swing.isSlide() && opener != null) {
				// A hinged door that hits something bounces, which is its own message. A slide
				// that hits something never moves, so it has to be said.
				opener.sendOverlayMessage(Component.translatable("more-doors-justfatlard.swing.no_room"));
			}
			sound = false;
		}
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
