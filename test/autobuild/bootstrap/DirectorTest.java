package autobuild.bootstrap;

import autobuild.bootstrap.app.*;
import autobuild.bootstrap.app.Director.Site;
import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.layout.Forecast;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.shared.*;
import autobuild.bootstrap.domain.staging.*;

import java.util.*;

import static autobuild.bootstrap.Check.*;

/** The director against a pretend game: a builder taking a few blocks a second, income from what stands. */
final class DirectorTest{
    /** blocks by centre tile, a build queue, the core */
    static final class FakeSite implements Site{
        final Catalog cat;
        final Map<Long, String> world = new HashMap<>();
        final ArrayDeque<Placement> queue = new ArrayDeque<>();
        final Map<String, Double> stock = new TreeMap<>();
        double perSecond = 6, budget;
        boolean negative;

        FakeSite(Catalog cat){ this.cat = cat; }

        static long key(int x, int y){ return (long)x << 32 | (y & 0xffffffffL); }

        public double stock(String item){ return stock.getOrDefault(item, 0.0); }

        public boolean infinite(){ return false; }

        public Status status(Placement p){
            String b = world.get(key(p.x, p.y));
            if(p.block.equals(b)) return Status.BUILT;
            if(queue.stream().anyMatch(q -> q.x == p.x && q.y == p.y && q.block.equals(p.block))) return Status.PENDING;
            // a stand-in may be replaced by its planned block
            if(b == null || b.equals("conveyor") && p.block.equals("titanium-conveyor") || b.equals("mechanical-drill") && p.block.equals("pneumatic-drill")) return Status.FREE;
            return Status.BLOCKED;
        }

        public void queue(List<Placement> places){ queue.addAll(places); }

        public void unqueue(List<Placement> places){ queue.removeAll(places); }

        public boolean canBuild(){ return true; }

        /** the builder: blocks in queue order, each once the core holds its cost */
        void step(double secs, Forecast income){
            income.toCore.forEach((it, r) -> stock.merge(it, r * secs, Double::sum));
            budget = Math.min(budget + perSecond * secs, perSecond);
            while(budget >= 1 && !queue.isEmpty()){
                Placement p = queue.peek();
                Bom cost = cat.block(p.block).req;
                boolean ok = true;
                for(String it : cost.items()) ok &= stock(it) >= cost.get(it);
                if(!ok) break;
                for(String it : cost.items()){
                    stock.merge(it, (double)-cost.get(it), Double::sum);
                    negative |= stock(it) < -1e-6;
                }
                world.put(key(p.x, p.y), p.block);
                queue.poll();
                budget--;
            }
        }
    }

    static void run() throws Exception{
        Catalog cat = catalog();
        Blueprint si = blueprint(FIXTURES + "silicon8.txt");
        PlanBaseUseCase.Input in = StageTest.input(LayoutTest.map(1), cat);
        in.modules.add(new PlanBaseUseCase.Order(si, 1));
        in.modules.add(new PlanBaseUseCase.Order(BuiltinModules.graphitePress(), 1));
        in.raw.put("copper", 1);
        PlanBaseUseCase.Result r = PlanBaseUseCase.run(in);

        // from the start to done
        {
            FakeSite site = site(cat, in);
            List<String> log = new ArrayList<>();
            Director d = new Director(r.steps, in.stage, site, events(log));
            run(d, site, 4 * 3600);
            eq("director: done", Director.Phase.DONE, d.phase);
            yes("director: never below zero", !site.negative);
            yes("director: every block stands", standing(d, site));
            if(System.getenv("PRINT") != null) log.forEach(l -> System.out.println("  " + l));
            System.out.printf("director: done in %.0fs (planned %.0fs), %d steps%n", d.state.time, r.schedule.time(), d.built());

            // a finished block destroyed: queued again
            Placement gone = r.steps.step(r.schedule.entries.get(0).step.id).placements(false).get(0);
            site.world.remove(FakeSite.key(gone.x, gone.y));
            run(d, site, 30);
            yes("director: repaired", standing(d, site));
        }

        // the builder dropped its queue (died) while building: queued again
        {
            FakeSite site = site(cat, in);
            Director d = new Director(r.steps, in.stage, site, null);
            boolean dropped = false;
            for(double t = 0; t < 4 * 3600 && d.phase != Director.Phase.DONE; t += 0.5){
                if(!dropped && d.phase == Director.Phase.BUILDING && site.queue.size() > 3){
                    site.queue.clear();
                    dropped = true;
                }
                site.step(0.5, StagePlanner.forecast(d.graph, d.state.status));
                d.update(0.5);
            }
            yes("director: dropped queue happened", dropped);
            eq("director: done after the queue was dropped", Director.Phase.DONE, d.phase);
        }

        // something of the player's in the way of the silicon module: blocked, skipped, the rest goes on
        {
            FakeSite site = site(cat, in);
            Step module = null;
            for(Step s : r.steps.steps) if(s.kind == Step.Kind.MODULE && s.what.startsWith("Silicon")) module = s;
            Placement p = module.placements.get(0);
            site.world.put(FakeSite.key(p.x, p.y), "scrap-wall");
            Director d = new Director(r.steps, in.stage, site, null);
            for(double t = 0; t < 4 * 3600 && d.phase != Director.Phase.BLOCKED; t += 0.5){
                site.step(0.5, StagePlanner.forecast(d.graph, d.state.status));
                d.update(0.5);
            }
            eq("director: blocked", Director.Phase.BLOCKED, d.phase);
            yes("director: blocked at the wall", d.blockedAt == p);
            d.skip();
            run(d, site, 4 * 3600);
            eq("director: done without the module", Director.Phase.DONE, d.phase);
            eq("director: module skipped", StageState.SKIPPED, d.state.status[module.id]);
            boolean sandSkipped = true;
            for(Step s : r.steps.steps) if(s.instance == module.instance && s.kind == Step.Kind.MINE) sandSkipped &= d.state.status[s.id] == StageState.SKIPPED;
            yes("director: its lines skipped", sandSkipped);
        }
    }

    static FakeSite site(Catalog cat, PlanBaseUseCase.Input in){
        FakeSite site = new FakeSite(cat);
        in.stock.forEach((it, n) -> site.stock.put(it, (double)n));
        return site;
    }

    static void run(Director d, FakeSite site, double secs){
        for(double t = 0; t < secs && (d.phase != Director.Phase.DONE || t < secs && secs <= 60); t += 0.5){
            site.step(0.5, StagePlanner.forecast(d.graph, d.state.status));
            d.update(0.5);
        }
    }

    /** every built step's blocks stand as built */
    static boolean standing(Director d, FakeSite site){
        for(Step s : d.graph.steps){
            if(s.kind == Step.Kind.UPGRADE || !d.state.built(s.id)) continue;
            for(Placement p : s.placements(d.state.status[s.id] == StageState.PLAIN)) if(site.status(p) != Site.Status.BUILT) return false;
        }
        return true;
    }

    static Director.Events events(List<String> log){
        return new Director.Events(){
            @Override public void started(Step step, boolean plain, double time){ log.add(String.format("%5.0fs start %s%s", time, step, plain ? " (stand-ins)" : "")); }

            @Override public void finished(Step step, double time){ log.add(String.format("%5.0fs done  %s", time, step)); }

            @Override public void blocked(Step step, Placement at){ log.add("blocked " + step + " at " + at); }

            @Override public void repaired(Step step, int blocks){ log.add("repaired " + step + ": " + blocks); }
        };
    }
}
