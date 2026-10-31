package autobuild.bootstrap.domain.layout;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;

import java.util.*;

/** The blocks the base is built from, by catalog name (null: not available), and layout parameters. */
public final class LayoutOptions{
    public final Catalog cat;
    public String belt = "conveyor", bridge = "bridge-conveyor", conduit = "conduit", liquidBridge = "bridge-conduit";
    public String node = "power-node", pump = "mechanical-pump";
    public final List<BlockInfo> drills = new ArrayList<>();
    /** block replacements inside modules (titanium conveyors by the chosen belt when there is no titanium) */
    public final Map<String, String> swap = new TreeMap<>();
    /** share of a patch's ore tiles its drills end up covering (as the production plan assumed) */
    public double coverage = 0.8;

    public LayoutOptions(Catalog cat){
        this.cat = cat;
    }

    public double beltSpeed(){ return cat.block(belt).speed; }

    public int bridgeRange(){ return bridge == null ? 0 : (int)cat.block(bridge).range; }

    public int liquidBridgeRange(){ return liquidBridge == null ? 0 : (int)cat.block(liquidBridge).range; }

    /** liquid per second of one pump on a tile */
    public double pumpRate(){ return pump == null ? 0 : cat.block(pump).pump * 60.0 * cat.block(pump).size * cat.block(pump).size; }

    /** the drill mining the most of this item per ore tile, or null */
    public BlockInfo drillFor(String item){
        Catalog.ItemInfo it = cat.item(item);
        BlockInfo best = null;
        for(BlockInfo d : drills){
            Float t = d.drillTime.get(item);
            if(it == null || d.tier < it.hardness || t == null || t <= 0) continue;
            if(best == null || t < best.drillTime.get(item)) best = d;
        }
        return best;
    }

    /** items per second one ore tile gives with {@link #drillFor} */
    public double perTile(String item){
        BlockInfo d = drillFor(item);
        return d == null ? 0 : 60.0 / d.drillTime.get(item);
    }
}
