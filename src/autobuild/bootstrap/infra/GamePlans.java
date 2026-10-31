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
import mindustry.world.blocks.distribution.*;

import java.util.*;

import static mindustry.Vars.*;

/** Domain placements to the game's build plans, the live catalog, and the danger mask from enemy routes. */
public final class GamePlans{
    private GamePlans(){}

    /**
     * The game links the bridge placed last, while it has no link, to the next bridge placed in line
     * within range (a player's chain building): one line's end bridge would be captured by the next
     * line's first bridge. Called after each of our bridges is finished, it forgets that bridge.
     */
    public static void forgetLastBridge(Block b){
        if(b instanceof ItemBridge ib) ib.lastBuild = null;
    }

    /** our planned bridges, by position: once one is finished, {@link #forgetLastBridge} (main thread) */
    static final IntSet watched = new IntSet();
    static boolean listening;

    public static void watchBridges(Seq<BuildPlan> plans){
        for(BuildPlan p : plans) if(p.block instanceof ItemBridge) watched.add(Point2.pack(p.x, p.y));
        if(listening) return;
        listening = true;
        arc.Events.on(mindustry.game.EventType.BlockBuildEndEvent.class, e -> {
            if(e.breaking || e.tile == null || !watched.remove(e.tile.pos())) return;
            forgetLastBridge(e.tile.block());
        });
    }

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
