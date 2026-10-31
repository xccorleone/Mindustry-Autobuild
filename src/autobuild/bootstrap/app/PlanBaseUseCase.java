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
 * titanium) or, for graphite when no module making it can be built, a seed module (a graphite press),
 * and the base is planned again. Pure (any thread); the game side fills {@link Input}.
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
        goal.first.clear();
        goal.first.addAll(in.stage.first);
        // first-product modules are placed first: the best spots by their ore
        goal.modules.sort(Comparator.comparing(m -> !goal.first(m.spec)));
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
            if(!added && r.schedule.missing.contains("graphite") && !r.seeded && !makes(r, "graphite")){
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

    /**
     * Balance, lay out, cut into steps and order them. Ore the layout could not use (no site, too little
     * for its way, no corridor) is lost to the lines it was balanced for: the patches are left out and
     * it all runs again (at most {@link #REBALANCE} times), keeping the better one ({@link #better}).
     */
    static void layout(Input in, Result r, ProductionGoal goal, LayoutOptions o){
        Set<OrePatch> gone = new LinkedHashSet<>();
        attempt(in, r, goal, o, r.survey);
        for(int k = 0; k < REBALANCE && !r.plan.failed.isEmpty(); k++){
            if(!gone.addAll(r.plan.failed)) break;
            Result t = new Result();
            attempt(in, t, goal, o, r.survey.without(gone));
            if(!better(t, r, in.stage)) break;
            r.production = t.production;
            r.plan = t.plan;
            r.steps = t.steps;
            r.schedule = t.schedule;
            r.placements = t.placements;
        }
    }

    /** fewer steps stuck; then more made in the end (by a hundredth at least); then done sooner */
    static boolean better(Result a, Result b, StageOptions o){
        if(a.schedule.stuck.size() != b.schedule.stuck.size()) return a.schedule.stuck.size() < b.schedule.stuck.size();
        double va = StagePlanner.value(a.schedule.end, o), vb = StagePlanner.value(b.schedule.end, o);
        if(Math.abs(va - vb) > 0.01 * Math.max(va, vb)) return va > vb;
        return a.schedule.time() < b.schedule.time() - 1e-6;
    }

    /** balancing again without the ore the layout could not use */
    static final int REBALANCE = 2;

    static void attempt(Input in, Result r, ProductionGoal goal, LayoutOptions o, SurveyService.Report survey){
        r.production = ChainSolver.solve(goal, survey, o.cat);
        r.plan = BasePlanner.plan(in.terrain, survey, r.production, o);
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

    /** a module making this item is planned and can be built (then a seed making it would not help) */
    static boolean makes(Result r, String item){
        for(Step s : r.steps.steps){
            if(s.kind == Step.Kind.MODULE && r.production.instances.get(s.instance).spec.outputs.containsKey(item) && !r.schedule.stuck.contains(s)) return true;
        }
        return false;
    }

    static boolean ok(Input in, String block){
        return in.cat.block(block) != null && (in.available == null || in.available.contains(block));
    }
}
