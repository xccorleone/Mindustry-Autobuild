package autobuild;

import arc.struct.*;
import arc.util.*;
import autobuild.bootstrap.app.PlanBaseUseCase;
import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.production.SupplyLine;
import autobuild.bootstrap.domain.shared.Placement;
import autobuild.bootstrap.infra.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Dedicated-server check of the bootstrap layout without a GUI:
 * <pre>
 * abboot [si=N] [press=N] [bp=PATH:N,...] [power=combustion|turbine|none|PATH] [raw=copper:1,lead:1] [belt=NAME] [danger=RADIUS] [dump=PATH] [keepchain]
 *     plans a base around the core (built-in silicon module and plants, or blueprint files), places
 *     every block instantly in build order, turns waves off and remembers the core's items
 * abbootcount
 *     items per second the core gained since the previous count, next to the forecast
 * </pre>
 */
public class ServerBootstrapTest{
    static final ObjectIntMap<Item> last = new ObjectIntMap<>();
    static long lastTime;
    static Map<String, Double> forecast = Map.of();
    static List<autobuild.bootstrap.domain.layout.Zone> zones = List.of();

    static void register(CommandHandler handler){
        handler.register("abboot", "[options...]", "AutoBuild: plan and place a bootstrap base around the core. "
            + "options: si=N press=N bp=PATH:N,... power=combustion|turbine|none|PATH raw=copper:1,lead:1 belt=NAME danger=RADIUS", args -> {
            if(!state.isGame()){ Log.err("host a map first"); return; }
            // waves off first: the drop zone around enemy spawns blocks building while waves run
            state.rules.waves = false;
            state.rules.waveTimer = false;
            Catalog cat = GamePlans.liveCatalog();
            PlanBaseUseCase.Input in = new PlanBaseUseCase.Input();
            in.cat = cat;
            in.terrain = GameTerrain.snapshot(state.rules.defaultTeam);
            if(in.terrain == null){ Log.err("no core"); return; }
            int si = 1, press = 0;
            String power = "combustion", raw = "copper:1";
            float danger = 0;
            boolean keepChain = false;
            Seq<String> opts = new Seq<>();
            for(String a : args) for(String o : a.trim().split("\\s+")) if(!o.isEmpty()) opts.add(o);
            for(String opt : opts){
                String[] kv = opt.split("=", 2);
                switch(kv[0]){
                    case "si" -> si = Strings.parseInt(kv[1], 1);
                    case "press" -> press = Strings.parseInt(kv[1], 0);
                    case "power" -> power = kv[1];
                    case "raw" -> raw = kv[1];
                    case "belt" -> in.belt = kv[1];
                    case "danger" -> danger = Strings.parseFloat(kv[1], 0);
                    case "keepchain" -> keepChain = true; // leave the game's bridge chaining on (to see what it does)
                    case "dump" -> dump(in.terrain, kv[1]);
                    case "bp" -> {
                        for(String f : kv[1].split(",")){
                            int c = f.lastIndexOf(':');
                            Blueprint b = GameBlueprints.file(c > 0 ? f.substring(0, c) : f, cat);
                            if(b != null) in.modules.add(new PlanBaseUseCase.Order(b, c > 0 ? Strings.parseInt(f.substring(c + 1), 1) : 1));
                        }
                    }
                    default -> { Log.err("unknown option @", opt); return; }
                }
            }
            if(si > 0) in.modules.add(new PlanBaseUseCase.Order(GameBlueprints.builtin(GameBlueprints.SILICON, cat), si));
            if(press > 0) in.modules.add(new PlanBaseUseCase.Order(BuiltinModules.graphitePress(), press));
            in.power = switch(power){
                case "combustion" -> BuiltinModules.combustionPlant();
                case "turbine" -> GameBlueprints.builtin(GameBlueprints.TURBINE, cat);
                case "none" -> null;
                default -> GameBlueprints.file(power, cat);
            };
            if(!raw.isEmpty() && !raw.equals("none")) for(String r : raw.split(",")){
                String[] ic = r.split(":");
                in.raw.put(ic[0], ic.length > 1 ? Strings.parseInt(ic[1], 1) : 1);
            }
            if(danger > 0) in.danger = GamePlans.danger(danger, 15f);

            long t0 = Time.millis();
            PlanBaseUseCase.Result r = PlanBaseUseCase.run(in);
            if(r.error != null){ Log.err("[abboot] @", r.error); return; }
            Log.info("[abboot] planned in @ms: @ blocks", Time.timeSinceMillis(t0), r.placements.size());
            r.unusable.forEach((k, v) -> Log.info("[abboot] unusable @: @", k, v));
            for(SupplyLine l : r.production.lines) Log.info("[abboot] line @ (@ sources)", l, l.sources.size());
            for(var z : r.plan.zones) Log.info("[abboot] zone @", z);
            for(String n : r.plan.notes) Log.info("[abboot] note: @", n);
            Log.info("[abboot] forecast: @", r.plan.forecast);
            forecast = r.plan.forecast.toCore;
            zones = r.plan.zones;

            int failed = 0;
            for(Placement p : r.placements){
                BuildPlan bp = GamePlans.toBuildPlan(p);
                if(!BuildJob.placeable(bp, state.rules.defaultTeam)){
                    if(failed++ < 5) Log.err("[abboot] not placeable: @ at (@,@)", bp.block.name, bp.x, bp.y);
                    continue;
                }
                Tile t = world.tile(bp.x, bp.y);
                t.setBlock(bp.block, state.rules.defaultTeam, bp.rotation);
                // same sequence as ConstructBlock.constructFinish for a player-built plan
                if(t.build != null){
                    t.build.placed();
                    if(bp.config != null) t.build.configured(null, bp.config);
                    t.build.playerPlaced(bp.config);
                }
                if(!keepChain) GamePlans.forgetLastBridge(bp.block);
            }
            Log.info("[abboot] placed @ blocks, @ not placeable", r.placements.size() - failed, failed);
            // every bridge links where the plan says (the game re-links unlinked bridges on its own)
            int wrong = 0;
            for(Placement p : r.placements){
                if(!(world.build(p.x, p.y) instanceof mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild b)) continue;
                int want = p.config instanceof int[] d ? arc.math.geom.Point2.pack(p.x + d[0], p.y + d[1]) : -1;
                if(b.link != want && wrong++ < 3) Log.err("[abboot] bridge (@,@) links @, planned @", p.x, p.y, arc.math.geom.Point2.unpack(b.link), want == -1 ? "none" : arc.math.geom.Point2.unpack(want));
            }
            Log.info("[abboot] bridges linked as planned: @", wrong == 0 ? "all" : wrong + " wrong");
            remember();
        });

        handler.register("abbootcount", "AutoBuild: items per second the core gained since the last count", args -> {
            Building core = state.rules.defaultTeam.core();
            if(core == null || lastTime == 0){ Log.err("run abboot first"); return; }
            float secs = Time.timeSinceMillis(lastTime) / 1000f;
            StringBuilder s = new StringBuilder();
            Set<String> items = new TreeSet<>(forecast.keySet());
            for(Item i : content.items()) if(core.items.get(i) != last.get(i, 0)) items.add(i.name);
            for(String name : items){
                Item i = content.item(name);
                float rate = (core.items.get(i) - last.get(i, 0)) / secs;
                s.append(" ").append(name).append(" ").append(Strings.autoFixed(rate, 2)).append("/s (forecast ")
                    .append(Strings.autoFixed(forecast.getOrDefault(name, 0.0).floatValue(), 2)).append(")");
            }
            Log.info("[abbootcount] @s:@", Strings.autoFixed(secs, 1), s);
            // every power grid of ours: buildings, made, used
            Set<mindustry.world.blocks.power.PowerGraph> graphs = new LinkedHashSet<>();
            for(Building b : state.rules.defaultTeam.data().buildings) if(b.power != null && b.power.graph != null && !(b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild)) graphs.add(b.power.graph);
            for(var g : graphs) Log.info("[abbootcount] power grid: @ blocks, satisfaction @, made @/s, used @/s", g.all.size, Strings.autoFixed(g.getSatisfaction(), 2),
                Strings.autoFixed(g.getLastPowerProduced() * 60f, 0), Strings.autoFixed(g.getLastPowerNeeded() * 60f, 0));
            // modules: what each crafter holds
            for(var z : zones){
                if(z.kind != autobuild.bootstrap.domain.layout.Zone.Kind.INDUSTRIAL) continue;
                StringBuilder g = new StringBuilder();
                for(Placement p : z.placements){
                    if(world.build(p.x, p.y) instanceof mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild b){
                        g.append(" (").append(p.x).append(",").append(p.y).append(")");
                        for(Item i : content.items()) if(b.items.get(i) > 0) g.append(i.name.charAt(0)).append(b.items.get(i));
                        g.append(" e").append(Strings.autoFixed(b.efficiency, 1));
                    }
                }
                Log.info("[abbootcount] module @:@", z.label, g);
            }
            // power plants: generators burning (efficiency) and their fuel
            for(var z : zones){
                if(z.kind != autobuild.bootstrap.domain.layout.Zone.Kind.POWER) continue;
                StringBuilder g = new StringBuilder();
                for(Placement p : z.placements){
                    if(world.build(p.x, p.y) instanceof mindustry.world.blocks.power.ConsumeGenerator.ConsumeGeneratorBuild b){
                        g.append(" (").append(p.x).append(",").append(p.y).append(")").append(b.items.total()).append("/").append(Strings.autoFixed(b.productionEfficiency, 1));
                    }
                }
                Log.info("[abbootcount] plant @: items/efficiency@", z.label, g);
            }
            // mining sites: what the drills actually mine, and how many are full (backed up)
            for(var z : zones){
                if(z.kind != autobuild.bootstrap.domain.layout.Zone.Kind.MINING) continue;
                float speed = 0;
                int drills = 0, full = 0;
                for(Placement p : z.placements){
                    if(!(world.build(p.x, p.y) instanceof mindustry.world.blocks.production.Drill.DrillBuild d)) continue;
                    drills++;
                    speed += d.lastDrillSpeed * 60f;
                    if(d.items.total() >= d.block.itemCapacity) full++;
                }
                Log.info("[abbootcount] zone @ line @: drills @ mine @/s (forecast @), @ full", z.label, z.line, drills, Strings.autoFixed(speed, 2), Strings.autoFixed((float)z.rate, 2), full);
                if(full > 0 && z.corridor != null){
                    // where it jams: the line's blocks and what they hold
                    StringBuilder ls = new StringBuilder();
                    for(Placement p : z.corridor.placements){
                        Building b = world.build(p.x, p.y);
                        ls.append(" ").append(p.block).append("(").append(p.x).append(",").append(p.y).append(")r").append(p.rot)
                            .append(b == null ? " MISSING" : " i" + b.items.total());
                    }
                    Placement first = z.corridor.placements.get(0);
                    for(int d = 0; d < 4; d++){
                        Tile n = world.tile(first.x + arc.math.geom.Geometry.d4x(d), first.y + arc.math.geom.Geometry.d4y(d));
                        if(n != null && n.build != null) Log.info("[abbootcount]   start neighbour @: @ r@ items @ at (@,@)", d, n.block().name, n.build.rotation,
                            n.build.items == null ? -1 : n.build.items.total(), n.build.tile.x, n.build.tile.y);
                    }
                    Building fb = world.build(first.x, first.y);
                    if(fb instanceof mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild ib) Log.info("[abbootcount]   start bridge link @", arc.math.geom.Point2.unpack(ib.link));
                    Placement end = z.corridor.placements.get(z.corridor.placements.size() - 1);
                    Log.info("[abbootcount]   line:@", ls);
                    for(int d = 0; d < 4; d++){
                        Tile n = world.tile(end.x + arc.math.geom.Geometry.d4x(d), end.y + arc.math.geom.Geometry.d4y(d));
                        if(n != null && n.build != null) Log.info("[abbootcount]   end neighbour @: @ r@ items @", d, n.block().name, n.build.rotation, n.build.items == null ? -1 : n.build.items.total());
                    }
                }
            }
            remember();
        });
    }

