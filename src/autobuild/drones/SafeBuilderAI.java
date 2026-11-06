package autobuild.drones;

import arc.math.geom.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.ai.types.*;
import mindustry.entities.units.*;

/**
 * The rebuild command for poly and mega: vanilla {@link BuilderAI} (plan choice, validation,
 * following, idle retreat), but the drone only works from a safe spot. Where it can be shot it
 * pauses and moves to a safe spot within build range; with none, it gives the plan up for a while
 * and flees.
 */
public class SafeBuilderAI extends BuilderAI{
    final SafeWork guard = new SafeWork();
    /** the vanilla movement is ignored while the guard steers */
    boolean muted;

    @Override
    public void updateMovement(){
        if(!SafeDrones.enabled() || hasStance(UnitStance.holdPosition)){
            super.updateMovement();
            return;
        }

        BuildPlan req = unit.buildPlan();
        // vanilla takes a plan given up a moment ago again (from the team's queue, or from the unit it follows)
        if(req != null && guard.dropped(Point2.pack(req.x, req.y))){
            abandon();
            req = unit.buildPlan();
        }

        float range = Math.min(unit.type.buildRange - unit.type.hitSize * 2f, buildRadius);
        SafeWork.Mode mode = req == null ? guard.update(unit, -1, 0f, 0f, 0f)
            : guard.update(unit, Point2.pack(req.x, req.y), req.drawx(), req.drawy(), range);
        if(mode == SafeWork.Mode.flee && req != null){
            guard.drop(Point2.pack(req.x, req.y));
            abandon();
            req = null;
        }

        boolean steer = req != null || mode != SafeWork.Mode.work;
        muted = steer;
        super.updateMovement();
        muted = false;

        if(steer) guard.steer(this);
        if(guard.inDanger) unit.updateBuilding = false;
    }

    /** drops the current plan (and whoever it came from) */
    void abandon(){
        unit.plans.removeFirst();
        lastPlan = null;
        following = null;
    }

    @Override
    public void moveTo(Position target, float circleLength, float smooth, boolean keepDistance, @Nullable Vec2 offset, boolean arrive){
        if(!muted) super.moveTo(target, circleLength, smooth, keepDistance, offset, arrive);
    }
}
