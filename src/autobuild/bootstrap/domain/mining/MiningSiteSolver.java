package autobuild.bootstrap.domain.mining;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.layout.LayoutOptions;
import autobuild.bootstrap.domain.shared.*;
import autobuild.bootstrap.domain.survey.*;
import autobuild.plan.DrillLayoutPlanner;
import autobuild.plan.DrillLayoutPlanner.*;

import java.util.*;

/**
 * Anti-corruption layer over {@link DrillLayoutPlanner}: a mining site for one supply line's share of
 * an ore patch.
 * <ol>
 * <li><b>Crop</b>: the patch's free tiles nearest the line's destination, until they give the share
 *     with a margin ({@link #MARGIN}) at the plan's coverage; their bounding box plus
 *     {@link #BORDER} tiles (fewer if nothing outside is free) is the planner's region (the rest of the
 *     patch stays for other lines).
 *     A site falling short is planned again on twice the tiles (at most {@link #GROW} times); the
 *     best one is kept.</li>
 * <li><b>Sink</b>: a free tile just outside the side of the region facing the destination, as close
 *     to it as possible; the planner's frame turns so that this side is +u.</li>
 * <li><b>Plan</b>: maximum output, one drill type (the best allowed for the item), bridges; belts and
 *     drills only where {@link Reservation} allows them for this line's network, belts limited to one
 *     belt's worth into the sink.</li>
 * </ol>
 */
public final class MiningSiteSolver{
    static final double MARGIN = 1.15;
    static final int BORDER = 2, GROW = 3, MIN_TILES = 12;

    private MiningSiteSolver(){}

    /** @param toCore the line ends in the core (else its drills must not touch the core: they would feed it) */
    public static MiningSite solve(Terrain t, Reservation res, LayoutOptions o, OrePatch patch, double need, int destX, int destY, int owner, boolean toCore){
        Catalog cat = o.cat;
        BlockInfo drill = o.drillFor(patch.item);
        double perTile = o.perTile(patch.item);
        int w = res.w;
        List<Integer> tiles = new ArrayList<>();
        for(int i : patch.tiles) if(res.free(i % w, i / w)) tiles.add(i);
        if(drill == null || tiles.isEmpty()){
            MiningSite s = new MiningSite(patch.item, null);
            s.error = drill == null ? "no drill can mine " + patch.item : "patch taken";
            return s;
        }
        tiles.sort(Comparator.comparingInt(i -> Math.abs(i % w - destX) + Math.abs(i / w - destY)));
        // grow the crop while the site falls short (small regions lose much of their ore to the drills' grid)
        MiningSite best = null;
        double margin = MARGIN;
        for(int attempt = 0; attempt < GROW; attempt++, margin *= 2){
            int take = (int)Math.min(tiles.size(), Math.max(MIN_TILES, Math.ceil(need * margin / (o.coverage * perTile))));
            MiningSite s = site(t, res, o, drill, perTile, patch.item, tiles, take, destX, destY, owner, toCore);
            if(best == null || s.ok() && (!best.ok() || s.rate > best.rate)){
                if(best != null && best.ok()) release(res, o, best);
                best = s;
            }else if(s.ok()) release(res, o, s);
            if(best.ok() && best.rate >= need * 0.95 || take == tiles.size()) break;
        }
        return best;
    }

    static void release(Reservation res, LayoutOptions o, MiningSite s){
        for(Placement q : s.placements) res.release(q, o.cat.block(q.block));
    }

