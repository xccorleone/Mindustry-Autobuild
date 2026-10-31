package autobuild.bootstrap.domain.layout;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.shared.*;
import autobuild.bootstrap.domain.survey.Terrain;
import autobuild.plan.GridRouter;
import autobuild.plan.GridRouter.*;

import java.util.*;

/**
 * Anti-corruption layer over {@link GridRouter} for the base: lines over the whole map that keep
 * off other networks ({@link Reservation}'s rules), off module rings (but for the tiles a line is
 * allowed to use: its attach tiles) and, where they can, off ore other drills may want later.
 */
public final class CorridorRouter{
    /** extra cost of a line tile on valuable ore */
    static final double ORE_COST = 0.5;

    final Reservation res;
    final Terrain terrain;
    final Catalog cat;
    final LayoutOptions o;

    public CorridorRouter(Reservation res, Terrain terrain, Catalog cat, LayoutOptions o){
        this.res = res;
        this.terrain = terrain;
        this.cat = cat;
        this.o = o;
    }

    /**
     * Routes and claims a line of network `owner`, or returns null. `allowed` tiles (x + y * w) may be
     * used even on a ring, kept, or next to `exempt`'s blocks (the module the line serves: its port's
     * attach tile and the tile before it); `exempt` also may take what the line's end pushes.
     */
    public Corridor route(String item, boolean liquid, int owner, int exempt, List<Start> starts, Goal goal, Set<Integer> allowed){
        int w = res.w;
        GridRouter r = new GridRouter(res.w, res.h, new GridRouter.Grid(){
            public double tileCost(int u, int v){
                if(!res.free(u, v)) return -1;
                if(!allowed.contains(u + v * w)){
                    if(res.ring(u, v) || res.kept(u, v)) return -1;
                    if(liquid ? !res.liquidSafe(u, v, owner) : !res.itemSafe(u, v, owner)) return -1;
                }else if(liquid ? !safeBut(u, v, owner, exempt, Reservation.PUSHES_LIQUID) : !safeBut(u, v, owner, exempt, Reservation.PUSHES_ITEMS)) return -1;
                String ore = terrain.ore(u, v);
                return 1 + (ore != null && !ore.equals(item) && !cat.item(ore).lowPriority ? ORE_COST : 0);
            }

            public boolean pushOk(int u, int v){
                if(res.nearKept(u, v, allowed)) return false;
                return liquid ? res.liquidPushSafe(u, v, owner, exempt) : res.pushSafe(u, v, owner, exempt);
            }
        });
        r.bridgeRange = liquid ? o.liquidBridgeRange() : o.bridgeRange();
        List<Step> steps = r.route(starts, goal);
        if(steps == null) return null;
        Corridor c = new Corridor(item, liquid, owner);
        c.cost = r.lastCost;
        String pipe = liquid ? o.conduit : o.belt, bridge = liquid ? o.liquidBridge : o.bridge;
        for(Step s : steps){
            Placement p = switch(s.kind){
                case GridRouter.BRIDGE -> new Placement(bridge, s.u, s.v, 0, new int[]{s.lu - s.u, s.lv - s.v}, owner);
                case GridRouter.END -> new Placement(bridge, s.u, s.v, 0, null, owner);
                default -> new Placement(pipe, s.u, s.v, s.dir, null, owner);
            };
            if(s.kind == GridRouter.PIPE) c.pipes.add(new int[]{s.u, s.v, s.dir});
            c.placements.add(p);
        }
        for(Placement p : c.placements) res.claim(p, cat.block(p.block));
        return c;
    }

    /** the attach tiles: nothing pushing into them but the served module's own blocks */
    boolean safeBut(int x, int y, int net, int exempt, int flag){
        for(int d = 0; d < 4; d++){
            int nx = x + Dir.DX[d], ny = y + Dir.DY[d];
            int on = res.ownerAt(nx, ny);
            if(on == Reservation.FREE || on == Reservation.CORE || on == net || on == exempt) continue;
            if((res.flagsAt(nx, ny) & flag) != 0) return false;
        }
        return true;
    }

    /** a line whose last block, on the tile before (x, y) along `dir`, feeds (x, y) (an inlet: attach tile + dir) */
    public static Goal into(int x, int y, int dir){
        return new Goal(){
            public boolean hit(int u, int v, int d){ return u == x && v == y && d == dir; }

            public double estimate(int u, int v){ return Math.max(0, Math.abs(u - x) + Math.abs(v - y) - 1) * 0.5; }
        };
    }

    /** a line ending in a block that feeds a rectangle (the core) from any side */
    public static Goal rect(int x0, int y0, int x1, int y1){
        return new Goal(){
            public boolean hit(int u, int v, int d){ return u >= x0 && u <= x1 && v >= y0 && v <= y1; }

            public double estimate(int u, int v){
                int dx = Math.max(0, Math.max(x0 - u, u - x1)), dy = Math.max(0, Math.max(y0 - v, v - y1));
                return Math.max(0, dx + dy - 1) * 0.5;
            }
        };
    }

    /** a line ending at a side of one of a corridor's plain belts (joining it) */
    public static Goal join(Corridor c){
        Map<Long, Integer> dirs = new HashMap<>();
        for(int[] p : c.pipes) dirs.put(((long)p[0] << 32) | (p[1] & 0xffffffffL), p[2]);
        return (u, v, d) -> {
            Integer pd = dirs.get(((long)u << 32) | (v & 0xffffffffL));
            return pd != null && (pd & 1) != (d & 1);
        };
    }

    /** one start: the first block goes on (x, y), items arrive moving `dir` */
    public static List<Start> from(int x, int y, int dir){
        return List.of(new Start(x, y, Dir.opposite(dir), 0, false));
    }
}
