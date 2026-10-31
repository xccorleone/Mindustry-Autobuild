package autobuild.bootstrap;

import autobuild.bootstrap.app.PlanBaseUseCase;
import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.layout.Forecast;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.shared.*;
import autobuild.bootstrap.domain.staging.*;

import java.nio.file.*;
import java.util.*;

import static autobuild.bootstrap.Check.*;

/** Steps of a planned base and their greedy order from a nearly empty core. */
final class StageTest{
    static void run() throws Exception{
        Catalog cat = catalog();
        Blueprint si = blueprint(FIXTURES + "silicon8.txt");

        // silicon + graphite press + combustion plant + copper to the core, from 300 copper and 200 lead
        {
            PlanBaseUseCase.Input in = input(LayoutTest.map(1), cat);
            in.modules.add(new PlanBaseUseCase.Order(si, 1));
            in.modules.add(new PlanBaseUseCase.Order(BuiltinModules.graphitePress(), 1));
            in.raw.put("copper", 1);
            PlanBaseUseCase.Result r = PlanBaseUseCase.run(in);
            print("basic", r);
            graph("basic", r, cat);
            replay("basic", r, in, cat);
            yes("basic: everything scheduled", r.schedule.stuck.isEmpty());
            yes("basic: no seed needed", !r.seeded);
            Schedule.Entry first = r.schedule.entries.get(0);
            yes("basic: copper to the core first", first.step.kind == Step.Kind.MINE && first.step.to == null && first.step.what.equals("copper"));
            yes("basic: drills built as mechanical while there is no graphite", first.plain);
            yes("basic: pneumatic drills later", r.schedule.entries.stream().anyMatch(e -> e.step.kind == Step.Kind.UPGRADE));
            near("basic: the end is the plan", r.plan.forecast.toCore.getOrDefault("silicon", 0.0), r.schedule.end.toCore.getOrDefault("silicon", 0.0));
        }

        // press + silicon + kiln with graphite worth little (by payback alone silicon would come first):
        // graphite first all the same, silicon and the kiln after the press
        {
            PlanBaseUseCase.Input in = input(LayoutTest.map(1), cat);
            in.stage.weights.put("graphite", 0.3);
            in.modules.add(new PlanBaseUseCase.Order(si, 1));
            in.modules.add(new PlanBaseUseCase.Order(BuiltinModules.graphitePress(), 1));
            in.modules.add(new PlanBaseUseCase.Order(BuiltinModules.kiln(), 1));
            in.raw.put("copper", 1);
            PlanBaseUseCase.Result r = PlanBaseUseCase.run(in);
            print("graphite first", r);
            graph("graphite first", r, cat);
            replay("graphite first", r, in, cat);
            yes("graphite first: everything scheduled", r.schedule.stuck.isEmpty());
            yes("graphite first: no seed", !r.seeded);
            int press = -1, silicon = -1, kiln = -1;
            for(int i = 0; i < r.schedule.entries.size(); i++){
                Step s = r.schedule.entries.get(i).step;
                if(s.kind != Step.Kind.MODULE) continue;
                if(s.what.equals(BuiltinModules.PRESS)) press = i;
                if(s.what.equals(si.name)) silicon = i;
                if(s.what.equals(BuiltinModules.KILN)) kiln = i;
            }
            yes("graphite first: the press before silicon and the kiln", press >= 0 && silicon > press && kiln > press);
            yes("graphite first: metaglass made", r.schedule.end.toCore.getOrDefault("metaglass", 0.0) > 0);

            PlanBaseUseCase.Input off = input(LayoutTest.map(1), cat);
            off.stage.weights.put("graphite", 0.3);
            off.stage.first.clear();
            off.modules.addAll(in.modules);
            off.raw.put("copper", 1);
            Step firstModule = PlanBaseUseCase.run(off).schedule.entries.stream().map(e -> e.step).filter(s -> s.kind == Step.Kind.MODULE).findFirst().orElse(null);
            yes("graphite first: by payback alone silicon first", firstModule != null && firstModule.what.equals(si.name));
        }

        // Fortress (a local terrain dump): from 300 copper and 200 lead the press must not spend the lead
        // the first lead line needs - before, the press came first and the lead line waited forever
        if(Files.exists(Path.of("test/local/maps/fortress.txt"))){
            GridTerrain t = new GridTerrain(Files.readAllLines(Path.of("test/local/maps/fortress.txt")).stream().filter(l -> !l.isEmpty()).toArray(String[]::new));
            PlanBaseUseCase.Input in = input(t, cat);
            in.modules.add(new PlanBaseUseCase.Order(BuiltinModules.graphitePress(), 1));
            in.modules.add(new PlanBaseUseCase.Order(si, 1));
            in.modules.add(new PlanBaseUseCase.Order(BuiltinModules.kiln(), 1));
            in.raw.put("copper", 1);
            PlanBaseUseCase.Result r = PlanBaseUseCase.run(in);
            print("fortress", r);
            replay("fortress", r, in, cat);
            yes("fortress: everything scheduled", r.schedule.stuck.isEmpty());

            // the workshop graphite and metaglass modules: the metaglass factory's three ports sit side by
            // side; next to the core the lines squeezed past them sealed the lead inlet
            Blueprint graphite = blueprint(LOCAL + "graphite4.txt"), glass = blueprint(LOCAL + "metaglass4.txt");
            if(graphite != null && glass != null){
                PlanBaseUseCase.Input ws = input(t, cat);
                ws.modules.add(new PlanBaseUseCase.Order(graphite, 1));
                ws.modules.add(new PlanBaseUseCase.Order(si, 1));
                ws.modules.add(new PlanBaseUseCase.Order(glass, 1));
                ws.raw.put("copper", 1);
                PlanBaseUseCase.Result w = PlanBaseUseCase.run(ws);
                print("fortress workshop", w);
                yes("fortress workshop: everything scheduled", w.schedule.stuck.isEmpty());
                yes("fortress workshop: metaglass made", w.plan.forecast.toCore.getOrDefault("metaglass", 0.0) > 1);
                yes("fortress workshop: no seed", !w.seeded);
                List<String> modules = w.schedule.entries.stream().filter(e -> e.step.kind == Step.Kind.MODULE).map(e -> e.step.what).toList();
                yes("fortress workshop: the graphite module first", !modules.isEmpty() && modules.get(0).equals(graphite.name));

                // two other loads of Fortress (its ore differs per load): the sand line laid first took the
                // way to the lead inlet's lane; laid out again with the lead line first, both connect
                for(String other : List.of("fortress-s1", "fortress-s2")){
                    Path f = Path.of("test/local/maps/" + other + ".txt");
                    if(!Files.exists(f)) continue;
                    PlanBaseUseCase.Input oi = input(new GridTerrain(Files.readAllLines(f).stream().filter(l -> !l.isEmpty()).toArray(String[]::new)), cat);
                    oi.modules.addAll(ws.modules);
                    oi.raw.put("copper", 1);
                    PlanBaseUseCase.Result or = PlanBaseUseCase.run(oi);
                    yes(other + " workshop: metaglass made", or.plan.forecast.toCore.getOrDefault("metaglass", 0.0) > 1);
                }
            }
        }else System.out.println("SKIP test/local/maps/fortress.txt (local terrain missing)");

        // titanium to the core and nothing making graphite: a graphite press is added as the seed
        {
            GridTerrain t = LayoutTest.map(3);
            for(int x = 50; x <= 58; x++) for(int y = 21; y <= 27; y++) if(t.g[x][y] == '.') t.g[x][y] = 't';
            PlanBaseUseCase.Input in = input(t, cat);
            in.modules.add(new PlanBaseUseCase.Order(si, 1));
            in.raw.put("titanium", 1);
            PlanBaseUseCase.Result r = PlanBaseUseCase.run(in);
            print("seed", r);
            graph("seed", r, cat);
            replay("seed", r, in, cat);
            yes("seed: seeded", r.seeded);
            yes("seed: everything scheduled", r.schedule.stuck.isEmpty());
            int press = -1, titanium = -1;
            for(int i = 0; i < r.schedule.entries.size(); i++){
                Step s = r.schedule.entries.get(i).step;
                if(s.kind == Step.Kind.MODULE && s.what.equals(BuiltinModules.PRESS) && press < 0) press = i;
                if(s.kind == Step.Kind.MINE && s.what.equals("titanium") && titanium < 0) titanium = i;
            }
            yes("seed: the press before titanium", press >= 0 && titanium > press);
        }

        // no lead at all: nothing with bridges or nodes can be built, and the summary says lead is missing
        {
            PlanBaseUseCase.Input in = input(LayoutTest.map(2), cat);
            in.stock.remove("lead");
            in.modules.add(new PlanBaseUseCase.Order(si, 1));
            in.raw.put("copper", 1);
            PlanBaseUseCase.Result r = PlanBaseUseCase.run(in);
            print("no lead", r);
            yes("no lead: stuck", !r.schedule.stuck.isEmpty());
            yes("no lead: lead missing", r.schedule.missing.contains("lead"));
        }

        // sandbox: costs nothing, everything as planned
        {
            PlanBaseUseCase.Input in = input(LayoutTest.map(1), cat);
            in.infinite = true;
            in.modules.add(new PlanBaseUseCase.Order(si, 1));
            in.raw.put("copper", 1);
            PlanBaseUseCase.Result r = PlanBaseUseCase.run(in);
            yes("sandbox: no stand-ins", r.schedule.entries.stream().noneMatch(e -> e.plain || e.step.kind == Step.Kind.UPGRADE));
            yes("sandbox: everything scheduled", r.schedule.stuck.isEmpty());
        }
    }