    /** a site on the first `take` tiles: their bounding box plus the border, the side facing the destination first */
    static MiningSite site(Terrain t, Reservation res, LayoutOptions o, BlockInfo drill, double perTile, String item, List<Integer> tiles, int take,
                           int destX, int destY, int owner, boolean toCore){
        int w = res.w;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for(int k = 0; k < take; k++){
            int i = tiles.get(k);
            x0 = Math.min(x0, i % w);
            y0 = Math.min(y0, i / w);
            x1 = Math.max(x1, i % w);
            y1 = Math.max(y1, i / w);
        }
        MiningSite best = null;
        // a narrower border when nothing outside the wider one is free for the output (walled-in patches)
        for(int border = BORDER; border >= 0; border--){
            int[] region = {Math.max(0, x0 - border), Math.max(0, y0 - border), Math.min(w - 1, x1 + border), Math.min(res.h - 1, y1 + border)};
            for(int side : sides(region, destX, destY)){
                MiningSite s = plan(t, res, o, drill, perTile, item, region, side, destX, destY, owner, toCore);
                if(s.ok()) return s;
                if(best == null) best = s;
            }
        }
        return best;
    }

    /** the region's sides, the one facing the destination first */
    static int[] sides(int[] r, int x, int y){
        double[] out = {x - r[2], y - r[3], r[0] - x, r[1] - y};
        Integer[] order = {0, 1, 2, 3};
        Arrays.sort(order, (a, b) -> Double.compare(out[b], out[a]));
        return Arrays.stream(order).mapToInt(Integer::intValue).toArray();
    }

    static MiningSite plan(Terrain t, Reservation res, LayoutOptions o, BlockInfo drill, double perTile, String item, int[] region, int side,
                           int destX, int destY, int owner, boolean toCore){
        MiningSite site = new MiningSite(item, region);
        Frame f = new Frame(region[0], region[1], region[2], region[3], side);
        int W = f.W, H = f.H, s = drill.size;
        // the sink: outside the facing side, nearest the destination
        int bestV = -1, bestD = Integer.MAX_VALUE;
        for(int v = 0; v < H; v++){
            int x = f.wx(W, v), y = f.wy(W, v);
            if(!res.free(x, y) || res.ring(x, y) || res.kept(x, y) || !res.itemSafe(x, y, owner)) continue;
            int d = Math.abs(x - destX) + Math.abs(y - destY);
            if(d < bestD){ bestD = d; bestV = v; }
        }
        if(bestV < 0){ site.error = "no free tile for the output"; return site; }

        Catalog cat = o.cat;
        boolean[] low = new boolean[256]; // by item id, filled for the ores in the region
        int target = cat.item(item).id;
        Params p = new Params();
        p.W = W;
        p.H = H;
        p.drillSize = s;
        p.target = target;
        p.mode = Mode.OUTPUT;
        p.power = false;
        p.bridges = o.bridge != null;
        p.bridgeRange = o.bridgeRange();
        p.beltOk = new boolean[W][H];
        p.nodeOk = new boolean[W][H];
        p.drillOk = new boolean[W][H];
        p.drop = new int[W][H];
        p.foreignAcceptor = new boolean[W][H];
        p.lowPriority = low;
        boolean[][] minOk = new boolean[region[2] - region[0] + 1][region[3] - region[1] + 1];
        for(int x = region[0]; x <= region[2]; x++) for(int y = region[1]; y <= region[3]; y++){
            boolean ok = x + s - 1 <= region[2] && y + s - 1 <= region[3] && res.footprintPushSafe(x, y, s, owner);
            for(int i = 0; i < s && ok; i++) for(int j = 0; j < s && ok; j++) ok = res.free(x + i, y + j) && !res.ring(x + i, y + j) && !res.kept(x + i, y + j);
            ok &= !nearKeptFootprint(res, x, y, s) && (toCore || !res.touchesCore(x, y, s));
            minOk[x - region[0]][y - region[1]] = ok;
        }
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            int x = f.wx(u, v), y = f.wy(u, v);
            // on the region's edge the planner can't see what's outside: an end bridge there must not touch another network
            boolean edge = x == region[0] || y == region[1] || x == region[2] || y == region[3];
            p.beltOk[u][v] = res.free(x, y) && !res.ring(x, y) && !res.kept(x, y) && res.itemSafe(x, y, owner)
                && (!edge || res.pushSafe(x, y, owner, Reservation.FREE) && (toCore || !res.touchesCore(x, y, 1)));
            String ore = t.ore(x, y);
            Catalog.ItemInfo it = ore == null ? null : cat.item(ore);
            p.drop[u][v] = it != null && drill.tier >= it.hardness && drill.drillTime.containsKey(ore) ? it.id : -1;
            if(it != null) low[it.id] = it.lowPriority;
            int on = res.ownerAt(x, y);
            p.foreignAcceptor[u][v] = on != Reservation.FREE && on != owner && (res.flagsAt(x, y) & Reservation.TAKES_ITEMS) != 0;
            if(u + s <= W && v + s <= H){
                int mx = f.minX(u, v, s), my = f.minY(u, v, s);
                p.drillOk[u][v] = minOk[mx - region[0]][my - region[1]];
            }
        }
        p.sinks = new int[][]{{W, bestV}};
        p.portV = bestV;
        p.sinkCapacity = (float)o.beltSpeed();
        p.itemsPerOre = (float)perTile;
        DrillLayoutPlanner planner = new DrillLayoutPlanner(p);
        if(!planner.plan()){ site.error = planner.failure; return site; }

