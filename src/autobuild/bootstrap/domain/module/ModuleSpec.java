package autobuild.bootstrap.domain.module;

import autobuild.bootstrap.domain.shared.Bom;

import java.util.*;

/**
 * What a factory / power-plant blueprint is to the planner: a black box with its footprint, the
 * ports outside lines connect to, its rates at full speed, its power and its cost.
 */
public final class ModuleSpec{
    /**
     * Where an outside line meets the module. (x, y) is the attach tile in blueprint coordinates: the
     * tile the line's last block stands on (inlet) or the first tile items reach outside (outlet) -
     * outside the footprint for edge ports, the marker's own tile for marker ports. {@code dir} is the
     * direction items move: into the module for an inlet, out of it for an outlet.
     */
    public static final class Port{
        public final int x, y, dir;
        public final boolean liquid, marker;
        /** inlet: the item / liquid it takes; outlet: the items leaving through it */
        public final Set<String> items = new TreeSet<>();
        /** most this port can carry per second */
        public final double cap;
        /** per second at the module's full speed */
        public double rate;

        public Port(int x, int y, int dir, boolean liquid, boolean marker, double cap){
            this.x = x;
            this.y = y;
            this.dir = dir;
            this.liquid = liquid;
            this.marker = marker;
            this.cap = cap;
        }

        /** an inlet's single item (or liquid) */
        public String item(){ return items.isEmpty() ? null : items.iterator().next(); }

        @Override public String toString(){ return (liquid ? "~" : "") + items + "@(" + x + "," + y + ")>" + dir; }
    }

    public final String name;
    public final int width, height;
    /** what was analyzed (the layout stamps it) */
    public Blueprint blueprint;
    public final List<Port> inlets = new ArrayList<>(), outlets = new ArrayList<>();
    /** products per second at full speed */
    public final Map<String, Double> outputs = new TreeMap<>();
    /** working crafters and generators, all of them */
    public int working, consumers;
    /** share of its crafters' capacity the module runs at, limited by its ports */
    public double factor;
    /** power per second: used at full speed (peak), produced */
    public double powerUse, powerGen;
    /** has a power node of its own (else the layout adds one) */
    public boolean hasNode;
    /** build cost without the marker blocks */
    public final Bom cost = new Bom();
    /** why the module can't be used, or null */
    public String error;

    public ModuleSpec(String name, int width, int height){
        this.name = name;
        this.width = width;
        this.height = height;
    }

    public boolean ok(){ return error == null; }
}
