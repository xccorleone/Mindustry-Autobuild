package autobuild.bootstrap.domain.production;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.module.ModuleSpec;
import autobuild.bootstrap.domain.module.ModuleSpec.Port;
import autobuild.bootstrap.domain.production.ProductionPlan.Instance;
import autobuild.bootstrap.domain.survey.*;

import java.util.*;

/**
 * Balances a goal against what the survey found.
 * <ol>
 * <li>Module copies; power plants added until they cover what the modules use beyond their own
 *     generation (peak use).</li>
 * <li>A supply line per module inlet (at most one outside belt's worth) and per raw line to the core.</li>
 * <li>Allocation - the lines of modules making a {@link ProductionGoal#first first} product (graphite:
 *     everything else waits for it), then power plants' (nothing else runs without power), then the
 *     core's (it pays for everything), then other modules', biggest first: nearest ore patches (water bodies) with capacity
 *     left; a patch gives tiles x coverage x the best allowed drill's rate, a water body tiles x pump
 *     rate. A far ore patch is used only for enough ore to be worth its line ({@link ProductionGoal#fetch}).</li>
 * <li>A module runs at its scarcest item's share of what its inlets for that item take together -
 *     short of ore, or held to one belt per inlet - so its other lines need less: every line's target
 *     is scaled to it and everything is allocated again (once: ore this frees is not offered back to
 *     other short modules), which also spreads scarce ore over the inlets sharing an item. The short
 *     lines of the item setting a module's speed, if ore is what they lack, are marked limiting; so is
 *     any core line still short after the last allocation.</li>
 * <li>Plants deliver power in proportion to the fuel and water they get.</li>
 * </ol>
 */
public final class ChainSolver{
    private ChainSolver(){}

    /** @throws IllegalArgumentException for a module that can't be used ({@link ModuleSpec#error}) or no belt speed */
    public static ProductionPlan solve(ProductionGoal goal, SurveyService.Report survey, Catalog cat){
        if(goal.belt <= 0) throw new IllegalArgumentException("goal.belt must be set");
        for(ProductionGoal.ModuleOrder o : goal.modules) if(!o.spec.ok()) throw new IllegalArgumentException(o.spec.name + ": " + o.spec.error);
        if(goal.power != null && !goal.power.ok()) throw new IllegalArgumentException(goal.power.name + ": " + goal.power.error);
        ProductionPlan plan = new ProductionPlan();
        plan.fetch = goal.fetch;
        double use = 0, own = 0;
        for(ProductionGoal.ModuleOrder o : goal.modules){
            for(int i = 0; i < o.count; i++) plan.instances.add(new Instance(o.spec, false));
            use += o.spec.powerUse * o.count;
            own += o.spec.powerGen * o.count;
        }
        for(Instance in : plan.instances) in.first = goal.first(in.spec);
        plan.powerDemand = Math.max(0, use - own);
        if(plan.powerDemand > 0){
            if(goal.power == null || goal.power.powerGen <= 0){
                plan.noPower = true;
            }else{
                int n = (int)Math.ceil(plan.powerDemand / goal.power.powerGen - 1e-9);
                for(int i = 0; i < n; i++) plan.instances.add(new Instance(goal.power, true));
            }
        }
        for(int m = 0; m < plan.instances.size(); m++){
            ModuleSpec spec = plan.instances.get(m).spec;
            for(int i = 0; i < spec.inlets.size(); i++){
                Port in = spec.inlets.get(i);
                if(in.rate <= 0) continue; // its item feeds no working crafter
                double wanted = in.liquid ? in.rate : Math.min(in.rate, goal.belt);
                if(!in.liquid && in.rate > goal.belt + 1e-6) plan.beltLimited.add(new double[]{m, i, in.rate});
                plan.lines.add(new SupplyLine(in.item(), in.liquid, m, i, in.rate, wanted));
            }
        }
        goal.raw.forEach((item, n) -> {
            for(int i = 0; i < n; i++) plan.lines.add(new SupplyLine(item, false, -1, -1, goal.belt, goal.belt));
        });

        allocate(plan, goal, survey, cat);
        boolean scaled = false;
        for(int m = 0; m < plan.instances.size(); m++){
            Map<String, double[]> byItem = new TreeMap<>(); // item -> {supplied, demand} over the module's lines
            for(SupplyLine l : plan.lines){
                if(l.instance != m) continue;
                double[] e = byItem.computeIfAbsent(l.item, k -> new double[2]);
                e[0] += l.supplied;
                e[1] += l.demand;
            }
            double k = 1;
            for(double[] e : byItem.values()) if(e[1] > 0) k = Math.min(k, e[0] / e[1]);
            plan.instances.get(m).factor = k;
            for(SupplyLine l : plan.lines){
                if(l.instance != m) continue;
                double[] e = byItem.get(l.item);
                l.limiting = l.supplied < l.wanted - 1e-6 && e[0] / e[1] <= k + 1e-9;
                if(k < 1 - 1e-6 && l.target > l.demand * k + 1e-9){
                    l.target = l.demand * k;
                    scaled = true;
                }
            }
        }
        if(scaled) allocate(plan, goal, survey, cat);
        for(SupplyLine l : plan.lines) if(l.toCore()) l.limiting = l.supplied < l.wanted - 1e-6;
        for(Instance in : plan.instances) if(in.power) plan.powerSupply += in.spec.powerGen * in.factor;
        plan.powerShort = plan.powerSupply < plan.powerDemand - 1e-6;
        return plan;
    }

