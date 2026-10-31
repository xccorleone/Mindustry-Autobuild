package autobuild.bootstrap;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.layout.*;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.production.*;
import autobuild.bootstrap.domain.shared.Placement;
import autobuild.bootstrap.domain.survey.*;

import java.util.*;

import static autobuild.bootstrap.Check.*;

final class LayoutTest{
    static final int W = 90, H = 70;

    /** core in the middle; sand south-west, coal south-east, copper north-east, a lake north-west, a wall with a gap */
    static GridTerrain map(long seed){
        char[][] g = new char[W][H];
        Random r = new Random(seed);
        for(char[] col : g) Arrays.fill(col, '.');
        fill(g, 5, 5, 24, 20, 's');
        fill(g, 60, 5, 79, 18, 'k');
        fill(g, 60, 50, 75, 62, 'c');
        fill(g, 70, 30, 80, 40, 'l');
        fill(g, 5, 50, 15, 62, '~');
        fill(g, 8, 54, 12, 59, 'W');
        for(int y = 22; y <= 48; y++) if(y < 33 || y > 37) g[32][y] = '#';
        // scattered rocks
        for(int i = 0; i < 60; i++){
            int x = r.nextInt(W), y = r.nextInt(H);
            if(g[x][y] == '.') g[x][y] = '#';
        }
        fill(g, 44, 34, 46, 36, 'C');
        String[] rows = new String[H];
        for(int y = 0; y < H; y++){
            StringBuilder s = new StringBuilder();
            for(int x = 0; x < W; x++) s.append(g[x][y]);
            rows[H - 1 - y] = s.toString();
        }
        return new GridTerrain(rows);
    }

    static void fill(char[][] g, int x0, int y0, int x1, int y1, char c){
        for(int x = x0; x <= x1; x++) for(int y = y0; y <= y1; y++) g[x][y] = c;
    }

    static LayoutOptions options(String belt) throws Exception{
        Catalog cat = catalog();
        LayoutOptions o = new LayoutOptions(cat);
        o.belt = belt;
        o.drills.add(cat.block("mechanical-drill"));
        o.drills.add(cat.block("pneumatic-drill"));
        if(!belt.equals("titanium-conveyor")) o.swap.put("titanium-conveyor", belt);
        return o;
    }

    static ProductionGoal goal(LayoutOptions o, ModuleSpec power, ModuleSpec... modules){
        ProductionGoal g = new ProductionGoal();
        for(ModuleSpec m : modules) g.modules.add(new ProductionGoal.ModuleOrder(m, 1));
        g.power = power;
        g.raw.put("copper", 1);
        g.drills.addAll(o.drills);
        g.pump = o.cat.block(o.pump);
        g.belt = o.beltSpeed();
        g.coverage = o.coverage;
        return g;
    }

