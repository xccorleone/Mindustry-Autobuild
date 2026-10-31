package autobuild.bootstrap.domain.layout;

import autobuild.bootstrap.domain.shared.Placement;

import java.util.*;

/** A line between zones: belts and bridges (conduits and bridge conduits) carrying one item (liquid). */
public final class Corridor{
    public final String item;
    public final boolean liquid;
    public final int owner;
    public final List<Placement> placements = new ArrayList<>();
    /** plain belts {x, y, dir}: where another line of the same item may join from a side */
    public final List<int[]> pipes = new ArrayList<>();
    /** route cost (tiles, bridges count extra) */
    public double cost;

    Corridor(String item, boolean liquid, int owner){
        this.item = item;
        this.liquid = liquid;
        this.owner = owner;
    }

    @Override public String toString(){ return (liquid ? "~" : "") + item + " x" + placements.size(); }
}
