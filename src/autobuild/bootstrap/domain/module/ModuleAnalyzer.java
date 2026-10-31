package autobuild.bootstrap.domain.module;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.module.ModuleSpec.Port;

import java.util.*;

import static autobuild.bootstrap.domain.shared.Dir.*;

/**
 * Turns a {@link Blueprint} into a {@link ModuleSpec}.
 * <ol>
 * <li><b>Ports</b>: on the blueprint's edge, a conveyor (conduit) whose back faces outside is an inlet
 *     and one whose front faces outside an outlet; a linked item (liquid) bridge with a free outside
 *     side, no bridge linking into it and nothing next to it but along its link is an inlet. Sandbox markers are ports too and say what they carry: an item / liquid
 *     source is an inlet, an item void an outlet; markers are not built.</li>
 * <li><b>Flow</b>: an item put into a port moves through the blueprint by the game's push rules -
 *     conveyors push forward and refuse items from their front, junctions pass straight, sorters pass
 *     their item forward and others to the sides (inverted: the reverse), gates and routers spread,
 *     a linked bridge sends to its link and refuses items from the link side, an end bridge takes
 *     items only over a link and dumps to every side but its incoming bridges; routers, crafters and
 *     source markers push from their whole footprint - until a crafter or generator consumes the item
 *     or it leaves.</li>
 * <li><b>Items</b>: an edge inlet's item is not written anywhere, so every assignment of candidate
 *     items (what the module's crafters eat, {@link #FUEL} for generators) is tried; the one letting
 *     the module run fastest wins, then the one leaking least into outlets; a tie is an error.</li>
 * <li><b>Power</b>: every block using power counts at full speed (working crafters, menders, ...).</li>
 * <li><b>Rates</b>: inlets and outlets carry at most their belt's speed (bridges and markers: the given
 *     default); the module runs at the share of its crafters' capacity that the inlets and every
 *     outlet allow, a product splitting evenly over the outlets it reaches.</li>
 * </ol>
 */
public final class ModuleAnalyzer{
    /** what generators are fed: the only fuel the bootstrap produces */
    public static final String FUEL = "coal";
    static final int MAX_ASSIGNMENTS = 1 << 14;
    static final double EPS = 1e-9;

    static final class Part{
        final Blueprint.Tile t;
        final BlockInfo b;
        final int x0, y0, x1, y1;
        /** valid bridge link: target part and direction */
        int link = -1, linkDir = -1;
        /** sides (bit per direction) where bridges linking into this one stand */
        int incoming;

        Part(Blueprint.Tile t, BlockInfo b){
            this.t = t;
            this.b = b;
            x0 = t.x + b.lo();
            y0 = t.y + b.lo();
            x1 = t.x + b.hi();
            y1 = t.y + b.hi();
        }

        String kind(){ return b.kind; }
    }

    /** where an item put into a port ends up */
    static final class Reach{
        final BitSet consumers = new BitSet(), outlets = new BitSet();
        boolean leak;
    }

    final Blueprint bp;
    final Catalog cat;
    final double defaultCap;
    final List<Part> parts = new ArrayList<>();
    final int[][] grid;
    final List<Integer> consumerParts = new ArrayList<>();
    final List<Port> inlets = new ArrayList<>(), outlets = new ArrayList<>();

    ModuleAnalyzer(Blueprint bp, Catalog cat, double defaultCap){
        this.bp = bp;
        this.cat = cat;
        this.defaultCap = defaultCap;
        grid = new int[bp.width][bp.height];
        for(int[] row : grid) Arrays.fill(row, -1);
    }

    /** @param defaultCap items per second a bridge or marker port carries (the fastest belt in use) */
    public static ModuleSpec analyze(Blueprint bp, Catalog cat, double defaultCap){
        ModuleAnalyzer a = new ModuleAnalyzer(bp, cat, defaultCap);
        ModuleSpec spec = new ModuleSpec(bp.name, bp.width, bp.height);
        spec.blueprint = bp;
        try{
            a.run(spec);
        }catch(Failure f){
            spec.error = f.getMessage();
        }
        return spec;
    }

    static final class Failure extends RuntimeException{
        Failure(String msg){ super(msg, null, false, false); }
    }

