package autobuild.drones;

import arc.struct.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.defense.turrets.BaseTurret.*;

import static mindustry.Vars.*;

/** Danger circles for a drone: enemy turrets that target air and armed enemy units that target air. */
final class ThreatScan{
    /** safety margins on top of a range, world units: units move, so theirs is wider */
    static final float turretPad = 2 * tilesize, unitPad = 3 * tilesize;
    /** longest turret / unit range in the loaded content (with some room for ammo range bonuses) */
    static float maxTurretRange = -1, maxUnitRange = -1;

    private ThreatScan(){}

    /** adds every danger circle that reaches into the rectangle (x, y, w, h), grown by the drone's half size */
    static void collect(Unit drone, float x, float y, float w, float h, SafeSpots out){
        if(maxTurretRange < 0) measure();
        float grow = drone.hitSize / 2f;
        float tr = maxTurretRange + turretPad + grow, ur = maxUnitRange + unitPad + grow;
        Seq<TeamData> data = state.teams.present;
        for(int i = 0; i < data.size; i++){
            TeamData other = data.items[i];
            if(other.team == drone.team || other.team == Team.derelict) continue;
            if(other.turretTree != null){
                other.turretTree.intersect(x - tr, y - tr, w + tr * 2, h + tr * 2, b -> {
                    if(!(b.block instanceof Turret t) || !t.targetAir || !b.isValid()) return;
                    float r = (b instanceof BaseTurretBuild tb ? tb.range() : t.range) + turretPad + grow;
                    if(reaches(b.x, b.y, r, x, y, w, h)) out.add(b.x, b.y, r);
                });
            }
            other.tree().intersect(x - ur, y - ur, w + ur * 2, h + ur * 2, u -> {
                if(!armedAgainstAir(u)) return;
                float r = u.range() + unitPad + grow;
                if(reaches(u.x, u.y, r, x, y, w, h)) out.add(u.x, u.y, r);
            });
        }
    }

    static boolean armedAgainstAir(Unit u){
        return !u.dead && u.type.targetAir && u.type.canAttack && u.hasWeapons() && !u.disarmed;
    }

    /** the circle (cx, cy, r) reaches into the rectangle */
    static boolean reaches(float cx, float cy, float r, float x, float y, float w, float h){
        float dx = cx - Math.max(x, Math.min(cx, x + w)), dy = cy - Math.max(y, Math.min(cy, y + h));
        return dx * dx + dy * dy < r * r;
    }

    static void measure(){
        maxTurretRange = 0;
        for(Block b : content.blocks()) if(b instanceof Turret t) maxTurretRange = Math.max(maxTurretRange, t.range);
        maxTurretRange += 10 * tilesize;
        maxUnitRange = 0;
        for(UnitType t : content.units()) maxUnitRange = Math.max(maxUnitRange, t.maxRange);
    }
}
