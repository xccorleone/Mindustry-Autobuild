package autobuild.bootstrap.infra;

import arc.Core;
import arc.Settings;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.sandbox.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.consumers.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Game data the bootstrap domain needs, as the text {@code Catalog.parse} reads: one line per item,
 * liquid and buildable block. {@link #text()} runs in the game (live catalog); {@link #main} loads the
 * content headlessly and writes the same text to the file given (the offline test fixture).
 */
public class CatalogDump{
    public static void main(String[] args) throws IOException{
        Core.settings = new Settings();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Files.writeString(Path.of(args[0]), text()); // not stdout: the game logs there
    }

    public static String text(){
        StringBuilder s = new StringBuilder();
        for(Item i : Vars.content.items()){
            s.append("item ").append(i.name).append(" id=").append(i.id).append(" hardness=").append(i.hardness)
                .append(" flammability=").append(i.flammability).append(" lowPriority=").append(i.lowPriority).append('\n');
        }
        for(Liquid l : Vars.content.liquids()) s.append("liquid ").append(l.name).append(" id=").append(l.id).append('\n');
        for(Block b : Vars.content.blocks()){
            boolean marker = b instanceof ItemSource || b instanceof ItemVoid || b instanceof LiquidSource;
            if(b.requirements.length == 0 && !marker) continue; // terrain, cores' hidden variants
            s.append("block ").append(b.name).append(" size=").append(b.size).append(" kind=").append(kind(b));
            if(b.requirements.length > 0) s.append(" req=").append(stacks(b.requirements));
            for(Consume c : b.consumers){
                if(c instanceof ConsumeItems ci) s.append(" in=").append(stacks(ci.items));
                if(c instanceof ConsumePower cp && !cp.buffered) s.append(" power=").append(cp.usage);
                if(c instanceof ConsumeLiquid cl && !(b instanceof Drill)) s.append(" liquid=").append(cl.liquid.name).append(':').append(cl.amount);
                if(c instanceof ConsumeItemFlammable) s.append(" fuel=flammable");
            }
            if(b instanceof GenericCrafter g){
                s.append(" craft=").append(g.craftTime);
                if(g.outputItems != null) s.append(" out=").append(stacks(g.outputItems));
            }
            if(b instanceof PowerGenerator g && g.powerProduction > 0) s.append(" gen=").append(g.powerProduction);
            if(b instanceof ConsumeGenerator g) s.append(" duration=").append(g.itemDuration);
            if(b instanceof Conveyor c) s.append(" speed=").append(c.displayedSpeed);
            if(b instanceof ItemBridge ib) s.append(" range=").append(ib.range);
            if(b instanceof PowerNode pn) s.append(" range=").append(pn.laserRange).append(" links=").append(pn.maxNodes);
            if(b instanceof Pump p) s.append(" pump=").append(p.pumpAmount);
            if(b instanceof Sorter so && so.invert) s.append(" invert=true");
            if(b instanceof OverflowGate og && og.invert) s.append(" invert=true");
            if(b instanceof Drill d){
                s.append(" tier=").append(d.tier).append(" boost=").append(d.liquidBoostIntensity).append(" drill=");
                StringJoiner j = new StringJoiner(",");
                for(Item i : Vars.content.items()) if(i.hardness <= d.tier) j.add(i.name + ":" + d.getDrillTime(i));
                s.append(j);
            }
            s.append('\n');
        }
        return s.toString();
    }

    static String stacks(ItemStack[] st){
        StringJoiner j = new StringJoiner(",");
        for(ItemStack i : st) j.add(i.item.name + ":" + i.amount);
        return j.toString();
    }

    static String kind(Block b){
        if(b instanceof ItemSource) return "itemsource";
        if(b instanceof LiquidSource) return "liquidsource";
        if(b instanceof ItemVoid) return "itemvoid";
        if(b instanceof GenericCrafter) return "crafter";
        if(b instanceof Drill) return "drill";
        if(b instanceof Pump) return "pump";
        if(b instanceof PowerNode) return "node";
        if(b instanceof Battery) return "battery";
        if(b instanceof PowerGenerator) return "generator";
        if(b instanceof StackConveyor) return "other";
        if(b instanceof Conveyor) return "conveyor";
        if(b instanceof LiquidBridge) return "liquidbridge";
        if(b instanceof ItemBridge) return "bridge";
        if(b instanceof Sorter) return "sorter";
        if(b instanceof OverflowGate) return "gate";
        if(b instanceof Junction) return "junction";
        if(b instanceof Router) return "router";
        if(b instanceof LiquidRouter) return "liquidrouter";
        if(b instanceof Conduit) return "conduit";
        if(b instanceof CoreBlock) return "core";
        return "other";
    }
}
