package autobuild.bootstrap.domain.layout;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.layout.Stamp.StampPort;
import autobuild.bootstrap.domain.mining.*;
import autobuild.bootstrap.domain.module.ModuleSpec;
import autobuild.bootstrap.domain.power.PowerGridSolver;
import autobuild.bootstrap.domain.production.*;
import autobuild.bootstrap.domain.shared.*;
import autobuild.bootstrap.domain.survey.*;
import autobuild.plan.GridRouter.Goal;

import java.util.*;

import static autobuild.bootstrap.domain.shared.Dir.*;

/**
 * Lays the production plan out on the map, in this order:
 * <ol>
 * <li>modules, then power plants ({@link ModulePlacer});</li>
 * <li>a corridor from every loaded outlet to the core;</li>
 * <li>supply lines - first-product modules' ({@link ProductionPlan.Instance#first}: graphite), plants',
 *     other modules', then the core's, each group biggest first: a
 *     mining site per ore share ({@link MiningSiteSolver}; dropped when it holds too little for its
 *     way, {@link ProductionPlan#fetch}) with a corridor to the inlet (the core);
 *     into an inlet a second share's corridor joins the first one's line from a side, into the core
 *     it joins a line of the same item that stays below {@link #JOIN_LOAD} of a belt, else gets its own
 *     (the core has only so many sides). A water line gets a pump
 *     site ({@link WaterSiteSolver}). A module's lines are laid out together; when a share of one
 *     finds no way (a neighbouring inlet's line took the way to its lane), the module's lines are
 *     laid out again with the short ones first, and the better of the two stays;</li>
 * <li>power nodes for everything using or making power ({@link PowerGridSolver});</li>
 * <li>the {@link Forecast} of what this layout makes.</li>
 * </ol>
 * Lines are laid out one at a time (not a global optimum): every tile one takes is gone for the
 * next. What can't be built is skipped and written into {@link BasePlan#notes}.
 */
public final class BasePlanner{
    /** a core line's later source joins a trunk only while it carries at most this share of one belt */
    static final double JOIN_LOAD = 0.9;

    private BasePlanner(){}

    /** a port's lane as tile indices: only its own line may use them */
    static Set<Integer> laneTiles(StampPort p, boolean inlet, int w){
        Set<Integer> out = new HashSet<>();
        for(int[] t : p.lane(inlet, 0, 0)) out.add(t[0] + t[1] * w);
        return out;
    }

    public static BasePlan plan(Terrain t, SurveyService.Report survey, ProductionPlan prod, LayoutOptions o){
        Catalog cat = o.cat;
        int w = t.width(), h = t.height();
        Reservation res = new Reservation(w, h, survey.usable);
        int[] core = t.core();
        res.claimCore(core);
        BasePlan plan = new BasePlan(res, prod.instances.size(), prod.lines.size());
        CorridorRouter router = new CorridorRouter(res, t, cat, o);

        // 1. modules and plants
        ModulePlacer placer = new ModulePlacer(t, res, survey, o);
        for(int m = 0; m < prod.instances.size(); m++){
            ProductionPlan.Instance in = prod.instances.get(m);
            SupplyLine[] lines = new SupplyLine[in.spec.inlets.size()];
            for(SupplyLine l : prod.lines) if(l.instance == m) lines[l.inlet] = l;
            Stamp s = placer.place(in.spec, m, lines);
            plan.outletRouted[m] = new boolean[in.spec.outlets.size()];
            if(s == null){
                plan.notes.add("no room for " + in.spec.name);
                continue;
            }
            plan.stamps[m] = s;
            Zone z = new Zone(in.power ? Zone.Kind.POWER : Zone.Kind.INDUSTRIAL, in.spec.name);
            z.stamp = s;
            z.instance = m;
            z.placements.addAll(s.blocks);
            plan.zones.add(z);
        }

        // 2. products to the core (short, and what the base is for: before the supply lines take the ground)
        for(int m = 0; m < prod.instances.size(); m++){
            Stamp s = plan.stamps[m];
            if(s == null) continue;
            for(int j = 0; j < s.outlets.size(); j++){
                StampPort p = s.outlets.get(j);
                if(p.port.rate <= 0) continue;
                Corridor c = router.route(String.join("+", p.port.items), false, BasePlan.outletOwner(m, j), BasePlan.MODULE + m,
                    CorridorRouter.from(p.x, p.y, p.dir), CorridorRouter.rect(core[0], core[1], core[2], core[3]),
                    laneTiles(p, false, w));
                if(c == null){
                    plan.notes.add(s.spec.name + ": no line from its outlet to the core");
                    continue;
                }
                plan.corridors.add(c);
                plan.outletRouted[m][j] = true;
            }
        }

        // 3. supply lines, a module's together (its inlets may need another order: see Lines.module)
        List<Integer> order = new ArrayList<>();
        for(int i = 0; i < prod.lines.size(); i++) order.add(i);
        double[] biggest = new double[prod.instances.size()];
        for(SupplyLine l : prod.lines) if(!l.toCore()) biggest[l.instance] = Math.max(biggest[l.instance], l.target);
        order.sort(Comparator.comparingInt((Integer i) -> {
            SupplyLine l = prod.lines.get(i);
            ProductionPlan.Instance in = l.toCore() ? null : prod.instances.get(l.instance);
            return in == null ? 3 : in.first ? 0 : in.power ? 1 : 2;
        }).thenComparingDouble(i -> prod.lines.get(i).toCore() ? 0 : -biggest[prod.lines.get(i).instance])
            .thenComparingInt(i -> prod.lines.get(i).instance)
            .thenComparingDouble(i -> -prod.lines.get(i).target));
        Lines lines = new Lines(t, survey, prod, o, plan, router, core);
        for(int k = 0; k < order.size(); ){
            int i = order.get(k), m = prod.lines.get(i).instance, e = k + 1;
            if(m >= 0) while(e < order.size() && prod.lines.get(order.get(e)).instance == m) e++;
            List<Integer> group = order.subList(k, e);
            if(m >= 0) lines.module(group);
            else lines.route(i);
            k = e;
        }

        // 4. power
        List<List<Placement>> targets = new ArrayList<>();
        List<Boolean> hasNode = new ArrayList<>();
        for(int m = 0; m < prod.instances.size(); m++){
            ModuleSpec spec = prod.instances.get(m).spec;
            if(plan.stamps[m] == null || spec.powerUse <= 0 && spec.powerGen <= 0) continue;
            targets.add(plan.stamps[m].blocks);
            hasNode.add(spec.hasNode);
        }
        boolean onlySelfPowered = targets.size() == 1 && hasNode.get(0);
        if(!targets.isEmpty() && !onlySelfPowered && o.node != null){
            PowerGridSolver.Result r = PowerGridSolver.solve(res, cat, o.node, targets, hasNode, BasePlan.NODES);
            plan.nodes.addAll(r.nodes);
            if(r.dropped > 0) plan.notes.add(r.dropped + " power targets no node reaches");
            if(r.groups > 1) plan.notes.add("power grid in " + r.groups + " parts");
        }

        plan.forecast = Forecast.compute(plan, prod, o);
        return plan;
    }

