package autobuild.drones;

import arc.*;
import arc.func.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.type.*;

/**
 * Poly and mega under the rebuild and repair commands avoid enemy fire while they work (see
 * {@link SafeBuilderAI}, {@link SafeRepairAI}). The commands' controller factories are wrapped once
 * at mod init; every other unit and command keeps the game's controller. The setting turns it off at
 * once, also for drones already out.
 */
public final class SafeDrones{
    public static final String setting = "autobuild-safe-drones";

    private SafeDrones(){}

    public static boolean enabled(){
        return Core.settings == null || Core.settings.getBool(setting, true);
    }

    public static boolean drone(UnitType type){
        return type == UnitTypes.poly || type == UnitTypes.mega;
    }

    /** after the content is loaded */
    public static void install(){
        wrap(UnitCommand.rebuildCommand, SafeBuilderAI::new);
        wrap(UnitCommand.repairCommand, SafeRepairAI::new);
    }

    static void wrap(UnitCommand command, Prov<AIController> safe){
        Func<Unit, AIController> vanilla = command.controller;
        Func<Unit, AIController> wrapped = u -> drone(u.type) ? safe.get() : vanilla.get(u);
        try{
            // a final field: the game has no hook for a command's controller
            var field = UnitCommand.class.getField("controller");
            field.setAccessible(true);
            field.set(command, wrapped);
        }catch(Exception e){
            Log.err("AutoBuild: drones keep the game's " + command.name + " behaviour", e);
        }
    }
}
