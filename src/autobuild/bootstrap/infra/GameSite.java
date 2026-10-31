package autobuild.bootstrap.infra;

import arc.struct.*;
import autobuild.bootstrap.app.Director;
import autobuild.bootstrap.domain.shared.Placement;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * The game as the director sees it (main thread): the team's core, and the player's unit building
 * what is queued - or, for server tests, a pretend builder placing at most `perSecond` blocks a second,
 * each once the core pays its cost.
 */
public final class GameSite implements Director.Site{
    final Team team;
    /** planned block -> stand-in: a stand-in standing where its planned block goes is replaced, not in the way */
    final Map<String, String> standIns;
    /** server tests: blocks waiting to be placed (null: the player's unit builds) */
    final ArrayDeque<Placement> instant;
    final float perSecond;
    float budget;

    GameSite(Team team, Map<String, String> standIns, boolean instant, float perSecond){
        this.team = team;
        this.standIns = standIns;
        this.instant = instant ? new ArrayDeque<>() : null;
        this.perSecond = perSecond;
    }

    public static GameSite player(Team team, Map<String, String> standIns){
        return new GameSite(team, standIns, false, 0);
    }

    public static GameSite instant(Team team, Map<String, String> standIns, float perSecond){
        return new GameSite(team, standIns, true, perSecond);
    }

    @Override
    public double stock(String item){
        Building core = team.core();
        Item it = content.item(item);
        return core == null || it == null ? 0 : core.items.get(it);
    }

    @Override
    public boolean infinite(){
        return state.rules.infiniteResources || state.rules.teams.get(team).infiniteResources;
    }

    @Override
    public Status status(Placement p){
        Tile t = world.tile(p.x, p.y);
        Block b = content.block(p.block);
        if(t == null || b == null) return Status.BLOCKED;
        if(t.build != null && t.build.tile == t && t.block() == b) return Status.BUILT;
        if(t.build instanceof ConstructBuild c && c.tile == t && c.current == b) return Status.PENDING;
        if(queued(p)) return Status.PENDING;
        String stand = standIns.get(p.block);
        int off = (b.size - 1) / 2;
        for(int i = 0; i < b.size; i++) for(int j = 0; j < b.size; j++){
            Tile f = world.tile(p.x - off + i, p.y - off + j);
            if(f == null) return Status.BLOCKED;
            if(f.build == null) continue;
            if(stand != null && f.block().name.equals(stand) && f.build.tile.x == p.x && f.build.tile.y == p.y) continue;
            return Status.BLOCKED;
        }
        return Status.FREE;
    }

    boolean queued(Placement p){
        if(instant != null){
            for(Placement q : instant) if(same(q, p)) return true;
            return false;
        }
        Unit u = player.unit();
        if(u == null) return false;
        for(BuildPlan q : u.plans()) if(!q.breaking && q.x == p.x && q.y == p.y && q.block.name.equals(p.block)) return true;
        return false;
    }

    static boolean same(Placement a, Placement b){ return a.x == b.x && a.y == b.y && a.block.equals(b.block); }

    @Override
    public void queue(List<Placement> places){
        if(instant != null){
            for(Placement p : places) if(!queued(p)) instant.add(p);
            return;
        }
        Unit u = player.unit();
        if(u == null) return;
        Seq<BuildPlan> added = new Seq<>();
        for(Placement p : places){
            if(queued(p)) continue;
            BuildPlan bp = GamePlans.toBuildPlan(p);
            u.addBuild(bp);
            added.add(bp);
        }
        GamePlans.watchBridges(added);
    }

    @Override
    public void unqueue(List<Placement> places){
        if(instant != null){
            instant.removeIf(q -> places.stream().anyMatch(p -> same(p, q)));
            return;
        }
        Unit u = player.unit();
        if(u == null) return;
        for(int i = u.plans().size - 1; i >= 0; i--){
            BuildPlan q = u.plans().get(i);
            for(Placement p : places) if(!q.breaking && q.x == p.x && q.y == p.y && q.block.name.equals(p.block)){
                u.plans().removeIndex(i);
                break;
            }
        }
    }

    @Override
    public boolean canBuild(){
        if(instant != null) return true;
        Unit u = player.unit();
        return u != null && !u.dead && u.canBuild();
    }

    /** server tests: place what the core pays for, in queue order, at most `perSecond` blocks a second */
    public void update(float secs){
        if(instant == null) return;
        budget = Math.min(budget + perSecond * secs, Math.max(1f, perSecond));
        Building core = team.core();
        while(budget >= 1f && !instant.isEmpty() && core != null){
            Placement p = instant.peek();
            BuildPlan bp = GamePlans.toBuildPlan(p);
            if(!infinite()){
                boolean ok = true;
                for(ItemStack s : bp.block.requirements) ok &= core.items.get(s.item) >= Math.round(s.amount * state.rules.buildCostMultiplier);
                if(!ok) return;
                for(ItemStack s : bp.block.requirements) core.items.remove(s.item, Math.round(s.amount * state.rules.buildCostMultiplier));
            }
            instant.poll();
            budget--;
            GamePlans.placeNow(bp, team);
            GamePlans.forgetLastBridge(bp.block);
        }
    }
}
