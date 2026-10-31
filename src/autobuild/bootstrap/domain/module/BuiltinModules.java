package autobuild.bootstrap.domain.module;

import java.util.*;

/**
 * Modules the mod brings that need no blueprint file: both cost only copper and lead, so they can be
 * built on a fresh sector.
 * <ul>
 * <li>{@link #combustionPlant}: a belt into a row of 4 overflow gates with a combustion generator on
 *     each side of each gate (8 generators, 480 power/s for 4 coal/s);</li>
 * <li>{@link #graphitePress}: one graphite press between an inlet and an outlet belt (the seed module
 *     that makes the first graphite).</li>
 * </ul>
 */
public final class BuiltinModules{
    public static final String COMBUSTION = "Combustion Plant (8)", PRESS = "Graphite Press (1)";

    private BuiltinModules(){}

    public static Blueprint combustionPlant(){
        List<Blueprint.Tile> t = new ArrayList<>();
        t.add(new Blueprint.Tile("conveyor", 0, 1, 0, null));
        for(int x = 1; x <= 4; x++){
            t.add(new Blueprint.Tile("overflow-gate", x, 1, 0, null));
            t.add(new Blueprint.Tile("combustion-generator", x, 0, 0, null));
            t.add(new Blueprint.Tile("combustion-generator", x, 2, 0, null));
        }
        return new Blueprint(COMBUSTION, 5, 3, t);
    }

    public static Blueprint graphitePress(){
        List<Blueprint.Tile> t = new ArrayList<>();
        t.add(new Blueprint.Tile("conveyor", 0, 0, 0, null));
        t.add(new Blueprint.Tile("graphite-press", 1, 0, 0, null));
        t.add(new Blueprint.Tile("conveyor", 3, 0, 0, null));
        return new Blueprint(PRESS, 4, 2, t);
    }
}
