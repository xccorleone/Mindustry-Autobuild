package autobuild.bootstrap.domain.layout;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.layout.Stamp.StampPort;
import autobuild.bootstrap.domain.module.ModuleSpec;
import autobuild.bootstrap.domain.production.*;
import autobuild.bootstrap.domain.shared.*;
import autobuild.bootstrap.domain.survey.*;

import java.util.*;

/**
 * Where the modules and power plants stand: one instance after the other (modules, then plants),
 * every turn and every position on the map. A position is possible when the footprint is free and
 * on nobody's ring, the ring around it holds nothing planned (and no other module's port lane), and
 * every port's lane (attach tile and the tile straight out) is free and on no other ring or lane.
 * Among those the cheapest wins:
 * <pre>
 *   sum over inlets  (rate x line cost from its source patch / water body to the attach tile)
 * + sum over outlets (rate x line cost from the attach tile to the core)
 * + ORE_COST x valuable ore tiles under the footprint
 * + COMPACT x distance to the modules placed so far (short power links, one industrial area)
 * </pre>
 * Line costs come from {@link PathField}s over usable ground (bridges included), so the score is what
 * the corridors will roughly cost; an inlet whose source no line can reach rules the position out.
 */
public final class ModulePlacer{
    static final double ORE_COST = 3, COMPACT = 2;

    final Terrain terrain;
    final Reservation res;
    final SurveyService.Report survey;
    final Catalog cat;
    final LayoutOptions o;
    final Map<Object, PathField> fields = new HashMap<>();
    /** reserved attach tiles of placed modules */
    final boolean[] attach;
    final List<int[]> centres = new ArrayList<>();

    public ModulePlacer(Terrain terrain, Reservation res, SurveyService.Report survey, LayoutOptions o){
        this.terrain = terrain;
        this.res = res;
        this.survey = survey;
        this.cat = o.cat;
        this.o = o;
        attach = new boolean[res.w * res.h];
    }

    /** where a line's main source is, as a cost field (null: no source) */
    PathField sourceField(SupplyLine l){
        if(l.sources.isEmpty()) return null;
        SupplyLine.Share s = l.sources.get(0);
        Object key = s.patch != null ? s.patch : s.water;
        return fields.computeIfAbsent(key, k -> {
            int[] seeds = s.patch != null ? s.patch.tiles : s.water.tiles;
            return PathField.fromTiles(res.w, res.h, survey.usable, seeds, Math.max(o.bridgeRange(), 1));
        });
    }

