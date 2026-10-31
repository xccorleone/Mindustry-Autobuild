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
    /** items per second of one outside line */
    public double belt;
    /** share of a patch's ore tiles its drills end up covering */
    public double coverage = 0.8;
}
