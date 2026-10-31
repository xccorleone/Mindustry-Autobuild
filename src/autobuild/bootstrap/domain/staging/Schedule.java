package autobuild.bootstrap.domain.staging;

import autobuild.bootstrap.domain.layout.Forecast;
import autobuild.bootstrap.domain.shared.Bom;

import java.util.*;

/** The steps in the order a simulation built them, and what it could not build. */
public final class Schedule{
    public static final class Entry{
        public final Step step;
        public final boolean plain;
        /** steps chosen together share a group (built one after another, re-ordered only between groups) */
        public final int group;
        /** seconds: when its cost was in the core */
        public final double start;
        public final Bom cost;
        /** value per second the base earns more once it stands */
        public final double gain;

        Entry(Step step, boolean plain, int group, double start, Bom cost, double gain){
            this.step = step;
            this.plain = plain;
            this.group = group;
            this.start = start;
            this.cost = cost;
            this.gain = gain;
        }

        @Override public String toString(){ return String.format("%d %s%s @%.0fs [%s] +%.2f", group, step, plain ? " (stand-ins)" : "", start, cost, gain); }
    }

    public final List<Entry> entries = new ArrayList<>();
    /** steps never built, and the items they lack with neither stock nor income */
    public final List<Step> stuck = new ArrayList<>();
    public final Set<String> missing = new TreeSet<>();
    /** what the base makes when the schedule is through */
    public Forecast end;

    public double time(){ return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).start; }
}
