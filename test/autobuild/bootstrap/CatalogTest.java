package autobuild.bootstrap;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;

import static autobuild.bootstrap.Check.*;

final class CatalogTest{
    static void run() throws Exception{
        Catalog c = catalog();
        eq("sand low priority", true, c.item("sand").lowPriority);
        eq("coal id", 5, c.item("coal").id);
        eq("item 9", "silicon", c.itemById(9).name);
        eq("liquid 0", "water", c.liquidById(0));
        yes("water is a liquid", c.isLiquid("water") && !c.isLiquid("coal"));

        BlockInfo smelter = c.block("silicon-smelter");
        eq("smelter kind", "crafter", smelter.kind);
        eq("smelter size", 2, smelter.size);
        eq("smelter in", "coal:1,sand:2", smelter.in);
        eq("smelter out", "silicon:1", smelter.out);
        near("smelter craft", 40, smelter.craft);
        near("smelter power", 0.5, smelter.power);
        eq("smelter cost", "copper:30,lead:25", smelter.req);
        eq("even footprint", "0..1", smelter.lo() + ".." + smelter.hi());

        BlockInfo steam = c.block("steam-generator");
        yes("steam burns fuel", steam.fuel);
        eq("steam liquid", "water", steam.liquid);
        near("steam water", 0.1, steam.liquidAmount);
        near("steam gen", 5.5, steam.gen);
        near("steam duration", 90, steam.duration);

        near("conveyor speed", 5, c.block("conveyor").speed);
        near("titanium conveyor speed", 10, c.block("titanium-conveyor").speed);
        near("bridge range", 4, c.block("bridge-conveyor").range);
        eq("bridge cost", "copper:6,lead:6", c.block("bridge-conveyor").req);
        eq("underflow gate inverted", true, c.block("underflow-gate").invert);
        eq("item source kind", "itemsource", c.block("item-source").kind);

        BlockInfo pneumatic = c.block("pneumatic-drill");
        eq("pneumatic tier", 3, pneumatic.tier);
        near("pneumatic on titanium", 550, pneumatic.drillTime.get("titanium"));
        yes("mechanical can't mine titanium", !c.block("mechanical-drill").drillTime.containsKey("titanium"));
        near("pump per tile", 7, c.block("mechanical-pump").pump * 60);
    }
}