    void run(ModuleSpec spec){
        for(Blueprint.Tile t : bp.tiles){
            BlockInfo b = cat.block(t.block);
            if(b == null) throw new Failure("unknown block " + t.block);
            Part p = new Part(t, b);
            for(int x = p.x0; x <= p.x1; x++) for(int y = p.y0; y <= p.y1; y++){
                if(!in(x, y)) throw new Failure(t.block + " sticks out of the blueprint");
                grid[x][y] = parts.size();
            }
            parts.add(p);
            if(!marker(p)) spec.cost.addAll(b.req);
            if(p.kind().equals("node")) spec.hasNode = true;
        }
        linkBridges();
        for(int i = 0; i < parts.size(); i++){
            String k = parts.get(i).kind();
            if(k.equals("crafter") || k.equals("generator")) consumerParts.add(i);
        }
        findPorts();
        spec.inlets.addAll(inlets);
        spec.outlets.addAll(outlets);
        spec.consumers = consumerParts.size();
        assign(spec);
    }

    boolean in(int x, int y){ return x >= 0 && y >= 0 && x < bp.width && y < bp.height; }

    static boolean marker(Part p){
        return p.kind().equals("itemsource") || p.kind().equals("liquidsource") || p.kind().equals("itemvoid");
    }

    Part partAt(int x, int y){ return in(x, y) && grid[x][y] >= 0 ? parts.get(grid[x][y]) : null; }

    void linkBridges(){
        for(Part p : parts){
            if(!(p.kind().equals("bridge") || p.kind().equals("liquidbridge")) || !(p.t.config instanceof int[] d)) continue;
            if((d[0] == 0) == (d[1] == 0) || Math.abs(d[0] + d[1]) > p.b.range) continue;
            Part q = partAt(p.t.x + d[0], p.t.y + d[1]);
            if(q == null || !q.t.block.equals(p.t.block)) continue;
            p.link = grid[q.t.x][q.t.y];
            p.linkDir = d[0] > 0 ? 0 : d[1] > 0 ? 1 : d[0] < 0 ? 2 : 3;
            q.incoming |= 1 << opposite(p.linkDir);
        }
    }

    // ---- ports ----

    void findPorts(){
        for(Part p : parts){
            int x = p.t.x, y = p.t.y, r = p.t.rot & 3;
            switch(p.kind()){
                case "conveyor", "conduit" -> {
                    boolean liquid = p.kind().equals("conduit");
                    double cap = liquid ? Double.MAX_VALUE : p.b.speed;
                    if(!in(x - DX[r], y - DY[r])) inlets.add(new Port(x - DX[r], y - DY[r], r, liquid, false, cap));
                    else if(!in(x + DX[r], y + DY[r])) outlets.add(new Port(x + DX[r], y + DY[r], r, liquid, false, cap));
                }
                case "bridge", "liquidbridge" -> {
                    if(p.link < 0 || p.incoming != 0 || fedInside(p)) break;
                    boolean liquid = p.kind().equals("liquidbridge");
                    for(int s = 0; s < 4; s++){
                        if(s == p.linkDir || in(x + DX[s], y + DY[s])) continue;
                        inlets.add(new Port(x + DX[s], y + DY[s], opposite(s), liquid, false, liquid ? Double.MAX_VALUE : defaultCap));
                        break;
                    }
                }
                case "itemsource", "liquidsource" -> {
                    boolean liquid = p.kind().equals("liquidsource");
                    if(!(p.t.config instanceof String item)) throw new Failure("source marker at (" + x + "," + y + ") has no item");
                    int dir = -1;
                    for(int e = 0; e < 4 && dir < 0; e++){
                        Part q = partAt(x + DX[e], y + DY[e]);
                        if(q != null && accepts(q, e, item, liquid, false)) dir = e;
                    }
                    if(dir < 0) throw new Failure("source marker at (" + x + "," + y + ") feeds nothing");
                    Port port = new Port(x, y, dir, liquid, true, liquid ? Double.MAX_VALUE : defaultCap);
                    port.items.add(item);
                    inlets.add(port);
                }
                case "itemvoid" -> {
                    int dir = -1;
                    for(int e = 0; e < 4 && dir < 0; e++){
                        Part q = partAt(x - DX[e], y - DY[e]);
                        if(q != null && q.kind().equals("conveyor") && (q.t.rot & 3) == e) dir = e;
                    }
                    for(int e = 0; e < 4 && dir < 0; e++) if(partAt(x - DX[e], y - DY[e]) != null) dir = e;
                    outlets.add(new Port(x, y, Math.max(dir, 0), false, true, defaultCap));
                }
                default -> {}
            }
        }
        if(inlets.isEmpty()) throw new Failure("no inlet");
    }