    static void run() throws Exception{
        Catalog cat = catalog();
        ModuleSpec si5 = ModuleAnalyzer.analyze(blueprint(FIXTURES + "silicon8.txt"), cat, 5);
        ModuleSpec si10 = ModuleAnalyzer.analyze(blueprint(FIXTURES + "silicon8.txt"), cat, 10);
        ModuleSpec plant = ModuleAnalyzer.analyze(BuiltinModules.combustionPlant(), cat, 5);
        ModuleSpec turbine = ModuleAnalyzer.analyze(blueprint(FIXTURES + "generator1.txt"), cat, 10);
        ModuleSpec press = ModuleAnalyzer.analyze(BuiltinModules.graphitePress(), cat, 5);

        // basic belts: silicon + combustion plant + graphite press + copper to the core
        for(long seed = 1; seed <= 3; seed++){
            GridTerrain t = map(seed);
            LayoutOptions o = options("conveyor");
            BasePlan plan = layout("basic #" + seed, t, new SurveyService.Options(), o, goal(o, plant, si5, press), seed == 1);
            Forecast f = plan.forecast;
            yes("basic #" + seed + " silicon reaches the core", f.toCore.getOrDefault("silicon", 0.0) > 1);
            yes("basic #" + seed + " graphite reaches the core", f.toCore.getOrDefault("graphite", 0.0) > 0.3);
            yes("basic #" + seed + " copper reaches the core", f.toCore.getOrDefault("copper", 0.0) > 1);
            yes("basic #" + seed + " silicon at most a belt", f.toCore.getOrDefault("silicon", 0.0) <= 5 + 1e-6);
            yes("basic #" + seed + " plant powered", f.powerMade >= f.powerUse - 1e-6);
            yes("basic #" + seed + " no titanium conveyor", plan.ordered(cat).stream().noneMatch(p -> p.block.equals("titanium-conveyor")));
        }

        // titanium belts and the turbine plant on water
        {
            GridTerrain t = map(7);
            LayoutOptions o = options("titanium-conveyor");
            BasePlan plan = layout("turbine", t, new SurveyService.Options(), o, goal(o, turbine, si10), false);
            yes("turbine: pumps built", plan.zones.stream().anyMatch(z -> z.kind == Zone.Kind.WATER));
            yes("turbine: silicon", plan.forecast.toCore.getOrDefault("silicon", 0.0) > 1);
        }

        // the player's workshop modules (local fixtures): marker ports and a mixed outlet, kilns and graphite
        Blueprint starter = blueprint(LOCAL + "starter.txt"), graphite = blueprint(LOCAL + "graphite4.txt"), glass = blueprint(LOCAL + "metaglass4.txt");
        if(starter != null && graphite != null && glass != null){
            GridTerrain t = map(5);
            LayoutOptions o = options("conveyor");
            ModuleSpec st = ModuleAnalyzer.analyze(starter, cat, 5), gr = ModuleAnalyzer.analyze(graphite, cat, 5), mg = ModuleAnalyzer.analyze(glass, cat, 5);
            BasePlan plan = layout("workshop", t, new SurveyService.Options(), o, goal(o, plant, st, gr, mg), true);
            yes("workshop: metaglass", plan.forecast.toCore.getOrDefault("metaglass", 0.0) > 0.3);
            yes("workshop: graphite", plan.forecast.toCore.getOrDefault("graphite", 0.0) > 0.3);
        }

        // a danger band through the middle: nothing built in it
        {
            GridTerrain t = map(11);
            SurveyService.Options so = new SurveyService.Options();
            List<int[]> route = new ArrayList<>();
            for(int x = 0; x < W; x++) route.add(new int[]{x, 26});
            so.danger = DangerMask.around(W, H, route, 1.2f); // 3 tiles: bridges jump it
            LayoutOptions o = options("conveyor");
            BasePlan plan = layout("danger", t, so, o, goal(o, plant, si5), false);
            yes("danger: silicon still made", plan.forecast.toCore.getOrDefault("silicon", 0.0) > 1);
        }
    }

    static BasePlan layout(String what, GridTerrain t, SurveyService.Options so, LayoutOptions o, ProductionGoal g, boolean print){
        SurveyService.Report survey = SurveyService.survey(t, so);
        ProductionPlan prod = ChainSolver.solve(g, survey, o.cat);
        long t0 = System.currentTimeMillis();
        BasePlan plan = BasePlanner.plan(t, survey, prod, o);
        long ms = System.currentTimeMillis() - t0;
        System.out.println(what + ": " + plan.forecast + " | " + plan.zones.size() + " zones, " + plan.corridors.size() + " corridors, "
            + plan.nodes.size() + " nodes in " + ms + "ms" + (plan.notes.isEmpty() ? "" : " | notes: " + plan.notes));
        LayoutChecks.all(what, plan, survey, o.cat, t.core());
        if(print) System.out.println(ascii(t, plan, o.cat));
        return plan;
    }

    /** the map with the plan: module blocks M, drills D, belts > ^ < v, bridges =, conduits ~, pumps P, nodes + */
    static String ascii(GridTerrain t, BasePlan plan, Catalog cat){
        char[][] g = new char[t.w][t.h];
        for(int x = 0; x < t.w; x++) for(int y = 0; y < t.h; y++){
            char c = t.g[x][y];
            g[x][y] = c == '.' ? ' ' : c == '#' ? '#' : c == 'C' ? 'C' : c == '~' || c == 'W' ? ',' : '.';
        }
        for(Zone z : plan.zones) for(Placement p : z.placements) draw(g, p, cat, z.stamp != null ? 'M' : null);
        for(Corridor c : plan.corridors) for(Placement p : c.placements) draw(g, p, cat, null);
        for(Placement p : plan.nodes) draw(g, p, cat, '+');
        StringBuilder s = new StringBuilder();
        for(int y = t.h - 1; y >= 0; y--){
            for(int x = 0; x < t.w; x++) s.append(g[x][y]);
            s.append('\n');
        }
        return s.toString();
    }

    static void draw(char[][] g, Placement p, Catalog cat, Character as){
        BlockInfo b = cat.block(p.block);
        char c = as != null ? as : switch(b.kind){
            case "conveyor" -> ">^<v".charAt(p.rot);
            case "bridge", "liquidbridge" -> '=';
            case "conduit" -> '~';
            case "drill" -> 'D';
            case "pump" -> 'P';
            default -> '?';
        };
        for(int x = p.x + b.lo(); x <= p.x + b.hi(); x++) for(int y = p.y + b.lo(); y <= p.y + b.hi(); y++) if(x >= 0 && y >= 0 && x < g.length && y < g[0].length) g[x][y] = c;
    }
}
