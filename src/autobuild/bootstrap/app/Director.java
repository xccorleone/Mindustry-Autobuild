package autobuild.bootstrap.app;

import autobuild.bootstrap.domain.shared.*;
import autobuild.bootstrap.domain.staging.*;
import autobuild.bootstrap.domain.staging.StagePlanner.*;

import java.util.*;

/**
 * Builds a step graph step by step as the core's stock allows. Every {@link #TICK} seconds of game
 * time:
 * <ul>
 * <li>waiting: when the core holds the cost of the current step's missing blocks, they go to the
 *     builder; a new group of steps is chosen ({@link StagePlanner#next}) only when the last one is
 *     through, from the actual stock and what stands;</li>
 * <li>building: when every block stands, the step is done; blocks the builder dropped (it died,
 *     the player cleared its queue) go to it again; a block that can't be placed (something else
 *     stands there) blocks the step until the player retries or skips it;</li>
 * <li>every {@link #REPAIR} seconds, blocks of finished steps that are gone are queued again (also
 *     once everything is done, until cancelled).</li>
 * </ul>
 */
public final class Director{
    public static final double TICK = 2, REPAIR = 10;

    public enum Phase{ WAITING, BUILDING, BLOCKED, PAUSED, DONE, CANCELLED }

    /** The game as the director sees it. */
    public interface Site{
        enum Status{ BUILT, PENDING, FREE, BLOCKED }

        /** items in the core */
        double stock(String item);

        /** building costs nothing */
        boolean infinite();

        /** BUILT: this block stands there; PENDING: queued or being built; FREE: it can be placed (over a block it replaces); BLOCKED: something else stands there */
        Status status(Placement p);

        void queue(List<Placement> places);

        void unqueue(List<Placement> places);

        /** something is there to build */
        boolean canBuild();
    }

    /** What happens, for logs and the HUD. */
    public interface Events{
        default void started(Step step, boolean plain, double time){}

        default void finished(Step step, double time){}

        default void blocked(Step step, Placement at){}

        default void repaired(Step step, int blocks){}

        default void done(double time){}
    }

    public final StepGraph graph;
    public final StageOptions options;
    public final StageState state;
    final Site site;
    final Events events;

    public Phase phase = Phase.WAITING;
    Phase paused;
    final ArrayDeque<Pick> group = new ArrayDeque<>();
    /** the step being waited for or built (null between groups) */
    public Pick current;
    /** waiting for these items (none in the core, or not enough) */
    public final Set<String> missing = new TreeSet<>();
    /** nothing left can be paid for from what comes in: these items have to come from elsewhere */
    public boolean stuck;
    /** the block that blocks the current step */
    public Placement blockedAt;
    /** waiting: what the current step's missing blocks cost; building: its blocks standing, of all */
    public Bom need = new Bom();
    public int blocksBuilt, blocks;
    double next, nextRepair = REPAIR;
    /** every item a step costs */
    final Set<String> items = new TreeSet<>();

    public Director(StepGraph graph, StageOptions options, Site site, Events events){
        this.graph = graph;
        this.options = options;
        this.site = site;
        this.events = events == null ? new Events(){} : events;
        state = new StageState(graph);
        for(Step s : graph.steps){
            items.addAll(s.cost(graph.options.cat, false).items());
            items.addAll(s.cost(graph.options.cat, true).items());
        }
    }

    /** `secs` of game time passed */
    public void update(double secs){
        state.time += secs;
        if(state.time < next) return;
        next = state.time + TICK;
        tick();
    }

    public void tick(){
        if(phase == Phase.PAUSED || phase == Phase.CANCELLED) return;
        // (also once done: the base is kept standing)
        if(state.time >= nextRepair){
            nextRepair = state.time + REPAIR;
            repair();
        }
        if(phase == Phase.BUILDING) building();
        if(phase == Phase.WAITING) waiting();
    }

