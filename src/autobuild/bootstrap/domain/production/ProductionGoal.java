package autobuild.bootstrap.domain.production;

import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.module.ModuleSpec;

import java.util.*;

/** What the player asks for: modules by count, raw items mined straight to the core, and the means. */
public final class ProductionGoal{
    public static final class ModuleOrder{
        public final ModuleSpec spec;
        public final int count;

        public ModuleOrder(ModuleSpec spec, int count){
            this.spec = spec;
            this.count = count;
        }
    }

    public final List<ModuleOrder> modules = new ArrayList<>();
    /** item -> full-belt lines mined to the core */
    public final Map<String, Integer> raw = new TreeMap<>();
    /** power plant module, built as often as the modules need (null: none available) */
    public ModuleSpec power;
    /** drills that may be built */
    public final List<BlockInfo> drills = new ArrayList<>();
    /** pump for water inlets (null: none available) */
    public BlockInfo pump;
    /** items per second of one outside line (required) */
    public double belt;
    /** share of a patch's ore tiles its drills end up covering */
    public double coverage = 0.8;
    /** products made first: modules making them get the ore before power plants and other modules */
    public final Set<String> first = new TreeSet<>(Set.of("graphite"));
    /**
     * An ore patch is used only for at least this much per second per unit of line cost from the core
     * (a bridge jump costs 2: about 12 items to build) - no long line for a little ore.
     */
    public double fetch = 0.027;

    /** the module makes one of the {@link #first} products */
    public boolean first(ModuleSpec spec){
        for(String it : spec.outputs.keySet()) if(first.contains(it)) return true;
        return false;
    }
}
