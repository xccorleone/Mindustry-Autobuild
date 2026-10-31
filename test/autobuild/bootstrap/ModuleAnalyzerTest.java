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
        rules();
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

    static Blueprint.Tile tile(String block, int x, int y, int rot, Object config){ return new Blueprint.Tile(block, x, y, rot, config); }

    /** push rules the fixtures don't exercise, each checked against the game's v160.7 source */
    static void rules() throws Exception{
        // an end bridge (no link) takes items only over a link, not from the belt beside it
        Blueprint endBridge = new Blueprint("x", 3, 1, List.of(
            tile("conveyor", 0, 0, 0, null), tile("bridge-conveyor", 1, 0, 0, null), tile("combustion-generator", 2, 0, 0, null)));
        yes("end bridge refuses a belt", String.valueOf(ModuleAnalyzer.analyze(endBridge, catalog(), CAP).error).contains("feeds no crafter"));

        // a bridge in the middle of a chain on the edge is fed by the chain, not an inlet
        Blueprint chain = new Blueprint("x", 5, 2, List.of(
            tile("bridge-conveyor", 0, 1, 0, new int[]{2, 0}), tile("bridge-conveyor", 2, 1, 0, new int[]{2, 0}),
            tile("bridge-conveyor", 4, 1, 0, null), tile("combustion-generator", 4, 0, 0, null)));
        ModuleSpec c = ModuleAnalyzer.analyze(chain, catalog(), CAP);
        eq("bridge chain ok", null, c.error);
        eq("only the chain's first bridge is an inlet", "coal@0,2>3", ports(c.inlets));

        // a 2x2 distributor pushes from its whole footprint
        Blueprint dist = new Blueprint("x", 4, 3, List.of(
            tile("conveyor", 0, 0, 0, null), tile("distributor", 1, 0, 0, null),
            tile("combustion-generator", 3, 1, 0, null), tile("combustion-generator", 1, 2, 0, null)));
        ModuleSpec d = ModuleAnalyzer.analyze(dist, catalog(), CAP);
        eq("distributor ok", null, d.error);
        eq("distributor feeds both generators", "2/2", d.working + "/" + d.consumers);

        // every outlet is limited by its own belt: press A reaches void 1 only, press B both voids,
        // so void 1 carries 0.67 + 0.33 = 1/s - over a 0.8/s cap although both together could take 1.6
        Blueprint outs = new Blueprint("x", 5, 4, List.of(
            tile("conveyor", 2, 0, 1, null), tile("router", 2, 1, 0, null),
            tile("graphite-press", 0, 1, 0, null), tile("graphite-press", 3, 1, 0, null),
            tile("item-void", 2, 2, 0, null), tile("item-void", 4, 3, 0, null)));
        ModuleSpec o = ModuleAnalyzer.analyze(outs, catalog(), 0.8);
        eq("two outlets ok", null, o.error);
        near("busiest outlet sets the speed", 0.8, o.factor);
        near("graphite", 2 * 60 / 90.0 * 0.8, o.outputs.get("graphite"));
        near("void 1 full", 0.8, o.outlets.get(0).rate);
        near("void 2", 60 / 90.0 / 2 * 0.8, o.outlets.get(1).rate);
    }
}
