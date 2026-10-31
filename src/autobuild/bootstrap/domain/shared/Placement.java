package autobuild.bootstrap.domain.shared;

/**
 * One block to build, in map coordinates. (x, y) is the block's centre tile as the game counts it
 * (footprint x + lo .. x + hi). {@code config} as in a blueprint tile: an item / liquid name, a link
 * {@code int[]{dx, dy}}, several links {@code int[][]}, an {@code Integer}, or null. {@code owner} is
 * the network it belongs to (see {@code layout.Reservation}).
 */
public final class Placement{
    public final String block;
    public final int x, y, rot, owner;
    public final Object config;

    public Placement(String block, int x, int y, int rot, Object config, int owner){
        this.block = block;
        this.x = x;
        this.y = y;
        this.rot = rot;
        this.config = config;
        this.owner = owner;
    }

    @Override public String toString(){ return block + "(" + x + "," + y + ")r" + rot; }
}
