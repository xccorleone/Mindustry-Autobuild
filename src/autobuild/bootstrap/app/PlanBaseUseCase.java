package autobuild.bootstrap.app;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.layout.*;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.production.*;
import autobuild.bootstrap.domain.shared.Placement;
import autobuild.bootstrap.domain.staging.*;
import autobuild.bootstrap.domain.survey.*;

import java.util.*;

/**
 * Plans a whole base: survey from the core, analyze the chosen blueprints, balance the goal against
 * the ore found, lay it out, cut it into steps and order them from the core's stock. When steps are
 * left that need an item nobody brings in, the goal gets a line to the core for it (copper, lead,
 * titanium) or, for graphite, a seed module (a graphite press), and the base is planned again. Pure (any thread); the game side fills {@link Input}.
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
        /** items in the core; building costs nothing */
        public final Map<String, Integer> stock = new TreeMap<>();
        public boolean infinite;
        public StageOptions stage = new StageOptions();
    }

    public static final class Result{
        public SurveyService.Report survey;
        public ProductionPlan production;
        public BasePlan plan;
        public LayoutOptions options;
        public List<Placement> placements = List.of();
        public StepGraph steps;
        public Schedule schedule;
        public StageOptions stage;
        /** the seed module was added */
        public boolean seeded;
        /** added to the goal so that the steps can be paid for: raw items mined to the core, the seed module */
        public final List<String> seeds = new ArrayList<>();
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
        in.stage.infinite |= in.infinite;
        r.stage = in.stage;
        layout(in, r, goal, o);
        // building needs what nobody makes: a line to the core for a raw material, then a graphite press
        for(int round = 0; round < 4 && !r.schedule.stuck.isEmpty(); round++){
            boolean added = false;
            for(String it : RAW_SEEDS){
                if(!r.schedule.missing.contains(it) || goal.raw.getOrDefault(it, 0) > 0) continue;
                goal.raw.put(it, 1);
                r.seeds.add(it);
                added = true;
            }
            if(!added && r.schedule.missing.contains("graphite") && !r.seeded){
                ModuleSpec seed = ModuleAnalyzer.analyze(BuiltinModules.graphitePress(), cat, cap);
                if(seed.ok()){
                    goal.modules.add(new ProductionGoal.ModuleOrder(seed, 1));
                    r.seeds.add(seed.name);
                    r.seeded = added = true;
                }
            }
            if(!added) break;
            layout(in, r, goal, o);
        }
        return r;
    }

    /** building materials mined straight to the core when the steps need them and nothing brings them in */
    static final List<String> RAW_SEEDS = List.of("copper", "lead", "titanium");

    /** balance, lay out, cut into steps and order them */
    static void layout(Input in, Result r, ProductionGoal goal, LayoutOptions o){
        r.production = ChainSolver.solve(goal, r.survey, o.cat);
        r.plan = BasePlanner.plan(in.terrain, r.survey, r.production, o);
        r.steps = StepGraph.of(r.plan, r.production, o, StepGraph.defaultDowngrade(o.cat));
        StageState start = new StageState(r.steps);
        in.stock.forEach((it, n) -> start.stock.put(it, (double)n));
        r.schedule = StagePlanner.simulate(r.steps, start, in.stage);
        // in the order they will be built, the rest after
        Set<Step> seen = new LinkedHashSet<>();
        for(Schedule.Entry e : r.schedule.entries) if(e.step.kind != Step.Kind.UPGRADE) seen.add(e.step);
        for(Step s : r.steps.steps) if(s.kind != Step.Kind.UPGRADE) seen.add(s);
        List<Placement> out = new ArrayList<>();
        for(Step s : seen) out.addAll(s.placements);
        r.placements = out;
    }

    static boolean ok(Input in, String block){
        return in.cat.block(block) != null && (in.available == null || in.available.contains(block));
    }
}
