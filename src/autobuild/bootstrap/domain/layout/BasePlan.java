package autobuild.bootstrap.domain.layout;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.shared.*;

import autobuild.bootstrap.domain.survey.OrePatch;

import java.util.*;

/**
 * Aggregate root of the base layout: every zone, corridor and power node, all on one
 * {@link Reservation} (tiles are never shared). Networks: supply line i is owner i (its mining or pump
 * sites and corridors), module instance m is {@link #MODULE} + m, outlet k of instance m is
 * {@link #outletOwner}, power nodes {@link #NODES}.
 */
public final class BasePlan{
    public static final int MODULE = 100000, OUTLET = 200000, NODES = 900000;

    public final Reservation res;
    public final List<Zone> zones = new ArrayList<>();
    public final List<Corridor> corridors = new ArrayList<>();
    public final List<Placement> nodes = new ArrayList<>();
    /** per production instance: where it stands (null: no room for it) */
    public final Stamp[] stamps;
    /** per supply line: per second its built sources deliver (before the belt limit) */
    public final double[] lineSupply;
    /** per instance and outlet: its products reach the core */
    public final boolean[][] outletRouted;
    /** what could not be built, and why (plain English, for logs and the summary) */
    public final List<String> notes = new ArrayList<>();
    /** ore patches a share of which got no mining site, too little for its way, or no corridor */
    public final List<OrePatch> failed = new ArrayList<>();
    public Forecast forecast;

    BasePlan(Reservation res, int instances, int lines){
        this.res = res;
        stamps = new Stamp[instances];
        lineSupply = new double[lines];
        outletRouted = new boolean[instances][];
    }

    public static int outletOwner(int instance, int outlet){ return OUTLET + instance * 64 + outlet; }

    /**
     * Everything in build order: power nodes first (consumers link to them when they are finished),
     * then belts, bridges and conduits, then module blocks, pumps and drills.
     */
    public List<Placement> ordered(Catalog cat){
        List<Placement> logistics = new ArrayList<>(), modules = new ArrayList<>(), producers = new ArrayList<>();
        for(Corridor c : corridors) logistics.addAll(c.placements);
        for(Zone z : zones) for(Placement p : z.placements){
            String k = cat.block(p.block).kind;
            if(z.stamp != null) modules.add(p);
            else if(k.equals("drill") || k.equals("pump")) producers.add(p);
            else logistics.add(p);
        }
        List<Placement> out = new ArrayList<>(nodes);
        out.addAll(logistics);
        out.addAll(modules);
        out.addAll(producers);
        return out;
    }
}
