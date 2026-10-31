package autobuild.bootstrap.domain.production;

import autobuild.bootstrap.domain.module.ModuleSpec;

import java.util.*;

/** Quantities only, no coordinates: module copies, their supply lines and the power balance. */
public final class ProductionPlan{
    public static final class Instance{
        public final ModuleSpec spec;
        public final boolean power;
        /** share of its full speed it gets after supply shortfalls */
        public double factor = 1;

        Instance(ModuleSpec spec, boolean power){
            this.spec = spec;
            this.power = power;
        }
    }

    public final List<Instance> instances = new ArrayList<>();
    public final List<SupplyLine> lines = new ArrayList<>();
    /** power per second the modules use beyond their own generation, and the plants' output */
    public double powerDemand, powerSupply;
    /** modules need power but no plant is available */
    public boolean noPower;
    /** inlets wanting more than one outside line carries: instance, inlet, wanted per second */
    public final List<double[]> beltLimited = new ArrayList<>();

    public List<SupplyLine> shortfalls(){ return lines.stream().filter(l -> l.limiting).toList(); }
}
