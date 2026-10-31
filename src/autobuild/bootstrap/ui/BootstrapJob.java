package autobuild.bootstrap.ui;

import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import autobuild.*;
import autobuild.bootstrap.app.PlanBaseUseCase;
import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.infra.*;
import mindustry.content.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Third tab, "Bootstrap": the selected region is the base area. The player picks how many of each
 * module (built-in or their own schematics), a power plant, raw lines to the core and the belt; the
 * whole end-state base is planned at once and previewed, and "Build" queues all of it (building
 * step by step as the core's stock allows comes with the director, M3).
 */
public class BootstrapJob extends BuildJob{
    /** module name -> count */
    public final ObjectIntMap<String> counts = new ObjectIntMap<>();
    public String power = BuiltinModules.COMBUSTION;
    public final ObjectIntMap<String> raw = new ObjectIntMap<>();
    public Block belt = Blocks.conveyor;
    public boolean avoidEnemies = true;

    Catalog catalog;
    /** every module the player can pick, by name (built-in first), and their analysis */
    final OrderedMap<String, Blueprint> blueprints = new OrderedMap<>();
    final ObjectMap<String, ModuleSpec> specs = new ObjectMap<>();
    float specCap = -1;

    public BootstrapJob(){
        counts.put("Silicon Smelter (8)", 1);
        raw.put("copper", 1);
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
        return any && belt != null;
    }

    /** main thread: the catalog, the blueprints (built-in + schematics) and their analysis at the chosen belt */
    void load(){
        if(catalog == null) catalog = GamePlans.liveCatalog();
        if(blueprints.isEmpty()){
            Blueprint si = GameBlueprints.builtin(GameBlueprints.SILICON, catalog), turbine = GameBlueprints.builtin(GameBlueprints.TURBINE, catalog);
            if(si != null) blueprints.put(si.name, si);
            Blueprint press = BuiltinModules.graphitePress(), plant = BuiltinModules.combustionPlant();
            blueprints.put(press.name, press);
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
        for(var e : counts) if(e.value > 0 && blueprints.containsKey(e.key)) in.modules.add(new PlanBaseUseCase.Order(blueprints.get(e.key), e.value));
        in.power = blueprints.get(power);
        for(var e : raw) if(e.value > 0) in.raw.put(e.key, e.value);
        in.belt = belt.name;
        in.available = new HashSet<>();
        for(Block b : content.blocks()) if(available(b)) in.available.add(b.name);
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