    /** a block stands next to the bridge on a side other than its link: the bridge is fed from inside */
    boolean fedInside(Part p){
        for(int s = 0; s < 4; s++) if(s != p.linkDir && partAt(p.t.x + DX[s], p.t.y + DY[s]) != null) return true;
        return false;
    }

    // ---- flow ----

    boolean consumes(Part q, String item, boolean liquid){
        if(q.kind().equals("crafter")) return liquid ? item.equals(q.b.liquid) : q.b.in.get(item) > 0;
        if(q.kind().equals("generator")){
            if(liquid) return item.equals(q.b.liquid);
            Catalog.ItemInfo it = cat.item(item);
            return q.b.fuel && it != null && it.flammability > 0;
        }
        return false;
    }

    /** q takes the item moving in direction `travel` (arriving over a bridge link: always) */
    boolean accepts(Part q, int travel, String item, boolean liquid, boolean viaBridge){
        int r = q.t.rot & 3;
        switch(q.kind()){
            case "conveyor": return !liquid && travel != opposite(r);
            case "conduit": return liquid && travel != opposite(r);
            case "bridge": return !liquid && (viaBridge || (q.link >= 0 && travel != opposite(q.linkDir)));
            case "liquidbridge": return liquid && (viaBridge || (q.link >= 0 && travel != opposite(q.linkDir)));
            case "sorter", "gate", "junction", "router": return !liquid;
            case "liquidrouter": return liquid;
            case "itemvoid": return !liquid;
            default: return consumes(q, item, liquid);
        }
    }

    Reach flowFromPort(Port port, String item){
        Reach reach = new Reach();
        Deque<int[]> queue = new ArrayDeque<>();
        boolean[] seen = new boolean[parts.size() * 8];
        if(port.marker) pushAround(parts.get(grid[port.x][port.y]), item, port.liquid, reach, queue, seen); // sources feed every side
        else push(port.x, port.y, port.x + DX[port.dir], port.y + DY[port.dir], port.dir, item, port.liquid, false, reach, queue, seen);
        drain(item, port.liquid, reach, queue, seen);
        return reach;
    }

    Reach flowFromCrafter(Part c, String item){
        Reach reach = new Reach();
        Deque<int[]> queue = new ArrayDeque<>();
        boolean[] seen = new boolean[parts.size() * 8];
        pushAround(c, item, false, reach, queue, seen);
        drain(item, false, reach, queue, seen);
        return reach;
    }

    /** a block pushing into every tile around its footprint (crafter output, router, source marker) */
    void pushAround(Part p, String item, boolean liquid, Reach reach, Deque<int[]> queue, boolean[] seen){
        for(int x = p.x0; x <= p.x1; x++){
            push(x, p.y1, x, p.y1 + 1, 1, item, liquid, false, reach, queue, seen);
            push(x, p.y0, x, p.y0 - 1, 3, item, liquid, false, reach, queue, seen);
        }
        for(int y = p.y0; y <= p.y1; y++){
            push(p.x1, y, p.x1 + 1, y, 0, item, liquid, false, reach, queue, seen);
            push(p.x0, y, p.x0 - 1, y, 2, item, liquid, false, reach, queue, seen);
        }
    }

    void push(int fx, int fy, int tx, int ty, int travel, String item, boolean liquid, boolean viaBridge,
              Reach reach, Deque<int[]> queue, boolean[] seen){
        if(!in(tx, ty)){
            int o = outletAt(tx, ty, travel, false);
            if(o >= 0) reach.outlets.set(o);
            else reach.leak = true;
            return;
        }
        int pi = grid[tx][ty];
        if(pi < 0) return;
        Part q = parts.get(pi);
        if(!accepts(q, travel, item, liquid, viaBridge)) return;
        if(q.kind().equals("itemvoid")){
            reach.outlets.set(outletAt(tx, ty, travel, true));
            return;
        }
        if(consumes(q, item, liquid)){
            reach.consumers.set(pi);
            return;
        }
        int key = pi * 8 + travel + (viaBridge ? 4 : 0);
        if(seen[key]) return;
        seen[key] = true;
        queue.add(new int[]{pi, travel, viaBridge ? 1 : 0});
    }

    int outletAt(int x, int y, int travel, boolean marker){
        for(int i = 0; i < outlets.size(); i++){
            Port o = outlets.get(i);
            if(o.x == x && o.y == y && o.marker == marker && (marker || o.dir == travel)) return i;
        }
        return -1;
    }

