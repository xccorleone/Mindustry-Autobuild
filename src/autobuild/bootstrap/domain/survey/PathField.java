package autobuild.bootstrap.domain.survey;

import java.util.*;

import static autobuild.bootstrap.domain.shared.Dir.*;

/**
 * Line cost from the core to every tile: Dijkstra over usable tiles from the tiles touching the core.
 * A belt step costs 1; a bridge jump of 2..range tiles over anything costs 2 (it lands on a usable
 * tile). {@link #UNREACHABLE} where no line can get.
 */
public final class PathField{
    public static final int UNREACHABLE = Integer.MAX_VALUE;
    public static final int BRIDGE_COST = 2;

    public final int w, h;
    final int[] dist;

    PathField(int w, int h){
        this.w = w;
        this.h = h;
        dist = new int[w * h];
        Arrays.fill(dist, UNREACHABLE);
    }

    public int at(int x, int y){ return x < 0 || y < 0 || x >= w || y >= h ? UNREACHABLE : dist[x + y * w]; }

    static PathField compute(int w, int h, boolean[] usable, int[] core, int bridgeRange){
        PathField f = new PathField(w, h);
        PriorityQueue<long[]> q = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
        for(int x = core[0] - 1; x <= core[2] + 1; x++) for(int y = core[1] - 1; y <= core[3] + 1; y++){
            boolean ring = (x == core[0] - 1 || x == core[2] + 1) != (y == core[1] - 1 || y == core[3] + 1);
            if(ring && x >= 0 && y >= 0 && x < w && y < h && usable[x + y * w]){
                f.dist[x + y * w] = 1;
                q.add(new long[]{1, x + y * w});
            }
        }
        while(!q.isEmpty()){
            long[] e = q.poll();
            int i = (int)e[1];
            if(e[0] > f.dist[i]) continue;
            int x = i % w, y = i / w;
            for(int d = 0; d < 4; d++){
                for(int k = 1; k <= Math.max(1, bridgeRange); k++){
                    int nx = x + DX[d] * k, ny = y + DY[d] * k;
                    if(nx < 0 || ny < 0 || nx >= w || ny >= h) break;
                    int n = nx + ny * w;
                    if(!usable[n]) continue;
                    long nd = e[0] + (k == 1 ? 1 : BRIDGE_COST);
                    if(nd < f.dist[n]){
                        f.dist[n] = (int)nd;
                        q.add(new long[]{nd, n});
                    }
                }
            }
        }
        return f;
    }
}
