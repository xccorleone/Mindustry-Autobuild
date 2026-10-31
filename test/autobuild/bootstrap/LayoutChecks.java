package autobuild.bootstrap;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.layout.*;
import autobuild.bootstrap.domain.shared.*;
import autobuild.bootstrap.domain.survey.SurveyService;

import java.util.*;

import static autobuild.bootstrap.Check.*;
import static autobuild.bootstrap.domain.shared.Dir.*;

/** Invariants every base layout must keep, checked on the placements alone. */
final class LayoutChecks{
    static void all(String what, BasePlan plan, SurveyService.Report survey, Catalog cat, int[] core){
        List<Placement> all = plan.ordered(cat);
        // 1. one block per tile, on usable ground inside the base and off the danger mask (pumps: any water)
        Reservation r = new Reservation(plan.res.w, plan.res.h, survey.usable);
        r.claimCore(core);
        int bad = 0;
        for(Placement p : all){
            BlockInfo b = cat.block(p.block);
            try{
                r.claim(p, b);
            }catch(IllegalStateException e){
                if(bad++ == 0) yes(what + ": " + e.getMessage(), false);
            }
            for(int x = p.x + b.lo(); x <= p.x + b.hi(); x++) for(int y = p.y + b.lo(); y <= p.y + b.hi(); y++){
                if(r.in(x, y) && !survey.allowed[x + y * r.w] && bad++ == 0) yes(what + ": " + p + " outside the base / in danger", false);
            }
        }
        eq(what + " overlaps / bad ground", 0, bad);

        // 2. every corridor connects block to block and ends in a block that takes from it
        for(Corridor c : plan.corridors){
            for(int i = 0; i < c.placements.size(); i++){
                Placement p = c.placements.get(i);
                boolean last = i == c.placements.size() - 1;
                int[] out = out(p, cat);
                if(out == null){
                    // an end bridge: next block (or the target) is a neighbour
                    continue;
                }
                if(!last){
                    Placement n = c.placements.get(i + 1);
                    if(n.x != out[0] || n.y != out[1]){ yes(what + ": " + c + " breaks after " + p, false); break; }
                }else{
                    int on = r.ownerAt(out[0], out[1]);
                    yes(what + ": " + c + " ends in something (" + out[0] + "," + out[1] + ")", on != Reservation.FREE);
                }
            }
        }

        // 3. no network pushes into another's blocks (core takes anything; a belt refuses from its front)
        Map<Long, Placement> at = new HashMap<>();
        for(Placement p : all){
            BlockInfo b = cat.block(p.block);
            for(int x = p.x + b.lo(); x <= p.x + b.hi(); x++) for(int y = p.y + b.lo(); y <= p.y + b.hi(); y++) at.put(key(x, y), p);
        }
        int mixed = 0;
        for(Placement p : all){
            BlockInfo b = cat.block(p.block);
            int f = Reservation.behaviour(b.kind, p.config instanceof int[], b.liquid != null);
            for(int[] side : sides(p, b)){
                Placement q = at.get(key(side[0], side[1]));
                if(q == null || q.owner == p.owner) continue;
                BlockInfo qb = cat.block(q.block);
                int g = Reservation.behaviour(qb.kind, q.config instanceof int[], qb.liquid != null);
                boolean items = (f & Reservation.PUSHES_ITEMS) != 0 && (g & Reservation.TAKES_ITEMS) != 0;
                boolean liquid = (f & Reservation.PUSHES_LIQUID) != 0 && (g & Reservation.TAKES_LIQUID) != 0;
                if(!items && !liquid) continue;
                // a belt (conduit) pointing into the pusher refuses what comes from its front
                if((qb.kind.equals("conveyor") || qb.kind.equals("conduit")) && pointsInto(q, p, b)) continue;
                // a line's end feeding its module or merging: the pusher is the line, the taker its target
                if(servedBy(q.owner, p.owner)) continue;
                if(mixed++ < 3) yes(what + ": " + p + " (net " + p.owner + ") pushes into " + q + " (net " + q.owner + ")", false);
            }
        }
        eq(what + " mixing", 0, mixed);

        // 4. every block using or making power is reached, and the nodes are one grid
        List<int[]> nodes = new ArrayList<>();
        for(Placement p : plan.nodes) nodes.add(new int[]{p.x, p.y});
        if(!nodes.isEmpty()){
            BlockInfo nb = cat.block(plan.nodes.get(0).block);
            int uncovered = 0;
            for(Zone z : plan.zones){
                if(z.stamp == null || z.stamp.spec.powerUse <= 0 && z.stamp.spec.powerGen <= 0) continue;
                boolean own = z.stamp.spec.hasNode, anyOwn = false;
                for(Placement p : z.placements){
                    BlockInfo b = cat.block(p.block);
                    if(own ? !b.kind.equals("node") : b.power <= 0 && b.gen <= 0) continue;
                    boolean hit = false;
                    for(int[] n : nodes) hit |= reaches(nb.range, n[0], n[1], p.x + b.lo(), p.y + b.lo(), b.size);
                    if(own) anyOwn |= hit;
                    else if(!hit) uncovered++;
                }
                if(own && !anyOwn) uncovered++;
            }
            eq(what + " power targets not reached", 0, uncovered);
            int[] comp = new int[nodes.size()];
            Arrays.fill(comp, -1);
            Deque<Integer> q = new ArrayDeque<>(List.of(0));
            comp[0] = 0;
            while(!q.isEmpty()){
                int a = q.poll();
                for(int b = 0; b < nodes.size(); b++){
                    if(comp[b] < 0 && reaches(nb.range, nodes.get(a)[0], nodes.get(a)[1], nodes.get(b)[0], nodes.get(b)[1], 1)){ comp[b] = 0; q.add(b); }
                }
            }
            yes(what + " nodes form one grid", Arrays.stream(comp).allMatch(c -> c == 0));
        }

        // 5. the forecast never exceeds a belt per line
        for(double v : plan.forecast.line) yes(what + " line within a belt or liquid", v >= 0);
    }

