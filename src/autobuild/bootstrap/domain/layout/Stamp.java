package autobuild.bootstrap.domain.layout;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.module.ModuleSpec.Port;
import autobuild.bootstrap.domain.shared.Placement;

import java.util.*;

/**
 * A module's blueprint turned by {@code rot} quarter turns counter-clockwise and moved so that its
 * footprint's lower left corner is (ox, oy). A block's centre is recomputed from its turned
 * footprint (even sizes sit off centre), links turn with it, ports keep their attach tiles and turn
 * their directions. Marker blocks are not built: their tiles stay free for the port's line.
 */
public final class Stamp{
    /**
     * A port in map coordinates: the attach tile (an inlet line's last block / an outlet line's
     * first block stands there) and the direction items move. An inlet's last block feeds the tile
     * one step further along {@code dir}.
     */
    public static final class StampPort{
        public final Port port;
        public final int x, y, dir;

        StampPort(Port port, int x, int y, int dir){
            this.port = port;
            this.x = x;
            this.y = y;
            this.dir = dir;
        }

        /** the tile the line comes from (inlet) or goes on to (outlet): one step outside the attach tile */
        public int approachX(boolean inlet){ return x + (inlet ? -1 : 1) * autobuild.bootstrap.domain.shared.Dir.DX[dir]; }

        public int approachY(boolean inlet){ return y + (inlet ? -1 : 1) * autobuild.bootstrap.domain.shared.Dir.DY[dir]; }
    }

    public final ModuleSpec spec;
    public final int rot, w, h, x0, y0;
    public final List<Placement> blocks = new ArrayList<>();
    public final List<StampPort> inlets = new ArrayList<>(), outlets = new ArrayList<>();
    /** marker tiles {x, y} */
    public final List<int[]> markers = new ArrayList<>();

    Stamp(ModuleSpec spec, int rot, int ox, int oy){
        this.spec = spec;
        this.rot = rot & 3;
        w = this.rot % 2 == 0 ? spec.width : spec.height;
        h = this.rot % 2 == 0 ? spec.height : spec.width;
        x0 = ox;
        y0 = oy;
    }

    public int x1(){ return x0 + w - 1; }

    public int y1(){ return y0 + h - 1; }

    /**
     * @param swap block replacements (e.g. titanium conveyors by plain ones), may be empty
     * @param owner network id of the module's blocks
     */
    public static Stamp of(ModuleSpec spec, int rot, int ox, int oy, Catalog cat, Map<String, String> swap, int owner){
        Stamp s = new Stamp(spec, rot, ox, oy);
        Blueprint bp = spec.blueprint;
        // corner of the turned blueprint: turned (0,0) / (w-1,h-1), whichever is lower
        int[] a = turn(s.rot, 0, 0), b = turn(s.rot, spec.width - 1, spec.height - 1);
        int mx = Math.min(a[0], b[0]), my = Math.min(a[1], b[1]);
        for(Blueprint.Tile t : bp.tiles){
            BlockInfo info = cat.block(t.block);
            int[] c = s.center(info, t.x, t.y, mx, my);
            String kind = info.kind;
            if(kind.equals("itemsource") || kind.equals("liquidsource") || kind.equals("itemvoid")){
                s.markers.add(c);
                continue;
            }
            Object config = t.config;
            if(config instanceof int[] d) config = s.link(info, t.x, t.y, d, c, mx, my, cat, bp);
            else if(config instanceof int[][] ds){
                int[][] out = new int[ds.length][];
                for(int i = 0; i < ds.length; i++) out[i] = s.link(info, t.x, t.y, ds[i], c, mx, my, cat, bp);
                config = out;
            }
            s.blocks.add(new Placement(swap.getOrDefault(t.block, t.block), c[0], c[1], (t.rot + s.rot) & 3, config, owner));
        }
        for(Port p : spec.inlets) s.inlets.add(s.port(p, mx, my));
        for(Port p : spec.outlets) s.outlets.add(s.port(p, mx, my));
        return s;
    }

    /** (x, y) turned r quarter turns counter-clockwise about the origin */
    static int[] turn(int r, int x, int y){
        return switch(r & 3){
            case 0 -> new int[]{x, y};
            case 1 -> new int[]{-y, x};
            case 2 -> new int[]{-x, -y};
            default -> new int[]{y, -x};
        };
    }

    /** blueprint tile -> map tile */
    int[] tile(int x, int y, int mx, int my){
        int[] t = turn(rot, x, y);
        return new int[]{x0 + t[0] - mx, y0 + t[1] - my};
    }

    /** map centre of a block whose blueprint centre is (x, y) */
    int[] center(BlockInfo b, int x, int y, int mx, int my){
        int[] p = tile(x + b.lo(), y + b.lo(), mx, my), q = tile(x + b.hi(), y + b.hi(), mx, my);
        return new int[]{Math.min(p[0], q[0]) - b.lo(), Math.min(p[1], q[1]) - b.lo()};
    }

    /** a link from the block at (x, y) by d, from its new centre c: to the turned target tile */
    int[] link(BlockInfo b, int x, int y, int[] d, int[] c, int mx, int my, Catalog cat, Blueprint bp){
        Blueprint.Tile target = null;
        for(Blueprint.Tile t : bp.tiles){
            BlockInfo ti = cat.block(t.block);
            if(x + d[0] >= t.x + ti.lo() && x + d[0] <= t.x + ti.hi() && y + d[1] >= t.y + ti.lo() && y + d[1] <= t.y + ti.hi()){ target = t; break; }
        }
        int[] to = target == null ? tile(x + d[0], y + d[1], mx, my) : center(cat.block(target.block), target.x, target.y, mx, my);
        return new int[]{to[0] - c[0], to[1] - c[1]};
    }

    StampPort port(Port p, int mx, int my){
        int[] t = tile(p.x, p.y, mx, my);
        return new StampPort(p, t[0], t[1], (p.dir + rot) & 3);
    }

    /** the tile is inside the footprint */
    public boolean inside(int x, int y){ return x >= x0 && y >= y0 && x <= x1() && y <= y1(); }
}
