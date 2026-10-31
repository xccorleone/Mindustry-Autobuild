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
 * <li>supply lines - plants' first, then modules', then the core's, each group biggest first: a
 *     mining site per ore share ({@link MiningSiteSolver}) with a corridor to the inlet (the core);
 *     into an inlet a second share's corridor joins the first one's line from a side, into the core
 *     every share has its own. A water line gets a pump
 *     site ({@link WaterSiteSolver});</li>
 * <li>power nodes for everything using or making power ({@link PowerGridSolver});</li>
 * <li>the {@link Forecast} of what this layout makes.</li>
 * </ol>
 * Lines are laid out one at a time (not a global optimum): every tile one takes is gone for the
 * next. What can't be built is skipped and written into {@link BasePlan#notes}.
 */
public final class BasePlanner{
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

        // 3. supply lines
        List<Integer> order = new ArrayList<>();
        for(int i = 0; i < prod.lines.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt((Integer i) -> {
            SupplyLine l = prod.lines.get(i);
            return l.toCore() ? 2 : prod.instances.get(l.instance).power ? 0 : 1;
        }).thenComparingDouble(i -> -prod.lines.get(i).target));
        for(int i : order){
            SupplyLine l = prod.lines.get(i);
            if(l.sources.isEmpty()) continue;
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
                if(s == null) continue;
                StampPort p = s.inlets.get(l.inlet);
                destX = p.x;
                destY = p.y;
                dir = p.dir;
                goal = CorridorRouter.into(p.x + DX[p.dir], p.y + DY[p.dir], p.dir);
                allowed.addAll(laneTiles(p, true, w));
                exempt = BasePlan.MODULE + l.instance;
            }
            if(l.liquid){
                if(dir < 0) continue; // liquids only go to modules
                WaterSiteSolver.Result r = WaterSiteSolver.solve(t, res, survey, o, router, l.sources.get(0).water, l.target,
                    destX + DX[dir], destY + DY[dir], dir, i, exempt, allowed);
                if(r.zone == null){
                    plan.notes.add(name + ": " + r.error);
                    continue;
                }
                r.zone.line = i;
                r.zone.corridor = r.corridor;
                plan.zones.add(r.zone);
                plan.corridors.add(r.corridor);
                plan.lineSupply[i] = r.rate;
                continue;
            }
            Corridor main = null;
            for(SupplyLine.Share sh : l.sources){
                MiningSite site = MiningSiteSolver.solve(t, res, o, sh.patch, sh.rate, destX, destY, i, l.toCore());
                if(!site.ok()){
                    plan.notes.add(name + ": " + sh.patch + ": " + site.error);
                    continue;
                }
                // into a module inlet a second source joins the first one's line; the core takes from every
                // side, so each source gets its own line there (joining a nearly full belt loses items)
                boolean join = main != null && !l.toCore();
                Corridor c = router.route(l.item, false, i, join ? i : exempt, CorridorRouter.from(site.sinkX, site.sinkY, site.sinkDir),
                    join ? CorridorRouter.join(main) : goal, join ? Set.of() : allowed);
                if(c == null){
                    for(Placement p : site.placements) res.release(p, cat.block(p.block));
                    plan.notes.add(name + ": no line from " + sh.patch);
                    continue;
                }
                if(main == null) main = c;
                Zone z = new Zone(Zone.Kind.MINING, l.item);
                z.line = i;
                z.corridor = c;
                z.rate = site.rate;
                z.placements.addAll(site.placements);
                plan.zones.add(z);
                plan.corridors.add(c);
                plan.lineSupply[i] += site.rate;
            }
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
}
