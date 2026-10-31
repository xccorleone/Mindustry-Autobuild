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
 * <li>Allocation - power plants' lines first (nothing runs without power), then modules', then the
 *     core's, biggest first: nearest ore patches (water bodies) with capacity left; a patch gives
 *     tiles x coverage x the best allowed drill's rate, a water body tiles x pump rate. Lines left
 *     short are marked limiting.</li>
 * <li>A module runs at its scarcest line's share of what that inlet takes - short of ore, or held to
 *     one belt - so its other lines need less: their targets are scaled to it and everything is
 *     allocated again (once: ore this frees is not offered back to other short modules). The line
 *     setting a module's speed, if ore is what it lacks, is marked limiting; so is any short core line.</li>
 * <li>Plants deliver power in proportion to the fuel and water they get.</li>
 * </ol>
 */
public final class ChainSolver{
    private ChainSolver(){}

    public static ProductionPlan solve(ProductionGoal goal, SurveyService.Report survey, Catalog cat){
        ProductionPlan plan = new ProductionPlan();
        double use = 0, own = 0;
        for(ProductionGoal.ModuleOrder o : goal.modules){
            for(int i = 0; i < o.count; i++) plan.instances.add(new Instance(o.spec, false));
            use += o.spec.powerUse * o.count;
            own += o.spec.powerGen * o.count;
        }
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
                double wanted = in.liquid ? in.rate : Math.min(in.rate, goal.belt);
                if(!in.liquid && in.rate > goal.belt + 1e-6) plan.beltLimited.add(new double[]{m, i, in.rate});
                plan.lines.add(new SupplyLine(in.item(), in.liquid, m, i, in.rate, wanted));
            }
        }
        goal.raw.forEach((item, n) -> {
            for(int i = 0; i < n; i++) plan.lines.add(new SupplyLine(item, false, -1, -1, goal.belt, goal.belt));
        });

        allocate(plan, goal, survey, cat);
        for(SupplyLine l : plan.lines) if(l.toCore()) l.limiting = l.supplied < l.wanted - 1e-6;
        boolean scaled = false;
        for(int m = 0; m < plan.instances.size(); m++){
            double k = 1;
            for(SupplyLine l : plan.lines) if(l.instance == m && l.demand > 0) k = Math.min(k, l.supplied / l.demand);
            plan.instances.get(m).factor = k;
            for(SupplyLine l : plan.lines){
                if(l.instance != m) continue;
                l.limiting = l.supplied < l.wanted - 1e-6 && l.supplied / l.demand <= k + 1e-9;
                if(k < 1 - 1e-6 && l.target > l.demand * k + 1e-9){
                    l.target = l.demand * k;
                    scaled = true;
                }
            }
        }
        if(scaled) allocate(plan, goal, survey, cat);
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

    static void allocate(ProductionPlan plan, ProductionGoal goal, SurveyService.Report survey, Catalog cat){
        Map<Object, Double> left = new HashMap<>();
        for(OrePatch p : survey.patches) left.put(p, p.size() * goal.coverage * perTile(p.item, goal, cat));
        double pumpRate = goal.pump == null ? 0 : goal.pump.pump * 60.0;
        for(WaterBody b : survey.waters) left.put(b, b.size() * pumpRate);

        List<SupplyLine> order = new ArrayList<>(plan.lines);
        order.sort(Comparator.comparingInt((SupplyLine l) -> l.toCore() ? 2 : plan.instances.get(l.instance).power ? 0 : 1)
            .thenComparingDouble(l -> -l.target));
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
                if(take <= 1e-9) continue;
                left.merge(c, -take, Double::sum);
                l.supplied += take;
                l.sources.add(new SupplyLine.Share(c instanceof OrePatch p ? p : null, c instanceof WaterBody b ? b : null, take));
            }
        }
    }
}
