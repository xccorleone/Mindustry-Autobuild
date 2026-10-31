package autobuild.bootstrap.domain.mining;

import autobuild.bootstrap.domain.shared.Placement;

import java.util.*;

/** Drills on (part of) an ore patch with their belts and bridges, delivering into one tile outside. */
public final class MiningSite{
    public final String item;
    /** the region given to the drill planner {x0, y0, x1, y1} */
    public final int[] region;
    public final List<Placement> placements = new ArrayList<>();
    /** where items leave the site: the tile they move into and their direction there */
    public int sinkX, sinkY, sinkDir;
    /** items per second into the sink (at most one belt) */
    public double rate;
    public int drills;
    /** why there is no site, or null */
    public String error;

    MiningSite(String item, int[] region){
        this.item = item;
        this.region = region;
    }

    public boolean ok(){ return error == null; }

    @Override public String toString(){ return item + " " + drills + " drills " + String.format("%.2f/s", rate) + (error == null ? "" : " (" + error + ")"); }
}
