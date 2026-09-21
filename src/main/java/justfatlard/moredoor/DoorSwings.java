package justfatlard.moredoor;

import justfatlard.pandorical.api.BlockMarkApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * What a door block cannot say about itself.
 *
 * <p>A door block encodes a side hinge and nothing else. Hung from the top or bottom, or sliding,
 * is remembered here per leaf, and copied to a leaf placed against it. And a door that has swung
 * open is remembered whole - which squares it is, where its wall was - because once a slid door's
 * squares are standing a room away looking closed, nothing in the world says they were a door.
 */
public final class DoorSwings extends SavedData {
	private static final String STORAGE_KEY = "swings";

	/** An open door: what it is, and the closed frame it came from. */
	public record OpenDoor(String block, Direction facing, DoorHingeSide hinge, Swing swing,
			long origin, int width, int height) {
		static final Codec<OpenDoor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("block").forGetter(OpenDoor::block),
			Direction.CODEC.fieldOf("facing").forGetter(OpenDoor::facing),
			Codec.STRING.xmap(s -> s.equals("right") ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT,
				h -> h == DoorHingeSide.RIGHT ? "right" : "left").fieldOf("hinge").forGetter(OpenDoor::hinge),
			Codec.STRING.xmap(Swing::parse, Swing::name).fieldOf("swing").forGetter(OpenDoor::swing),
			Codec.LONG.fieldOf("origin").forGetter(OpenDoor::origin),
			Codec.INT.fieldOf("width").forGetter(OpenDoor::width),
			Codec.INT.fieldOf("height").forGetter(OpenDoor::height)
		).apply(instance, OpenDoor::new));
	}

	private record Setting(long pos, Swing swing) {
		static final Codec<Setting> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.LONG.fieldOf("pos").forGetter(Setting::pos),
			Codec.STRING.xmap(Swing::parse, Swing::name).fieldOf("swing").forGetter(Setting::swing)
		).apply(instance, Setting::new));
	}

	private record Opened(OpenDoor door, List<Long> squares) {
		static final Codec<Opened> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			OpenDoor.CODEC.fieldOf("door").forGetter(Opened::door),
			Codec.LONG.listOf().fieldOf("squares").forGetter(Opened::squares)
		).apply(instance, Opened::new));
	}

	private record Data(List<Setting> settings, List<Opened> open, List<Long> detached) {
		static final Codec<Data> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Setting.CODEC.listOf().optionalFieldOf("settings", List.of()).forGetter(Data::settings),
			Opened.CODEC.listOf().optionalFieldOf("open", List.of()).forGetter(Data::open),
			Codec.LONG.listOf().optionalFieldOf("detached", List.of()).forGetter(Data::detached)
		).apply(instance, Data::new));
	}

	/** The mark a client sees on a leaf cut loose, so it draws it as its own door. */
	public static final String DETACHED_MARK = BlockMarkApi.DOOR_DETACHED;
	/** The mark a client sees on a leaf that slides, so it draws it without a knob. */
	public static final String SLIDING_MARK = BlockMarkApi.DOOR_SLIDING;

	public static final Codec<DoorSwings> CODEC = Data.CODEC.xmap(DoorSwings::fromData, DoorSwings::toData);

	private static final SavedDataType<DoorSwings> TYPE = new SavedDataType<>(
		Identifier.fromNamespaceAndPath(Main.MOD_ID, STORAGE_KEY), DoorSwings::new, CODEC, DataFixTypes.LEVEL);

	/** Leaves hung some way other than their block says, by lower half. */
	private final Map<Long, Swing> settings = new HashMap<>();
	/** Every square of every open door, by where it is now. */
	private final Map<Long, OpenDoor> squares = new HashMap<>();
	/** Leaves cut loose from the leaves beside them, by lower half. */
	private final java.util.Set<Long> detached = new java.util.HashSet<>();

	public static DoorSwings get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	/**
	 * How this leaf moves: a slide if one is remembered, else the hinge its block says.
	 *
	 * <p>The block is the one truth for a side hinge. A remembered "left" beside a block that
	 * says right had the door swinging about one side and drawn about the other.
	 */
	public Swing settingOf(BlockPos foot, BlockState state) {
		Swing remembered = this.settings.get(foot.asLong());
		if (remembered != null && remembered.isSlide()) return remembered;
		return state.hasProperty(DoorBlock.HINGE) ? Swing.of(state.getValue(DoorBlock.HINGE)) : Swing.LEFT;
	}

	/** Remember a slide; a side hinge is written into the block by whoever sets it, and forgotten here. */
	public void setSetting(ServerLevel level, BlockPos foot, Swing swing) {
		if (swing.isSlide()) {
			this.settings.put(foot.asLong(), swing);
			marks().mark(level, foot, SLIDING_MARK);
		} else {
			this.settings.remove(foot.asLong());
			marks().unmark(level, foot, SLIDING_MARK);
		}
		this.setDirty();
	}

	public void forgetSetting(ServerLevel level, BlockPos foot) {
		if (this.settings.remove(foot.asLong()) != null) this.setDirty();
		marks().unmark(level, foot, SLIDING_MARK);
	}

	/** A square that travelled takes its marks with it, so the client draws it wherever it stands. */
	public void moveMarks(ServerLevel level, BlockPos from, BlockPos to) {
		for (String mark : new String[] {DETACHED_MARK, SLIDING_MARK}) {
			if (!marks().isMarked(level, from, mark)) continue;
			marks().unmark(level, from, mark);
			marks().mark(level, to, mark);
		}
	}

	private static justfatlard.pandorical.api.BlockMarkApi marks() {
		return justfatlard.pandorical.api.PandoricalApi.blockMarks();
	}

	public boolean isDetached(BlockPos foot) {
		return this.detached.contains(foot.asLong());
	}

	/**
	 * Cut a leaf loose from its neighbours, or join it back; the client is told either way,
	 * whether or not anything changed here, so a stale mark never outlives the fact.
	 */
	public void setDetached(ServerLevel level, BlockPos foot, boolean apart) {
		boolean changed = apart ? this.detached.add(foot.asLong()) : this.detached.remove(foot.asLong());
		if (changed) this.setDirty();
		if (apart) marks().mark(level, foot, DETACHED_MARK);
		else marks().unmark(level, foot, DETACHED_MARK);
	}

	/**
	 * Marks are not saved; every detached and sliding leaf is marked again when its level loads,
	 * where it stands now, which for a door left open is not where it lives.
	 */
	public void remark(ServerLevel level) {
		Map<Long, BlockPos> standing = new HashMap<>();
		for (OpenDoor door : new java.util.HashSet<>(this.squares.values())) {
			DoorGroup group = DoorGroup.of(door);
			List<BlockPos> home = group.feet();
			List<BlockPos> now = group.openFeet();
			for (int i = 0; i < home.size(); i++) standing.put(home.get(i).asLong(), now.get(i));
		}
		for (long foot : this.detached) {
			marks().mark(level, standing.getOrDefault(foot, BlockPos.of(foot)), DETACHED_MARK);
		}
		for (Map.Entry<Long, Swing> setting : this.settings.entrySet()) {
			if (!setting.getValue().isSlide()) continue;
			marks().mark(level, standing.getOrDefault(setting.getKey(), BlockPos.of(setting.getKey())), SLIDING_MARK);
		}
	}

	/** Every door standing open, each once. */
	public java.util.Set<OpenDoor> openDoors() {
		return new java.util.HashSet<>(this.squares.values());
	}

	/** The open door this square belongs to, or null. */
	public OpenDoor openAt(BlockPos pos) {
		return this.squares.get(pos.asLong());
	}

	public void markOpen(OpenDoor door, Iterable<BlockPos> at) {
		for (BlockPos pos : at) this.squares.put(pos.asLong(), door);
		this.setDirty();
	}

	public void clearOpen(OpenDoor door) {
		if (this.squares.values().removeIf(door::equals)) this.setDirty();
	}

	private static DoorSwings fromData(Data data) {
		DoorSwings swings = new DoorSwings();
		for (Setting setting : data.settings()) swings.settings.put(setting.pos(), setting.swing());
		for (Opened opened : data.open()) {
			for (long square : opened.squares()) swings.squares.put(square, opened.door());
		}
		swings.detached.addAll(data.detached());
		return swings;
	}

	private static Data toData(DoorSwings swings) {
		List<Setting> settings = new ArrayList<>();
		for (Map.Entry<Long, Swing> e : swings.settings.entrySet()) settings.add(new Setting(e.getKey(), e.getValue()));
		Map<OpenDoor, List<Long>> grouped = new LinkedHashMap<>();
		for (Map.Entry<Long, OpenDoor> e : swings.squares.entrySet()) {
			grouped.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
		}
		List<Opened> open = new ArrayList<>();
		for (Map.Entry<OpenDoor, List<Long>> e : grouped.entrySet()) open.add(new Opened(e.getKey(), e.getValue()));
		return new Data(settings, open, List.copyOf(swings.detached));
	}
}
