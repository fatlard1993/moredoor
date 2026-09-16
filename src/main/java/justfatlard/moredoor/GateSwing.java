package justfatlard.moredoor;

import justfatlard.pandorical.api.BlockMarkApi;

/**
 * How a fence gate opens: as the two leaves the game gives it, meeting in the middle, or as
 * one leaf hung from its left post or its right. Left and right are the gate's own, looking
 * the way it faces.
 */
public enum GateSwing {
	DOUBLE, LEFT, RIGHT;

	/** The mark a client sees on a gate hung this way, or null for the gate the game draws. */
	public String mark() {
		return switch (this) {
			case LEFT -> BlockMarkApi.GATE_HINGE_LEFT;
			case RIGHT -> BlockMarkApi.GATE_HINGE_RIGHT;
			case DOUBLE -> null;
		};
	}

	public GateSwing mirrored() {
		return this == LEFT ? RIGHT : this == RIGHT ? LEFT : DOUBLE;
	}

	public static GateSwing parse(String name) {
		for (GateSwing swing : values()) {
			if (swing.name().equalsIgnoreCase(name)) return swing;
		}
		return DOUBLE;
	}
}
