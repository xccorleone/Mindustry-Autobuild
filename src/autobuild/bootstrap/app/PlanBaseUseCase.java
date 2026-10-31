package autobuild.bootstrap.app;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.layout.*;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.production.*;
import autobuild.bootstrap.domain.shared.Placement;
import autobuild.bootstrap.domain.survey.*;

import java.util.*;

/**
 * Plans a whole base: survey from the core, analyze the chosen blueprints, balance the goal against
 * the ore found, lay it out. Pure (any thread); the game side fills {@link Input}.
 */
public final class PlanBaseUseCase{
    public static final class Order{
        public final Blueprint blueprint;
        public final int count;

        public Order(Blueprint blueprint, int count){
            this.blueprint = blueprint;
            this.count = count;
        }
    }

    public static final class Input{
        public Terrain terrain;
        public Catalog cat;
        /** tiles nothing may be built on, or null; base area {x0, y0, x1, y1} or null */
        public boolean[][] danger;
        public int[] base;
        public final List<Order> modules = new ArrayList<>();
        /** power plant module (null: none) */
        public Blueprint power;
        /** item -> belt lines mined to the core */
        public final Map<String, Integer> raw = new TreeMap<>();
        public String belt = "conveyor";
        /** blocks the player may build (null: all) */
        public Set<String> available;
    }

    public static final class Result{
        public SurveyService.Report survey;
        public ProductionPlan production;
        public BasePlan plan;
        public LayoutOptions options;
        public List<Placement> placements = List.of();
        /** blueprint name -> why it can't be used */
        public final Map<String, String> unusable = new TreeMap<>();
        public String error;
    }

    private PlanBaseUseCase(){}

    public static Result run(Input in){
        Result r = new Result();
        Catalog cat = in.cat;
        LayoutOptions o = new LayoutOptions(cat);
        o.belt = in.belt;
        o.bridge = ok(in, "bridge-conveyor") ? "bridge-conveyor" : null;
        o.conduit = ok(in, "conduit") ? "conduit" : null;
        o.liquidBridge = ok(in, "bridge-conduit") ? "bridge-conduit" : null;
        o.node = ok(in, "power-node") ? "power-node" : null;
        o.pump = ok(in, "mechanical-pump") ? "mechanical-pump" : null;
        for(BlockInfo b : cat.blocks()) if(b.kind.equals("drill") && b.power <= 0 && b.liquid == null && ok(in, b.name)) o.drills.add(b);
        if(!in.belt.equals("titanium-conveyor")) o.swap.put("titanium-conveyor", in.belt);
        r.options = o;
        double cap = o.beltSpeed();

        ProductionGoal goal = new ProductionGoal();
        for(Order m : in.modules){
            if(m.count <= 0) continue;
            ModuleSpec spec = ModuleAnalyzer.analyze(m.blueprint, cat, cap);
            if(!spec.ok()){ r.unusable.put(spec.name, spec.error); continue; }
            goal.modules.add(new ProductionGoal.ModuleOrder(spec, m.count));
        }
        if(in.power != null){
            ModuleSpec p = ModuleAnalyzer.analyze(in.power, cat, cap);
            if(p.ok() && p.powerGen > 0) goal.power = p;
            else r.unusable.put(p.name, p.ok() ? "makes no power" : p.error);
        }
        goal.raw.putAll(in.raw);
        goal.drills.addAll(o.drills);
        goal.pump = o.pump == null ? null : cat.block(o.pump);
        goal.belt = cap;
        goal.coverage = o.coverage;
        if(goal.modules.isEmpty() && goal.raw.isEmpty()){
            r.error = "nothing to build";
            return r;
        }

        SurveyService.Options so = new SurveyService.Options();
        so.bridgeRange = o.bridgeRange();
        so.base = in.base;
        so.danger = in.danger;
        r.survey = SurveyService.survey(in.terrain, so);
        r.production = ChainSolver.solve(goal, r.survey, cat);
        r.plan = BasePlanner.plan(in.terrain, r.survey, r.production, o);
        r.placements = r.plan.ordered(cat);
        return r;
    }

    static boolean ok(Input in, String block){
        return in.cat.block(block) != null && (in.available == null || in.available.contains(block));
    }
}