    void drain(String item, boolean liquid, Reach reach, Deque<int[]> queue, boolean[] seen){
        while(!queue.isEmpty()){
            int[] s = queue.poll();
            Part p = parts.get(s[0]);
            int d = s[1], x = p.t.x, y = p.t.y;
            switch(p.kind()){
                case "conveyor", "conduit" -> {
                    int r = p.t.rot & 3;
                    push(x, y, x + DX[r], y + DY[r], r, item, liquid, false, reach, queue, seen);
                }
                case "junction" -> push(x, y, x + DX[d], y + DY[d], d, item, liquid, false, reach, queue, seen);
                case "sorter" -> {
                    if(Objects.equals(item, p.t.config) != p.b.invert) push(x, y, x + DX[d], y + DY[d], d, item, liquid, false, reach, queue, seen);
                    else for(int e : new int[]{(d + 1) & 3, (d + 3) & 3}) push(x, y, x + DX[e], y + DY[e], e, item, liquid, false, reach, queue, seen);
                }
                case "gate" -> {
                    for(int e : new int[]{d, (d + 1) & 3, (d + 3) & 3}) push(x, y, x + DX[e], y + DY[e], e, item, liquid, false, reach, queue, seen);
                }
                case "router", "liquidrouter" -> pushAround(p, item, liquid, reach, queue, seen);
                case "bridge", "liquidbridge" -> {
                    if(p.link >= 0){
                        Part q = parts.get(p.link);
                        push(x, y, q.t.x, q.t.y, p.linkDir, item, liquid, true, reach, queue, seen);
                    }else{
                        for(int e = 0; e < 4; e++) if((p.incoming & (1 << e)) == 0) push(x, y, x + DX[e], y + DY[e], e, item, liquid, false, reach, queue, seen);
                    }
                }
                default -> {}
            }
        }
    }

    // ---- items and rates ----

    /** per second at full speed, by item (liquids included) */
    Map<String, Double> needs(Part c){
        Map<String, Double> m = new TreeMap<>();
        if(c.kind().equals("crafter")) for(String i : c.b.in.items()) m.put(i, c.b.in.get(i) * 60.0 / c.b.craft);
        if(c.kind().equals("generator") && c.b.fuel) m.put(FUEL, 60.0 / c.b.duration);
        if(c.b.liquid != null) m.put(c.b.liquid, c.b.liquidAmount * 60.0);
        return m;
    }

