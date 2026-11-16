package justfatlard.moredoor;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Whether squares can be read without loading anything: every chunk they or their neighbours stand in is in memory. */
final class Loaded {
	private Loaded() {}

	@SafeVarargs
	static boolean around(ServerLevel level, List<BlockPos>... squares) {
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (List<BlockPos> each : squares) {
			for (BlockPos square : each) {
				minX = Math.min(minX, square.getX());
				minZ = Math.min(minZ, square.getZ());
				maxX = Math.max(maxX, square.getX());
				maxZ = Math.max(maxZ, square.getZ());
			}
		}
		return minX > maxX || level.hasChunksAt(minX - 1, minZ - 1, maxX + 1, maxZ + 1);
	}
}
