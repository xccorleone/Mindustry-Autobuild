package autobuild.bootstrap.domain.power;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.shared.*;
import autobuild.plan.PowerNetwork;

import java.util.*;

/**
 * Anti-corruption layer over {@link PowerNetwork}: power nodes linking the modules and plants into
 * one grid. What must be reached: a module with nodes of its own through one of them (its blocks are
 * linked inside), a module without through each block using or making power. The network is solved
 * in a window around those (plus a node's range), on tiles free in the {@link Reservation} and not
 * kept for a port's line.
 */
public final class PowerGridSolver{
    public static final class Result{
        public final List<Placement> nodes = new ArrayList<>();
        /** targets no node could reach, linked groups left (1 = one grid) */
        public int dropped, groups;
    }

    private PowerGridSolver(){}

    /**
     * @param targets per module (or plant) its blocks
     * @param hasNode per module: it has nodes of its own
     */
    public static Result solve(Reservation res, Catalog cat, String node, List<List<Placement>> targets, List<Boolean> hasNode, int owner){
        Result out = new Result();
        BlockInfo nb = cat.block(node);
        List<int[]> world = new ArrayList<>();
        for(int m = 0; m < targets.size(); m++){
            for(Placement p : targets.get(m)){
                BlockInfo b = cat.block(p.block);
                if(hasNode.get(m)){
                    if(b.kind.equals("node")){ world.add(new int[]{p.x + b.lo(), p.y + b.lo(), b.size}); break; }
                }else if(b.power > 0 || b.gen > 0) world.add(new int[]{p.x + b.lo(), p.y + b.lo(), b.size});
            }
        }
        if(world.isEmpty()) return out;
        int margin = (int)Math.ceil(nb.range) + 1;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for(int[] c : world){
            x0 = Math.min(x0, c[0]); y0 = Math.min(y0, c[1]);
            x1 = Math.max(x1, c[0] + c[2] - 1); y1 = Math.max(y1, c[1] + c[2] - 1);
        }
        int gx = Math.max(0, x0 - margin), gy = Math.max(0, y0 - margin);
        int W = Math.min(res.w - 1, x1 + margin) - gx + 1, H = Math.min(res.h - 1, y1 + margin) - gy + 1;
        Set<Integer> taken = new HashSet<>();
        PowerNetwork net = new PowerNetwork(W, H, nb.range, nb.links, new PowerNetwork.Host(){
            public boolean nodeFree(int u, int v){ return nodeOk(u, v) && !taken.contains(u * H + v); }

            public boolean nodeOk(int u, int v){
                int x = gx + u, y = gy + v;
                return res.free(x, y) && !res.kept(x, y);
            }

            public int size(int[] c){ return c[2]; }

            public void occupy(int u, int v){ taken.add(u * H + v); }

            public void drop(int[] c){}

            public Object save(){ return new HashSet<>(taken); }

            @SuppressWarnings("unchecked")
            public void restore(Object s){
                taken.clear();
                taken.addAll((Set<Integer>)s);
            }
        });
        List<int[]> local = new ArrayList<>();
        for(int[] c : world) local.add(new int[]{c[0] - gx, c[1] - gy, c[2]});
        net.place(local);
        out.dropped = net.dropped;
        out.groups = net.groups;
        for(int[] n : net.nodes){
            Placement p = new Placement(node, gx + n[0], gy + n[1], 0, null, owner);
            res.claim(p, nb);
            out.nodes.add(p);
        }
        return out;
    }
}
