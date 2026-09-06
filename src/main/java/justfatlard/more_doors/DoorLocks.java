package justfatlard.more_doors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Which doors are locked, and by whom.
 *
 * <p>A locked door opens and breaks for its owner, for anybody they have let in, and for an op.
 * That is the whole rule - no key to carry, nothing to craft twice - and it is the same rule
 * chest-utils applies to chests, deliberately: a player who has met one should not have to learn
 * the other.
 *
 * <p><b>A bank locks as a whole.</b> Every operation expands across {@link DoorBank}, because half
 * a locked gate is a gate: the unlocked leaf beside the locked one is a door-shaped hole in it.
 *
 * <p>Kept against the level rather than in a block entity. Doors do not have one and giving them
 * one would cost every other mod's understanding of what a door is, to store one name.
 */
public final class DoorLocks extends SavedData {
	private static final String STORAGE_KEY = "more_doors_locks";

	public record Lock(UUID owner, String ownerName, List<UUID> allowed) {
		static final Codec<Lock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("owner").forGetter(Lock::owner),
			Codec.STRING.fieldOf("owner_name").forGetter(Lock::ownerName),
			UUIDUtil.CODEC.listOf().optionalFieldOf("allowed", List.of()).forGetter(Lock::allowed)
		).apply(instance, Lock::new));

		public boolean admits(UUID player) {
			return this.owner.equals(player) || this.allowed.contains(player);
		}

		Lock with(UUID guest) {
			List<UUID> next = new ArrayList<>(this.allowed);
			if (!next.contains(guest)) next.add(guest);
			return new Lock(this.owner, this.ownerName, List.copyOf(next));
		}

		Lock without(UUID guest) {
			List<UUID> next = new ArrayList<>(this.allowed);
			next.remove(guest);
			return new Lock(this.owner, this.ownerName, List.copyOf(next));
		}
	}

	private record Entry(long pos, Lock lock) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
			Lock.CODEC.fieldOf("lock").forGetter(Entry::lock)
		).apply(instance, Entry::new));
	}

	public static final Codec<DoorLocks> CODEC = Entry.CODEC.listOf()
		.xmap(DoorLocks::fromEntries, DoorLocks::toEntries);

	private static final SavedDataType<DoorLocks> TYPE = new SavedDataType<>(
		Identifier.parse(STORAGE_KEY), DoorLocks::new, CODEC, DataFixTypes.LEVEL);

	private final Map<Long, Lock> locks = new HashMap<>();

	public static DoorLocks get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	public Lock lockAt(BlockPos pos) {
		return this.locks.get(pos.asLong());
	}

	/** Whether this door is shut against this player. Gamemaster is where somebody else's lock stops mattering. */
	public boolean refuses(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		if (player.permissions().hasPermission(
			net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) return false;

		for (BlockPos leaf : DoorBank.leavesOf(level, pos, state)) {
			Lock lock = lockAt(leaf);
			if (lock != null && !lock.admits(player.getUUID())) return true;
		}
		return false;
	}

	/** The name on whichever leaf is locked, for telling the refused player who to ask. */
	/** Whether any of these squares carries a lock that keeps this player out. */
	public boolean refusesAny(ServerPlayer player, Iterable<BlockPos> squares) {
		if (player.permissions().hasPermission(
			net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) return false;
		for (BlockPos square : squares) {
			Lock lock = lockAt(square);
			if (lock != null && !lock.admits(player.getUUID())) return true;
		}
		return false;
	}

	public String lockedBy(ServerLevel level, BlockPos pos, BlockState state) {
		for (BlockPos leaf : DoorBank.leavesOf(level, pos, state)) {
			Lock lock = lockAt(leaf);
			if (lock != null) return lock.ownerName();
		}
		return null;
	}

	public void lock(ServerPlayer owner, ServerLevel level, BlockPos pos, BlockState state) {
		Lock lock = new Lock(owner.getUUID(), owner.getGameProfile().name(), List.of());

		for (BlockPos leaf : DoorBank.leavesOf(level, pos, state)) {
			this.locks.put(leaf.asLong(), lock);
		}
		this.setDirty();
	}

	public void unlock(ServerLevel level, BlockPos pos, BlockState state) {
		for (BlockPos leaf : DoorBank.leavesOf(level, pos, state)) {
			this.locks.remove(leaf.asLong());
		}
		this.setDirty();
	}

	/** Let somebody in, or stop letting them. Applied across the bank so the gate agrees with itself. */
	public boolean share(ServerLevel level, BlockPos pos, BlockState state, UUID guest, boolean allow) {
		Set<BlockPos> bank = DoorBank.leavesOf(level, pos, state);
		boolean changed = false;

		for (BlockPos leaf : bank) {
			Lock lock = lockAt(leaf);
			if (lock == null) continue;

			this.locks.put(leaf.asLong(), allow ? lock.with(guest) : lock.without(guest));
			changed = true;
		}
		if (changed) this.setDirty();
		return changed;
	}

	/** Forget one block's lock, for when that block stops existing. */
	/** A leaf that swung somewhere else takes its lock with it. */
	public void move(BlockPos from, BlockPos to) {
		if (from.equals(to)) return;
		Lock lock = this.locks.remove(from.asLong());
		if (lock != null) {
			this.locks.put(to.asLong(), lock);
			this.setDirty();
		}
	}

	public void forget(BlockPos pos) {
		if (this.locks.remove(pos.asLong()) != null) this.setDirty();
	}

	private static DoorLocks fromEntries(List<Entry> entries) {
		DoorLocks locks = new DoorLocks();
		for (Entry entry : entries) locks.locks.put(entry.pos(), entry.lock());
		return locks;
	}

	private static List<Entry> toEntries(DoorLocks locks) {
		return locks.locks.entrySet().stream()
			.map(entry -> new Entry(entry.getKey(), entry.getValue()))
			.toList();
	}
}