    void assign(ModuleSpec spec){
        Set<String> itemCands = new TreeSet<>(), liquidCands = new TreeSet<>();
        for(int ci : consumerParts){
            Part c = parts.get(ci);
            for(String n : needs(c).keySet()) (cat.isLiquid(n) ? liquidCands : itemCands).add(n);
        }
        int n = inlets.size();
        List<List<String>> valid = new ArrayList<>();
        List<Map<String, Reach>> reach = new ArrayList<>();
        for(Port in : inlets){
            Map<String, Reach> byItem = new TreeMap<>();
            Set<String> cands = in.marker ? in.items : in.liquid ? liquidCands : itemCands;
            for(String c : cands) byItem.put(c, flowFromPort(in, c));
            List<String> ok = new ArrayList<>();
            byItem.forEach((c, r) -> { if(!r.consumers.isEmpty()) ok.add(c); });
            if(ok.isEmpty()) throw new Failure("inlet at (" + in.x + "," + in.y + ") feeds no crafter");
            valid.add(ok);
            reach.add(byItem);
        }
        long combos = 1;
        for(List<String> v : valid){
            combos *= v.size();
            if(combos > MAX_ASSIGNMENTS) throw new Failure("too many ways to feed the inlets");
        }

        String[] cur = new String[n], best = null;
        double bestScore = -1;
        int bestLeaks = Integer.MAX_VALUE;
        boolean tie = false;
        int[] idx = new int[n];
        for(long k = 0; k < combos; k++){
            for(int i = 0; i < n; i++) cur[i] = valid.get(i).get(idx[i]);
            double f = factor(cur, reach, null);
            int alive = alive(cur, reach).cardinality();
            double score = f * alive;
            int leaks = 0;
            for(int i = 0; i < n; i++){
                Reach r = reach.get(i).get(cur[i]);
                if(r.leak || !r.outlets.isEmpty()) leaks++;
            }
            if(score > bestScore + EPS || (Math.abs(score - bestScore) <= EPS && leaks < bestLeaks)){
                bestScore = score;
                bestLeaks = leaks;
                best = cur.clone();
                tie = false;
            }else if(Math.abs(score - bestScore) <= EPS && leaks == bestLeaks){
                tie = true;
            }
            for(int i = 0; i < n && ++idx[i] == valid.get(i).size(); i++) idx[i] = 0;
        }
        if(bestScore <= EPS) throw new Failure("no crafter can run");
        if(tie) throw new Failure("can't tell which inlet takes which item");

        for(int i = 0; i < n; i++){
            inlets.get(i).items.clear();
            inlets.get(i).items.add(best[i]);
        }
        BitSet alive = alive(best, reach);
        Map<String, double[]> demand = new TreeMap<>();
        double f = factor(best, reach, demand);

        // each crafter's product splits evenly over the outlets it reaches; no outlet may carry more than its belt
        double[] load = new double[outlets.size()];
        for(int ci = alive.nextSetBit(0); ci >= 0; ci = alive.nextSetBit(ci + 1)){
            Part c = parts.get(ci);
            if(!c.kind().equals("crafter")) continue;
            for(String product : c.b.out.items()){
                Reach r = flowFromCrafter(c, product);
                if(r.outlets.isEmpty()) throw new Failure(product + " from (" + c.t.x + "," + c.t.y + ") reaches no outlet");
                double rate = c.b.out.get(product) * 60.0 / c.b.craft;
                for(int o = r.outlets.nextSetBit(0); o >= 0; o = r.outlets.nextSetBit(o + 1)){
                    outlets.get(o).items.add(product);
                    load[o] += rate / r.outlets.cardinality();
                }
                spec.outputs.merge(product, rate, Double::sum);
            }
        }
        for(int o = 0; o < outlets.size(); o++) if(load[o] * f > outlets.get(o).cap) f = outlets.get(o).cap / load[o];

        double ff = f;
        spec.outputs.replaceAll((k, v) -> v * ff);
        for(int o = 0; o < outlets.size(); o++) outlets.get(o).rate = load[o] * f;
        for(int i = 0; i < n; i++){
            double[] ds = demand.get(best[i]);
            Port in = inlets.get(i);
            in.rate = ds[0] * f * (in.cap == Double.MAX_VALUE ? 1.0 / ds[2] : in.cap / ds[1]);
        }
        for(int pi = 0; pi < parts.size(); pi++){
            Part p = parts.get(pi);
            if(p.kind().equals("crafter") && !alive.get(pi)) continue;
            spec.powerUse += p.b.power * 60.0;
            if(alive.get(pi) && p.kind().equals("generator")) spec.powerGen += p.b.gen * 60.0 * f;
        }
        spec.working = alive.cardinality();
        spec.factor = f;
    }

    /** consumers that get every input they need from some inlet under this assignment */
    BitSet alive(String[] items, List<Map<String, Reach>> reach){
        BitSet out = new BitSet();
        for(int ci : consumerParts){
            boolean ok = true;
            for(String need : needs(parts.get(ci)).keySet()){
                boolean fed = false;
                for(int i = 0; i < items.length && !fed; i++) fed = need.equals(items[i]) && reach.get(i).get(items[i]).consumers.get(ci);
                ok &= fed;
            }
            if(ok) out.set(ci);
        }
        return out;
    }

    /**
     * Share of capacity the inlets allow: min over needed items of supply / demand (at most 1).
     * Fills `demand` (if given) with item -> {demand per second, supply per second, inlets}.
     */
    double factor(String[] items, List<Map<String, Reach>> reach, Map<String, double[]> demand){
        Map<String, double[]> d = demand != null ? demand : new TreeMap<>();
        BitSet alive = alive(items, reach);
        for(int ci = alive.nextSetBit(0); ci >= 0; ci = alive.nextSetBit(ci + 1)){
            needs(parts.get(ci)).forEach((item, rate) -> d.computeIfAbsent(item, k -> new double[3])[0] += rate);
        }
        for(int i = 0; i < items.length; i++){
            double[] e = d.computeIfAbsent(items[i], k -> new double[3]);
            double cap = inlets.get(i).cap;
            e[1] = e[1] == Double.MAX_VALUE || cap == Double.MAX_VALUE ? Double.MAX_VALUE : e[1] + cap;
            e[2]++;
        }
        double f = 1;
        for(double[] e : d.values()) if(e[0] > 0) f = Math.min(f, e[1] / e[0]);
        return f;
    }
}
