package autobuild.bootstrap.domain.survey;

/** Pumpable tiles of one liquid, 4-connected. */
public final class WaterBody{
    public final String liquid;
    /** tile indices x + y * w, w = the surveyed map's width ({@link PathField#w}) */
    public final int[] tiles;
    /** line cost from the core to its nearest tile (or a usable tile next to it, + 1) */
    public final int distance;

    WaterBody(String liquid, int[] tiles, int distance){
        this.liquid = liquid;
        this.tiles = tiles;
        this.distance = distance;
    }

    public int size(){ return tiles.length; }
}
