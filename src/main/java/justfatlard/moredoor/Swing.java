package justfatlard.moredoor;

import net.minecraft.world.level.block.state.properties.DoorHingeSide;

/**
 * The ways a door moves: hung from a side and swung, or slid along its own plane.
 *
 * <p>Sides only. A door block hangs from its left or its right, and that is what a door is;
 * hanging from the top or the bottom is what a trapdoor is, and trapdoors stay trapdoors.
 */
public enum Swing {
	LEFT, RIGHT, SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN;

	public boolean isSideHinge() {
		return this == LEFT || this == RIGHT;
	}

	public boolean isSlide() {
		return !isSideHinge();
	}

	/** The swing a vanilla door block encodes in its hinge. */
	public static Swing of(DoorHingeSide hinge) {
		return hinge == DoorHingeSide.RIGHT ? RIGHT : LEFT;
	}

	public static Swing parse(String name) {
		for (Swing swing : values()) {
			if (swing.name().equalsIgnoreCase(name)) return swing;
		}
		return LEFT;
	}
}
