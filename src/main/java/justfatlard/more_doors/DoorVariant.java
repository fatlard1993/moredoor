package justfatlard.more_doors;

/**
 * The six doors every wood can be, beside the one vanilla already makes.
 *
 * <p>Vanilla is inconsistent here and always has been: an oak door has windows, a spruce door has
 * iron banding, an acacia door has slats, a cherry door has blossom cutouts. Which one you get is a
 * fact about the tree rather than a choice, so a builder who wants a solid oak door and a glazed
 * cherry one cannot have either.
 *
 * <p>So the shape and the wood are separated. Two shapes are offered in every wood - the panelled
 * one dark oak has always had, and the windowed one oak and iron have always had - and the wood's
 * own vanilla face stays available beside them rather than being the only thing it can be.
 *
 * <p>That fifth face is not listed here, because it already exists: it is vanilla's own block. A
 * copy of it would be a duplicate of vanilla for eleven woods and an exact duplicate of two of
 * these variants for the other two, and it would gain nothing - this mod's locking, its door banks
 * and its three-dimensional models all reach vanilla's doors already. The stonecutter cuts across
 * to it instead.
 */
public enum DoorVariant {
	/**
	 * Four panels, no opening: dark oak's shape, in this wood's colour.
	 *
	 * <p>The default, and the one the ordinary six-plank recipe makes. A door is a thing you cannot
	 * see through - that is most of the point of a door - so the shape that shuts properly is the
	 * one you get without asking for anything else.
	 */
	SOLID("", true),

	/** Oak's and iron's windowed shape, in this wood's colour. Open, so the wind comes through. */
	CLASSIC("classic_", false),

	/** The windowed shape, glazed. Lets the light in and keeps the weather out. */
	GLASS("glass_", false),

	/** The windowed shape, barred. Sees through, hears through, keeps nothing out but you. */
	BARRED("barred_", false),

	/** Glazed the whole way down, so the light reaches the floor. */
	FULL_GLASS("full_glass_", false),

	/** Barred the whole way down: a gate you can see through, not a door with a window in it. */
	FULL_BARRED("full_barred_", false);

	private final String prefix;

	/** Whether it blocks light and sound the way a shut door ought to. */
	public final boolean solid;

	DoorVariant(String prefix, boolean solid) {
		this.prefix = prefix;
		this.solid = solid;
	}

	/** The block id for this wood and variant, e.g. {@code oak_glass_door}. */
	public String doorName(String material) {
		return material + "_" + this.prefix + "door";
	}

	public String trapdoorName(String material) {
		return material + "_" + this.prefix + "trapdoor";
	}
}
