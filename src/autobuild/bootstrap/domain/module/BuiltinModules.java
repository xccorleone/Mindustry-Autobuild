package autobuild.bootstrap.domain.module;

import java.util.*;

/**
 * Modules the mod brings that need no blueprint file: both cost only copper and lead, so they can be
 * built on a fresh sector.
 * <ul>
 * <li>{@link #combustionPlant}: belts and overflow gates in turn (a gate fed by a gate passes nothing
 *     on), a combustion generator on each side of each gate (8 generators, 480 power/s for 4 coal/s);</li>
 * <li>{@link #graphitePress}: one graphite press between an inlet and an outlet belt (the seed module
 *     that makes the first graphite).</li>
 * </ul>
 */
public final class BuiltinModules{
    public static final String COMBUSTION = "Combustion Plant (8)", PRESS = "Graphite Press (1)";

    private BuiltinModules(){}

    public static Blueprint combustionPlant(){
        List<Blueprint.Tile> t = new ArrayList<>();
        // belts between the gates: a gate fed by a gate can't pass items on to the next one
        for(int i = 0; i < 4; i++){
            int x = i * 2;
            t.add(new Blueprint.Tile("conveyor", x, 1, 0, null));
            t.add(new Blueprint.Tile("overflow-gate", x + 1, 1, 0, null));
            t.add(new Blueprint.Tile("combustion-generator", x + 1, 0, 0, null));
            t.add(new Blueprint.Tile("combustion-generator", x + 1, 2, 0, null));
        }
        return new Blueprint(COMBUSTION, 8, 3, t);
    }

    public static Blueprint graphitePress(){
        List<Blueprint.Tile> t = new ArrayList<>();
        t.add(new Blueprint.Tile("conveyor", 0, 0, 0, null));
        t.add(new Blueprint.Tile("graphite-press", 1, 0, 0, null));
        t.add(new Blueprint.Tile("conveyor", 3, 0, 0, null));
        return new Blueprint(PRESS, 4, 2, t);
    }
}