    /**
     * The terrain as text for offline runs (test/autobuild/bootstrap/MapRun): top row first, '.'
     * buildable, '#' not, 'C' core, ores c l s k t (other ores '.'), '~' buildable water, 'W' deep.
     */
    static void dump(autobuild.bootstrap.domain.survey.Terrain t, String path){
        StringBuilder s = new StringBuilder();
        int[] c = t.core();
        for(int y = t.height() - 1; y >= 0; y--){
            for(int x = 0; x < t.width(); x++){
                String ore = t.ore(x, y);
                char ch = !t.buildable(x, y) ? '#' : '.';
                if(t.liquid(x, y) != null) ch = t.buildable(x, y) ? '~' : 'W';
                else if(ore != null && t.buildable(x, y)) ch = switch(ore){
                    case "copper" -> 'c';
                    case "lead" -> 'l';
                    case "sand" -> 's';
                    case "coal" -> 'k';
                    case "titanium" -> 't';
                    default -> '.';
                };
                if(x >= c[0] && x <= c[2] && y >= c[1] && y <= c[3]) ch = 'C';
                s.append(ch);
            }
            s.append('\n');
        }
        new arc.files.Fi(path).writeString(s.toString());
        Log.info("[abboot] terrain written to @", path);
    }

    static void remember(){
        Building core = state.rules.defaultTeam.core();
        last.clear();
        for(Item i : content.items()) last.put(i, core.items.get(i));
        lastTime = Time.millis();
    }
}
