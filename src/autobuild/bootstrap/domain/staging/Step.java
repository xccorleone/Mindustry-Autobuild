package autobuild.bootstrap.domain.staging;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.shared.*;

import java.util.*;

/**
 * The smallest part of the base that earns something once built: a module with its outlet lines, a
 * power plant, a mining site with its line, a pump site with its conduit, the power nodes, or the
 * upgrade of a step built with slower stand-ins.
 */
public final class Step{
    public enum Kind{ NODES, MODULE, PLANT, MINE, WATER, UPGRADE }

    public final int id;
    public final Kind kind;
    /** MINE / WATER: the item; MODULE / PLANT: the module's name; UPGRADE: the upgraded step's label */
    public final String what;
    /** MINE / WATER: where the line goes (a module's name, or null for the core) */
    public String to;
    /** as planned (an UPGRADE: the planned blocks of its step that have stand-ins) */
    public final List<Placement> placements = new ArrayList<>();
    /** steps that must stand first */
    public final List<Integer> deps = new ArrayList<>();
    /** zones (indices into the plan's zones) this step builds */
    public final List<Integer> zones = new ArrayList<>();
    /** MODULE / PLANT: the production instance; MINE / WATER: the line (and the instance it feeds, or -1) */
    public int instance = -1, line = -1;
    /** UPGRADE: the step it upgrades; others: their upgrade step, or -1 */
    public int upgradeOf = -1, upgrade = -1;
    /** upgrades that pay off only together share a network: the instance fed, or -1 - line for a core line */
    public int network;
    /** planned block -> slower stand-in this step may be built with (empty: none) */
    public final Map<String, String> standIns = new TreeMap<>();
    /** built with stand-ins: share of its zones' rate the stand-in drills mine, and what its belts carry */
    public double slowRate = 1, slowBelt;

    Step(int id, Kind kind, String what){
        this.id = id;
        this.kind = kind;
        this.what = what;
    }

    /** the blocks as built: planned, or with the stand-ins */
    public List<Placement> placements(boolean plain){
        if(!plain || standIns.isEmpty()) return placements;
        List<Placement> out = new ArrayList<>(placements.size());
        for(Placement p : placements){
            String b = standIns.get(p.block);
            out.add(b == null ? p : new Placement(b, p.x, p.y, p.rot, p.config, p.owner));
        }
        return out;
    }

    public Bom cost(Catalog cat, boolean plain){
        return cost(cat, placements(plain));
    }

    public static Bom cost(Catalog cat, List<Placement> places){
        Bom b = new Bom();
        for(Placement p : places) b.addAll(cat.block(p.block).req);
        return b;
    }

    public String label(){
        return switch(kind){
            case NODES -> "power nodes";
            case MODULE, PLANT -> what;
            case MINE, WATER -> what + " to " + (to == null ? "the core" : to);
            case UPGRADE -> "upgrade " + what;
        };
    }

    @Override public String toString(){ return "#" + id + " " + label(); }
}
