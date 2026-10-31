package autobuild.bootstrap.domain.survey;

/** Tiles too close to enemy routes to build on. */
public final class DangerMask{
    private DangerMask(){}

    /** every tile within `radius` (tiles, Euclidean) of a route tile {x, y} */
    public static boolean[][] around(int w, int h, Iterable<int[]> route, float radius){
        boolean[][] m = new boolean[w][h];
        int r = (int)Math.ceil(radius);
        for(int[] p : route){
            for(int x = Math.max(0, p[0] - r); x <= Math.min(w - 1, p[0] + r); x++){
                for(int y = Math.max(0, p[1] - r); y <= Math.min(h - 1, p[1] + r); y++){
                    if((x - p[0]) * (x - p[0]) + (y - p[1]) * (y - p[1]) <= radius * radius) m[x][y] = true;
                }
            }
        }
        return m;
    }
}
