package autobuild.bootstrap.domain.layout;

import autobuild.bootstrap.domain.module.ModuleSpec;
import autobuild.bootstrap.domain.module.ModuleSpec.Port;
import autobuild.bootstrap.domain.production.*;

import java.util.*;

/**
 * What the base as laid out will make, from what was actually built rather than what the production
 * plan hoped for:
 * <ol>
 * <li>a line gets what its built sources deliver, at most one belt (liquids: no limit);</li>
 * <li>an instance runs at its scarcest item's supply share (as {@link ChainSolver} counts it), not at
 *     all without room or with an outlet that reaches nowhere (it would clog), and at most as fast as
 *     each outlet's belt carries;</li>
 * <li>plants make power in proportion to that speed; when they make less than the modules use at
 *     peak, every module using power slows down by the same share;</li>
 * <li>the core gets the modules' products and the raw lines' items.</li>
 * </ol>
 */
public final class Forecast{
    /** per supply line, per instance */
    public final double[] line, speed;
    public double powerUse, powerMade;
    /** item -> per second into the core */
    public final Map<String, Double> toCore = new TreeMap<>();

    Forecast(int lines, int instances){
        line = new double[lines];
        speed = new double[instances];
    }

    public static Forecast compute(BasePlan plan, ProductionPlan prod, LayoutOptions o){
        Forecast f = new Forecast(prod.lines.size(), prod.instances.size());
        double belt = o.beltSpeed();
        for(int i = 0; i < prod.lines.size(); i++){
            SupplyLine l = prod.lines.get(i);
            f.line[i] = l.liquid ? plan.lineSupply[i] : Math.min(belt, plan.lineSupply[i]);
        }
        for(int m = 0; m < prod.instances.size(); m++){
            ModuleSpec spec = prod.instances.get(m).spec;
            if(plan.stamps[m] == null) continue;
            Map<String, double[]> byItem = new TreeMap<>();
            for(int i = 0; i < prod.lines.size(); i++){
                SupplyLine l = prod.lines.get(i);
                if(l.instance != m) continue;
                double[] e = byItem.computeIfAbsent(l.item, k -> new double[2]);
                e[0] += f.line[i];
                e[1] += l.demand;
            }
            double k = 1;
            for(double[] e : byItem.values()) if(e[1] > 0) k = Math.min(k, e[0] / e[1]);
            for(int j = 0; j < spec.outlets.size(); j++){
                Port p = spec.outlets.get(j);
                if(p.rate <= 0) continue;
                if(!plan.outletRouted[m][j]) k = 0;
                else k = Math.min(k, Math.min(p.cap, belt) / p.rate);
            }
            f.speed[m] = k;
        }
        for(int m = 0; m < prod.instances.size(); m++){
            ModuleSpec spec = prod.instances.get(m).spec;
            f.powerMade += spec.powerGen * f.speed[m];
            if(!prod.instances.get(m).power) f.powerUse += spec.powerUse * f.speed[m];
        }
        if(f.powerUse > f.powerMade + 1e-9){
            double share = f.powerMade / f.powerUse;
            for(int m = 0; m < prod.instances.size(); m++){
                if(!prod.instances.get(m).power && prod.instances.get(m).spec.powerUse > 0) f.speed[m] *= share;
            }
        }
        for(int m = 0; m < prod.instances.size(); m++){
            double k = f.speed[m];
            prod.instances.get(m).spec.outputs.forEach((item, rate) -> f.toCore.merge(item, rate * k, Double::sum));
        }
        for(int i = 0; i < prod.lines.size(); i++){
            SupplyLine l = prod.lines.get(i);
            if(l.toCore()) f.toCore.merge(l.item, f.line[i], Double::sum);
        }
        f.toCore.values().removeIf(v -> v <= 1e-9);
        return f;
    }

    @Override public String toString(){
        StringJoiner j = new StringJoiner(", ");
        toCore.forEach((k, v) -> j.add(k + String.format(" %.2f/s", v)));
        return j + String.format(" | power %.0f/%.0f", powerMade, powerUse);
    }
}
