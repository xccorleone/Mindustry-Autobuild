package autobuild.drones;

import arc.math.geom.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.ai.types.*;
import mindustry.gen.*;
import mindustry.world.blocks.ConstructBlock.*;

import static mindustry.Vars.*;

/**
 * The repair command for poly and mega: vanilla {@link RepairAI} (closest damaged building, heal
 * shots, idle retreat), but the drone only repairs from a safe spot. Where it can be shot it stops
 * shooting and moves to a safe spot within weapon range; with none, it gives the building up for a
 * while, repairs the closest other one and flees.
 */
public class SafeRepairAI extends RepairAI{
    /** share of the weapon range to repair from (vanilla closes in to 0.65) */
    static final float rangeShare = 0.8f;

    final SafeWork guard = new SafeWork();
    /** the vanilla movement is ignored while the guard steers */
    boolean muted;
    @Nullable Building other;
    float otherTimer;

    @Override
    public void updateTargeting(){
        super.updateTargeting();
        if(!SafeDrones.enabled()) return;
        // vanilla always takes the closest damaged building: past one given up, the closest other one
        if(target instanceof Building b && b.team == unit.team && guard.dropped(b.pos())){
            if((otherTimer -= Time.delta) <= 0f || !usable(other)){
                otherTimer = 15f;
                other = indexer.getDamaged(unit.team).min(this::usable, c -> c.dst2(unit));
            }
            target = usable(other) ? other : null;
        }
    }

    boolean usable(@Nullable Building b){
        return b != null && b.isValid() && b.damaged() && !(b instanceof ConstructBuild) && !guard.dropped(b.pos());
    }

    @Override
    public void updateMovement(){
        if(!SafeDrones.enabled() || hasStance(UnitStance.holdPosition)){
            super.updateMovement();
            return;
        }

        Building job = target instanceof Building b && b.team == unit.team ? b : null;
        SafeWork.Mode mode = job == null ? guard.update(unit, -1, 0f, 0f, 0f)
            : guard.update(unit, job.pos(), job.x, job.y, unit.type.range * rangeShare);
        if(mode == SafeWork.Mode.flee && job != null){
            guard.drop(job.pos());
            target = null;
            job = null;
        }

        boolean steer = job != null || mode != SafeWork.Mode.work;
        muted = steer;
        super.updateMovement();
        muted = false;

        if(steer) guard.steer(this);
        if(guard.inDanger) unit.controlWeapons(false);
    }

    @Override
    public void moveTo(Position target, float circleLength, float smooth, boolean keepDistance, @Nullable Vec2 offset, boolean arrive){
        if(!muted) super.moveTo(target, circleLength, smooth, keepDistance, offset, arrive);
    }
}
