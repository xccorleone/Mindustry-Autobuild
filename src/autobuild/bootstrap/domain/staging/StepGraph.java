package autobuild.bootstrap.domain.staging;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.layout.*;
import autobuild.bootstrap.domain.module.ModuleSpec;
import autobuild.bootstrap.domain.production.*;
import autobuild.bootstrap.domain.shared.Placement;

import java.util.*;

/**
 * A base plan cut into {@link Step}s with their dependencies:
 * <ul>
 * <li>modules and plants using or making power need the power nodes (when the plan has any: nodes
 *     come first, a block built later links itself to the nodes in range);</li>
 * <li>a mining or pump site feeding a module needs that module;</li>
 * <li>a site whose line joins another site's line needs that site;</li>
 * <li>an upgrade needs its step built with stand-ins.</li>
 * </ul>
 * Every block of the plan belongs to exactly one step that is not an upgrade.
 */
public final class StepGraph{
    public final BasePlan plan;
    public final ProductionPlan prod;
    public final LayoutOptions options;
    public final List<Step> steps = new ArrayList<>();
    /** per instance: its MODULE / PLANT step, or -1 (no room for it) */
    public final int[] moduleStep;
    /** per zone: its step */
    public final int[] zoneStep;
    /** per line: the step of its first source (an inlet line's later sources join that site's line), or -1 */
    public final int[] mainStep;
    public int nodeStep = -1;

    StepGraph(BasePlan plan, ProductionPlan prod, LayoutOptions o){
        this.plan = plan;
        this.prod = prod;
        options = o;
        moduleStep = new int[prod.instances.size()];
        zoneStep = new int[plan.zones.size()];
        mainStep = new int[prod.lines.size()];
        Arrays.fill(moduleStep, -1);
        Arrays.fill(zoneStep, -1);
        Arrays.fill(mainStep, -1);
    }

    /**
     * @param downgrade planned block -> slower stand-in of the same size (titanium conveyor -> conveyor,
     *                  pneumatic drill -> mechanical drill); a drill stand-in only where it mines the item
     */
    public static StepGraph of(BasePlan plan, ProductionPlan prod, LayoutOptions o, Map<String, String> downgrade){
        Catalog cat = o.cat;
        StepGraph g = new StepGraph(plan, prod, o);
        if(!plan.nodes.isEmpty()){
            Step s = g.add(Step.Kind.NODES, "nodes");
            s.placements.addAll(plan.nodes);
            s.network = Integer.MIN_VALUE;
            g.nodeStep = s.id;
        }
        // modules and plants with their outlet lines
        for(int z = 0; z < plan.zones.size(); z++){
            Zone zone = plan.zones.get(z);
            if(zone.stamp == null) continue;
            int m = zone.instance;
            ModuleSpec spec = prod.instances.get(m).spec;
            Step s = g.add(prod.instances.get(m).power ? Step.Kind.PLANT : Step.Kind.MODULE, spec.name);
            s.instance = m;
            s.network = m;
            s.zones.add(z);
            s.placements.addAll(zone.placements);
            for(Corridor c : plan.corridors){
                if(c.owner >= BasePlan.outletOwner(m, 0) && c.owner < BasePlan.outletOwner(m + 1, 0)) s.placements.addAll(c.placements);
            }
            if(g.nodeStep >= 0 && (spec.powerUse > 0 || spec.powerGen > 0)) s.deps.add(g.nodeStep);
            g.moduleStep[m] = s.id;
            g.zoneStep[z] = s.id;
        }
        // mining and pump sites with their lines
        for(int z = 0; z < plan.zones.size(); z++){
            Zone zone = plan.zones.get(z);
            if(zone.stamp != null || zone.line < 0) continue;
            SupplyLine l = prod.lines.get(zone.line);
            Step s = g.add(zone.kind == Zone.Kind.WATER ? Step.Kind.WATER : Step.Kind.MINE, l.item);
            s.line = zone.line;
            s.instance = l.instance;
            s.network = l.toCore() ? -1 - zone.line : l.instance;
            s.to = l.toCore() ? null : prod.instances.get(l.instance).spec.name;
            s.zones.add(z);
            s.placements.addAll(zone.placements);
            if(zone.corridor != null) s.placements.addAll(zone.corridor.placements);
            if(!l.toCore()){
                if(g.moduleStep[l.instance] >= 0) s.deps.add(g.moduleStep[l.instance]);
                if(g.mainStep[zone.line] < 0) g.mainStep[zone.line] = s.id;
            }
            // its line joins another site's
            if(zone.joins >= 0) s.deps.add(g.zoneStep[zone.joins]);
            g.zoneStep[z] = s.id;
        }
        // in each step logistics before drills and pumps (they'd fill up with nowhere to go)
        for(Step s : g.steps) s.placements.sort(Comparator.comparingInt(p -> {
            String k = cat.block(p.block).kind;
            return k.equals("drill") || k.equals("pump") ? 1 : 0;
        }));
        // stand-ins and upgrades
        int planned = g.steps.size();
        for(int i = 0; i < planned; i++){
            Step s = g.steps.get(i);
            String item = s.kind == Step.Kind.MINE ? s.what : null;
            double full = 0, slow = 0;
            for(Placement p : s.placements){
                String b = downgrade.get(p.block);
                if(b == null || cat.block(b) == null) continue;
                BlockInfo from = cat.block(p.block), to = cat.block(b);
                if(from.kind.equals("drill")){
                    // the stand-in must mine this site's item
                    Float tf = from.drillTime.get(item), tt = to.drillTime.get(item);
                    if(item == null || tf == null || tt == null || cat.item(item) == null || to.tier < cat.item(item).hardness) continue;
                    full += 1 / tf;
                    slow += 1 / tt;
                }
                s.standIns.put(p.block, b);
            }
            if(s.standIns.isEmpty()) continue;
            if(full > 0) s.slowRate = slow / full;
            s.slowBelt = o.beltSpeed();
            for(Map.Entry<String, String> e : s.standIns.entrySet()){
                BlockInfo b = cat.block(e.getValue());
                if(b.kind.equals("conveyor")) s.slowBelt = Math.min(s.slowBelt, b.speed);
            }
            Step u = g.add(Step.Kind.UPGRADE, s.label());
            u.upgradeOf = s.id;
            u.network = s.network;
            u.instance = s.instance;
            u.line = s.line;
            u.deps.add(s.id);
            for(Placement p : s.placements) if(s.standIns.containsKey(p.block)) u.placements.add(p);
            s.upgrade = u.id;
        }
        return g;
    }

    Step add(Step.Kind kind, String what){
        Step s = new Step(steps.size(), kind, what);
        steps.add(s);
        return s;
    }

    public Step step(int id){ return steps.get(id); }

    /** the default stand-ins: titanium conveyors by conveyors, pneumatic drills by mechanical drills */
    public static Map<String, String> defaultDowngrade(Catalog cat){
        Map<String, String> d = new TreeMap<>();
        if(cat.block("titanium-conveyor") != null && cat.block("conveyor") != null) d.put("titanium-conveyor", "conveyor");
        if(cat.block("pneumatic-drill") != null && cat.block("mechanical-drill") != null) d.put("pneumatic-drill", "mechanical-drill");
        return d;
    }

    /** all blocks of the plan in build order (each step's as planned), for checks */
    public List<Placement> allPlacements(){
        List<Placement> out = new ArrayList<>();
        for(Step s : steps) if(s.kind != Step.Kind.UPGRADE) out.addAll(s.placements);
        return out;
    }
}
