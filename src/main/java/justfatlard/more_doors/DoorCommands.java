package justfatlard.more_doors;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Letting somebody else through your door.
 *
 * <p>Aimed rather than addressed: you look at the door and say who. Naming a door in a command
 * would mean naming a position, and nobody knows the coordinates of their own front door.
 */
public final class DoorCommands {
	private DoorCommands() {}

	/** How far you can be standing from a door and still mean that one. */
	private static final double REACH = 6.0;

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("doors")
			.then(Commands.literal("allow")
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
					.executes(context -> share(context.getSource(),
						GameProfileArgument.getGameProfiles(context, "player"), true))))
			.then(Commands.literal("deny")
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
					.executes(context -> share(context.getSource(),
						GameProfileArgument.getGameProfiles(context, "player"), false))))
			.then(Commands.literal("unlock")
				.executes(context -> unlock(context.getSource()))));
	}

	private static int share(CommandSourceStack source,
			java.util.Collection<NameAndId> guests, boolean allow)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = player.level();

		BlockPos door = lookingAt(player, level);
		if (door == null) {
			source.sendFailure(Component.translatable("more-doors-justfatlard.command.no_door"));
			return 0;
		}

		DoorLocks locks = DoorLocks.get(level);
		BlockState state = level.getBlockState(door);
		DoorLocks.Lock lock = locks.lockAt(DoorBank.footOf(door, state));

		if (lock == null) {
			source.sendFailure(Component.translatable("more-doors-justfatlard.command.not_locked"));
			return 0;
		}
		// The owner's list is the owner's to change, and an op's is anybody's.
		if (!lock.owner().equals(player.getUUID()) && !source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			source.sendFailure(Component.translatable("more-doors-justfatlard.command.not_yours",
				lock.ownerName()));
			return 0;
		}

		int changed = 0;
		for (NameAndId guest : guests) {
			if (locks.share(level, door, state, guest.id(), allow)) changed++;
		}

		source.sendSuccess(() -> Component.translatable(allow
			? "more-doors-justfatlard.command.allowed"
			: "more-doors-justfatlard.command.denied", guests.size()), false);
		return changed;
	}

	private static int unlock(CommandSourceStack source)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = player.level();

		BlockPos door = lookingAt(player, level);
		if (door == null) {
			source.sendFailure(Component.translatable("more-doors-justfatlard.command.no_door"));
			return 0;
		}

		DoorLocks locks = DoorLocks.get(level);
		BlockState state = level.getBlockState(door);
		DoorLocks.Lock lock = locks.lockAt(DoorBank.footOf(door, state));

		if (lock == null) {
			source.sendFailure(Component.translatable("more-doors-justfatlard.command.not_locked"));
			return 0;
		}
		if (!lock.owner().equals(player.getUUID()) && !source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			source.sendFailure(Component.translatable("more-doors-justfatlard.command.not_yours",
				lock.ownerName()));
			return 0;
		}

		locks.unlock(level, door, state);
		source.sendSuccess(() -> Component.translatable("more-doors-justfatlard.command.unlocked"), false);
		return 1;
	}

	/** The door under the player's crosshair, or null. */
	private static BlockPos lookingAt(ServerPlayer player, ServerLevel level) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH));

		BlockHitResult hit = level.clip(new ClipContext(eye, end,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

		if (hit.getType() != HitResult.Type.BLOCK) return null;
		return level.getBlockState(hit.getBlockPos()).getBlock() instanceof DoorBlock
			? hit.getBlockPos()
			: null;
	}
}
