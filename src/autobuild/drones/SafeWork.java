package autobuild.drones;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.entities.units.*;
import mindustry.gen.*;

import static mindustry.Vars.*;

/**
 * One drone's safety state: decides every few ticks whether it works where it is, moves to a safe
 * spot from which it can do the same job, or gives the job up and flees; remembers jobs given up and
 * where it got hurt.
 */
final class SafeWork{
    enum Mode{
        /** safe and in range (or idle and safe): hold here and work */
        work,
        /** fly to {@link #spot}, a safe place within range of the job */
        relocate,
        /** no safe place for the job, or idle in danger: fly to {@link #spot}, the refuge */
        flee
    }

    static final float decideEvery = 15f, hurtRadius = 6 * tilesize, hurtTime = 5 * 60f, dropTime = 10 * 60f;

    final SafeSpots spots = new SafeSpots();
    final float[] out = new float[2];
    final Vec2 spot = new Vec2();
    Mode mode = Mode.work;
    /** the drone stands in danger: pause the work */
    boolean inDanger;
    float timer, lastHealth = -1;
    int lastJob = -2;
    /** hurt places: x, y, until (game time) */
    final FloatSeq hurt = new FloatSeq();
    /** jobs given up (tile pos) -> until (game time) */
    final IntFloatMap dropped = new IntFloatMap();

    boolean dropped(int pos){
        float until = dropped.get(pos, -1f);
        if(until < 0) return false;
        if(until < Time.time){
            dropped.remove(pos, 0f);
            return false;
        }
        return true;
    }

    void drop(int pos){
        dropped.put(pos, Time.time + dropTime);
        timer = 0f;
    }

    /**
     * @param job the job's tile pos, -1 when idle
     * @param wx work point, world units
     * @param range how far from the work point the drone can do the job
     */
    Mode update(Unit unit, int job, float wx, float wy, float range){
        if(lastHealth >= 0 && unit.health < lastHealth - 0.001f){
            hurt.add(unit.x, unit.y, Time.time + hurtTime);
            timer = 0f;
        }
        lastHealth = unit.health;
        if(job != lastJob){
            lastJob = job;
            timer = 0f;
        }
        if((timer -= Time.delta) > 0f) return mode;
        timer = decideEvery;

        if(job >= 0){
            scan(unit, Math.min(unit.x, wx) - range, Math.min(unit.y, wy) - range, Math.abs(unit.x - wx) + range * 2, Math.abs(unit.y - wy) + range * 2);
            inDanger = !spots.safe(unit.x, unit.y);
            if(!inDanger && unit.within(wx, wy, range)){
                // hold where it is, or keep the spot it holds if that is still good
                if(mode != Mode.work || !spot.within(wx, wy, range) || !spots.safe(spot.x, spot.y)) spot.set(unit.x, unit.y);
                mode = Mode.work;
            }else if(spots.workSpot(unit.x, unit.y, wx, wy, range, out)){
                spot.set(out[0], out[1]);
                mode = Mode.relocate;
            }else{
                refuge(unit);
            }
        }else{
            // idle: flee while in danger (the circles have margins), then the controller's own idle
            // behaviour (it retreats to the core when enemies are near)
            scanAround(unit);
            inDanger = !spots.safe(unit.x, unit.y);
            if(inDanger) refuge(unit);
            else mode = Mode.work;
        }
        return mode;
    }

    /** flies to {@link #spot} (the controller's own movement is muted meanwhile) */
    void steer(AIController ai){
        ai.moveTo(spot, 4f, 24f, false, null, true);
    }

    void refuge(Unit unit){
        var core = unit.closestCore();
        float hx = core == null ? Float.NaN : core.x, hy = core == null ? Float.NaN : core.y;
        float x0 = unit.x - SafeSpots.refugeMax, y0 = unit.y - SafeSpots.refugeMax, x1 = unit.x + SafeSpots.refugeMax, y1 = unit.y + SafeSpots.refugeMax;
        if(core != null){
            x0 = Math.min(x0, hx); y0 = Math.min(y0, hy);
            x1 = Math.max(x1, hx); y1 = Math.max(y1, hy);
        }
        scan(unit, x0, y0, x1 - x0, y1 - y0);
        inDanger = !spots.safe(unit.x, unit.y);
        spots.refuge(unit.x, unit.y, hx, hy, out);
        spot.set(out[0], out[1]);
        mode = Mode.flee;
    }

    void scanAround(Unit unit){
        float r = unit.hitSize + 2 * tilesize;
        scan(unit, unit.x - r, unit.y - r, r * 2, r * 2);
    }

    void scan(Unit unit, float x, float y, float w, float h){
        spots.clear();
        spots.bounds(world.unitWidth(), world.unitHeight());
        ThreatScan.collect(unit, x, y, w, h, spots);
        for(int i = 0; i < hurt.size; ){
            if(hurt.items[i + 2] < Time.time){
                hurt.removeRange(i, i + 2);
            }else{
                spots.add(hurt.items[i], hurt.items[i + 1], hurtRadius + unit.hitSize / 2f);
                i += 3;
            }
        }
    }
}
