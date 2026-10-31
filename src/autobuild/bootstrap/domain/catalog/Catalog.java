package autobuild.bootstrap.domain.catalog;

import autobuild.bootstrap.domain.shared.Bom;

import java.util.*;

/**
 * What the bootstrap domain knows about the game's content, read from the text
 * {@code autobuild.bootstrap.infra.CatalogDump} writes: one line per item, liquid and block, with
 * {@code key=value} fields. In the game it is built from the live content; offline tests read a dump
 * of v160.7. Rates in the game's units: per tick (60 ticks = 1 second), except conveyor speed (items
 * per second, as the game displays it).
 */
public class Catalog{
    public static final class ItemInfo{
        public final String name;
        public final int id, hardness;
        public final float flammability;
        public final boolean lowPriority;

        ItemInfo(String name, Map<String, String> f){
            this.name = name;
            id = Integer.parseInt(f.get("id"));
            hardness = Integer.parseInt(f.get("hardness"));
            flammability = Float.parseFloat(f.get("flammability"));
            lowPriority = Boolean.parseBoolean(f.get("lowPriority"));
        }
    }

    public static final class BlockInfo{
        public final String name, kind;
        public final int size, links, tier;
        /** build cost; crafter inputs per craft; crafter outputs per craft */
        public final Bom req, in, out;
        /** ticks per craft; power used / produced per tick; ticks one fuel item lasts */
        public final float craft, power, gen, duration;
        /** conveyor items per second; bridge range (tiles) or node laser range (tiles) */
        public final float speed, range;
        /** pump: liquid per tick per tile; drill: liquid boost speed factor */
        public final float pump, boost;
        /** consumed liquid (crafters, generators) and its amount per tick */
        public final String liquid;
        public final float liquidAmount;
        /** burns any flammable item; sorter / gate inverted */
        public final boolean fuel, invert;
        /** drill: ticks per item for each mineable item (game's getDrillTime) */
        public final Map<String, Float> drillTime = new TreeMap<>();

        BlockInfo(String name, Map<String, String> f){
            this.name = name;
            kind = f.get("kind");
            size = Integer.parseInt(f.getOrDefault("size", "1"));
            links = Integer.parseInt(f.getOrDefault("links", "0"));
            tier = Integer.parseInt(f.getOrDefault("tier", "0"));
            req = Bom.parse(f.get("req"));
            in = Bom.parse(f.get("in"));
            out = Bom.parse(f.get("out"));
            craft = num(f, "craft");
            power = num(f, "power");
            gen = num(f, "gen");
            duration = num(f, "duration");
            speed = num(f, "speed");
            range = num(f, "range");
            pump = num(f, "pump");
            boost = num(f, "boost");
            fuel = "flammable".equals(f.get("fuel"));
            invert = Boolean.parseBoolean(f.get("invert"));
            String liq = f.get("liquid");
            liquid = liq == null ? null : liq.substring(0, liq.lastIndexOf(':'));
            liquidAmount = liq == null ? 0f : Float.parseFloat(liq.substring(liq.lastIndexOf(':') + 1));
            String drill = f.get("drill");
            if(drill != null) for(String part : drill.split(",")){
                int c = part.lastIndexOf(':');
                drillTime.put(part.substring(0, c), Float.parseFloat(part.substring(c + 1)));
            }
        }

        static float num(Map<String, String> f, String key){
            String v = f.get(key);
            return v == null ? 0f : Float.parseFloat(v);
        }

        /** footprint of a block placed at x: tiles x + lo() .. x + hi() (the game's offset for even sizes) */
        public int lo(){ return -(size - 1) / 2; }

        public int hi(){ return size / 2; }
    }

    final Map<String, ItemInfo> items = new LinkedHashMap<>();
    final Map<Integer, ItemInfo> itemIds = new HashMap<>();
    final Map<Integer, String> liquidIds = new HashMap<>();
    final Map<String, BlockInfo> blocks = new LinkedHashMap<>();

    public static Catalog parse(String text){
        Catalog c = new Catalog();
        for(String line : text.split("\n")){
            line = line.trim();
            if(line.isEmpty()) continue;
            String[] w = line.split(" ");
            Map<String, String> f = new HashMap<>();
            for(int i = 2; i < w.length; i++){
                int e = w[i].indexOf('=');
                f.put(w[i].substring(0, e), w[i].substring(e + 1));
            }
            switch(w[0]){
                case "item" -> {
                    ItemInfo it = new ItemInfo(w[1], f);
                    c.items.put(it.name, it);
                    c.itemIds.put(it.id, it);
                }
                case "liquid" -> c.liquidIds.put(Integer.parseInt(f.get("id")), w[1]);
                case "block" -> c.blocks.put(w[1], new BlockInfo(w[1], f));
                default -> throw new IllegalArgumentException("catalog line: " + line);
            }
        }
        return c;
    }

    public ItemInfo item(String name){ return items.get(name); }

    public ItemInfo itemById(int id){ return itemIds.get(id); }

    public String liquidById(int id){ return liquidIds.get(id); }

    public boolean isLiquid(String name){ return liquidIds.containsValue(name); }

    /** null for blocks the catalog doesn't know */
    public BlockInfo block(String name){ return blocks.get(name); }

    public Collection<BlockInfo> blocks(){ return Collections.unmodifiableCollection(blocks.values()); }
}
