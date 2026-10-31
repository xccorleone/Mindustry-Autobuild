package autobuild.bootstrap.infra;

import arc.files.*;
import arc.math.geom.*;
import arc.util.*;
import autobuild.AutoBuildMod;
import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.module.*;
import mindustry.ctype.*;
import mindustry.game.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Blueprints from the game: the player's schematics (converted directly, no decoding) and the ones
 * the mod ships ({@code assets/bootstrap}). A schematic that can't be read is skipped and logged, never
 * fatal.
 */
public final class GameBlueprints{
    /** files the mod ships: built-in silicon module and turbine plant */
    public static final String SILICON = "silicon8.txt", TURBINE = "generator1.txt";

    private GameBlueprints(){}

    /** the player's schematics (main thread) */
    public static List<Blueprint> schematics(){
        List<Blueprint> out = new ArrayList<>();
        for(Schematic s : schematics.all()){
            try{
                out.add(convert(s));
            }catch(Throwable e){
                Log.err("[autobuild] schematic @ unreadable: @", s.name(), e);
            }
        }
        return out;
    }

    public static Blueprint convert(Schematic s){
        List<Blueprint.Tile> tiles = new ArrayList<>();
        for(Schematic.Stile t : s.tiles) tiles.add(new Blueprint.Tile(t.block.name, t.x, t.y, t.rotation, config(t.config)));
        return new Blueprint(s.name(), s.width, s.height, tiles);
    }

    /** the neutral config: content name, link(s) as int[] / int[][], Integer, else null */
    static Object config(Object c){
        if(c instanceof UnlockableContent u) return u.name;
        if(c instanceof Point2 p) return new int[]{p.x, p.y};
        if(c instanceof Point2[] ps){
            int[][] out = new int[ps.length][];
            for(int i = 0; i < ps.length; i++) out[i] = new int[]{ps[i].x, ps[i].y};
            return out;
        }
        if(c instanceof Integer i) return i;
        return null;
    }

    /** a blueprint the mod ships (base64 msch text), or null if it can't be read */
    public static Blueprint builtin(String file, Catalog cat){
        try{
            Fi f = mods.getMod(AutoBuildMod.class).root.child("bootstrap").child(file);
            return BlueprintCodec.decodeBase64(f.readString(), cat);
        }catch(Throwable e){
            Log.err("[autobuild] built-in blueprint @ unreadable: @", file, e);
            return null;
        }
    }

    /** a blueprint file anywhere (server tests), or null */
    public static Blueprint file(String path, Catalog cat){
        try{
            return BlueprintCodec.decodeBase64(new Fi(path).readString(), cat);
        }catch(Throwable e){
            Log.err("[autobuild] blueprint @ unreadable: @", path, e);
            return null;
        }
    }
}
