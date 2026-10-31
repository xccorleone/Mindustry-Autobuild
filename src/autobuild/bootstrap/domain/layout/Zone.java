package autobuild.bootstrap.domain.layout;

import autobuild.bootstrap.domain.shared.Placement;

import java.util.*;

/** A part of the base that produces something: a module, a mining site, a pump site. */
public final class Zone{
    public enum Kind{ INDUSTRIAL, POWER, MINING, WATER }

    public final Kind kind;
    public final String label;
    public final List<Placement> placements = new ArrayList<>();
    /** module zones: the stamped module and its index in the production plan */
    public Stamp stamp;
    public int instance = -1;
    /** mining / water zones: the supply line they feed (index into the production plan's lines) and its rate */
    public int line = -1;
    public double rate;
    /** mining / water zones: their corridor */
    public Corridor corridor;
    /** mining zones: the zone (index into the plan's zones) whose corridor this one's joins, or -1 */
    public int joins = -1;

    Zone(Kind kind, String label){
        this.kind = kind;
        this.label = label;
    }

    @Override public String toString(){ return kind + " " + label + (rate > 0 ? String.format(" %.2f/s", rate) : ""); }
}