    /** items per second one ore tile gives with the best drill that can mine it (0: none can) */
    static double perTile(String item, ProductionGoal goal, Catalog cat){
        Catalog.ItemInfo it = cat.item(item);
        double best = 0;
        for(BlockInfo d : goal.drills){
            Float t = d.drillTime.get(item);
            if(it != null && d.tier >= it.hardness && t != null && t > 0) best = Math.max(best, 60.0 / t);
        }
        return best;
    }

    /**
     * First-product modules, power plants (nothing else runs without power; near fuel keeps them near
     * the modules, in reach of one grid), the core (it pays for everything), other modules.
     */
    static int rank(ProductionPlan plan, SupplyLine l){
        if(l.toCore()) return 2;
        Instance in = plan.instances.get(l.instance);
        return in.first ? 0 : in.power ? 1 : 3;
    }

    /** a line `distance` long from the core for `rate` per second: enough to be worth the way */
    static boolean worth(ProductionGoal goal, int distance, double rate){
        return rate >= distance * goal.fetch - 1e-9;
    }

    static void allocate(ProductionPlan plan, ProductionGoal goal, SurveyService.Report survey, Catalog cat){
        Map<Object, Double> left = new HashMap<>();
        for(OrePatch p : survey.patches) left.put(p, p.size() * goal.coverage * perTile(p.item, goal, cat));
        double pumpRate = goal.pump == null ? 0 : goal.pump.pump * 60.0;
        for(WaterBody b : survey.waters) left.put(b, b.size() * pumpRate);

        List<SupplyLine> order = new ArrayList<>(plan.lines);
        order.sort(Comparator.comparingInt((SupplyLine l) -> rank(plan, l)).thenComparingDouble(l -> -l.target));
        for(SupplyLine l : order){
            l.sources.clear();
            l.supplied = 0;
            List<?> cands = l.liquid
                ? survey.waters.stream().filter(b -> b.liquid.equals(l.item) && b.distance != PathField.UNREACHABLE).toList()
                : survey.patches(l.item).stream().filter(p -> p.distance != PathField.UNREACHABLE).toList();
            for(Object c : cands){
                double need = l.target - l.supplied;
                if(need <= 1e-9) break;
                double take = Math.min(need, left.get(c));
                if(take <= 1e-9 || c instanceof OrePatch p && !worth(goal, p.distance, take)) continue;
                left.merge(c, -take, Double::sum);
                l.supplied += take;
                l.sources.add(new SupplyLine.Share(c instanceof OrePatch p ? p : null, c instanceof WaterBody b ? b : null, take));
            }
        }
    }
}
