package autobuild.bootstrap.domain.staging;

import autobuild.bootstrap.domain.layout.Forecast;
import autobuild.bootstrap.domain.shared.Bom;

import java.util.*;

/** What of a step graph stands (and how), the core's stock, the clock. */
public final class StageState{
    public static final byte NONE = 0, PLAIN = 1, FULL = 2, SKIPPED = 3;

    /** per step */
    public final byte[] status;
    /** item -> amount in the core */
    public final Map<String, Double> stock = new TreeMap<>();
    /** seconds */
    public double time;

    public StageState(StepGraph g){
        status = new byte[g.steps.size()];
    }

    StageState(StageState o){
        status = o.status.clone();
        stock.putAll(o.stock);
        time = o.time;
    }

    public StageState copy(){ return new StageState(this); }

    public boolean built(int step){ return status[step] == PLAIN || status[step] == FULL; }

    /** a step is done: built, or skipped */
    public boolean done(int step){ return status[step] != NONE; }

    /** the step stands; built as planned it needs no upgrade, and an upgrade makes its step as planned */
    public void build(StepGraph g, int id, boolean plain){
        Step s = g.step(id);
        if(s.kind == Step.Kind.UPGRADE){
            status[id] = FULL;
            status[s.upgradeOf] = FULL;
            return;
        }
        status[id] = plain ? PLAIN : FULL;
        if(!plain && s.upgrade >= 0) status[s.upgrade] = FULL;
    }

    public void skip(StepGraph g, int id){
        status[id] = SKIPPED;
        Step s = g.step(id);
        if(s.upgrade >= 0 && status[s.upgrade] == NONE) status[s.upgrade] = SKIPPED;
    }

    public double stock(String item){ return stock.getOrDefault(item, 0.0); }

    public void take(Bom cost){
        for(String it : cost.items()) stock.put(it, stock(it) - cost.get(it));
    }

    /** `secs` of income, each item up to the core's capacity */
    public void earn(Forecast income, double secs, int capacity){
        time += secs;
        income.toCore.forEach((it, rate) -> stock.put(it, Math.max(stock(it), Math.min(capacity, stock(it) + rate * secs))));
    }
}