    void waiting(){
        if(current == null){
            if(group.isEmpty()){
                readStock();
                missing.clear();
                Choice c = StagePlanner.next(graph, state, options, missing);
                if(c == null){
                    stuck = left() > 0;
                    if(!stuck){
                        phase = Phase.DONE;
                        events.done(state.time);
                    }
                    return;
                }
                stuck = false;
                group.addAll(c.picks);
            }
            current = group.poll();
        }
        List<Placement> places = places(current);
        List<Placement> todo = new ArrayList<>();
        for(Placement p : places){
            Site.Status s = site.status(p);
            if(s == Site.Status.BLOCKED){ block(p); return; }
            if(s == Site.Status.FREE) todo.add(p);
        }
        missing.clear();
        need = Step.cost(graph.options.cat, todo);
        if(!site.infinite()){
            for(String it : need.items()) if(site.stock(it) < Math.min(need.get(it), options.capacity)) missing.add(it);
        }
        if(!missing.isEmpty() || !site.canBuild()) return;
        site.queue(todo);
        phase = Phase.BUILDING;
        events.started(graph.step(current.step), current.plain, state.time);
        building();
    }

    void building(){
        List<Placement> again = new ArrayList<>();
        boolean all = true;
        blocks = blocksBuilt = 0;
        for(Placement p : places(current)){
            Site.Status s = site.status(p);
            if(s == Site.Status.BLOCKED){ block(p); return; }
            blocks++;
            if(s == Site.Status.BUILT) blocksBuilt++;
            else all = false;
            if(s == Site.Status.FREE) again.add(p);
        }
        if(all){
            state.build(graph, current.step, current.plain);
            events.finished(graph.step(current.step), state.time);
            current = null;
            phase = Phase.WAITING;
            return;
        }
        if(!again.isEmpty() && site.canBuild()) site.queue(again);
    }

    void block(Placement p){
        blockedAt = p;
        phase = Phase.BLOCKED;
        events.blocked(graph.step(current.step), p);
    }

    /** queues again what is gone from finished steps */
    void repair(){
        if(!site.canBuild()) return;
        for(Step s : graph.steps){
            if(s.kind == Step.Kind.UPGRADE || !state.built(s.id)) continue;
            List<Placement> gone = new ArrayList<>();
            for(Placement p : s.placements(state.status[s.id] == StageState.PLAIN)) if(site.status(p) == Site.Status.FREE) gone.add(p);
            if(gone.isEmpty()) continue;
            site.queue(gone);
            events.repaired(s, gone.size());
        }
    }

    void readStock(){
        state.stock.clear();
        for(String it : items) state.stock.put(it, site.stock(it));
    }

    /** the blocks of a pick as it is built */
    public List<Placement> places(Pick p){
        return graph.step(p.step).placements(p.plain);
    }

    /** steps not done yet (upgrades only once their step stands with stand-ins) */
    public int left(){
        int n = 0;
        for(Step s : graph.steps){
            if(state.status[s.id] != StageState.NONE) continue;
            if(s.kind == Step.Kind.UPGRADE && state.status[s.upgradeOf] != StageState.PLAIN) continue;
            n++;
        }
        return n;
    }

    /** steps built so far */
    public int built(){
        int n = 0;
        for(Step s : graph.steps) if(state.built(s.id) && (s.kind != Step.Kind.UPGRADE || state.status[s.upgradeOf] == StageState.FULL)) n++;
        return n;
    }

    public void pause(){
        if(phase == Phase.PAUSED || phase == Phase.DONE || phase == Phase.CANCELLED) return;
        paused = phase;
        phase = Phase.PAUSED;
    }

    public void resume(){
        if(phase != Phase.PAUSED) return;
        phase = paused == Phase.BLOCKED ? Phase.BLOCKED : paused;
        next = state.time;
    }

    /** blocked: look again (the player cleared the tile) */
    public void retry(){
        if(phase != Phase.BLOCKED) return;
        blockedAt = null;
        phase = Phase.WAITING;
        next = state.time;
    }

    /** blocked: give up the current step and every step that needs it */
    public void skip(){
        if(current == null) return;
        site.unqueue(places(current));
        state.skip(graph, current.step);
        for(boolean changed = true; changed; ){
            changed = false;
            for(Step s : graph.steps){
                if(state.status[s.id] != StageState.NONE) continue;
                for(int d : s.deps) if(state.status[d] == StageState.SKIPPED){
                    state.skip(graph, s.id);
                    changed = true;
                    break;
                }
            }
        }
        // the rest of the group may need it too: choose again
        group.clear();
        current = null;
        blockedAt = null;
        phase = Phase.WAITING;
        next = state.time;
    }

    public void cancel(){
        if(current != null) site.unqueue(places(current));
        phase = Phase.CANCELLED;
    }

    public Step step(){ return current == null ? null : graph.step(current.step); }
}
