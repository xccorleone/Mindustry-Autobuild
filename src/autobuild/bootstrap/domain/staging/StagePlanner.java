package autobuild.bootstrap.domain.staging;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.layout.Forecast;
import autobuild.bootstrap.domain.module.ModuleSpec;
import autobuild.bootstrap.domain.production.SupplyLine;
import autobuild.bootstrap.domain.shared.Bom;

import java.util.*;

/**
 * Orders the steps greedily by payback: what the base earns more once a candidate stands, per what
 * it costs (both in {@link StageOptions#weight weighted} items). A candidate is a step with what it
 * needs to earn anything ({@link #closure}). Of the candidates the core can pay for - from its stock
 * and the income of what already stands - within the horizon, the best payback wins; past the
 * horizon, the one paid for soonest; steps earning nothing come last, cheapest first.
 * <p>
 * A step whose planned blocks have stand-ins ({@link Step#standIns}) is built with them when an item
 * only the planned blocks need (titanium, graphite) is neither in the core nor coming in; its
 * upgrade becomes a step of its own.
 */
public final class StagePlanner{
    static final double EPS = 1e-9;

    public static final class Pick{
        public final int step;
        public final boolean plain;

        Pick(int step, boolean plain){
            this.step = step;
            this.plain = plain;
        }

        @Override public String toString(){ return step + (plain ? "p" : ""); }
    }

    /** steps to build one after another */
    public static final class Choice{
        public final List<Pick> picks = new ArrayList<>();
        public final Bom cost = new Bom();
        /** value per second earned more; weighted cost; seconds until the core holds the cost */
        public double gain, price, wait;
        int tier;
        double key;
    }

    private StagePlanner(){}

    /** what stands in `status`, as the layout's forecast */
    public static Forecast forecast(StepGraph g, byte[] status){
        double belt = g.options.beltSpeed();
        return Forecast.compute(g.plan, g.prod, new Forecast.Built(){
            @Override public double zoneRate(int z){
                int id = g.zoneStep[z];
                if(id < 0 || !built(status[id])) return 0;
                double r = g.plan.zones.get(z).rate;
                Step s = g.step(id);
                return status[id] == StageState.PLAIN ? Math.min(r * s.slowRate, s.slowBelt) : r;
            }

            @Override public boolean module(int m){
                return g.moduleStep[m] >= 0 && built(status[g.moduleStep[m]]);
            }

            @Override public double lineBelt(int i){
                int id = g.mainStep[i];
                return id >= 0 && status[id] == StageState.PLAIN ? g.step(id).slowBelt : belt;
            }

            @Override public double moduleBelt(int m){
                int id = m < 0 ? -1 : g.moduleStep[m];
                return id >= 0 && status[id] == StageState.PLAIN ? g.step(id).slowBelt : belt;
            }
        });
    }

    static boolean built(byte st){ return st == StageState.PLAIN || st == StageState.FULL; }

    public static double value(Forecast f, StageOptions o){
        double v = 0;
        for(Map.Entry<String, Double> e : f.toCore.entrySet()) v += o.weight(e.getKey()) * e.getValue();
        return v;
    }

    public static double price(Bom cost, StageOptions o){
        double v = 0;
        for(String it : cost.items()) v += o.weight(it) * cost.get(it);
        return v;
    }

    /**
     * Seconds until the core holds `cost` (each item at most its capacity) with the income `now`;
     * infinite when an item short has no income (it is added to `missing`, if given).
     */
    public static double waitFor(Bom cost, StageState s, Forecast now, StageOptions o, Set<String> missing){
        if(o.infinite) return 0;
        double w = 0;
        for(String it : cost.items()){
            double short_ = Math.min(cost.get(it), o.capacity) - s.stock(it);
            if(short_ <= EPS) continue;
            double rate = now.toCore.getOrDefault(it, 0.0);
            if(rate <= EPS){
                if(missing == null) return Double.POSITIVE_INFINITY;
                missing.add(it);
                w = Double.POSITIVE_INFINITY;
                continue;
            }
            w = Math.max(w, short_ / rate);
        }
        return w;
    }

    /** build the step with its stand-ins: an item only the planned blocks need is neither in the core nor coming in */
    public static boolean plain(StepGraph g, StageState s, int id, Forecast now, StageOptions o){
        Step st = g.step(id);
        if(st.standIns.isEmpty() || o.infinite) return false;
        Catalog cat = g.options.cat;
        Bom full = st.cost(cat, false), stand = st.cost(cat, true);
        for(String it : full.items()){
            if(full.get(it) <= stand.get(it)) continue;
            if(s.stock(it) < full.get(it) && now.toCore.getOrDefault(it, 0.0) <= EPS) return true;
        }
        return false;
    }