    /** lays out supply lines; a module's inlets are laid out again in another order when one fails */
    static final class Lines{
        final Terrain t;
        final SurveyService.Report survey;
        final ProductionPlan prod;
        final LayoutOptions o;
        final Catalog cat;
        final BasePlan plan;
        final Reservation res;
        final CorridorRouter router;
        final int[] core;
        final int w;
        /** per line: shares that got no mining site or no corridor */
        final int[] failed;

        Lines(Terrain t, SurveyService.Report survey, ProductionPlan prod, LayoutOptions o, BasePlan plan, CorridorRouter router, int[] core){
            this.t = t;
            this.survey = survey;
            this.prod = prod;
            this.o = o;
            this.cat = o.cat;
            this.plan = plan;
            this.res = plan.res;
            this.router = router;
            this.core = core;
            this.w = t.width();
            failed = new int[prod.lines.size()];
        }

        /**
         * A module's lines, in order. If a share of one finds no site or no way (a neighbouring inlet's
         * line took the way to its lane), they are laid out again with the short ones first; the better
         * layout stays - fewer lines with nothing, then more supplied.
         */
        void module(List<Integer> group){
            int zones = plan.zones.size(), corridors = plan.corridors.size(), notes = plan.notes.size(), lost = plan.failed.size();
            for(int i : group) route(i);
            List<Integer> shortFirst = new ArrayList<>();
            for(int i : group) if(failed[i] > 0) shortFirst.add(i);
            if(shortFirst.isEmpty() || shortFirst.size() == group.size()) return;
            double[] before = score(group);
            for(int i : group) if(!shortFirst.contains(i)) shortFirst.add(i);
            undo(group, zones, corridors, notes, lost);
            for(int i : shortFirst) route(i);
            double[] after = score(group);
            if(after[0] < before[0] || after[0] == before[0] && after[1] > before[1] + 1e-6) return;
            undo(group, zones, corridors, notes, lost);
            for(int i : group) route(i);
        }

        /** {lines getting nothing, supplied in all} */
        double[] score(List<Integer> group){
            double[] out = new double[2];
            for(int i : group){
                if(plan.lineSupply[i] <= 1e-9) out[0]++;
                out[1] += Math.min(plan.lineSupply[i], prod.lines.get(i).target);
            }
            return out;
        }

        /** takes back what was laid out since the plan had these many zones, corridors, notes and failed patches */
        void undo(List<Integer> group, int zones, int corridors, int notes, int lost){
            while(plan.zones.size() > zones){
                Zone z = plan.zones.remove(plan.zones.size() - 1);
                for(Placement p : z.placements) res.release(p, cat.block(p.block));
            }
            while(plan.corridors.size() > corridors){
                Corridor c = plan.corridors.remove(plan.corridors.size() - 1);
                for(Placement p : c.placements) res.release(p, cat.block(p.block));
            }
            while(plan.notes.size() > notes) plan.notes.remove(plan.notes.size() - 1);
            while(plan.failed.size() > lost) plan.failed.remove(plan.failed.size() - 1);
            for(int i : group){
                plan.lineSupply[i] = 0;
                failed[i] = 0;
            }
        }

