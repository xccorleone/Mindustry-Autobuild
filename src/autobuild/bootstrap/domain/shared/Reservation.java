package autobuild.bootstrap.domain.shared;

import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;

import java.util.*;

import static autobuild.bootstrap.domain.shared.Dir.*;

/**
 * Who owns each map tile, and the rules that keep networks from mixing. Every placed block belongs
 * to a network ({@link Placement#owner}: a supply line with its mining site, a module, ...) and has
 * item / liquid behaviour: it takes items, or pushes them into its neighbours (drills, crafters,
 * routers, sorters, gates, junctions, end bridges - a conveyor pushes only into its own next block;
 * an end bridge takes items only over its incoming link).
 * <ul>
 * <li>a block taking items must not stand next to another network's pusher ({@link #itemSafe});</li>
 * <li>a pusher must not stand next to another network's taker ({@link #pushSafe});</li>
 * <li>the same for liquids;</li>
 * <li>the core takes anything from anyone: it never mixes, but only lines to the core may push into
 *     it (a module's sand site next to the core would hand its sand to the core instead).</li>
 * </ul>
 * Ring tiles around a module (crafters push into anything there) take no logistics, only power nodes
 * and the module's own attach tiles. A port's attach tile and the tile before it are kept for that
 * port's line: nothing else is built there and no other line's pusher stands next to them.
 */
public final class Reservation{
    public static final int FREE = -1, CORE = -2;
    public static final int TAKES_ITEMS = 1, PUSHES_ITEMS = 2, TAKES_LIQUID = 4, PUSHES_LIQUID = 8;

    public final int w, h;
    /** buildable, inside the base, off the danger mask */
    final boolean[] usable;
    final int[] owner;
    final byte[] flags;
    /** owner of the module whose ring this is, or FREE */
    final int[] ring;
    /** a port's attach tile or the tile before it: only that port's line may use it */
    final boolean[] kept;
    /** the tile past a port's lane: other lines avoid it */
    final boolean[] mouth;

    public Reservation(int w, int h, boolean[] usable){
        this.w = w;
        this.h = h;
        this.usable = usable;
        owner = new int[w * h];
        flags = new byte[w * h];
        ring = new int[w * h];
        kept = new boolean[w * h];
        mouth = new boolean[w * h];
        Arrays.fill(owner, FREE);
        Arrays.fill(ring, FREE);
    }

    public boolean in(int x, int y){ return x >= 0 && y >= 0 && x < w && y < h; }

    public boolean usable(int x, int y){ return in(x, y) && usable[x + y * w]; }

    /** usable and nothing planned here */
    public boolean free(int x, int y){ return usable(x, y) && owner[x + y * w] == FREE; }

    public int ownerAt(int x, int y){ return in(x, y) ? owner[x + y * w] : FREE; }

    public int flagsAt(int x, int y){ return in(x, y) ? flags[x + y * w] : 0; }

    public boolean ring(int x, int y){ return in(x, y) && ring[x + y * w] != FREE; }

    public int ringOwner(int x, int y){ return in(x, y) ? ring[x + y * w] : FREE; }

    /** item / liquid behaviour of a block kind (catalog kinds); a bridge pushes only without a link */
    public static int behaviour(String kind, boolean linked, boolean takesLiquid){
        int f = switch(kind){
            case "conveyor", "generator" -> TAKES_ITEMS;
            // an end bridge takes items only over a link (from its own line), and pushes them around
            case "bridge" -> linked ? TAKES_ITEMS : PUSHES_ITEMS;
            case "router", "sorter", "gate", "junction", "crafter" -> TAKES_ITEMS | PUSHES_ITEMS;
            case "drill" -> PUSHES_ITEMS;
            case "core" -> TAKES_ITEMS;
            case "conduit" -> TAKES_LIQUID;
            case "liquidbridge" -> linked ? TAKES_LIQUID : PUSHES_LIQUID;
            case "liquidrouter" -> TAKES_LIQUID | PUSHES_LIQUID;
            case "pump" -> PUSHES_LIQUID;
            default -> 0;
        };
        return f | (takesLiquid ? TAKES_LIQUID : 0);
    }

    /** marks a footprint {x0, y0, size} as the placement's; throws if a tile is taken or unusable (pumps may stand on deep water) */
    public void claim(Placement p, int lo, int size, int behaviour, boolean anyGround){
        for(int x = p.x + lo; x < p.x + lo + size; x++) for(int y = p.y + lo; y < p.y + lo + size; y++){
            if(!in(x, y) || owner[x + y * w] != FREE || !(anyGround || usable[x + y * w])) throw new IllegalStateException(p + " on a taken tile (" + x + "," + y + ")");
        }
        for(int x = p.x + lo; x < p.x + lo + size; x++) for(int y = p.y + lo; y < p.y + lo + size; y++){
            owner[x + y * w] = p.owner;
            flags[x + y * w] = (byte)behaviour;
        }
    }

