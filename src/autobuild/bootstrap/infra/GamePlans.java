package autobuild.bootstrap.infra;

import arc.math.geom.*;
import arc.struct.*;
import autobuild.DefenseJob;
import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.shared.Placement;
import autobuild.bootstrap.domain.survey.DangerMask;
import autobuild.plan.EnemyRoutes;
import mindustry.entities.units.*;
import mindustry.world.*;

import java.util.*;

import static mindustry.Vars.*;

/** Domain placements to the game's build plans, the live catalog, and the danger mask from enemy routes. */
public final class GamePlans{
    private GamePlans(){}

    /** the catalog from the loaded content (any thread once content is loaded) */
    public static Catalog liveCatalog(){
        return Catalog.parse(CatalogDump.text());
    }

    public static Seq<BuildPlan> toBuildPlans(List<Placement> places){
        Seq<BuildPlan> out = new Seq<>(places.size());
        for(Placement p : places) out.add(toBuildPlan(p));
        return out;
    }

    public static BuildPlan toBuildPlan(Placement p){
        Block b = content.block(p.block);
        return new BuildPlan(p.x, p.y, p.rot, b, config(p.config));
    }

    static Object config(Object c){
        if(c instanceof String name){
            Object item = content.item(name);
            return item != null ? item : content.liquid(name);
        }
        if(c instanceof int[] d) return new Point2(d[0], d[1]);
        if(c instanceof int[][] ds){
            Point2[] out = new Point2[ds.length];
            for(int i = 0; i < ds.length; i++) out[i] = new Point2(ds[i][0], ds[i][1]);
            return out;
        }
        return c;
    }

    /**
     * Tiles within `radius` of the enemies' ground routes (as the game's pathfinder walks them, from
     * every spawn to the cores) and of their flying lines (main thread). Every route ends at a core:
     * its last `clear` tiles before one are left out (the base has to stand there anyway).
     */
    public static boolean[][] danger(float radius, float clear){
        DefenseJob.MapData m = DefenseJob.mapData();
        int w = world.width(), h = world.height();
        EnemyRoutes r = new EnemyRoutes(w, h, m.cost, m.nearSolid);
        r.flow(m.targets);
        List<int[]> route = new ArrayList<>();
        for(int s : m.spawns) for(int t : r.trace(s)) add(route, t, w, m.targets, clear);
        for(float[] a : m.air) for(int t : EnemyRoutes.line(w, h, a[0], a[1], a[2], a[3])) add(route, t, w, m.targets, clear);
        return DangerMask.around(w, h, route, radius);
    }

    static void add(List<int[]> route, int t, int w, int[] cores, float clear){
        int x = t % w, y = t / w;
        for(int c : cores) if(Math.hypot(x - c % w, y - c / w) < clear) return;
        route.add(new int[]{x, y});
    }
}
