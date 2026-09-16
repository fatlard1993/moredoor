package justfatlard.moredoor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.pandorical.api.BlockMarkApi;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Which gates are hung as one leaf, and from which post. A gate block has no property for it,
 * so it lives here per gate, and the client is told by a mark on the block.
 */
public final class GateSwings extends SavedData {
	private static final String STORAGE_KEY = "moredoor_gate_swings";

	private record Setting(long pos, GateSwing swing) {
		static final Codec<Setting> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.LONG.fieldOf("pos").forGetter(Setting::pos),
			Codec.STRING.xmap(GateSwing::parse, GateSwing::name).fieldOf("swing").forGetter(Setting::swing)
		).apply(instance, Setting::new));
	}

	private record Data(List<Setting> settings) {
		static final Codec<Data> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Setting.CODEC.listOf().optionalFieldOf("settings", List.of()).forGetter(Data::settings)
		).apply(instance, Data::new));
	}

	public static final Codec<GateSwings> CODEC = Data.CODEC.xmap(GateSwings::fromData, GateSwings::toData);
	private static final SavedDataType<GateSwings> TYPE = new SavedDataType<>(
		Identifier.parse(STORAGE_KEY), GateSwings::new, CODEC, DataFixTypes.LEVEL);

	/** Gates hung as one leaf; a gate not here opens the way the game draws it. */
	private final Map<Long, GateSwing> settings = new HashMap<>();

	public static GateSwings get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	private static GateSwings fromData(Data data) {
		GateSwings swings = new GateSwings();
		for (Setting setting : data.settings()) swings.settings.put(setting.pos(), setting.swing());
		return swings;
	}

	private Data toData() {
		List<Setting> out = new ArrayList<>();
		for (Map.Entry<Long, GateSwing> entry : this.settings.entrySet()) {
			out.add(new Setting(entry.getKey(), entry.getValue()));
		}
		return new Data(out);
	}

	public GateSwing settingOf(BlockPos pos) {
		return this.settings.getOrDefault(pos.asLong(), GateSwing.DOUBLE);
	}

	/** Hang this gate this way; the client hears of it through the mark either way. */
	public void set(ServerLevel level, BlockPos pos, GateSwing swing) {
		if (swing == GateSwing.DOUBLE) this.settings.remove(pos.asLong());
		else this.settings.put(pos.asLong(), swing);
		this.setDirty();
		remarkOne(level, pos, swing);
	}

	public void forget(ServerLevel level, BlockPos pos) {
		if (this.settings.remove(pos.asLong()) != null) this.setDirty();
		remarkOne(level, pos, GateSwing.DOUBLE);
	}

	/** Marks are not saved: every hung gate is marked again when its level loads. */
	public void remark(ServerLevel level) {
		for (Map.Entry<Long, GateSwing> entry : this.settings.entrySet()) {
			remarkOne(level, BlockPos.of(entry.getKey()), entry.getValue());
		}
	}

	private static void remarkOne(ServerLevel level, BlockPos pos, GateSwing swing) {
		BlockMarkApi marks = PandoricalApi.blockMarks();
		for (GateSwing each : GateSwing.values()) {
			if (each.mark() == null) continue;
			if (each == swing) marks.mark(level, pos, each.mark());
			else marks.unmark(level, pos, each.mark());
		}
	}
}
