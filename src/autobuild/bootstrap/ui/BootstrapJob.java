package autobuild.bootstrap.ui;

import arc.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import autobuild.*;
import autobuild.bootstrap.app.*;
import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.shared.*;
import autobuild.bootstrap.domain.staging.*;
import autobuild.bootstrap.infra.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Third tab, "Bootstrap": the selected region is the base area. The player picks how many of each
 * module (built-in or their own schematics; at first one of each of {@link #DEFAULTS}, the one making
 * most of it), a power plant, raw lines to the core and the belt; the
 * whole end-state base is planned at once, cut into steps ordered from the core's stock, and
 * previewed with the step numbers. "Build" starts the {@link Director}: it hands the player's unit
 * one step after another as the core can pay for them ({@link DirectorHud} shows how far it is).
 */
public class BootstrapJob extends BuildJob{
    /** products a module is picked for at first: the one making most of it alone */
    static final String[] DEFAULTS = {"graphite", "silicon", "metaglass"};

    /** module name -> count (the defaults are picked once the blueprints are read) */
    public final ObjectIntMap<String> counts = new ObjectIntMap<>();
    boolean picked;
    public String power = BuiltinModules.COMBUSTION;
    public final ObjectIntMap<String> raw = new ObjectIntMap<>();
    /** null until the content is loaded (the job is made with the mod): then the plain conveyor */
    public Block belt;
    public boolean avoidEnemies = true;

    Catalog catalog;
    /** every module the player can pick, by name (built-in first), and their analysis */
    final OrderedMap<String, Blueprint> blueprints = new OrderedMap<>();
    final ObjectMap<String, ModuleSpec> specs = new ObjectMap<>();
    float specCap = -1;

    /** building the confirmed base (null: nothing confirmed, or a new world) */
    public Director director;
    GameSite site;

    public BootstrapJob(){
        raw.put("copper", 1);
        Events.on(WorldLoadEvent.class, e -> {
            if(director != null) Log.info("[autobuild] bootstrap: a new world, the director stops");
            director = null;
        });
        Events.run(Trigger.update, () -> {
            if(director != null && state.isGame() && !state.isPaused()) director.update(Time.delta / 60f);
        });
        Events.on(ClientLoadEvent.class, e -> DirectorHud.build(this));
    }

    @Override
    public String name(){ return Tr.t("boot.name"); }

    @Override
    public Seq<PortKind> portKinds(){ return new Seq<>(); }

    @Override
    public boolean ready(){
        boolean any = false;
        for(var e : counts) any |= e.value > 0;
        for(var e : raw) any |= e.value > 0;
        return any;
    }

    /** main thread: the catalog, the blueprints (built-in + schematics) and their analysis at the chosen belt */
    void load(){
        if(belt == null) belt = Blocks.conveyor;
        if(catalog == null) catalog = GamePlans.liveCatalog();
        if(blueprints.isEmpty()){
            Blueprint si = GameBlueprints.builtin(GameBlueprints.SILICON, catalog), turbine = GameBlueprints.builtin(GameBlueprints.TURBINE, catalog);
            if(si != null) blueprints.put(si.name, si);
            Blueprint press = BuiltinModules.graphitePress(), kiln = BuiltinModules.kiln(), plant = BuiltinModules.combustionPlant();
            blueprints.put(press.name, press);
            blueprints.put(kiln.name, kiln);
            blueprints.put(plant.name, plant);
            if(turbine != null) blueprints.put(turbine.name, turbine);
            for(Blueprint b : GameBlueprints.schematics()) if(!blueprints.containsKey(b.name)) blueprints.put(b.name, b);
        }
        float cap = ((mindustry.world.blocks.distribution.Conveyor)belt).displayedSpeed;
        if(cap != specCap){
            specs.clear();
            for(var e : blueprints){
                try{
                    specs.put(e.key, ModuleAnalyzer.analyze(e.value, catalog, cap));
                }catch(Throwable t){
                    Log.err("[autobuild] blueprint @ not analyzed: @", e.key, t);
                }
            }
            specCap = cap;
        }
        if(!picked){
            picked = true;
            for(String item : DEFAULTS){
                String best = null;
                double rate = 0;
                for(var e : specs){
                    ModuleSpec s = e.value;
                    if(!product(s) || s.outputs.size() != 1 || !s.outputs.containsKey(item)) continue;
                    if(s.outputs.get(item) > rate + 1e-9){
                        rate = s.outputs.get(item);
                        best = e.key;
                    }
                }
                if(best != null) counts.put(best, 1);
            }
        }
    }

    boolean product(ModuleSpec s){ return s != null && s.ok() && !s.outputs.isEmpty(); }

    boolean plant(ModuleSpec s){ return s != null && s.ok() && s.powerGen > 0 && s.outputs.isEmpty(); }

    @Override
    public void settings(Table c, Runnable rebuild){
        load();
        c.add(Tr.t("boot.area")).row();
        c.add(Tr.t("boot.modules")).row();
        c.table(t -> {
            for(var e : blueprints){
                ModuleSpec s = specs.get(e.key);
                if(!product(s)) continue;
                String name = e.key;
                StringBuilder out = new StringBuilder();
                s.outputs.forEach((item, rate) -> out.append(out.length() > 0 ? Tr.t("sep") : "").append(label(item)).append(' ').append(Strings.autoFixed(rate.floatValue(), 1)).append("/s"));
                t.add(name + " [lightgray]" + out).left().growX().padRight(8f);
                t.button("-", Styles.flatt, () -> { counts.put(name, Math.max(0, counts.get(name, 0) - 1)); rebuild.run(); }).size(40f);
                t.add(String.valueOf(counts.get(name, 0))).width(30f).center();
                t.button("+", Styles.flatt, () -> { counts.put(name, counts.get(name, 0) + 1); rebuild.run(); }).size(40f).row();
            }
        }).growX().row();

        c.add(Tr.t("boot.power")).row();
        c.table(t -> {
            int i = 0;
            for(var e : blueprints){
                ModuleSpec s = specs.get(e.key);
                if(!plant(s)) continue;
                String name = e.key;
                t.button(name + " [lightgray]" + Strings.autoFixed((float)s.powerGen, 0) + "/s", Styles.flatTogglet, () -> { power = name; rebuild.run(); })
                    .checked(b -> name.equals(power)).size(320f, 40f);
                if(++i % 2 == 0) t.row();
            }
        }).row();

        c.table(t -> {
            t.add(Tr.t("boot.raw")).padRight(6f);
            for(Item it : Seq.with(Items.copper, Items.lead, Items.titanium)){
                t.image(it.uiIcon).size(24f).padLeft(10f);
                t.button("-", Styles.flatt, () -> { raw.put(it.name, Math.max(0, raw.get(it.name, 0) - 1)); rebuild.run(); }).size(36f);
                t.add(String.valueOf(raw.get(it.name, 0))).width(24f).center();
                t.button("+", Styles.flatt, () -> { raw.put(it.name, raw.get(it.name, 0) + 1); rebuild.run(); }).size(36f);
            }
        }).row();

        c.table(t -> {
            t.add(Tr.t("boot.belt")).padRight(6f);
            for(Block b : Seq.with(Blocks.conveyor, Blocks.titaniumConveyor)){
                if(!available(b)) continue;
                t.button(bt -> bt.image(b.uiIcon).size(24f), Styles.flatTogglet, () -> { belt = b; rebuild.run(); })
                    .checked(bt -> belt == b).size(44f, 40f).tooltip(b.localizedName);
            }
            t.check(Tr.t("boot.avoid"), avoidEnemies, v -> avoidEnemies = v).padLeft(12f);
        }).row();

        StringBuilder bad = new StringBuilder();
        for(var e : specs) if(!e.value.ok()) bad.append("\n").append(e.key).append(": ").append(e.value.error);
        if(bad.length() > 0) c.add(Tr.t("boot.unusable") + "[lightgray]" + bad).left().wrap().width(640f).row();
    }

    /** main thread: everything the planner needs, copied */
    @Override
    public Object prepare(){
        error = null;
        cancelled = false;
        load();
        PlanBaseUseCase.Input in = new PlanBaseUseCase.Input();
        in.cat = catalog;
        in.terrain = GameTerrain.snapshot(team);
        if(in.terrain == null){ error = Tr.t("boot.noCore"); return null; }
        in.base = new int[]{ax, ay, bx, by};
        for(var e : blueprints) if(counts.get(e.key, 0) > 0) in.modules.add(new PlanBaseUseCase.Order(e.value, counts.get(e.key, 0)));
        in.power = blueprints.get(power);
        for(var e : raw) if(e.value > 0) in.raw.put(e.key, e.value);
        in.belt = belt.name;
        in.available = new HashSet<>();
        for(Block b : content.blocks()) if(available(b)) in.available.add(b.name);
        // the steps are ordered from what the core holds now
        mindustry.world.blocks.storage.CoreBlock.CoreBuild core = team.core();
        if(core != null){
            for(Item it : content.items()) if(core.items.get(it) > 0) in.stock.put(it.name, core.items.get(it));
            in.stage.capacity = core.storageCapacity;
        }
        in.infinite = state.rules.infiniteResources || state.rules.teams.get(team).infiniteResources;
        // the routes enemies walk and fly, plus what ground units shoot from there
        if(avoidEnemies && state.rules.waves) in.danger = GamePlans.danger(8f, 15f);
        return in;
    }

    @Override
    public PlanResult search(Object snapshot, int variantBase, int variants, Progress progress){
        Result r = new Result();
        try{
            r.run = PlanBaseUseCase.run((PlanBaseUseCase.Input)snapshot);
        }catch(Throwable t){
            Log.err("[autobuild] bootstrap planning failed", t);
            r.error = String.valueOf(t.getMessage());
            return r;
        }
        if(r.run.error != null){ r.error = Tr.t("boot.nothing"); return r; }
        r.plans.addAll(GamePlans.toBuildPlans(r.run.placements));
        if(r.plans.isEmpty()) r.error = Tr.t("boot.nothing");
        if(progress != null) progress.frame(Tr.t("boot.name"), r.plans);
        return r;
    }

    @Override
    public void committed(PlanResult result){
        GamePlans.watchBridges(result.plans);
    }

    /** "Build": the director takes over, one step at a time */
    @Override
    public boolean commit(PlanResult result){
        PlanBaseUseCase.Result run = ((Result)result).run;
        if(director != null) director.cancel();
        site = GameSite.player(team, StepGraph.defaultDowngrade(catalog));
        director = new Director(run.steps, run.stage, site, new Director.Events(){
            @Override public void started(Step step, boolean plain, double time){
                Log.info("[autobuild] bootstrap @s: step @ started@", (int)time, step, plain ? " (stand-ins)" : "");
            }

            @Override public void finished(Step step, double time){
                Log.info("[autobuild] bootstrap @s: step @ done", (int)time, step);
            }

            @Override public void blocked(Step step, Placement at){
                Log.info("[autobuild] bootstrap: step @ blocked at @", step, at);
                ui.showInfoToast(Tr.t("boot.hud.blockedToast", stepName(step)), 4f);
            }

            @Override public void repaired(Step step, int blocks){
                Log.info("[autobuild] bootstrap: @ blocks of @ queued again", blocks, step);
            }

            @Override public void done(double time){
                Log.info("[autobuild] bootstrap @s: all steps done", (int)time);
                ui.showInfoToast(Tr.t("boot.hud.doneToast"), 4f);
            }
        });
        director.tick();
        ui.showInfoToast(Tr.t("boot.started", run.steps.steps.size()), 3f);
        return true;
    }

    /** the step numbers on the map, at the middle of each step's blocks */
    @Override
    public void drawOverlay(PlanResult shown){
        if(!(shown instanceof Result r) || !r.ok()) return;
        int n = 0;
        for(Step s : order(r.run)){
            n++;
            float x = 0, y = 0;
            for(Placement p : s.placements){ x += p.x; y += p.y; }
            if(s.placements.isEmpty()) continue;
            x = x / s.placements.size() * tilesize;
            y = y / s.placements.size() * tilesize;
            WorldLabel.drawAt(String.valueOf(n), x, y, Layer.overlayUI + 1f, WorldLabel.flagOutline | WorldLabel.flagBackground, 1.2f, Align.center, Align.center);
        }
    }

    /** the steps as they will be built (upgrades left out: they stand where their step does) */
    static Seq<Step> order(PlanBaseUseCase.Result run){
        Seq<Step> out = new Seq<>();
        for(Schedule.Entry e : run.schedule.entries) if(e.step.kind != Step.Kind.UPGRADE && !out.contains(e.step)) out.add(e.step);
        for(Step s : run.schedule.stuck) if(s.kind != Step.Kind.UPGRADE && !out.contains(s)) out.add(s);
        return out;
    }

    /** a step in the player's language */
    public static String stepName(Step s){
        return switch(s.kind){
            case NODES -> Tr.t("boot.step.nodes");
            case MODULE, PLANT -> s.what;
            case MINE, WATER -> Tr.t("boot.step.line", label(s.what), s.to == null ? Tr.t("boot.step.core") : s.to);
            case UPGRADE -> Tr.t("boot.step.upgrade", s.what.contains(" to ") ? upgraded(s) : s.what);
        };
    }

    /** an upgrade's name from its step's */
    static String upgraded(Step u){
        String[] w = u.what.split(" to ", 2);
        return Tr.t("boot.step.line", label(w[0]), w[1].equals("the core") ? Tr.t("boot.step.core") : w[1]);
    }

    /** "120 copper, 40 lead" with icons */
    public static String cost(Bom b){
        StringBuilder sb = new StringBuilder();
        for(String it : b.items()){
            Item i = content.item(it);
            if(sb.length() > 0) sb.append(' ');
            sb.append(b.get(it)).append(i != null ? i.emoji() : it);
        }
        return sb.toString();
    }

    public static String time(double secs){
        int s = (int)Math.round(secs);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    static String label(String item){
        Item i = content.item(item);
        if(i != null) return i.localizedName;
        Liquid l = content.liquid(item);
        return l != null ? l.localizedName : item;
    }

    public class Result extends PlanResult{
        public PlanBaseUseCase.Result run;

        @Override
        public double score(){ return ok() ? 1 : Double.NEGATIVE_INFINITY; }

        @Override
        public String summary(){
            if(!ok()) return "[scarlet]" + error;
            var f = run.plan.forecast;
            StringBuilder sb = new StringBuilder(Tr.t("boot.sum.core"));
            f.toCore.forEach((item, rate) -> sb.append("\n  ").append(label(item)).append(" [accent]").append(Strings.autoFixed(rate.floatValue(), 2)).append("/s[]"));
            sb.append("\n").append(Tr.t("boot.sum.power", Strings.autoFixed((float)f.powerMade, 0), Strings.autoFixed((float)f.powerUse, 0)));
            sb.append("\n").append(Tr.t("boot.sum.blocks", plans.size, run.plan.zones.size(), run.plan.corridors.size()));
            // the steps: when each starts (from the core's stock and income), what it costs
            Schedule sc = run.schedule;
            sb.append("\n").append(Tr.t("boot.sum.steps", order(run).size, time(sc.time())));
            int shown = 0;
            boolean plain = false;
            for(Schedule.Entry e : sc.entries){
                if(shown++ >= 8){
                    sb.append("\n  ...");
                    break;
                }
                plain |= e.plain;
                sb.append("\n  [lightgray]").append(time(e.start)).append("[] ").append(stepName(e.step)).append(e.plain ? "*" : "")
                    .append(" [lightgray]").append(cost(e.cost)).append("[]");
            }
            if(plain) sb.append("\n[lightgray]").append(Tr.t("boot.sum.plain")).append("[]");
            if(!run.seeds.isEmpty()){
                StringBuilder seeds = new StringBuilder();
                for(String it : run.seeds) seeds.append(seeds.length() > 0 ? Tr.t("sep") : "").append(content.item(it) != null ? Tr.t("boot.step.line", label(it), Tr.t("boot.step.core")) : it);
                sb.append("\n[accent]").append(Tr.t("boot.sum.seeds", seeds)).append("[]");
            }
            if(!sc.stuck.isEmpty()){
                StringBuilder miss = new StringBuilder();
                for(String it : sc.missing) miss.append(miss.length() > 0 ? Tr.t("sep") : "").append(label(it));
                sb.append("\n[orange]").append(Tr.t("boot.sum.stuck", sc.stuck.size(), miss)).append("[]");
            }
            for(var e : run.unusable.entrySet()) sb.append("\n[scarlet]").append(e.getKey()).append(": ").append(e.getValue()).append("[]");
            if(!run.plan.notes.isEmpty()){
                sb.append("\n[lightgray]").append(Tr.t("boot.sum.notes", run.plan.notes.size()));
                for(int i = 0; i < Math.min(4, run.plan.notes.size()); i++) sb.append("\n  ").append(run.plan.notes.get(i));
                sb.append("[]");
            }
            return sb.toString();
        }

        @Override
        public String portText(){ return ""; }

        @Override
        public Seq<Port> ports(PortKind kind){ return new Seq<>(); }
    }
}
