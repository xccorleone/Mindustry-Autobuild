package autobuild.bootstrap.domain.layout;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.shared.*;
import autobuild.bootstrap.domain.survey.*;
import autobuild.plan.GridRouter.Goal;

import java.util.*;

import static autobuild.bootstrap.domain.shared.Dir.*;

/**
 * A pump site and its conduit line to a water inlet. The site is a straight "spine" of conduits on
 * buildable ground (shallow water included) with pumps on the water beside it (and behind its first
 * conduit): a conduit takes liquid from its back and sides, a pump pushes into every neighbour. Pumps
 * = ceil(need / one pump's rate). Candidate spines are ranked by spine length plus the line cost from
 * the spine's end to the inlet; the best few are tried with a real corridor, the first that routes
 * is kept.
 */
public final class WaterSiteSolver{
    static final int MAX_SPINE = 8, TRIES = 12;

    public static final class Result{
        public Zone zone;
        public Corridor corridor;
        public double rate;
        public String error;
    }

    private WaterSiteSolver(){}

    /**
     * @param feedX feedY dir the inlet: the line's last block feeds (feedX, feedY) moving dir
     * @param allowed tiles the line may use on the module's ring (its attach tile)
     */
    public static Result solve(Terrain t, Reservation res, SurveyService.Report survey, LayoutOptions o, CorridorRouter router, WaterBody body,
                               double need, int feedX, int feedY, int dir, int owner, int exempt, Set<Integer> allowed){
        Result out = new Result();
        Catalog cat = o.cat;
        double pumpRate = o.pumpRate();
        if(o.pump == null || o.conduit == null || pumpRate <= 0){
            out.error = "no pumps or conduits available";
            return out;
        }
        int n = (int)Math.ceil(need / pumpRate - 1e-9), w = res.w;
        Set<Integer> inBody = new HashSet<>();
        for(int i : body.tiles) inBody.add(i);
        int ax = feedX - DX[dir], ay = feedY - DY[dir];
        PathField toInlet = PathField.fromTiles(w, res.h, survey.usable, new int[]{ax + ay * w}, Math.max(1, o.liquidBridgeRange()));

        List<int[]> cands = new ArrayList<>(); // {x, y, dir, length, estimate}
        Set<Integer> near = new HashSet<>();
        for(int i : body.tiles){
            int x = i % w, y = i / w;
            for(int d = 0; d < 4; d++) near.add(x + DX[d] + (y + DY[d]) * w);
            near.add(i);
        }
        for(int i : near){
            int x = i % w, y = i / w;
            if(!spineOk(res, x, y, owner)) continue;
            for(int d = 0; d < 4; d++){
                Set<Integer> pumps = new LinkedHashSet<>();
                int len = 0;
                for(int k = 0; k < MAX_SPINE && pumps.size() < n; k++){
                    int sx = x + DX[d] * k, sy = y + DY[d] * k;
                    if(!spineOk(res, sx, sy, owner)) break;
                    len = k + 1;
                    int[] sides = k == 0 ? new int[]{(d + 1) & 3, (d + 3) & 3, opposite(d)} : new int[]{(d + 1) & 3, (d + 3) & 3};
                    for(int e : sides){
                        int px = sx + DX[e], py = sy + DY[e];
                        if(pumpOk(res, survey, inBody, px, py, owner)) pumps.add(px + py * w);
                    }
                }
                if(pumps.size() < n) continue;
                int ex = x + DX[d] * len, ey = y + DY[d] * len;
                int c = toInlet.at(ex, ey);
                if(c == PathField.UNREACHABLE) continue;
                cands.add(new int[]{x, y, d, len, len + c});
            }
        }
        cands.sort(Comparator.comparingInt(a -> a[4]));
        for(int k = 0; k < Math.min(TRIES, cands.size()); k++){
            int[] c = cands.get(k);
            Zone z = new Zone(Zone.Kind.WATER, body.liquid);
            Set<Integer> pumps = new LinkedHashSet<>();
            for(int s = 0; s < c[3]; s++){
                int sx = c[0] + DX[c[2]] * s, sy = c[1] + DY[c[2]] * s;
                z.placements.add(new Placement(o.conduit, sx, sy, c[2], null, owner));
                int[] sides = s == 0 ? new int[]{(c[2] + 1) & 3, (c[2] + 3) & 3, opposite(c[2])} : new int[]{(c[2] + 1) & 3, (c[2] + 3) & 3};
                for(int e : sides){
                    int px = sx + DX[e], py = sy + DY[e];
                    if(pumps.size() < n && pumpOk(res, survey, inBody, px, py, owner)) pumps.add(px + py * w);
                }
            }
            for(int pi : pumps) z.placements.add(new Placement(o.pump, pi % w, pi / w, 0, null, owner));
            for(Placement p : z.placements) res.claim(p, cat.block(p.block));
            int ex = c[0] + DX[c[2]] * c[3], ey = c[1] + DY[c[2]] * c[3];
            Goal goal = CorridorRouter.into(feedX, feedY, dir);
            Corridor line = router.route(body.liquid, true, owner, exempt, CorridorRouter.from(ex, ey, c[2]), goal, allowed);
            if(line == null){
                for(Placement p : z.placements) res.release(p, cat.block(p.block));
                continue;
            }
            z.rate = pumps.size() * pumpRate;
            out.zone = z;
            out.corridor = line;
            out.rate = z.rate;
            return out;
        }
        out.error = cands.isEmpty() ? "no shore with room for " + n + " pumps" : "no conduit line from the pumps";
        return out;
    }

    static boolean spineOk(Reservation res, int x, int y, int owner){
        return res.free(x, y) && !res.ring(x, y) && !res.kept(x, y) && res.liquidSafe(x, y, owner);
    }

    /** a pump may stand on this water tile (deep water too: pumps float) */
    static boolean pumpOk(Reservation res, SurveyService.Report survey, Set<Integer> inBody, int x, int y, int owner){
        if(!res.in(x, y)) return false;
        int i = x + y * res.w;
        return inBody.contains(i) && survey.allowed[i] && res.ownerAt(x, y) == Reservation.FREE && !res.ring(x, y) && !res.kept(x, y)
            && !res.nearKept(x, y, Set.of()) && res.liquidPushSafe(x, y, owner, Reservation.FREE);
    }
}
