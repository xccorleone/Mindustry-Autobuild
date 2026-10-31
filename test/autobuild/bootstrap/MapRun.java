package autobuild.bootstrap;

import autobuild.bootstrap.domain.layout.*;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.production.*;
import autobuild.bootstrap.domain.survey.*;

import java.nio.file.*;

import static autobuild.bootstrap.Check.*;

/**
 * Runs the bootstrap layout on a terrain dumped by the server's {@code abboot dump=PATH} (default
 * goal: built-in silicon, combustion plant, a copper line, basic belts) and prints the forecast,
 * notes and the map. {@code java autobuild.bootstrap.MapRun test/local/maps/fortress.txt [belt]}
 * <p>
 * {@code STAGE=copper:300,lead:200}: the whole use case instead (seeds, steps ordered from that stock),
 * printing the schedule and what stays stuck; {@code MODULES=graphite4,silicon8,metaglass4} picks its
 * modules (a local or fixture blueprint by file name, {@code press} / {@code kiln} the built-in ones;
 * default silicon8).
 */
public class MapRun{
    public static void main(String[] args) throws Exception{
        GridTerrain t = new GridTerrain(Files.readAllLines(Path.of(args[0])).stream().filter(l -> !l.isEmpty()).toArray(String[]::new));
        String belt = args.length > 1 ? args[1] : "conveyor";
        if(System.getenv("STAGE") != null){
            stage(t, belt, System.getenv("STAGE"));
            return;
        }
        LayoutOptions o = LayoutTest.options(belt);
        double cap = o.beltSpeed();
        ModuleSpec si = ModuleAnalyzer.analyze(blueprint(FIXTURES + "silicon8.txt"), catalog(), cap);
        ModuleSpec plant = ModuleAnalyzer.analyze(BuiltinModules.combustionPlant(), catalog(), cap);
        BasePlan plan = LayoutTest.layout(args[0], t, new SurveyService.Options(), o, LayoutTest.goal(o, plant, si), false);
        for(String n : plan.notes) System.out.println("note: " + n);
        for(Corridor c : plan.corridors) System.out.println("corridor " + c + " owner " + c.owner + " pipes " + c.pipes.size() + " cost " + c.cost);
        if(args.length > 3){
            int x = Integer.parseInt(args[2]), y = Integer.parseInt(args[3]);
            for(Zone z : plan.zones) for(var p : z.placements) if(Math.abs(p.x - x) <= 2 && Math.abs(p.y - y) <= 2) System.out.println("zone " + z + ": " + p + " cfg " + cfg(p.config));
            for(Corridor c : plan.corridors) for(int i = 0; i < c.placements.size(); i++){
                var p = c.placements.get(i);
                if(Math.abs(p.x - x) <= 2 && Math.abs(p.y - y) <= 2) System.out.println("corridor " + c + " #" + i + "/" + c.placements.size() + ": " + p + " cfg " + cfg(p.config));
            }
        }
        System.out.println(LayoutTest.ascii(t, plan, o.cat));
        System.out.println(fails == 0 ? "checks OK" : fails + " checks FAILED");
    }

    static void stage(GridTerrain t, String belt, String stock) throws Exception{
        var in = new autobuild.bootstrap.app.PlanBaseUseCase.Input();
        in.terrain = t;
        in.cat = catalog();
        in.belt = belt;
        in.power = BuiltinModules.combustionPlant();
        for(String m : System.getenv().getOrDefault("MODULES", "silicon8").split(",")){
            Blueprint b = m.equals("press") ? BuiltinModules.graphitePress() : m.equals("kiln") ? BuiltinModules.kiln()
                : Files.exists(Path.of(LOCAL + m + ".txt")) ? blueprint(LOCAL + m + ".txt") : blueprint(FIXTURES + m + ".txt");
            in.modules.add(new autobuild.bootstrap.app.PlanBaseUseCase.Order(b, 1));
        }
        in.raw.put("copper", 1);
        for(String r : stock.split(",")){
            String[] ic = r.split(":");
            in.stock.put(ic[0], Integer.parseInt(ic[1]));
        }
        var r = autobuild.bootstrap.app.PlanBaseUseCase.run(in);
        System.out.println("forecast " + r.plan.forecast + " seeds " + r.seeds);
        for(var l : r.production.lines){
            StringBuilder src = new StringBuilder();
            for(var sh : l.sources) src.append(' ').append(sh.patch != null ? sh.patch : sh.water).append(String.format(" %.2f;", sh.rate));
            System.out.println("line " + l + ":" + src);
        }
        for(String n : r.plan.notes) System.out.println("note: " + n);
        for(var e : r.schedule.entries) System.out.println("  " + e);
        System.out.println("stuck " + r.schedule.stuck + " missing " + r.schedule.missing);
        LayoutChecks.all("map", r.plan, r.survey, in.cat, t.core());
        if(System.getenv("PRINT") != null) System.out.println(LayoutTest.ascii(t, r.plan, in.cat));
        System.out.println(fails == 0 ? "checks OK" : fails + " checks FAILED");
    }

    static String cfg(Object c){ return c instanceof int[] d ? d[0] + "," + d[1] : String.valueOf(c); }
}
