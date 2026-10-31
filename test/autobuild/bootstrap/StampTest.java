package autobuild.bootstrap;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.layout.Stamp;
import autobuild.bootstrap.domain.layout.Stamp.StampPort;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.shared.*;

import java.util.*;

import static autobuild.bootstrap.Check.*;

final class StampTest{
    static void run() throws Exception{
        Catalog cat = catalog();
        ModuleSpec si = ModuleAnalyzer.analyze(blueprint(FIXTURES + "silicon8.txt"), cat, 10);
        for(int r = 0; r < 4; r++) check("silicon r" + r, Stamp.of(si, r, 10, 20, cat, Map.of(), 1), cat);
        ModuleSpec gen = ModuleAnalyzer.analyze(blueprint(FIXTURES + "generator1.txt"), cat, 10);
        for(int r = 0; r < 4; r++) check("generator r" + r, Stamp.of(gen, r, 3, 4, cat, Map.of(), 1), cat);
        Blueprint starter = blueprint(LOCAL + "starter.txt");
        if(starter != null){
            ModuleSpec st = ModuleAnalyzer.analyze(starter, cat, 10);
            for(int r = 0; r < 4; r++){
                Stamp s = Stamp.of(st, r, 0, 0, cat, Map.of(), 1);
                check("starter r" + r, s, cat);
                eq("starter markers kept free r" + r, 4, s.markers.size());
            }
        }

        // turned once: 15 x 7 becomes 7 x 15; the west coal inlet faces north... from below: dir 0 -> 1
        Stamp s1 = Stamp.of(si, 1, 0, 0, cat, Map.of(), 1);
        eq("turned size", "7x15", s1.w + "x" + s1.h);
        eq("coal inlet turns", 1, s1.inlets.get(0).dir);
        // swapping titanium conveyors
        Stamp plain = Stamp.of(si, 0, 0, 0, cat, Map.of("titanium-conveyor", "conveyor"), 1);
        yes("no titanium after the swap", plain.blocks.stream().noneMatch(p -> p.block.equals("titanium-conveyor")));
        // four quarter turns: the same blocks
        Set<String> a = new TreeSet<>(), b = new TreeSet<>();
        for(Placement p : Stamp.of(si, 0, 5, 5, cat, Map.of(), 1).blocks) a.add(p.toString());
        for(Placement p : Stamp.of(si, 4, 5, 5, cat, Map.of(), 1).blocks) b.add(p.toString());
        eq("full turn", a, b);

        ModuleSpec plant = ModuleAnalyzer.analyze(BuiltinModules.combustionPlant(), cat, 10);
        eq("plant ok", null, plant.error);
        eq("plant inlets", "[[coal]@(-1,1)>0]", plant.inlets.toString());
        near("plant power", 480, plant.powerGen);
        near("plant coal", 4, plant.inlets.get(0).rate);
        yes("plant needs a node", !plant.hasNode);
        eq("plant cost", "copper:220,lead:128", plant.cost.toString());

        ModuleSpec press = ModuleAnalyzer.analyze(BuiltinModules.graphitePress(), cat, 10);
        eq("press ok", null, press.error);
        near("press coal", 2 * 60 / 90.0, press.inlets.get(0).rate);
        near("press graphite", 60 / 90.0, press.outputs.get("graphite"));
        eq("press outlet", "[[graphite]@(4,0)>0]", press.outlets.toString());
    }

    /** blocks inside the footprint, not overlapping; edge ports attach just outside, pointing in (out) */
    static void check(String what, Stamp s, Catalog cat){
        Set<Long> used = new HashSet<>();
        for(Placement p : s.blocks){
            BlockInfo b = cat.block(p.block);
            for(int x = p.x + b.lo(); x <= p.x + b.hi(); x++) for(int y = p.y + b.lo(); y <= p.y + b.hi(); y++){
                if(!s.inside(x, y)){ yes(what + ": " + p + " inside", false); return; }
                if(!used.add(((long)x << 32) | (y & 0xffffffffL))){ yes(what + ": " + p + " overlaps", false); return; }
            }
        }
        eq(what + " inlets", s.spec.inlets.size(), s.inlets.size());
        for(StampPort p : s.inlets) if(!p.port.marker){
            yes(what + " inlet " + p.x + "," + p.y + " outside", !s.inside(p.x, p.y));
            yes(what + " inlet points in", s.inside(p.x + Dir.DX[p.dir], p.y + Dir.DY[p.dir]));
        }
        for(StampPort p : s.outlets) if(!p.port.marker){
            yes(what + " outlet outside", !s.inside(p.x, p.y));
            yes(what + " outlet comes from inside", s.inside(p.x - Dir.DX[p.dir], p.y - Dir.DY[p.dir]));
        }
    }
}
