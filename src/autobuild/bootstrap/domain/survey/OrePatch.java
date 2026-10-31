package autobuild.bootstrap.domain.survey;

/** Usable tiles of one ore, 8-connected. */
public final class OrePatch{
    public final String item;
    /** tile indices x + y * w, w = the surveyed map's width ({@link PathField#w}) */
    public final int[] tiles;
    public final int x0, y0, x1, y1;
    /** line cost from the core to its nearest tile */
    public final int distance;

    OrePatch(String item, int[] tiles, int w, int distance){
        this.item = item;
        this.tiles = tiles;
        int ax = Integer.MAX_VALUE, ay = Integer.MAX_VALUE, bx = Integer.MIN_VALUE, by = Integer.MIN_VALUE;
        for(int t : tiles){
            ax = Math.min(ax, t % w);
            ay = Math.min(ay, t / w);
            bx = Math.max(bx, t % w);
            by = Math.max(by, t / w);
        }
        x0 = ax;
        y0 = ay;
        x1 = bx;
        y1 = by;
        this.distance = distance;
    }

    public int size(){ return tiles.length; }

    @Override public String toString(){ return item + "x" + tiles.length + "@(" + x0 + "," + y0 + ")-(" + x1 + "," + y1 + ") d=" + distance; }
}