    /**
     * Places instance m (claims its blocks, sets its ring); null when there is no room.
     * @param lines the plan's lines feeding this instance, by inlet index (null where none)
     */
    public Stamp place(ModuleSpec spec, int m, SupplyLine[] lines){
        int w = res.w, h = res.h, owner = BasePlan.MODULE + m;
        // prefix sums: footprint blocked (taken or ring), ring blocked (anything planned, attach tiles), valuable ore
        int[] foot = new int[(w + 1) * (h + 1)], exp = new int[(w + 1) * (h + 1)], ore = new int[(w + 1) * (h + 1)];
        for(int x = 0; x < w; x++) for(int y = 0; y < h; y++){
            String it = terrain.ore(x, y);
            boolean valuable = it != null && !cat.item(it).lowPriority;
            put(foot, w, x, y, !res.free(x, y) || res.ring(x, y) ? 1 : 0);
            put(exp, w, x, y, res.ownerAt(x, y) != Reservation.FREE || attach[x + y * w] ? 1 : 0);
            put(ore, w, x, y, valuable ? 1 : 0);
        }
        double cx = 0, cy = 0;
        for(int[] c : centres){ cx += c[0]; cy += c[1]; }
        if(!centres.isEmpty()){ cx /= centres.size(); cy /= centres.size(); }

        double best = Double.MAX_VALUE;
        int bestR = -1, bestX = 0, bestY = 0;
        for(int r = 0; r < 4; r++){
            Stamp s = Stamp.of(spec, r, 0, 0, cat, o.swap, owner);
            for(int ox = 1; ox + s.w < w; ox++) for(int oy = 1; oy + s.h < h; oy++){
                if(sum(foot, w, ox, oy, ox + s.w - 1, oy + s.h - 1) > 0) continue;
                if(sum(exp, w, ox - 1, oy - 1, ox + s.w, oy + s.h) > 0) continue;
                double score = 0;
                boolean ok = true;
                for(int i = 0; i < s.inlets.size() && ok; i++){
                    StampPort p = s.inlets.get(i);
                    int ax = p.x + ox, ay = p.y + oy;
                    if(!portFree(p, true, ox, oy)){ ok = false; break; }
                    SupplyLine l = lines[i];
                    PathField f = l == null ? null : sourceField(l);
                    if(f == null) continue;
                    int d = f.at(ax, ay);
                    if(d == PathField.UNREACHABLE){ ok = false; break; }
                    score += l.target * d;
                }
                for(int i = 0; i < s.outlets.size() && ok; i++){
                    StampPort p = s.outlets.get(i);
                    int ax = p.x + ox, ay = p.y + oy;
                    if(!portFree(p, false, ox, oy)){ ok = false; break; }
                    if(p.port.rate <= 0) continue;
                    int d = survey.field.at(ax, ay);
                    if(d == PathField.UNREACHABLE){ ok = false; break; }
                    score += p.port.rate * d;
                }
                if(!ok) continue;
                score += ORE_COST * sum(ore, w, ox, oy, ox + s.w - 1, oy + s.h - 1);
                if(!centres.isEmpty()) score += COMPACT * (Math.abs(ox + s.w / 2.0 - cx) + Math.abs(oy + s.h / 2.0 - cy));
                if(score < best){
                    best = score;
                    bestR = r;
                    bestX = ox;
                    bestY = oy;
                }
            }
        }
        if(bestR < 0) return null;
        Stamp s = Stamp.of(spec, bestR, bestX, bestY, cat, o.swap, owner);
        for(Placement p : s.blocks) res.claim(p, cat.block(p.block));
        res.setRing(s.x0, s.y0, s.x1(), s.y1(), owner);
        for(int[] mk : s.markers) res.markRing(mk[0], mk[1], owner);
        for(StampPort p : s.inlets) keep(p, true);
        for(StampPort p : s.outlets) keep(p, false);
        centres.add(new int[]{s.x0 + s.w / 2, s.y0 + s.h / 2});
        return s;
    }

    /**
     * The port's lane ({@link StampPort#lane}) is free, on no ring and no other port's lane. A marker's
     * attach tile is inside the module (its own) and is not checked.
     */
    boolean portFree(StampPort p, boolean inlet, int ox, int oy){
        int[][] lane = p.lane(inlet, ox, oy);
        for(int k = p.port.marker ? 1 : 0; k < lane.length; k++){
            int x = lane[k][0], y = lane[k][1];
            if(!res.free(x, y) || res.ring(x, y) || attach[x + y * res.w]) return false;
        }
        return true;
    }

    /** later modules keep their footprints and rings off a port's lane and mouth; lines other than its own off the lane */
    void keep(StampPort p, boolean inlet){
        for(int[] t : p.lane(inlet, 0, 0)){
            if(!res.in(t[0], t[1])) continue;
            attach[t[0] + t[1] * res.w] = true;
            res.keep(t[0], t[1]);
        }
        for(int[] m : p.mouth(inlet, 0, 0)){
            if(!res.free(m[0], m[1])) break;
            attach[m[0] + m[1] * res.w] = true;
            res.markMouth(m[0], m[1]);
        }
    }

    static void put(int[] ps, int w, int x, int y, int v){
        int W = w + 1;
        ps[(x + 1) + (y + 1) * W] = v + ps[x + (y + 1) * W] + ps[(x + 1) + y * W] - ps[x + y * W];
    }

    /** count over [x0..x1] x [y0..y1] (clipped to the map) */
    static int sum(int[] ps, int w, int x0, int y0, int x1, int y1){
        int W = w + 1, h = ps.length / W - 1;
        x0 = Math.max(0, x0); y0 = Math.max(0, y0); x1 = Math.min(w - 1, x1); y1 = Math.min(h - 1, y1);
        if(x0 > x1 || y0 > y1) return 0;
        return ps[(x1 + 1) + (y1 + 1) * W] - ps[x0 + (y1 + 1) * W] - ps[(x1 + 1) + y0 * W] + ps[x0 + y0 * W];
    }
}
