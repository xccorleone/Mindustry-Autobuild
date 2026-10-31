package autobuild.bootstrap.infra;

import autobuild.BuildJob;
import autobuild.bootstrap.domain.survey.Terrain;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/**
 * The world as the bootstrap domain sees it, copied once on the main thread (planning runs on
 * another): ore a drill would mine, ground a block can go on (nothing built there, the game allows a
 * conveyor), pumpable liquid, and the team's core.
 */
public final class GameTerrain implements Terrain{
    final int w, h;
    final String[] ore, liquid;
    final boolean[] buildable;
    final int[] core;

    GameTerrain(int w, int h, int[] core){
        this.w = w;
        this.h = h;
        this.core = core;
        ore = new String[w * h];
        liquid = new String[w * h];
        buildable = new boolean[w * h];
    }

    /** main thread; null when the team has no core */
    public static GameTerrain snapshot(Team team){
        Building c = team.core();
        if(c == null) return null;
        int off = (c.block.size - 1) / 2, x0 = c.tile.x - off, y0 = c.tile.y - off;
        GameTerrain t = new GameTerrain(world.width(), world.height(), new int[]{x0, y0, x0 + c.block.size - 1, y0 + c.block.size - 1});
        for(int x = 0; x < t.w; x++) for(int y = 0; y < t.h; y++){
            Tile tile = world.tile(x, y);
            int i = x + y * t.w;
            Item drop = tile.solid() ? null : tile.drop();
            t.ore[i] = drop == null ? null : drop.name;
            Liquid l = tile.floor().liquidDrop;
            t.liquid[i] = l == null || tile.build != null || tile.solid() ? null : l.name;
            t.buildable[i] = BuildJob.empty(x, y) && Build.validPlace(Blocks.conveyor, team, x, y, 0);
        }
        return t;
    }

    public int width(){ return w; }

    public int height(){ return h; }

    public String ore(int x, int y){ return ore[x + y * w]; }

    public boolean buildable(int x, int y){ return buildable[x + y * w]; }

    public String liquid(int x, int y){ return liquid[x + y * w]; }

    public int[] core(){ return core; }
}