    /**
     * The best group of steps to build next, or null when nothing left can be paid for (the items
     * short go into `missing`) or nothing is left. The greedy order alone can strand steps (spend the
     * last copper on a lead line, and the copper line is never paid for): of the best few
     * candidates, the first whose greedy continuation leaves the fewest steps unbuilt wins.
     */
    public static Choice next(StepGraph g, StageState s, StageOptions o, Set<String> missing){
        return next(g, s, o, missing, true);
    }

    static Choice next(StepGraph g, StageState s, StageOptions o, Set<String> missing, boolean lookahead){
        List<Choice> cands = candidates(g, s, o, missing);
        if(cands.isEmpty()) return null;
        if(missing != null) missing.clear();
        if(!lookahead || cands.size() == 1) return cands.get(0);
        Choice best = null;
        int bestLeft = Integer.MAX_VALUE;
        for(int i = 0; i < Math.min(LOOKAHEAD, cands.size()); i++){
            StageState t = s.copy();
            for(Pick p : cands.get(i).picks) build(g, t, p, o, null, 0);
            int left = simulate(g, t, o, false).stuck.size();
            if(left < bestLeft){
                best = cands.get(i);
                bestLeft = left;
            }
            if(left == 0) break;
        }
        return best;
    }

    /** candidates checked by their continuation */
    static final int LOOKAHEAD = 6;

    /**
     * Every candidate the core can pay for, best first: earning something within the horizon by
     * payback (gain per price), earning something past it by wait, earning nothing cheapest first.
     */
    static List<Choice> candidates(StepGraph g, StageState s, StageOptions o, Set<String> missing){
        Forecast now = forecast(g, s.status);
        double base = value(now, o);
        List<Choice> out = new ArrayList<>();
        for(Step st : g.steps){
            if(s.status[st.id] != StageState.NONE) continue;
            if(st.kind == Step.Kind.UPGRADE && s.status[st.upgradeOf] != StageState.PLAIN) continue;
            Choice c = closure(g, s, st.id, now, base, o);
            if(c == null) continue;
            c.wait = waitFor(c.cost, s, now, o, missing);
            if(Double.isInfinite(c.wait)) continue;
            c.tier = c.gain <= EPS ? 2 : c.wait <= o.horizon ? 0 : 1;
            c.key = c.tier == 0 ? -c.gain / Math.max(c.price, EPS) : c.tier == 1 ? c.wait : c.wait * 1e-3 + c.price;
            out.add(c);
        }
        out.sort(Comparator.comparingInt((Choice c) -> c.tier).thenComparingDouble(c -> c.key).thenComparingDouble(c -> c.wait));
        return out;
    }

    /**
     * A step with what it needs to earn: its dependencies; for a module or plant, a source for each
     * item it gets none of yet (the cheapest site of a line of that item); for a module using power
     * while the plants make too little, plants (with their fuel) until they don't; for an upgrade
     * earning nothing alone, the other upgrades of its network. Null if a dependency was skipped.
     */
    static Choice closure(StepGraph g, StageState s, int seed, Forecast now, double base, StageOptions o){
        byte[] st = s.status.clone();
        Choice c = new Choice();
        if(!add(g, s, st, seed, c, now, o)) return null;
        for(int round = 0; round < 8; round++){
            Forecast f = forecast(g, st);
            boolean changed = false, consumer = false;
            for(int k = 0; k < c.picks.size(); k++){
                Step p = g.step(c.picks.get(k).step);
                if(p.kind != Step.Kind.MODULE && p.kind != Step.Kind.PLANT) continue;
                ModuleSpec spec = g.prod.instances.get(p.instance).spec;
                consumer |= p.kind == Step.Kind.MODULE && spec.powerUse > 0;
                Map<String, Double> got = new TreeMap<>();
                for(int i = 0; i < g.prod.lines.size(); i++){
                    SupplyLine l = g.prod.lines.get(i);
                    if(l.instance == p.instance) got.merge(l.item, f.line[i], Double::sum);
                }
                for(Map.Entry<String, Double> e : got.entrySet()){
                    if(e.getValue() > EPS) continue;
                    int src = cheapestSource(g, st, p.instance, e.getKey(), o);
                    if(src >= 0 && add(g, s, st, src, c, now, o)) changed = true;
                }
            }
            if(consumer && f.powerUse > f.powerMade + EPS){
                int plant = -1;
                double price = Double.POSITIVE_INFINITY;
                for(Step p : g.steps){
                    if(p.kind != Step.Kind.PLANT || st[p.id] != StageState.NONE) continue;
                    double pr = price(p.cost(g.options.cat, false), o);
                    if(pr < price){ price = pr; plant = p.id; }
                }
                if(plant >= 0 && add(g, s, st, plant, c, now, o)) changed = true;
            }
            if(!changed) break;
        }
        Step first = g.step(seed);
        if(first.kind == Step.Kind.UPGRADE && value(forecast(g, st), o) - base <= EPS){
            for(Step u : g.steps){
                if(u.kind != Step.Kind.UPGRADE || u.network != first.network || st[u.id] != StageState.NONE || st[u.upgradeOf] != StageState.PLAIN) continue;
                add(g, s, st, u.id, c, now, o);
            }
        }
        c.gain = value(forecast(g, st), o) - base;
        c.price = price(c.cost, o);
        return c;
    }