        /** one line: a mining site per share with its corridor (a pump site for a liquid) */
        void route(int i){
            SupplyLine l = prod.lines.get(i);
            if(l.sources.isEmpty()) return;
            int destX, destY, exempt, dir = -1;
            Goal goal;
            Set<Integer> allowed = new HashSet<>();
            String name = l.item + (l.toCore() ? " to the core" : " to " + prod.instances.get(l.instance).spec.name);
            if(l.toCore()){
                destX = (core[0] + core[2]) / 2;
                destY = (core[1] + core[3]) / 2;
                goal = CorridorRouter.rect(core[0], core[1], core[2], core[3]);
                exempt = Reservation.CORE;
            }else{
                Stamp s = plan.stamps[l.instance];
                if(s == null) return;
                StampPort p = s.inlets.get(l.inlet);
                destX = p.x;
                destY = p.y;
                dir = p.dir;
                goal = CorridorRouter.into(p.x + DX[p.dir], p.y + DY[p.dir], p.dir);
                allowed.addAll(laneTiles(p, true, w));
                exempt = BasePlan.MODULE + l.instance;
            }
            if(l.liquid){
                if(dir < 0) return; // liquids only go to modules
                WaterSiteSolver.Result r = WaterSiteSolver.solve(t, res, survey, o, router, l.sources.get(0).water, l.target,
                    destX + DX[dir], destY + DY[dir], dir, i, exempt, allowed);
                if(r.zone == null){
                    plan.notes.add(name + ": " + r.error);
                    return;
                }
                r.zone.line = i;
                r.zone.corridor = r.corridor;
                plan.zones.add(r.zone);
                plan.corridors.add(r.corridor);
                plan.lineSupply[i] = r.rate;
                return;
            }
            // the line's corridors that reach the destination, with the zone that laid each and its load
            List<Corridor> trunks = new ArrayList<>();
            List<Integer> trunkZone = new ArrayList<>();
            List<Double> trunkLoad = new ArrayList<>();
            double belt = o.beltSpeed();
            for(SupplyLine.Share sh : l.sources){
                MiningSite site = MiningSiteSolver.solve(t, res, o, sh.patch, sh.rate, destX, destY, i, l.toCore());
                if(!site.ok()){
                    plan.notes.add(name + ": " + sh.patch + ": " + site.error);
                    plan.failed.add(sh.patch);
                    failed[i]++;
                    continue;
                }
                // the site holds less than the share (tiles taken): still worth its way?
                if(site.rate < prod.fetch * sh.patch.distance - 1e-9){
                    for(Placement p : site.placements) res.release(p, cat.block(p.block));
                    plan.notes.add(name + ": " + sh.patch + ": " + String.format("%.2f/s", site.rate) + " is too little for the way");
                    plan.failed.add(sh.patch);
                    continue;
                }
                // into a module inlet a later source joins the first one's line. The core takes from every
                // side, but it has only so many: a later source joins a trunk that stays well below one belt
                // (joining a nearly full belt loses items), else gets its own line to the core
                int trunk = -1;
                if(!trunks.isEmpty() && !l.toCore()) trunk = 0;
                for(int k = 0; k < trunks.size() && l.toCore(); k++){
                    if(trunkLoad.get(k) + site.rate <= JOIN_LOAD * belt && (trunk < 0 || trunkLoad.get(k) < trunkLoad.get(trunk))) trunk = k;
                }
                Corridor c = trunk >= 0 ? router.route(l.item, false, i, i, CorridorRouter.from(site.sinkX, site.sinkY, site.sinkDir), CorridorRouter.join(trunks.get(trunk)), Set.of()) : null;
                if(c == null && (l.toCore() || trunks.isEmpty())){
                    trunk = -1;
                    c = router.route(l.item, false, i, exempt, CorridorRouter.from(site.sinkX, site.sinkY, site.sinkDir), goal, allowed);
                }
                if(c == null){
                    for(Placement p : site.placements) res.release(p, cat.block(p.block));
                    plan.notes.add(name + ": no line from " + sh.patch);
                    plan.failed.add(sh.patch);
                    failed[i]++;
                    continue;
                }
                Zone z = new Zone(Zone.Kind.MINING, l.item);
                if(trunk >= 0){
                    z.joins = trunkZone.get(trunk);
                    trunkLoad.set(trunk, trunkLoad.get(trunk) + site.rate);
                }else{
                    trunks.add(c);
                    trunkZone.add(plan.zones.size());
                    trunkLoad.add(site.rate);
                }
                z.line = i;
                z.corridor = c;
                z.rate = site.rate;
                z.placements.addAll(site.placements);
                plan.zones.add(z);
                plan.corridors.add(c);
                plan.lineSupply[i] += site.rate;
            }
        }
    }
}