    /** a line (owner < MODULE) feeding its module / the core / another line of the same network */
    static boolean servedBy(int taker, int pusher){
        return pusher < BasePlan.MODULE && taker >= BasePlan.MODULE && taker < BasePlan.OUTLET;
    }

    static long key(int x, int y){ return ((long)x << 32) | (y & 0xffffffffL); }

    /** the tile a block passes items to, or null when it pushes around (end bridge) */
    static int[] out(Placement p, Catalog cat){
        String k = cat.block(p.block).kind;
        if(k.equals("conveyor") || k.equals("conduit")) return new int[]{p.x + DX[p.rot], p.y + DY[p.rot]};
        if(p.config instanceof int[] d) return new int[]{p.x + d[0], p.y + d[1]};
        return null;
    }

    static List<int[]> sides(Placement p, BlockInfo b){
        List<int[]> out = new ArrayList<>();
        int x0 = p.x + b.lo(), y0 = p.y + b.lo(), s = b.size;
        for(int i = 0; i < s; i++){
            out.add(new int[]{x0 + i, y0 - 1});
            out.add(new int[]{x0 + i, y0 + s});
            out.add(new int[]{x0 - 1, y0 + i});
            out.add(new int[]{x0 + s, y0 + i});
        }
        return out;
    }

    static boolean pointsInto(Placement belt, Placement p, BlockInfo b){
        int fx = belt.x + DX[belt.rot], fy = belt.y + DY[belt.rot];
        return fx >= p.x + b.lo() && fx <= p.x + b.hi() && fy >= p.y + b.lo() && fy <= p.y + b.hi();
    }

    static boolean reaches(float range, int nu, int nv, int x, int y, int size){
        float r = range * 0.95f;
        float x0 = x - 0.5f, x1 = x + size - 0.5f, y0 = y - 0.5f, y1 = y + size - 0.5f;
        float dx = Math.max(0, Math.max(x0 - nu, nu - x1)), dy = Math.max(0, Math.max(y0 - nv, nv - y1));
        return dx * dx + dy * dy <= r * r;
    }
}
