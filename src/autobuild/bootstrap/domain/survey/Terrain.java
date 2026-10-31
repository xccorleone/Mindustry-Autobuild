package autobuild.bootstrap.domain.survey;

/** Port: the map as the survey sees it (the game adapter reads a snapshot of the world). */
public interface Terrain{
    int width();

    int height();

    /** item a drill would mine here (ore overlay, else the floor's drop; ore under a wall: null) */
    String ore(int x, int y);

    /** nothing stands here and blocks can be built */
    boolean buildable(int x, int y);

    /** liquid a pump would pump here, or null */
    String liquid(int x, int y);

    /** the core's footprint {x0, y0, x1, y1} */
    int[] core();
}
