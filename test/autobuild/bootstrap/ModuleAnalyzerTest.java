package autobuild.bootstrap;

import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.module.ModuleSpec.Port;

import java.util.*;

import static autobuild.bootstrap.Check.*;

final class ModuleAnalyzerTest{
    /** the fastest belt in the tests: titanium */
    static final double CAP = 10;

    static void run() throws Exception{
        silicon();
        generator();
        local();
        errors();
    }

    static String ports(List<Port> ps){
        StringJoiner j = new StringJoiner(" ");
        for(Port p : ps) j.add(String.join("+", p.items) + "@" + p.x + "," + p.y + ">" + p.dir);
        return j.toString();
    }

    static void silicon() throws Exception{
        ModuleSpec s = ModuleAnalyzer.analyze(blueprint(FIXTURES + "silicon8.txt"), catalog(), CAP);
        eq("silicon ok", null, s.error);
        eq("silicon inlets", "coal@-1,2>0 sand@4,7>3 sand@10,7>3", ports(s.inlets));
        eq("silicon outlets", "silicon@15,2>0", ports(s.outlets));
        // 8 smelters make 12/s, the titanium outlet carries 10
        near("silicon factor", 10.0 / 12, s.factor);
        near("silicon output", 10, s.outputs.get("silicon"));
        near("coal inlet", 10, s.inlets.get(0).rate);
        near("sand inlet", 10, s.inlets.get(1).rate);
        near("silicon peak power", 240, s.powerUse);
        eq("silicon working", "8/8", s.working + "/" + s.consumers);
        eq("silicon has node", true, s.hasNode);
        eq("silicon titanium", 4, s.cost.get("titanium"));
    }

    static void generator() throws Exception{
        ModuleSpec g = ModuleAnalyzer.analyze(blueprint(FIXTURES + "generator1.txt"), catalog(), CAP);
        eq("generator ok", null, g.error);
        eq("generator inlets", "water@-1,3>0 coal@3,-1>1", ports(g.inlets));
        eq("water inlet is liquid", true, g.inlets.get(0).liquid);
        near("generator power", 4 * 5.5 * 60, g.powerGen);
        near("generator water", 24, g.inlets.get(0).rate);
        near("generator coal", 4 * 60 / 90.0, g.inlets.get(1).rate);
        eq("generator has no node", false, g.hasNode);
        eq("generator no outlet", 0, g.outlets.size());
    }

    static void local() throws Exception{
        Blueprint kiln = blueprint(LOCAL + "metaglass4.txt");
        if(kiln != null){
            ModuleSpec k = ModuleAnalyzer.analyze(kiln, catalog(), CAP);
            eq("kiln ok", null, k.error);
            eq("kiln inlets", "sand@3,-1>1 lead@4,-1>1", ports(k.inlets));
            eq("kiln outlets", "metaglass@5,-1>3", ports(k.outlets));
            near("kiln output", 10, k.outputs.get("metaglass"));
        }
        Blueprint graphite = blueprint(LOCAL + "graphite4.txt");
        if(graphite != null){
            ModuleSpec g = ModuleAnalyzer.analyze(graphite, catalog(), CAP);
            eq("graphite inlets", "coal@2,6>3", ports(g.inlets));
            eq("graphite outlets", "graphite@2,-1>3", ports(g.outlets));
            near("graphite output", 4 * 60 / 90.0, g.outputs.get("graphite"));
            near("graphite coal", 8 * 60 / 90.0, g.inlets.get(0).rate);
            near("graphite no power", 0, g.powerUse);
        }
        Blueprint starter = blueprint(LOCAL + "starter.txt");
        if(starter != null){
            ModuleSpec st = ModuleAnalyzer.analyze(starter, catalog(), CAP);
            eq("starter ok", null, st.error);
            eq("starter inlets (markers)", "coal@2,6>3 sand@4,6>3 lead@6,6>3", ports(st.inlets));
            eq("starter mixed outlet", "graphite+metaglass+silicon@2,0>3", ports(st.outlets));
            yes("starter inlets are markers", st.inlets.get(0).marker);
            yes("starter powers itself", st.powerGen > st.powerUse);
            eq("markers not in cost", 0, st.cost.get("item-source"));
        }
    }

    static void errors() throws Exception{
        Blueprint unknown = new Blueprint("x", 2, 1, List.of(new Blueprint.Tile("no-such-block", 0, 0, 0, null)));
        yes("unknown block", ModuleAnalyzer.analyze(unknown, catalog(), CAP).error.startsWith("unknown block"));
        Blueprint noInlet = new Blueprint("x", 3, 3, List.of(new Blueprint.Tile("graphite-press", 1, 1, 0, null)));
        eq("no inlet", "no inlet", ModuleAnalyzer.analyze(noInlet, catalog(), CAP).error);
        // a belt straight through: its item can't be told
        Blueprint pass = new Blueprint("x", 3, 1, List.of(
            new Blueprint.Tile("conveyor", 0, 0, 0, null), new Blueprint.Tile("conveyor", 1, 0, 0, null), new Blueprint.Tile("conveyor", 2, 0, 0, null)));
        yes("pass-through belt", ModuleAnalyzer.analyze(pass, catalog(), CAP).error.contains("feeds no crafter"));
    }
}
