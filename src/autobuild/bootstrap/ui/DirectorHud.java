package autobuild.bootstrap.ui;

import arc.scene.ui.layout.*;
import autobuild.*;
import autobuild.bootstrap.app.Director;
import autobuild.bootstrap.domain.staging.Step;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

/** Bottom left while a confirmed base is being built: the current step, what it waits for, the controls. */
final class DirectorHud{
    private DirectorHud(){}

    static void build(BootstrapJob job){
        ui.hudGroup.fill(t -> {
            t.bottom().left().marginBottom(170f).marginLeft(10f);
            t.table(Styles.black6, b -> {
                b.label(() -> job.director == null ? "" : text(job.director)).pad(6f).left().wrap().width(420f).row();
                b.table(r -> {
                    r.defaults().size(120f, 40f);
                    r.button(bt -> bt.label(() -> job.director != null && job.director.phase == Director.Phase.PAUSED ? Tr.t("boot.hud.resume") : Tr.t("boot.hud.pause")),
                        Styles.defaultt, () -> {
                            Director d = job.director;
                            if(d.phase == Director.Phase.PAUSED) d.resume();
                            else d.pause();
                        }).visible(() -> active(job));
                    r.button(Tr.t("boot.hud.retry"), Icon.refresh, () -> job.director.retry()).visible(() -> blocked(job));
                    r.button(Tr.t("boot.hud.skip"), Icon.right, () -> job.director.skip()).visible(() -> blocked(job));
                    r.button(Tr.t("btn.cancel"), Icon.cancel, () -> job.director.cancel()).visible(() -> active(job));
                    r.button(Tr.t("boot.hud.close"), Icon.ok, () -> job.director = null).visible(() -> !active(job));
                }).left();
            }).visible(() -> job.director != null && state.isGame());
        });
    }

    static boolean active(BootstrapJob job){
        return job.director != null && job.director.phase != Director.Phase.DONE && job.director.phase != Director.Phase.CANCELLED;
    }

    static boolean blocked(BootstrapJob job){
        return job.director != null && job.director.phase == Director.Phase.BLOCKED;
    }

    static String text(Director d){
        StringBuilder sb = new StringBuilder(Tr.t("boot.hud.title", d.built(), d.built() + d.left()));
        Step s = d.step();
        if(s != null) sb.append("\n[accent]").append(BootstrapJob.stepName(s)).append(d.current.plain ? "*" : "").append("[]");
        sb.append("\n");
        switch(d.phase){
            case WAITING -> {
                if(d.stuck){
                    StringBuilder miss = new StringBuilder();
                    for(String it : d.missing) miss.append(miss.length() > 0 ? Tr.t("sep") : "").append(BootstrapJob.label(it));
                    sb.append("[orange]").append(Tr.t("boot.hud.stuck", miss)).append("[]");
                }else if(!d.missing.isEmpty()){
                    StringBuilder miss = new StringBuilder();
                    for(String it : d.missing){
                        Item i = content.item(it);
                        if(miss.length() > 0) miss.append("  ");
                        miss.append(i != null ? i.emoji() : it).append(' ').append((int)Math.floor(stock(it))).append('/').append(d.need.get(it));
                    }
                    sb.append(Tr.t("boot.hud.waiting", miss));
                }else sb.append(Tr.t("boot.hud.noUnit"));
            }
            case BUILDING -> sb.append(Tr.t("boot.hud.building", d.blocksBuilt, d.blocks));
            case BLOCKED -> sb.append("[scarlet]").append(Tr.t("boot.hud.blocked", d.blockedAt == null ? "" : d.blockedAt.x + "," + d.blockedAt.y)).append("[]");
            case PAUSED -> sb.append(Tr.t("boot.hud.paused"));
            case DONE -> sb.append("[accent]").append(Tr.t("boot.hud.done")).append("[]");
            case CANCELLED -> sb.append(Tr.t("boot.hud.cancelled"));
        }
        return sb.toString();
    }

    static double stock(String item){
        var core = player.team().core();
        Item i = content.item(item);
        return core == null || i == null ? 0 : core.items.get(i);
    }
}