    static PlanBaseUseCase.Input input(GridTerrain t, Catalog cat){
        PlanBaseUseCase.Input in = new PlanBaseUseCase.Input();
        in.terrain = t;
        in.cat = cat;
        in.power = BuiltinModules.combustionPlant();
        in.stock.put("copper", 300);
        in.stock.put("lead", 200);
        return in;
    }

    static void print(String what, PlanBaseUseCase.Result r){
        if(System.getenv("PRINT") == null) return;
        System.out.println(what + ": " + r.steps.steps.size() + " steps" + (r.seeded ? ", seeded" : ""));
        for(Schedule.Entry e : r.schedule.entries) System.out.println("  " + e);
        if(!r.schedule.stuck.isEmpty()) System.out.println("  stuck " + r.schedule.stuck + " missing " + r.schedule.missing);
    }

    /** every block in exactly one step; dependencies point back; built as planned the forecast is the plan's */
    static void graph(String what, PlanBaseUseCase.Result r, Catalog cat){
        StepGraph g = r.steps;
        List<String> planned = new ArrayList<>(), stepped = new ArrayList<>();
        for(Placement p : r.plan.ordered(cat)) planned.add(p.toString());
        for(Placement p : g.allPlacements()) stepped.add(p.toString());
        Collections.sort(planned);
        Collections.sort(stepped);
        yes(what + ": steps hold the plan's blocks once each", planned.equals(stepped));
        for(Step s : g.steps) for(int d : s.deps) yes(what + ": " + s + " depends on a later step", d < s.id);
        byte[] all = new byte[g.steps.size()];
        Arrays.fill(all, StageState.FULL);
        Forecast f = StagePlanner.forecast(g, all);
        yes(what + ": all built = the plan's forecast", f.toCore.toString().equals(r.plan.forecast.toCore.toString()));
        // one core line's site alone: the core gets only that
        for(Step s : g.steps){
            if(s.kind != Step.Kind.MINE || s.to != null) continue;
            byte[] one = new byte[g.steps.size()];
            one[s.id] = StageState.FULL;
            Forecast o = StagePlanner.forecast(g, one);
            yes(what + ": one core site feeds only the core its item", o.toCore.keySet().equals(Set.of(s.what)));
            break;
        }
    }

    /** builds the schedule again: never below zero stock, dependencies first */
    static void replay(String what, PlanBaseUseCase.Result r, PlanBaseUseCase.Input in, Catalog cat){
        StepGraph g = r.steps;
        StageState s = new StageState(g);
        in.stock.forEach((it, n) -> s.stock.put(it, (double)n));
        for(Schedule.Entry e : r.schedule.entries){
            Forecast now = StagePlanner.forecast(g, s.status);
            s.earn(now, e.start - s.time, in.stage.capacity);
            for(String it : e.cost.items()){
                if(s.stock(it) < e.cost.get(it) - 1e-6){
                    yes(what + ": " + e.step + " paid with " + String.format("%.1f", s.stock(it)) + " " + it + " of " + e.cost.get(it), false);
                    return;
                }
            }
            for(int d : e.step.deps) if(!s.built(d)){
                yes(what + ": " + e.step + " before " + g.step(d), false);
                return;
            }
            s.take(e.cost);
            s.build(g, e.step.id, e.plain);
        }
    }
}
