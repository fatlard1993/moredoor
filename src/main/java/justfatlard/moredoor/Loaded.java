package justfatlard.moredoor;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Whether squares and the signals reaching them can be read without loading anything: every chunk
 * within two blocks is in memory. A signal can come through a block beside a square, which reads
 * that block's own neighbours.
 */
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
		return minX > maxX || level.hasChunksAt(minX - 2, minZ - 2, maxX + 2, maxZ + 2);
	}
}
