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
 */
public class MapRun{
    public static void main(String[] args) throws Exception{
        GridTerrain t = new GridTerrain(Files.readAllLines(Path.of(args[0])).stream().filter(l -> !l.isEmpty()).toArray(String[]::new));
        String belt = args.length > 1 ? args[1] : "conveyor";
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

    static String cfg(Object c){ return c instanceof int[] d ? d[0] + "," + d[1] : String.valueOf(c); }
}