    /** claims a placement's footprint with its block's behaviour (a pump may stand on deep water) */
    public void claim(Placement p, BlockInfo b){
        boolean linked = p.config instanceof int[];
        claim(p, b.lo(), b.size, behaviour(b.kind, linked, b.liquid != null), b.kind.equals("pump"));
    }

    /** undoes {@link #claim(Placement, BlockInfo)} */
    public void release(Placement p, BlockInfo b){
        for(int x = p.x + b.lo(); x <= p.x + b.hi(); x++) for(int y = p.y + b.lo(); y <= p.y + b.hi(); y++){
            if(in(x, y) && owner[x + y * w] == p.owner){
                owner[x + y * w] = FREE;
                flags[x + y * w] = 0;
            }
        }
    }

    /** keeps a tile for one port's line (see {@link #kept}) */
    public void keep(int x, int y){
        if(in(x, y)) kept[x + y * w] = true;
    }

    public boolean kept(int x, int y){ return in(x, y) && kept[x + y * w]; }

    public void markMouth(int x, int y){
        if(in(x, y)) mouth[x + y * w] = true;
    }

    public boolean mouth(int x, int y){ return in(x, y) && mouth[x + y * w]; }

    /** a kept tile next to (x, y) that is not in `own` (tile indices): a pusher here would feed another port's line */
    public boolean nearKept(int x, int y, Set<Integer> own){
        for(int d = 0; d < 4; d++){
            int nx = x + DX[d], ny = y + DY[d];
            if(kept(nx, ny) && !own.contains(nx + ny * w)) return true;
        }
        return false;
    }

    /** a single tile joins a module's ring (its marker and attach tiles: only its own lines may use them) */
    public void markRing(int x, int y, int module){
        if(in(x, y)) ring[x + y * w] = module;
    }

    /** the core's footprint {x0, y0, x1, y1} */
    public void claimCore(int[] core){
        for(int x = core[0]; x <= core[2]; x++) for(int y = core[1]; y <= core[3]; y++){
            if(!in(x, y)) continue;
            owner[x + y * w] = CORE;
            flags[x + y * w] = TAKES_ITEMS;
        }
    }

    /** the free tiles around a module's footprint become its ring */
    public void setRing(int x0, int y0, int x1, int y1, int module){
        for(int x = x0 - 1; x <= x1 + 1; x++) for(int y = y0 - 1; y <= y1 + 1; y++){
            boolean edge = x < x0 || x > x1 || y < y0 || y > y1;
            if(edge && in(x, y) && owner[x + y * w] == FREE) ring[x + y * w] = module;
        }
    }

    boolean foreign(int i, int net, int exempt){
        int o = owner[i];
        return o != FREE && o != CORE && o != net && o != exempt;
    }

    /** a block of network `net` taking items at (x, y) gets nothing from other networks' pushers */
    public boolean itemSafe(int x, int y, int net){ return safe(x, y, net, FREE, PUSHES_ITEMS); }

    /** a block of network `net` pushing items at (x, y) gives nothing to other networks (but `exempt`, its target) */
    public boolean pushSafe(int x, int y, int net, int exempt){ return safe(x, y, net, exempt, TAKES_ITEMS); }

    public boolean liquidSafe(int x, int y, int net){ return safe(x, y, net, FREE, PUSHES_LIQUID); }

    public boolean liquidPushSafe(int x, int y, int net, int exempt){ return safe(x, y, net, exempt, TAKES_LIQUID); }

    boolean safe(int x, int y, int net, int exempt, int flag){
        for(int d = 0; d < 4; d++){
            int nx = x + DX[d], ny = y + DY[d];
            if(!in(nx, ny)) continue;
            int i = nx + ny * w;
            if(foreign(i, net, exempt) && (flags[i] & flag) != 0) return false;
        }
        return true;
    }

    /** a footprint (min corner, size) touches the core from a side */
    public boolean touchesCore(int x0, int y0, int size){
        for(int i = -1; i <= size; i++) for(int j = -1; j <= size; j++){
            boolean edge = (i == -1 || i == size) != (j == -1 || j == size);
            if(edge && in(x0 + i, y0 + j) && owner[x0 + i + (y0 + j) * w] == CORE) return true;
        }
        return false;
    }

    /** a footprint (min corner, size) of a block of `net` pushing items: every tile around it safe */
    public boolean footprintPushSafe(int x0, int y0, int size, int net){
        for(int i = -1; i <= size; i++) for(int j = -1; j <= size; j++){
            boolean edge = (i == -1 || i == size) != (j == -1 || j == size);
            if(!edge || !in(x0 + i, y0 + j)) continue;
            int k = x0 + i + (y0 + j) * w;
            if(foreign(k, net, FREE) && (flags[k] & TAKES_ITEMS) != 0) return false;
        }
        return true;
    }
}