        site.sinkX = f.wx(W, bestV);
        site.sinkY = f.wy(W, bestV);
        site.sinkDir = side;
        site.rate = planner.sinkLoad.length == 0 ? 0 : Math.min(planner.sinkLoad[0], o.beltSpeed());
        for(Place pl : planner.places){
            int x = f.wx(pl.u, pl.v), y = f.wy(pl.u, pl.v);
            Placement q = switch(pl.kind){
                case DrillLayoutPlanner.DRILL -> {
                    site.drills++;
                    yield new Placement(drill.name, f.minX(pl.u, pl.v, s) - drill.lo(), f.minY(pl.u, pl.v, s) - drill.lo(), 0, null, owner);
                }
                case DrillLayoutPlanner.BELT -> new Placement(o.belt, x, y, (pl.dir + side) % 4, null, owner);
                case DrillLayoutPlanner.BRIDGE -> new Placement(o.bridge, x, y, 0,
                    pl.lu == pl.u && pl.lv == pl.v ? null : new int[]{f.wx(pl.lu, pl.lv) - x, f.wy(pl.lu, pl.lv) - y}, owner);
                default -> null;
            };
            if(q == null){ site.error = "unexpected block from the drill planner: " + pl; return site; }
            site.placements.add(q);
        }
        if(site.rate <= 1e-6){ site.error = "no ore reaches the output"; return site; }
        for(Placement q : site.placements) res.claim(q, cat.block(q.block));
        return site;
    }

    /** a drill (pusher) here would stand next to a port's lane or its mouth */
    static boolean nearKeptFootprint(Reservation res, int x0, int y0, int s){
        for(int i = -1; i <= s; i++) for(int j = -1; j <= s; j++){
            boolean edge = (i == -1 || i == s) != (j == -1 || j == s);
            if(edge && (res.kept(x0 + i, y0 + j) || res.mouth(x0 + i, y0 + j))) return true;
        }
        return false;
    }

    /** The region in the drill planner's frame, where items leave toward +u (= map side `dir`). */
    static final class Frame{
        final int ax, ay, bx, by, dir, W, H;

        Frame(int ax, int ay, int bx, int by, int dir){
            this.ax = ax; this.ay = ay; this.bx = bx; this.by = by; this.dir = dir;
            boolean rot = dir % 2 == 1;
            W = rot ? by - ay + 1 : bx - ax + 1;
            H = rot ? bx - ax + 1 : by - ay + 1;
        }

        int wx(int u, int v){
            return switch(dir){
                case 0 -> ax + u;
                case 1 -> bx - v;
                case 2 -> bx - u;
                default -> ax + v;
            };
        }

        int wy(int u, int v){
            return switch(dir){
                case 0 -> ay + v;
                case 1 -> ay + u;
                case 2 -> by - v;
                default -> by - u;
            };
        }

        int minX(int u, int v, int s){ return Math.min(wx(u, v), wx(u + s - 1, v + s - 1)); }

        int minY(int u, int v, int s){ return Math.min(wy(u, v), wy(u + s - 1, v + s - 1)); }
    }
}
