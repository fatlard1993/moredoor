package justfatlard.moredoor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.pandorical.api.BlockMarkApi;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * What a fence gate cannot say about itself: which post it hangs from, and, for a wide gate
 * standing open, which squares its blocks have swung out to.
 *
 * <p>A gate block has no property for the hinge, so it lives here per gate, and the client is told
 * by a mark on the block. An open wide gate is remembered whole, because once its blocks stand out
 * along the leaf nothing in the world says where its doorway was; its squares are marked
 * {@link BlockMarkApi#GATE_WIDE} and with the post their leaf hangs from, so the client draws each
 * one's piece of the leaf.
 */
public final class GateSwings extends SavedData {
	private static final String STORAGE_KEY = "gate_swings";

	private record Setting(long pos, GateSwing swing) {
		static final Codec<Setting> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.LONG.fieldOf("pos").forGetter(Setting::pos),
			Codec.STRING.xmap(GateSwing::parse, GateSwing::name).fieldOf("swing").forGetter(Setting::swing)
		).apply(instance, Setting::new));
	}

	/** A wide gate standing open, described as it was when shut. */
	public record OpenGate(String block, Direction facing, long origin, int width, int height, GateSwing swing) {
		static final Codec<OpenGate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("block").forGetter(OpenGate::block),
			Direction.CODEC.fieldOf("facing").forGetter(OpenGate::facing),
			Codec.LONG.fieldOf("origin").forGetter(OpenGate::origin),
			Codec.INT.fieldOf("width").forGetter(OpenGate::width),
			Codec.INT.fieldOf("height").forGetter(OpenGate::height),
			Codec.STRING.xmap(GateSwing::parse, GateSwing::name).optionalFieldOf("swing", GateSwing.DOUBLE)
				.forGetter(OpenGate::swing)
		).apply(instance, OpenGate::new));
	}

	private record Opened(OpenGate gate, List<Long> squares) {
		static final Codec<Opened> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			OpenGate.CODEC.fieldOf("gate").forGetter(Opened::gate),
			Codec.LONG.listOf().fieldOf("squares").forGetter(Opened::squares)
		).apply(instance, Opened::new));
	}

	private record Data(List<Setting> settings, List<Opened> opened) {
		static final Codec<Data> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Setting.CODEC.listOf().optionalFieldOf("settings", List.of()).forGetter(Data::settings),
			Opened.CODEC.listOf().optionalFieldOf("opened", List.of()).forGetter(Data::opened)
		).apply(instance, Data::new));
	}

	public static final Codec<GateSwings> CODEC = Data.CODEC.xmap(GateSwings::fromData, GateSwings::toData);
	private static final SavedDataType<GateSwings> TYPE = new SavedDataType<>(
		Identifier.fromNamespaceAndPath(Main.MOD_ID, STORAGE_KEY), GateSwings::new, CODEC, DataFixTypes.LEVEL);

	/** Gates hung as one leaf, by where they stand; a gate not here opens as two leaves. */
	private final Map<Long, GateSwing> settings = new HashMap<>();
	/** Every square of every open wide gate, by where it stands now. */
	private final Map<Long, OpenGate> squares = new HashMap<>();

	public static GateSwings get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	private static GateSwings fromData(Data data) {
		GateSwings swings = new GateSwings();
		for (Setting setting : data.settings()) swings.settings.put(setting.pos(), setting.swing());
		for (Opened opened : data.opened()) {
			for (long square : opened.squares()) swings.squares.put(square, opened.gate());
		}
		return swings;
	}

	private Data toData() {
		List<Setting> out = new ArrayList<>();
		for (Map.Entry<Long, GateSwing> entry : this.settings.entrySet()) {
			out.add(new Setting(entry.getKey(), entry.getValue()));
		}
		Map<OpenGate, List<Long>> grouped = new LinkedHashMap<>();
		for (Map.Entry<Long, OpenGate> entry : this.squares.entrySet()) {
			grouped.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
		}
		List<Opened> opened = new ArrayList<>();
		for (Map.Entry<OpenGate, List<Long>> entry : grouped.entrySet()) {
			opened.add(new Opened(entry.getKey(), entry.getValue()));
		}
		return new Data(out, opened);
	}

	public GateSwing settingOf(BlockPos pos) {
		return this.settings.getOrDefault(pos.asLong(), GateSwing.DOUBLE);
	}

	/** Hang this gate this way, telling the client only when that changes what it draws. */
	public void set(ServerLevel level, BlockPos pos, GateSwing swing) {
		GateSwing was = swing == GateSwing.DOUBLE ? this.settings.remove(pos.asLong()) : this.settings.put(pos.asLong(), swing);
		if (was == null ? swing == GateSwing.DOUBLE : was == swing) return;
		this.setDirty();
		if (this.openAt(pos) == null) mark(level, pos, swing, false);
	}

	/**
	 * Hang every one of these gates from the same post. {@code own} is the hinge as a gate facing
	 * {@code facing} has it; a gate facing the other way has its own left on the other side, so
	 * it is hung mirrored.
	 */
	public void hang(ServerLevel level, Iterable<BlockPos> gates, Direction facing, GateSwing own) {
		for (BlockPos gate : gates) {
			BlockState state = level.getBlockState(gate);
			if (!(state.getBlock() instanceof FenceGateBlock)) continue;
			this.set(level, gate, state.getValue(FenceGateBlock.FACING) == facing ? own : own.mirrored());
		}
	}

	/**
	 * The hinge a bank of gates agrees on, as a gate facing {@code facing} has it: one post if
	 * every gate is hung from it, two leaves if any is not or they differ. A bank whose gates were
	 * hung one by one, or joined from two banks hung different ways, opens as two leaves.
	 */
	public GateSwing agreedBy(ServerLevel level, Iterable<BlockPos> gates, Direction facing) {
		GateSwing agreed = null;
		for (BlockPos gate : gates) {
			BlockState state = level.getBlockState(gate);
			if (!(state.getBlock() instanceof FenceGateBlock)) continue;
			GateSwing own = this.settingOf(gate);
			GateSwing seen = state.getValue(FenceGateBlock.FACING) == facing ? own : own.mirrored();
			if (agreed == null) agreed = seen;
			else if (agreed != seen) return GateSwing.DOUBLE;
		}
		return agreed == null ? GateSwing.DOUBLE : agreed;
	}

	/**
	 * A gate new to a bank: the whole bank is hung the way the rest of it agrees on, the new gate
	 * included, so a bank always has one hinge. A setting left behind on this square by a gate
	 * that is gone goes with it.
	 */
	public void adopt(ServerLevel level, BlockPos pos, BlockState state) {
		this.set(level, pos, GateSwing.DOUBLE);
		Set<BlockPos> bank = new HashSet<>(GateBank.gatesOf(level, pos, state));
		bank.removeIf(gate -> this.openAt(gate) != null);
		Set<BlockPos> others = new HashSet<>(bank);
		others.remove(pos);
		Direction facing = state.getValue(FenceGateBlock.FACING);
		this.hang(level, bank, facing, this.agreedBy(level, others, facing));
	}

	/**
	 * A gate turning round: its left and right swap, so its hinge swaps too, and the leaf stays
	 * on the post it was hung from.
	 */
	public void followTurn(ServerLevel level, BlockPos pos) {
		GateSwing swing = this.settingOf(pos);
		if (swing != GateSwing.DOUBLE) this.set(level, pos, swing.mirrored());
	}

	/** A gate that travelled takes its hinge with it, and leaves no mark where it stood. */
	public void move(ServerLevel level, BlockPos from, BlockPos to) {
		GateSwing swing = this.settings.remove(from.asLong());
		if (swing != null) this.settings.put(to.asLong(), swing);
		this.setDirty();
		mark(level, from, GateSwing.DOUBLE, false);
	}

	/** Mark this gate by its own hinge, as a gate not standing open as part of a wide one. */
	public void remark(ServerLevel level, BlockPos pos) {
		mark(level, pos, this.settingOf(pos), false);
	}

	/** A gate gone from this square, however it went: its hinge, and the open gate it was part of. */
	public void forget(ServerLevel level, BlockPos pos) {
		OpenGate open = this.openAt(pos);
		if (open != null) this.clearOpen(level, open, true);
		if (this.settings.remove(pos.asLong()) != null) this.setDirty();
		mark(level, pos, GateSwing.DOUBLE, false);
	}

	public OpenGate openAt(BlockPos pos) {
		return this.squares.get(pos.asLong());
	}

	public Set<OpenGate> openGates() {
		return new HashSet<>(this.squares.values());
	}

	/** Remember an open wide gate where its squares now stand, and mark each with its leaf. */
	public void markOpen(ServerLevel level, OpenGate gate, List<BlockPos> at, List<GateSwing> sides) {
		for (int i = 0; i < at.size(); i++) {
			this.squares.put(at.get(i).asLong(), gate);
			mark(level, at.get(i), sides.get(i), true);
		}
		this.setDirty();
	}

	/** Forget an open wide gate, and, when {@code remark}, mark what is left of it by its own hinges again. */
	public void clearOpen(ServerLevel level, OpenGate gate, boolean remark) {
		List<Long> standing = new ArrayList<>();
		this.squares.entrySet().removeIf(entry -> {
			if (!entry.getValue().equals(gate)) return false;
			standing.add(entry.getKey());
			return true;
		});
		if (standing.isEmpty()) return;
		this.setDirty();
		if (!remark) return;
		for (long square : standing) {
			BlockPos pos = BlockPos.of(square);
			mark(level, pos, this.settingOf(pos), false);
		}
	}

	/** Marks are not saved: every hung gate and every open wide gate is marked again on load. */
	public void remark(ServerLevel level) {
		for (Map.Entry<Long, GateSwing> entry : this.settings.entrySet()) {
			if (!this.squares.containsKey(entry.getKey())) mark(level, BlockPos.of(entry.getKey()), entry.getValue(), false);
		}
		for (OpenGate gate : this.openGates()) {
			GateGroup group = GateGroup.of(gate);
			List<BlockPos> at = group.squares(true);
			for (int i = 0; i < at.size(); i++) mark(level, at.get(i), group.sideOf(i), true);
		}
	}

	private static void mark(ServerLevel level, BlockPos pos, GateSwing swing, boolean wide) {
		BlockMarkApi marks = PandoricalApi.blockMarks();
		for (GateSwing each : GateSwing.values()) {
			if (each.mark() == null) continue;
			if (each == swing) marks.mark(level, pos, each.mark());
			else marks.unmark(level, pos, each.mark());
		}
		if (wide) marks.mark(level, pos, BlockMarkApi.GATE_WIDE);
		else marks.unmark(level, pos, BlockMarkApi.GATE_WIDE);
	}
}
