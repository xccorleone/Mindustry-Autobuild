package autobuild.bootstrap.domain.production;

import autobuild.bootstrap.domain.survey.OrePatch;
import autobuild.bootstrap.domain.survey.WaterBody;

import java.util.*;

/** One outside line: an item (or liquid) from ore patches (a water body) to a module inlet or the core. */
public final class SupplyLine{
    public static final class Share{
        public final OrePatch patch;
        public final WaterBody water;
        public final double rate;

        Share(OrePatch patch, WaterBody water, double rate){
            this.patch = patch;
            this.water = water;
            this.rate = rate;
        }
    }

    public final String item;
    public final boolean liquid;
    /** index into {@link ProductionPlan#instances} and inlet index, or -1, -1 for the core */
    public final int instance, inlet;
    /** per second: asked for (one belt at most), aimed at after its module slowed down, found */
    public double wanted, target, supplied;
    /** too little ore / water for this line: it is what slows its module down (or the core gets less) */
    public boolean limiting;
    public final List<Share> sources = new ArrayList<>();

    SupplyLine(String item, boolean liquid, int instance, int inlet, double target){
        this.item = item;
        this.liquid = liquid;
        this.instance = instance;
        this.inlet = inlet;
        this.wanted = this.target = target;
    }

    public boolean toCore(){ return instance < 0; }

    @Override public String toString(){
        return item + (toCore() ? "->core" : "->" + instance + "." + inlet) + String.format(" %.2f/%.2f", supplied, wanted) + (limiting ? " limiting" : "");
    }
}
