package autobuild;

import arc.*;
import arc.struct.*;
import arc.util.*;
import autobuild.drones.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

import static mindustry.Vars.*;

/**
 * Dedicated-server check of the safe drones:
 * <pre>
 * abdrones [off] [inside]
 *     far from the core: an enemy scatter (anti-air, endless ammo); a destroyed wall to rebuild and a
 *     damaged wall to repair just inside its range (each repairable from outside), and one of each
 *     deep inside (not); a poly (rebuild) and a mega (repair) outside the range, or with `inside`
 *     inside it. `off` = vanilla AI
 * abdronescount
 *     each drone's closest approach to the scatter's range (negative = inside), seconds spent inside,
 *     damage taken, and whether each job is done
 * </pre>
 */
public class ServerDroneTest{
    static final int rebuildEasy = 22, rebuildDeep = 3, repairEasy = 24, repairDeep = 3, start = 55, startInside = 12;
    static Building turret;
    static Unit poly, mega;
    /** closest approach to the scatter's range; seconds inside it; damage taken (summed, the drones heal); last health */
    static float polyMin, megaMin, polyIn, megaIn, polyHurt, megaHurt, polyLast, megaLast;
    static Tile easyBuild, deepBuild, easyRepair, deepRepair;
    static boolean hooked;

    static void register(CommandHandler handler){
        handler.register("abdrones", "[options...]", "AutoBuild: poly and mega next to an enemy anti-air turret", args -> {
            if(!state.isGame()){ Log.err("host a map first"); return; }
            Seq<String> opts = new Seq<>();
            for(String a : args) for(String o : a.trim().split("\\s+")) if(!o.isEmpty()) opts.add(o);
            Core.settings.put(SafeDrones.setting, !opts.contains("off"));
            int from = opts.contains("inside") ? startInside : start;
            state.rules.waves = false;
            state.rules.waveTimer = false;
            Team us = state.rules.defaultTeam, enemy = state.rules.waveTeam;
            state.rules.teams.get(enemy).cheat = true; // the turret refills its own ammo
            var core = us.core();
            if(core == null){ Log.err("no core"); return; }
            core.items.add(Items.titanium, 200); // rebuilding the walls

            // the quadrant centre farthest from the core
            int tx = 0, ty = 0;
            float far = -1;
            for(int qx = 1; qx <= 3; qx += 2) for(int qy = 1; qy <= 3; qy += 2){
                int x = world.width() * qx / 4, y = world.height() * qy / 4;
                float d = core.tile.dst(x * tilesize, y * tilesize);
                if(d > far){ far = d; tx = x; ty = y; }
            }
            Tile t = world.tile(tx, ty);
            t.setBlock(Blocks.scatter, enemy, 0);
            turret = t.build;

            easyBuild = wall(tx + rebuildEasy, ty, us);
            deepBuild = wall(tx, ty + rebuildDeep, us);
            // destroyed: the game queues them for rebuilding
            for(Tile w : new Tile[]{easyBuild, deepBuild}) w.build.kill();
            easyRepair = wall(tx - repairEasy, ty, us);
            deepRepair = wall(tx, ty - repairDeep, us);
            // damaged through the game, so its index of damaged buildings has them
            for(Tile w : new Tile[]{easyRepair, deepRepair}) w.build.damage(w.build.maxHealth * 0.7f);

            poly = UnitTypes.poly.spawn(us, (tx + from) * tilesize, ty * tilesize);
            mega = UnitTypes.mega.spawn(us, (tx - from) * tilesize, ty * tilesize);
            polyMin = megaMin = Float.MAX_VALUE;
            polyIn = megaIn = 0f;
            polyHurt = megaHurt = 0f;
            polyLast = poly.health;
            megaLast = mega.health;
            Log.info("[abdrones] scatter at (@,@), range @ tiles; safe drones @; core @ tiles away", tx, ty,
                Strings.fixed(((Turret)Blocks.scatter).range / tilesize, 1), SafeDrones.enabled(), (int)(far / tilesize));

            if(!hooked){
                hooked = true;
                Events.run(Trigger.update, ServerDroneTest::watch);
            }
        });

        handler.register("abdronescount", "AutoBuild: drones' closest approach and their jobs", args -> {
            if(turret == null){ Log.err("run abdrones first"); return; }
            report("poly", poly, polyMin, polyIn, polyHurt);
            report("mega", mega, megaMin, megaIn, megaHurt);
            Log.info("[abdrones] rebuild: near edge @, deep inside @ (should stay undone)", done(easyBuild), done(deepBuild));
            Log.info("[abdrones] repair: near edge @, deep inside @ (should stay damaged)", health(easyRepair), health(deepRepair));
        });
    }

    static Tile wall(int x, int y, Team team){
        Tile w = world.tile(x, y);
        w.setBlock(Blocks.titaniumWall, team, 0);
        return w;
    }

    static void watch(){
        if(turret == null || !state.isGame()) return;
        float range = ((Turret)Blocks.scatter).range;
        if(poly != null && poly.isValid()){
            float d = poly.dst(turret) - range - poly.hitSize / 2f;
            polyMin = Math.min(polyMin, d);
            if(d < 0) polyIn += Time.delta / 60f;
            polyHurt += Math.max(0f, polyLast - poly.health);
            polyLast = poly.health;
        }
        if(mega != null && mega.isValid()){
            float d = mega.dst(turret) - range - mega.hitSize / 2f;
            megaMin = Math.min(megaMin, d);
            if(d < 0) megaIn += Time.delta / 60f;
            megaHurt += Math.max(0f, megaLast - mega.health);
            megaLast = mega.health;
        }
    }

    static void report(String name, Unit u, float min, float in, float hurt){
        Log.info("[abdrones] @: @, controller @, closest approach to the range @ tiles, @ s inside, damage taken @", name,
            u.isValid() ? "alive" : "DEAD", u.controller() instanceof mindustry.ai.types.CommandAI c ? c.currentCommand().name : u.controller().getClass().getSimpleName(),
            Strings.fixed(min / tilesize, 1), Strings.fixed(in, 1), Strings.fixed(hurt, 0));
    }

    static String done(Tile t){
        return t.block() == Blocks.titaniumWall ? "built" : "not built";
    }

    static String health(Tile t){
        return t.build == null ? "gone" : (int)(t.build.health / t.build.maxHealth * 100) + " %";
    }
}