    /** the cheapest site not yet chosen of a line of this item into this instance (one joining no site not yet built) */
    static int cheapestSource(StepGraph g, byte[] st, int instance, String item, StageOptions o){
        int best = -1;
        double price = Double.POSITIVE_INFINITY;
        for(Step p : g.steps){
            if(p.kind != Step.Kind.MINE && p.kind != Step.Kind.WATER || p.instance != instance || !p.what.equals(item) || st[p.id] != StageState.NONE) continue;
            boolean ready = true;
            for(int d : p.deps) ready &= d == g.moduleStep[instance] || built(st[d]);
            if(!ready) continue;
            double pr = price(p.cost(g.options.cat, false), o);
            if(pr < price){ price = pr; best = p.id; }
        }
        return best;
    }

    /** adds a step after its dependencies; false if one of them was skipped */
    static boolean add(StepGraph g, StageState s, byte[] st, int id, Choice c, Forecast now, StageOptions o){
        if(built(st[id])) return true;
        if(st[id] == StageState.SKIPPED) return false;
        Step step = g.step(id);
        for(int d : step.deps) if(!add(g, s, st, d, c, now, o)) return false;
        boolean plain = step.kind != Step.Kind.UPGRADE && plain(g, s, id, now, o);
        if(step.kind == Step.Kind.UPGRADE){
            st[id] = StageState.FULL;
            st[step.upgradeOf] = StageState.FULL;
        }else st[id] = plain ? StageState.PLAIN : StageState.FULL;
        c.picks.add(new Pick(id, plain));
        c.cost.addAll(step.cost(g.options.cat, plain));
        return true;
    }

    /** builds the steps greedily from `start` (not changed) as far as the core can pay for them */
    public static Schedule simulate(StepGraph g, StageState start, StageOptions o){
        return simulate(g, start, o, true);
    }

    static Schedule simulate(StepGraph g, StageState start, StageOptions o, boolean lookahead){
        StageState s = start.copy();
        Schedule out = new Schedule();
        for(int group = 0; ; group++){
            Set<String> missing = new TreeSet<>();
            Choice c = next(g, s, o, missing, lookahead);
            if(c == null){
                out.missing.addAll(missing);
                break;
            }
            for(Pick p : c.picks) build(g, s, p, o, out, group);
        }
        for(Step st : g.steps){
            if(s.status[st.id] != StageState.NONE) continue;
            if(st.kind == Step.Kind.UPGRADE && !built(s.status[st.upgradeOf])) continue;
            out.stuck.add(st);
        }
        out.end = forecast(g, s.status);
        return out;
    }

    /** waits for a pick's cost, pays it and builds it (recorded in `out`, if given) */
    static void build(StepGraph g, StageState s, Pick p, StageOptions o, Schedule out, int group){
        Forecast now = forecast(g, s.status);
        Bom cost = g.step(p.step).cost(g.options.cat, p.plain);
        // (finite: the group's cost was covered, and income only grows)
        double w = waitFor(cost, s, now, o, null);
        s.earn(now, Double.isInfinite(w) ? 0 : w, o.capacity);
        s.take(cost);
        s.build(g, p.step, p.plain);
        if(out != null) out.entries.add(new Schedule.Entry(g.step(p.step), p.plain, group, s.time, cost, value(forecast(g, s.status), o) - value(now, o)));
    }
}
