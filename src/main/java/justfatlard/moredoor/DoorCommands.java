package justfatlard.moredoor;

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
				.executes(context -> unlock(context.getSource())))
			// For operators and test rigs: hang the door at a position without the menu.
			.then(Commands.literal("hang")
				.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
				.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
					.then(Commands.argument("swing", com.mojang.brigadier.arguments.StringArgumentType.word())
						.executes(context -> hang(context.getSource(),
							net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(context, "pos"),
							com.mojang.brigadier.arguments.StringArgumentType.getString(context, "swing")))))));
	}

	private static int hang(CommandSourceStack source, BlockPos pos, String swingName) {
		ServerLevel level = source.getLevel();
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
			source.sendFailure(Component.translatable("more-doors-justfatlard.command.no_door"));
			return 0;
		}
		// Looked up here rather than through Swing.parse, which settles anything unknown on LEFT
		// for saved data's sake: a typo on the command line should be refused, not hung left.
		Swing swing = null;
		for (Swing each : Swing.values()) {
			if (each.name().equalsIgnoreCase(swingName)) swing = each;
		}
		if (swing == null) {
			source.sendFailure(Component.literal("swing: left, right, slide_left, slide_right, slide_up, slide_down"));
			return 0;
		}
		Swing chosen = swing;
		BlockPos foot = DoorBank.footOf(pos, state);
		DoorGroup group = DoorGroup.at(level, foot);
		for (BlockPos leaf : group != null ? group.feet() : java.util.List.of(foot)) {
			SwingMenu.hang(level, leaf, chosen, null);
		}
		source.sendSuccess(() -> Component.literal("hung " + chosen.name().toLowerCase()), true);
		return 1;
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
