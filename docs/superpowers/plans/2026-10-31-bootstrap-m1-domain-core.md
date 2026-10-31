# 开局产线 M1（领域核心）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现开局产线的纯算法领域核心：游戏数据目录、蓝图解码、模块分析（端口识别 + 物品推断 + 满速速率）、勘探、配平，全部离线可测。

**Architecture:** 新代码放在 `autobuild.bootstrap.domain` 下，按限界上下文分包（shared / catalog / module / survey / production），纯 Java，零游戏依赖（test.sh 会检查）。唯一的游戏侧代码是 `autobuild.bootstrap.infra.CatalogDump`：用游戏 jar 离线导出方块数据作为测试夹具，M2 时也用它在游戏里构建实时目录。设计依据：`docs/superpowers/specs/2026-10-31-bootstrap-production-design.md` 第 3、4.1–4.3 节。

**Tech Stack:** Java 17（无第三方依赖）；离线测试为普通 `main`（沿用 `PlannerTest` 风格，计失败数）；游戏数据来自 Gradle 缓存里的 `Mindustry-v160.7.jar`。

## Global Constraints

- 游戏版本：Mindustry v8 build 160.7（`build.gradle.kts` 里 `mindustryVersion = "v160.7"`），只考虑 Serpulo。
- JDK 17；`test.sh` 优先用 `~/jdk17`，没有就用 PATH 上的 `javac`/`java`。
- `src/autobuild/bootstrap/domain/**` 不得 import `mindustry.*` 或 `arc.*`。
- 代码风格与仓库一致：4 空格缩进，`){` 不加空格，`if(`/`for(` 不加空格，类与非显然方法写 Javadoc。
- 速率单位：物品（液体）每秒；目录里的原始数值是游戏的每 tick（60 tick = 1 秒）。
- 带 Steam 创意工坊标记的蓝图（石墨、玻璃、Starter Factory）只放在 git 忽略的 `test/local/blueprints/`，不提交；测试在文件缺失时打印 SKIP 并跳过。
- 提交信息结尾加：`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- 在功能分支 `feat/bootstrap-m1` 上工作，不直接提交到 `main`。

## File Structure

| 文件 | 职责 |
|---|---|
| `src/autobuild/bootstrap/domain/shared/Dir.java` | 方向常量（游戏的旋转顺序） |
| `src/autobuild/bootstrap/domain/shared/Bom.java` | 物品数量表（造价、配方） |
| `src/autobuild/bootstrap/domain/catalog/Catalog.java` | 解析目录文本：物品、液体、方块属性 |
| `src/autobuild/bootstrap/infra/CatalogDump.java` | 游戏侧：把游戏内容导出为目录文本 |
| `src/autobuild/bootstrap/domain/module/Blueprint.java` | 蓝图的中立表示 |
| `src/autobuild/bootstrap/domain/module/BlueprintCodec.java` | 解码游戏蓝图（msch v0/v1） |
| `src/autobuild/bootstrap/domain/module/ModuleSpec.java` | 模块规格：端口、速率、电力、造价 |
| `src/autobuild/bootstrap/domain/module/ModuleAnalyzer.java` | 端口识别、蓝图内流动模拟、物品分配、满速 |
| `src/autobuild/bootstrap/domain/survey/Terrain.java` | 端口接口：只读地形 |
| `src/autobuild/bootstrap/domain/survey/PathField.java` | 从核心出发的线路代价场 |
| `src/autobuild/bootstrap/domain/survey/OrePatch.java`、`WaterBody.java` | 矿团、水体 |
| `src/autobuild/bootstrap/domain/survey/DangerMask.java` | 敌人路线周边禁建区 |
| `src/autobuild/bootstrap/domain/survey/SurveyService.java` | 勘探：代价场 + 矿团 + 水体 |
| `src/autobuild/bootstrap/domain/production/ProductionGoal.java` | 目标：模块数量、原料线、可用矿机/泵/带速 |
| `src/autobuild/bootstrap/domain/production/SupplyLine.java` | 一条供给线 |
| `src/autobuild/bootstrap/domain/production/ProductionPlan.java` | 配平结果 |
| `src/autobuild/bootstrap/domain/production/ChainSolver.java` | 配平：电厂数量、供给线、矿团分配、降产 |
| `test/autobuild/bootstrap/*.java` | 离线测试（`BootstrapTest` 为入口） |
| `test/autobuild/bootstrap/fixtures/` | 目录快照、用户自有蓝图（提交） |
| `test/local/blueprints/` | 创意工坊蓝图（git 忽略） |
| `test.sh`、`catalogdump.sh`、`.gitignore` | 测试脚本、目录导出脚本、忽略规则 |

---

### Task 1: 测试骨架、共享内核与游戏数据目录

**Files:**
- Create: `src/autobuild/bootstrap/domain/shared/Dir.java`, `src/autobuild/bootstrap/domain/shared/Bom.java`, `src/autobuild/bootstrap/domain/catalog/Catalog.java`, `src/autobuild/bootstrap/infra/CatalogDump.java`, `catalogdump.sh`
- Create: `test/autobuild/bootstrap/Check.java`, `test/autobuild/bootstrap/CatalogTest.java`, `test/autobuild/bootstrap/BootstrapTest.java`
- Create: `test/autobuild/bootstrap/fixtures/catalog-v160.7.txt`（由脚本生成）
- Modify: `test.sh`（整个文件替换）, `.gitignore`

**Interfaces:**
- Produces: `Dir.DX/DY/opposite(int)`；`Bom.parse(String)`, `add`, `addAll`, `get(String)`, `items()`, `toString()`（`"copper:30,lead:25"`，按物品名排序）；`Catalog.parse(String)`, `item(String)`, `itemById(int)`, `liquidById(int)`, `isLiquid(String)`, `block(String)`, `blocks()`；`Catalog.ItemInfo{name,id,hardness,flammability,lowPriority}`；`Catalog.BlockInfo{name,kind,size,links,tier,req,in,out,craft,power,gen,duration,speed,range,pump,boost,liquid,liquidAmount,fuel,invert,drillTime,lo(),hi()}`；`kind` 取值：`crafter drill pump node battery generator conveyor liquidbridge bridge sorter gate junction router liquidrouter conduit core itemsource liquidsource itemvoid other`；测试工具 `Check.eq/near/yes/catalog()/blueprint(path)`，常量 `Check.FIXTURES`、`Check.LOCAL`。

- [ ] **Step 1: 建分支**

```bash
git checkout -b feat/bootstrap-m1
```

- [ ] **Step 2: 写 `test.sh`（整个替换）与 `.gitignore`**

`test.sh`：

```sh
#!/bin/sh
# Offline tests (no game needed). `./test.sh` runs everything: drill and defense planners on random
# maps with invariant checks (prints two ASCII layouts, a few minutes), then the bootstrap domain.
# `./test.sh bootstrap` runs only the bootstrap domain tests (seconds).
set -e
BIN="$HOME/jdk17/bin/"
[ -x "${BIN}javac" ] || BIN="" # else JDK 17 on the PATH
if grep -rlE "import (mindustry|arc)\." src/autobuild/bootstrap/domain; then
    echo "the bootstrap domain must not import game classes"
    exit 1
fi
OUT=$(mktemp -d)
"${BIN}javac" -d "$OUT" src/autobuild/plan/*.java $(find src/autobuild/bootstrap/domain -name '*.java') test/autobuild/*.java test/autobuild/bootstrap/*.java
if [ "$1" != bootstrap ]; then
    "${BIN}java" -cp "$OUT" autobuild.DefenseTest
    "${BIN}java" -cp "$OUT" autobuild.PlannerTest
fi
"${BIN}java" -cp "$OUT" autobuild.bootstrap.BootstrapTest
```

`.gitignore` 末尾加一行：

```
test/local/
```

- [ ] **Step 3: 写测试工具、目录测试和入口**

`test/autobuild/bootstrap/CatalogTest.java`：

```java
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
```

`test/autobuild/bootstrap/BootstrapTest.java`（后续任务会逐行加入各测试）：

```java
package autobuild.bootstrap;

/** Offline tests of the bootstrap domain: run from the repository root (see test.sh). */
public class BootstrapTest{
    public static void main(String[] args) throws Exception{
        CatalogTest.run();
        System.out.println(Check.fails == 0 ? "BOOTSTRAP OK" : Check.fails + " FAILED");
        if(Check.fails > 0) System.exit(1);
    }
}
```

`test/autobuild/bootstrap/Check.java`（精简版，不含 `blueprint()`；Task 2 换成完整版）：

```java
package autobuild.bootstrap;

import autobuild.bootstrap.domain.catalog.Catalog;

import java.io.IOException;
import java.nio.file.*;

/** Assertions counting failures (the offline tests are plain mains, like PlannerTest), and fixtures. */
final class Check{
    static final String FIXTURES = "test/autobuild/bootstrap/fixtures/", LOCAL = "test/local/blueprints/";
    static int fails;
    static Catalog catalog;

    static void eq(String what, Object expected, Object actual){
        if(!String.valueOf(expected).equals(String.valueOf(actual))){
            fails++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }

    static void near(String what, double expected, double actual){
        if(Math.abs(expected - actual) > 1e-3 * Math.max(1, Math.abs(expected))){
            fails++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }

    static void yes(String what, boolean ok){
        if(!ok){
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    static Catalog catalog() throws IOException{
        if(catalog == null) catalog = Catalog.parse(Files.readString(Path.of(FIXTURES + "catalog-v160.7.txt")));
        return catalog;
    }
}
```

- [ ] **Step 4: 运行，确认失败**

Run: `./test.sh bootstrap`
Expected: 编译失败，`package autobuild.bootstrap.domain.catalog does not exist`。

- [ ] **Step 5: 写共享内核与目录**

`src/autobuild/bootstrap/domain/shared/Dir.java`：

```java
package autobuild.bootstrap.domain.shared;

/** Grid directions in the game's rotation order: 0 = +x, 1 = +y, 2 = -x, 3 = -y. */
public final class Dir{
    public static final int[] DX = {1, 0, -1, 0}, DY = {0, 1, 0, -1};

    private Dir(){}

    public static int opposite(int d){ return (d + 2) & 3; }
}
```

`src/autobuild/bootstrap/domain/shared/Bom.java`：

```java
package autobuild.bootstrap.domain.shared;

import java.util.*;

/** Item amounts by item name (a bill of materials, or a recipe's inputs / outputs). */
public final class Bom{
    final TreeMap<String, Integer> amounts = new TreeMap<>();

    /** "copper:30,lead:25" (empty string = empty bill) */
    public static Bom parse(String text){
        Bom b = new Bom();
        if(text == null || text.isEmpty()) return b;
        for(String part : text.split(",")){
            int c = part.lastIndexOf(':');
            b.add(part.substring(0, c), Integer.parseInt(part.substring(c + 1)));
        }
        return b;
    }

    public Bom add(String item, int n){
        if(n != 0) amounts.merge(item, n, Integer::sum);
        return this;
    }

    public Bom addAll(Bom o){
        o.amounts.forEach(this::add);
        return this;
    }

    public int get(String item){ return amounts.getOrDefault(item, 0); }

    public Set<String> items(){ return Collections.unmodifiableSet(amounts.keySet()); }

    public boolean isEmpty(){ return amounts.isEmpty(); }

    @Override public boolean equals(Object o){ return o instanceof Bom b && b.amounts.equals(amounts); }

    @Override public int hashCode(){ return amounts.hashCode(); }

    @Override public String toString(){
        StringJoiner j = new StringJoiner(",");
        amounts.forEach((k, v) -> j.add(k + ":" + v));
        return j.toString();
    }
}
```

`src/autobuild/bootstrap/domain/catalog/Catalog.java`：

```java
package autobuild.bootstrap.domain.catalog;

import autobuild.bootstrap.domain.shared.Bom;

import java.util.*;

/**
 * What the bootstrap domain knows about the game's content, read from the text
 * {@code autobuild.bootstrap.infra.CatalogDump} writes: one line per item, liquid and block, with
 * {@code key=value} fields. In the game it is built from the live content; offline tests read a dump
 * of v160.7. Rates in the game's units: per tick (60 ticks = 1 second).
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
```

- [ ] **Step 6: 写游戏数据导出器与脚本，生成夹具**

`src/autobuild/bootstrap/infra/CatalogDump.java`：

```java
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
```

`catalogdump.sh`（`chmod +x`）：

```sh
#!/bin/sh
# Regenerates the bootstrap tests' game data (test/autobuild/bootstrap/fixtures/catalog-v160.7.txt)
# from the Mindustry jar in the Gradle cache (run ./gradlew jar once to download it).
set -e
BIN="$HOME/jdk17/bin/"
[ -x "${BIN}javac" ] || BIN=""
JAR=$(ls ~/.gradle/caches/modules-2/files-2.1/Anuken/Mindustry/v160.7/*/Mindustry-v160.7.jar | head -1)
OUT=$(mktemp -d)
# -sourcepath: the jar also holds the game's sources, which must not be compiled
"${BIN}javac" -sourcepath src -cp "$JAR" -d "$OUT" src/autobuild/bootstrap/infra/CatalogDump.java
"${BIN}java" -cp "$JAR:$OUT" autobuild.bootstrap.infra.CatalogDump test/autobuild/bootstrap/fixtures/catalog-v160.7.txt > /dev/null
```

Run:

```bash
chmod +x catalogdump.sh && mkdir -p test/autobuild/bootstrap/fixtures && ./catalogdump.sh && wc -l test/autobuild/bootstrap/fixtures/catalog-v160.7.txt && grep -E "^block (silicon-smelter|conveyor) " test/autobuild/bootstrap/fixtures/catalog-v160.7.txt
```

Expected:

```
     283 test/autobuild/bootstrap/fixtures/catalog-v160.7.txt
block silicon-smelter size=2 kind=crafter req=copper:30,lead:25 in=coal:1,sand:2 power=0.5 craft=40.0 out=silicon:1
block conveyor size=1 kind=conveyor req=copper:1 speed=5.0
```

- [ ] **Step 7: 运行，确认通过**

Run: `./test.sh bootstrap`
Expected: 最后一行 `BOOTSTRAP OK`。

- [ ] **Step 8: 提交**

```bash
git add .gitignore test.sh catalogdump.sh src/autobuild/bootstrap test/autobuild/bootstrap
git commit -m "bootstrap M1: shared kernel, game data catalog and its dump

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 蓝图解码

**Files:**
- Create: `src/autobuild/bootstrap/domain/module/Blueprint.java`, `src/autobuild/bootstrap/domain/module/BlueprintCodec.java`
- Create: `test/autobuild/bootstrap/BlueprintCodecTest.java`, `test/autobuild/bootstrap/fixtures/silicon8.txt`, `test/autobuild/bootstrap/fixtures/generator1.txt`
- Create（不提交）: `test/local/blueprints/{metaglass4,graphite4,starter}.txt`
- Modify: `test/autobuild/bootstrap/Check.java`（替换为完整版）, `test/autobuild/bootstrap/BootstrapTest.java`

**Interfaces:**
- Consumes: `Catalog.block(name).kind`, `Catalog.itemById`, `Catalog.liquidById`
- Produces: `Blueprint(String name, int width, int height, List<Tile> tiles)`，`Blueprint.at(x, y)`；`Blueprint.Tile(String block, int x, int y, int rot, Object config)`，config 归一为 `String`（物品/液体名）、`int[]{dx,dy}`（相对连接）、`int[][]`（多个连接）、`Integer` 或 `null`；`BlueprintCodec.decodeBase64(String, Catalog)`、`decode(byte[], Catalog)`，抛 `IOException`；`Check.blueprint(String path)`（文件不存在返回 null 并打印 SKIP）。

- [ ] **Step 1: 夹具与测试**

用户自有蓝图（提交）：

```bash
printf '%s\n' 'bXNjaAB4nE1PW26DMBAcwME8mkRVL5C/th/89x45AQkucgV2BaZRTtujpOvFWMVavDue3dnBETKBMO2o8HLWg75aczqPanBqOr19vBd4dtq1Ri9jQ08/6m4nHC+T7nr1D5jXzmZeOyEvraP7jny2kweKr8VcnbYGh8V0avoc7K3pW6dQfdubmhpjOwXglQKp/yGhkyKjSAOCUK0c/7byCH38PnrKBNWSQtBJ6C6xo2ybJ5BFnmCuzzwj456cJVJC1i+nbFPbRbWcqpIiYSXJe0hWA2MpCgpJJws7S9YCvyDwPOeJXRRxchl8ZKgCT1C2+aiijyr48DwRMc/wk6roo45Tau71W9dRzavv2cc+6AKHoPEHeutBOQ==' > test/autobuild/bootstrap/fixtures/silicon8.txt
printf '%s\n' 'bXNjaAF4nEVN0QqCQBCcOw8Di/wSH4Kir+gLoodTFxH0lPMKIvqmoh+0vVPrYZeZ2Z0ZxIgVkqIzjow76R7y8UTc6JyaAfJ8gTK6JSQVGbLadXaHdUlDYeve1Z0BImxzW5cVZRxSXmuHdHCk2+znQPp/uNGdBWDDg2jFS3jEW0xIsDqO44fhIWjKq3L6lZgJsAJmo6fCe95Mj4HK5SoDVct1H/JCj5p6XnOP8j1fHfg0rQ==' > test/autobuild/bootstrap/fixtures/generator1.txt
```

创意工坊蓝图（只放本地，`.gitignore` 已忽略）：

```bash
mkdir -p test/local/blueprints && cp /private/tmp/claude-501/-Users-corleone-IdeaProjects-Mindustry-Autobuild/a511394e-1d0a-400e-b636-60b27142c334/scratchpad/blueprints/*.txt test/local/blueprints/
```

（若该临时目录已不存在，请向用户索取这三份蓝图的 base64 文本，分别存为 `metaglass4.txt`、`graphite4.txt`、`starter.txt`。）

`test/autobuild/bootstrap/Check.java` 替换为完整版：

```java
package autobuild.bootstrap;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.module.*;

import java.io.IOException;
import java.nio.file.*;

/** Assertions counting failures (the offline tests are plain mains, like PlannerTest), and fixtures. */
final class Check{
    static final String FIXTURES = "test/autobuild/bootstrap/fixtures/", LOCAL = "test/local/blueprints/";
    static int fails;
    static Catalog catalog;

    static void eq(String what, Object expected, Object actual){
        if(!String.valueOf(expected).equals(String.valueOf(actual))){
            fails++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }

    static void near(String what, double expected, double actual){
        if(Math.abs(expected - actual) > 1e-3 * Math.max(1, Math.abs(expected))){
            fails++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }

    static void yes(String what, boolean ok){
        if(!ok){
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    static Catalog catalog() throws IOException{
        if(catalog == null) catalog = Catalog.parse(Files.readString(Path.of(FIXTURES + "catalog-v160.7.txt")));
        return catalog;
    }

    /** a committed fixture, or a local one (null when that file isn't there) */
    static Blueprint blueprint(String path) throws IOException{
        Path p = Path.of(path);
        if(!Files.exists(p)){
            System.out.println("SKIP " + path + " (local fixture missing)");
            return null;
        }
        return BlueprintCodec.decodeBase64(Files.readString(p), catalog());
    }
}
```

`test/autobuild/bootstrap/BlueprintCodecTest.java`：

```java
package autobuild.bootstrap;

import autobuild.bootstrap.domain.module.Blueprint;

import java.util.Arrays;

import static autobuild.bootstrap.Check.*;

final class BlueprintCodecTest{
    static void run() throws Exception{
        // version 0: configs are ints (absolute bridge targets, item ids)
        Blueprint si = blueprint(FIXTURES + "silicon8.txt");
        eq("v0 name", "Silicon Smelter (8)", si.name);
        eq("v0 size", "15x7", si.width + "x" + si.height);
        eq("v0 tiles", 39, si.tiles.size());
        eq("v0 bridge link is relative", "[0, -2]", Arrays.toString((int[])si.at(4, 5).config));
        eq("v0 sorter item", "silicon", si.at(4, 1).config);
        eq("v0 rotation", 3, si.at(4, 6).rot);

        // version 1: TypeIO objects
        Blueprint gen = blueprint(FIXTURES + "generator1.txt");
        eq("v1 size", "6x6", gen.width + "x" + gen.height);
        eq("v1 liquid bridge link", "[1, 0]", Arrays.toString((int[])gen.at(0, 3).config));
        eq("v1 block", "steam-generator", gen.at(1, 4).block);

        // version 1 with a contentMap tag (item ids remapped by name)
        Blueprint kiln = blueprint(LOCAL + "metaglass4.txt");
        if(kiln != null){
            eq("contentMap sorter", "sand", kiln.at(3, 1).config);
            eq("contentMap sorter 2", "lead", kiln.at(4, 1).config);
            eq("power node links", 5, ((int[][])kiln.at(2, 3).config).length);
        }
    }
}
```

`BootstrapTest.main` 里 `CatalogTest.run();` 之后加一行：

```java
        BlueprintCodecTest.run();
```

- [ ] **Step 2: 运行，确认失败**

Run: `./test.sh bootstrap`
Expected: 编译失败，`package autobuild.bootstrap.domain.module does not exist`。

- [ ] **Step 3: 实现**

`src/autobuild/bootstrap/domain/module/Blueprint.java`：

```java
package autobuild.bootstrap.domain.module;

import java.util.*;

/**
 * A game schematic in neutral form. Configs are normalized to: {@code String} (an item or liquid
 * name), {@code int[]{dx, dy}} (a link relative to the block), {@code int[][]} (several links),
 * {@code Integer}, or null for anything else.
 */
public final class Blueprint{
    public static final class Tile{
        public final String block;
        public final int x, y, rot;
        public final Object config;

        public Tile(String block, int x, int y, int rot, Object config){
            this.block = block;
            this.x = x;
            this.y = y;
            this.rot = rot;
            this.config = config;
        }

        @Override public String toString(){ return block + "(" + x + "," + y + ")r" + rot; }
    }

    public final String name;
    public final int width, height;
    public final List<Tile> tiles;

    public Blueprint(String name, int width, int height, List<Tile> tiles){
        this.name = name;
        this.width = width;
        this.height = height;
        this.tiles = List.copyOf(tiles);
    }

    public Tile at(int x, int y){
        for(Tile t : tiles) if(t.x == x && t.y == y) return t;
        return null;
    }
}
```

`src/autobuild/bootstrap/domain/module/BlueprintCodec.java`：

```java
package autobuild.bootstrap.domain.module;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.InflaterInputStream;

/**
 * Decodes the game's schematic format ("msch", versions 0 and 1, as {@code mindustry.game.Schematics}
 * reads it): header, version byte, then deflate data - width, height, tags, block names, and per tile
 * block index, packed position, config, rotation. Version 0 stores configs as ints mapped by block
 * type; version 1 stores {@code TypeIO} objects (only the types schematics carry are read).
 */
public final class BlueprintCodec{
    static final int CONTENT_ITEM = 0, CONTENT_LIQUID = 4;

    private BlueprintCodec(){}

    public static Blueprint decodeBase64(String text, Catalog catalog) throws IOException{
        return decode(Base64.getDecoder().decode(text.trim()), catalog);
    }

    public static Blueprint decode(byte[] bytes, Catalog catalog) throws IOException{
        InputStream in = new ByteArrayInputStream(bytes);
        for(byte b : "msch".getBytes()) if(in.read() != b) throw new IOException("not a schematic (missing header)");
        int ver = in.read();
        if(ver > 1) throw new IOException("unknown schematic version " + ver);
        try(DataInputStream s = new DataInputStream(new InflaterInputStream(in))){
            int w = s.readShort(), h = s.readShort();
            Map<String, String> tags = new HashMap<>();
            int tagCount = s.readUnsignedByte();
            for(int i = 0; i < tagCount; i++) tags.put(s.readUTF(), s.readUTF());
            Map<Integer, Map<Integer, String>> contentMap = contentMap(tags.get("contentMap"));
            String[] names = new String[s.readUnsignedByte()];
            for(int i = 0; i < names.length; i++) names[i] = s.readUTF();
            int total = s.readInt();
            List<Blueprint.Tile> tiles = new ArrayList<>();
            for(int i = 0; i < total; i++){
                String block = names[s.readByte()];
                int pos = s.readInt();
                int x = (short)(pos >>> 16), y = (short)(pos & 0xFFFF);
                Object config = ver == 0 ? mapConfig(catalog.block(block), s.readInt(), x, y, catalog) : readObject(s, catalog, contentMap);
                int rot = s.readByte();
                tiles.add(new Blueprint.Tile(block, x, y, rot, config));
            }
            return new Blueprint(tags.getOrDefault("name", "?"), w, h, tiles);
        }
    }

    /** version 0: the game's Schematics.mapConfig */
    static Object mapConfig(BlockInfo b, int value, int x, int y, Catalog catalog){
        if(b == null) return null;
        switch(b.kind){
            case "sorter", "itemsource" -> {
                Catalog.ItemInfo it = catalog.itemById(value);
                return it == null ? null : it.name;
            }
            case "liquidsource" -> { return catalog.liquidById(value); }
            case "bridge", "liquidbridge" -> { return new int[]{(short)(value >>> 16) - x, (short)(value & 0xFFFF) - y}; }
            default -> { return null; }
        }
    }

    /** version 1: TypeIO.readObject, for the object types a schematic config can hold */
    static Object readObject(DataInputStream s, Catalog catalog, Map<Integer, Map<Integer, String>> contentMap) throws IOException{
        int type = s.readByte();
        switch(type){
            case 0: return null;
            case 1: return s.readInt();
            case 2: s.readLong(); return null;
            case 3: s.readFloat(); return null;
            case 4: return s.readByte() != 0 ? s.readUTF() : null;
            case 5: {
                int ct = s.readByte(), id = s.readShort();
                Map<Integer, String> mapped = contentMap.get(ct);
                if(mapped != null && mapped.containsKey(id)) return mapped.get(id);
                if(ct == CONTENT_ITEM){
                    Catalog.ItemInfo it = catalog.itemById(id);
                    return it == null ? null : it.name;
                }
                return ct == CONTENT_LIQUID ? catalog.liquidById(id) : null;
            }
            case 6: { int n = s.readShort(); for(int i = 0; i < n; i++) s.readInt(); return null; }
            case 7: return new int[]{s.readInt(), s.readInt()};
            case 8: {
                int n = s.readUnsignedByte();
                int[][] out = new int[n][];
                for(int i = 0; i < n; i++){
                    int p = s.readInt();
                    out[i] = new int[]{(short)(p >>> 16), (short)(p & 0xFFFF)};
                }
                return out;
            }
            case 9: s.readByte(); s.readShort(); return null;
            case 10: s.readBoolean(); return null;
            case 11: s.readDouble(); return null;
            case 12: s.readInt(); return null;
            case 13: s.readShort(); return null;
            case 14: { int n = s.readInt(); s.readFully(new byte[n]); return null; }
            case 15: s.readByte(); return null;
            case 16: { int n = s.readInt(); for(int i = 0; i < n; i++) s.readBoolean(); return null; }
            case 17: s.readInt(); return null;
            case 18: { int n = s.readShort(); for(int i = 0; i < n * 2; i++) s.readFloat(); return null; }
            case 19: s.readFloat(); s.readFloat(); return null;
            case 20: s.readUnsignedByte(); return null;
            case 21: { int n = s.readShort(); for(int i = 0; i < n; i++) s.readInt(); return null; }
            case 22: { int n = s.readInt(); for(int i = 0; i < n; i++) readObject(s, catalog, contentMap); return null; }
            case 23: s.readUnsignedShort(); return null;
            default: throw new IOException("unknown config type " + type);
        }
    }

    static final Pattern TYPE = Pattern.compile("(\\d+)\\s*:\\s*\\{([^}]*)\\}"), ENTRY = Pattern.compile("\"?([\\w-]+)\"?\\s*:\\s*(-?\\d+)");

    /** tag "contentMap": {type: {name: id, ...}, ...} (hjson, names unquoted) -> type -> id -> name */
    static Map<Integer, Map<Integer, String>> contentMap(String tag){
        Map<Integer, Map<Integer, String>> out = new HashMap<>();
        if(tag == null) return out;
        Matcher t = TYPE.matcher(tag);
        while(t.find()){
            Map<Integer, String> inner = out.computeIfAbsent(Integer.parseInt(t.group(1)), k -> new HashMap<>());
            Matcher e = ENTRY.matcher(t.group(2));
            while(e.find()) inner.put(Integer.parseInt(e.group(2)), e.group(1));
        }
        return out;
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./test.sh bootstrap`
Expected: `BOOTSTRAP OK`（本地蓝图缺失时会多出 `SKIP` 行，仍应 OK）。

- [ ] **Step 5: 提交**

```bash
git add src/autobuild/bootstrap/domain/module test/autobuild/bootstrap
git status --short test/local   # 应无输出：本地蓝图不入库
git commit -m "bootstrap M1: schematic decoding (msch v0 and v1)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 模块分析（端口、物品推断、满速）

**Files:**
- Create: `src/autobuild/bootstrap/domain/module/ModuleSpec.java`, `src/autobuild/bootstrap/domain/module/ModuleAnalyzer.java`
- Create: `test/autobuild/bootstrap/ModuleAnalyzerTest.java`
- Modify: `test/autobuild/bootstrap/BootstrapTest.java`

**Interfaces:**
- Consumes: `Blueprint`, `Catalog.BlockInfo`（`kind`、`in/out/craft/power/gen/duration/fuel/liquid/liquidAmount/speed/range/invert/req`、`lo()/hi()`）, `Dir`
- Produces: `ModuleAnalyzer.analyze(Blueprint, Catalog, double defaultCap) -> ModuleSpec`；`ModuleAnalyzer.FUEL = "coal"`；`ModuleSpec{name,width,height,inlets,outlets,outputs(Map<String,Double>),working,consumers,factor,powerUse,powerGen,hasNode,cost(Bom),error; ok()}`；`ModuleSpec.Port{x,y,dir,liquid,marker,items(Set<String>),cap,rate; item()}`。端口坐标是"接入格"：边缘端口在蓝图外一格，标记端口在标记自身格；`dir` 为物品移动方向。

关键规则（实现见下方代码的类注释）：
- 入口：边缘上背面朝外的传送带/导管；有有效连接、外侧空、且除连接方向外四周无方块的物品桥/桥接导管；物品源/液体源标记。
- 出口：边缘上正面朝外的传送带；物品黑洞标记。
- 无标记的入口：枚举候选物品（工厂原料，发电机用 `FUEL`）的所有分配，取"满速占比 × 能运转的工厂数"最高者，再取漏到出口最少者；仍并列则报错。
- 满速：`min(1, 各物品供给/需求)`，再受出口带速限制。

- [ ] **Step 1: 写测试**

`test/autobuild/bootstrap/ModuleAnalyzerTest.java`：

```java
package autobuild.bootstrap;

import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.module.ModuleSpec.Port;

import java.util.*;

import static autobuild.bootstrap.Check.*;

final class ModuleAnalyzerTest{
    /** the fastest belt in the tests: titanium */
    static final double CAP = 10;

    static void run() throws Exception{
        silicon();
        generator();
        local();
        errors();
    }

    static String ports(List<Port> ps){
        StringJoiner j = new StringJoiner(" ");
        for(Port p : ps) j.add(String.join("+", p.items) + "@" + p.x + "," + p.y + ">" + p.dir);
        return j.toString();
    }

    static void silicon() throws Exception{
        ModuleSpec s = ModuleAnalyzer.analyze(blueprint(FIXTURES + "silicon8.txt"), catalog(), CAP);
        eq("silicon ok", null, s.error);
        eq("silicon inlets", "coal@-1,2>0 sand@4,7>3 sand@10,7>3", ports(s.inlets));
        eq("silicon outlets", "silicon@15,2>0", ports(s.outlets));
        // 8 smelters make 12/s, the titanium outlet carries 10
        near("silicon factor", 10.0 / 12, s.factor);
        near("silicon output", 10, s.outputs.get("silicon"));
        near("coal inlet", 10, s.inlets.get(0).rate);
        near("sand inlet", 10, s.inlets.get(1).rate);
        near("silicon peak power", 240, s.powerUse);
        eq("silicon working", "8/8", s.working + "/" + s.consumers);
        eq("silicon has node", true, s.hasNode);
        eq("silicon titanium", 4, s.cost.get("titanium"));
    }

    static void generator() throws Exception{
        ModuleSpec g = ModuleAnalyzer.analyze(blueprint(FIXTURES + "generator1.txt"), catalog(), CAP);
        eq("generator ok", null, g.error);
        eq("generator inlets", "water@-1,3>0 coal@3,-1>1", ports(g.inlets));
        eq("water inlet is liquid", true, g.inlets.get(0).liquid);
        near("generator power", 4 * 5.5 * 60, g.powerGen);
        near("generator water", 24, g.inlets.get(0).rate);
        near("generator coal", 4 * 60 / 90.0, g.inlets.get(1).rate);
        eq("generator has no node", false, g.hasNode);
        eq("generator no outlet", 0, g.outlets.size());
    }

    static void local() throws Exception{
        Blueprint kiln = blueprint(LOCAL + "metaglass4.txt");
        if(kiln != null){
            ModuleSpec k = ModuleAnalyzer.analyze(kiln, catalog(), CAP);
            eq("kiln ok", null, k.error);
            eq("kiln inlets", "sand@3,-1>1 lead@4,-1>1", ports(k.inlets));
            eq("kiln outlets", "metaglass@5,-1>3", ports(k.outlets));
            near("kiln output", 10, k.outputs.get("metaglass"));
        }
        Blueprint graphite = blueprint(LOCAL + "graphite4.txt");
        if(graphite != null){
            ModuleSpec g = ModuleAnalyzer.analyze(graphite, catalog(), CAP);
            eq("graphite inlets", "coal@2,6>3", ports(g.inlets));
            eq("graphite outlets", "graphite@2,-1>3", ports(g.outlets));
            near("graphite output", 4 * 60 / 90.0, g.outputs.get("graphite"));
            near("graphite coal", 8 * 60 / 90.0, g.inlets.get(0).rate);
            near("graphite no power", 0, g.powerUse);
        }
        Blueprint starter = blueprint(LOCAL + "starter.txt");
        if(starter != null){
            ModuleSpec st = ModuleAnalyzer.analyze(starter, catalog(), CAP);
            eq("starter ok", null, st.error);
            eq("starter inlets (markers)", "coal@2,6>3 sand@4,6>3 lead@6,6>3", ports(st.inlets));
            eq("starter mixed outlet", "graphite+metaglass+silicon@2,0>3", ports(st.outlets));
            yes("starter inlets are markers", st.inlets.get(0).marker);
            yes("starter powers itself", st.powerGen > st.powerUse);
            eq("markers not in cost", 0, st.cost.get("item-source"));
        }
    }

    static void errors() throws Exception{
        Blueprint unknown = new Blueprint("x", 2, 1, List.of(new Blueprint.Tile("no-such-block", 0, 0, 0, null)));
        yes("unknown block", ModuleAnalyzer.analyze(unknown, catalog(), CAP).error.startsWith("unknown block"));
        Blueprint noInlet = new Blueprint("x", 3, 3, List.of(new Blueprint.Tile("graphite-press", 1, 1, 0, null)));
        eq("no inlet", "no inlet", ModuleAnalyzer.analyze(noInlet, catalog(), CAP).error);
        // a belt straight through: its item can't be told
        Blueprint pass = new Blueprint("x", 3, 1, List.of(
            new Blueprint.Tile("conveyor", 0, 0, 0, null), new Blueprint.Tile("conveyor", 1, 0, 0, null), new Blueprint.Tile("conveyor", 2, 0, 0, null)));
        yes("pass-through belt", ModuleAnalyzer.analyze(pass, catalog(), CAP).error.contains("feeds no crafter"));
    }
}
```

`BootstrapTest.main` 里加：

```java
        ModuleAnalyzerTest.run();
```

- [ ] **Step 2: 运行，确认失败**

Run: `./test.sh bootstrap`
Expected: 编译失败，`cannot find symbol ... ModuleAnalyzer`。

- [ ] **Step 3: 实现**

`src/autobuild/bootstrap/domain/module/ModuleSpec.java`：

```java
package autobuild.bootstrap.domain.module;

import autobuild.bootstrap.domain.shared.Bom;

import java.util.*;

/**
 * What a factory / power-plant blueprint is to the planner: a black box with its footprint, the
 * ports outside lines connect to, its rates at full speed, its power and its cost.
 */
public final class ModuleSpec{
    /**
     * Where an outside line meets the module. (x, y) is the attach tile in blueprint coordinates: the
     * tile the line's last block stands on (inlet) or the first tile items reach outside (outlet) -
     * outside the footprint for edge ports, the marker's own tile for marker ports. {@code dir} is the
     * direction items move: into the module for an inlet, out of it for an outlet.
     */
    public static final class Port{
        public final int x, y, dir;
        public final boolean liquid, marker;
        /** inlet: the item / liquid it takes; outlet: the items leaving through it */
        public final Set<String> items = new TreeSet<>();
        /** most this port can carry per second */
        public final double cap;
        /** per second at the module's full speed */
        public double rate;

        public Port(int x, int y, int dir, boolean liquid, boolean marker, double cap){
            this.x = x;
            this.y = y;
            this.dir = dir;
            this.liquid = liquid;
            this.marker = marker;
            this.cap = cap;
        }

        /** an inlet's single item (or liquid) */
        public String item(){ return items.isEmpty() ? null : items.iterator().next(); }

        @Override public String toString(){ return (liquid ? "~" : "") + items + "@(" + x + "," + y + ")>" + dir; }
    }

    public final String name;
    public final int width, height;
    public final List<Port> inlets = new ArrayList<>(), outlets = new ArrayList<>();
    /** products per second at full speed */
    public final Map<String, Double> outputs = new TreeMap<>();
    /** working crafters and generators, all of them */
    public int working, consumers;
    /** share of its crafters' capacity the module runs at, limited by its ports */
    public double factor;
    /** power per second: used at full speed (peak), produced */
    public double powerUse, powerGen;
    /** has a power node of its own (else the layout adds one) */
    public boolean hasNode;
    /** build cost without the marker blocks */
    public final Bom cost = new Bom();
    /** why the module can't be used, or null */
    public String error;

    public ModuleSpec(String name, int width, int height){
        this.name = name;
        this.width = width;
        this.height = height;
    }

    public boolean ok(){ return error == null; }
}
```

`src/autobuild/bootstrap/domain/module/ModuleAnalyzer.java`：

```java
package autobuild.bootstrap.domain.module;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.module.ModuleSpec.Port;

import java.util.*;

import static autobuild.bootstrap.domain.shared.Dir.*;

/**
 * Turns a {@link Blueprint} into a {@link ModuleSpec}.
 * <ol>
 * <li><b>Ports</b>: on the blueprint's edge, a conveyor (conduit) whose back faces outside is an inlet
 *     and one whose front faces outside an outlet; a linked item (liquid) bridge with a free outside
 *     side and nothing next to it but along its link is an inlet. Sandbox markers are ports too and say what they carry: an item / liquid
 *     source is an inlet, an item void an outlet; markers are not built.</li>
 * <li><b>Flow</b>: an item put into a port moves through the blueprint by the game's push rules -
 *     conveyors push forward and refuse items from their front, junctions pass straight, sorters pass
 *     their item forward and others to the sides (inverted: the reverse), gates and routers spread,
 *     a linked bridge sends to its link and refuses items from the link side, an end bridge dumps to
 *     every side but its incoming bridges - until a crafter or generator consumes it or it leaves.</li>
 * <li><b>Items</b>: an edge inlet's item is not written anywhere, so every assignment of candidate
 *     items (what the module's crafters eat, {@link #FUEL} for generators) is tried; the one letting
 *     the module run fastest wins, then the one leaking least into outlets; a tie is an error.</li>
 * <li><b>Rates</b>: inlets carry at most their belt's speed (bridges and markers: the given default);
 *     the module runs at the share of its crafters' capacity that the inlets and outlets allow.</li>
 * </ol>
 */
public final class ModuleAnalyzer{
    /** what generators are fed: the only fuel the bootstrap produces */
    public static final String FUEL = "coal";
    static final int MAX_ASSIGNMENTS = 1 << 14;
    static final double EPS = 1e-9;

    static final class Part{
        final Blueprint.Tile t;
        final BlockInfo b;
        final int x0, y0, x1, y1;
        /** valid bridge link: target part and direction */
        int link = -1, linkDir = -1;
        /** sides (bit per direction) where bridges linking into this one stand */
        int incoming;

        Part(Blueprint.Tile t, BlockInfo b){
            this.t = t;
            this.b = b;
            x0 = t.x + b.lo();
            y0 = t.y + b.lo();
            x1 = t.x + b.hi();
            y1 = t.y + b.hi();
        }

        String kind(){ return b.kind; }
    }

    /** where an item put into a port ends up */
    static final class Reach{
        final BitSet consumers = new BitSet(), outlets = new BitSet();
        boolean leak;
    }

    final Blueprint bp;
    final Catalog cat;
    final double defaultCap;
    final List<Part> parts = new ArrayList<>();
    final int[][] grid;
    final List<Integer> consumerParts = new ArrayList<>();
    final List<Port> inlets = new ArrayList<>(), outlets = new ArrayList<>();

    ModuleAnalyzer(Blueprint bp, Catalog cat, double defaultCap){
        this.bp = bp;
        this.cat = cat;
        this.defaultCap = defaultCap;
        grid = new int[bp.width][bp.height];
        for(int[] row : grid) Arrays.fill(row, -1);
    }

    /** @param defaultCap items per second a bridge or marker port carries (the fastest belt in use) */
    public static ModuleSpec analyze(Blueprint bp, Catalog cat, double defaultCap){
        ModuleAnalyzer a = new ModuleAnalyzer(bp, cat, defaultCap);
        ModuleSpec spec = new ModuleSpec(bp.name, bp.width, bp.height);
        try{
            a.run(spec);
        }catch(Failure f){
            spec.error = f.getMessage();
        }
        return spec;
    }

    static final class Failure extends RuntimeException{
        Failure(String msg){ super(msg, null, false, false); }
    }

    void run(ModuleSpec spec){
        for(Blueprint.Tile t : bp.tiles){
            BlockInfo b = cat.block(t.block);
            if(b == null) throw new Failure("unknown block " + t.block);
            Part p = new Part(t, b);
            for(int x = p.x0; x <= p.x1; x++) for(int y = p.y0; y <= p.y1; y++){
                if(!in(x, y)) throw new Failure(t.block + " sticks out of the blueprint");
                grid[x][y] = parts.size();
            }
            parts.add(p);
            if(!marker(p)) spec.cost.addAll(b.req);
            if(p.kind().equals("node")) spec.hasNode = true;
        }
        linkBridges();
        for(int i = 0; i < parts.size(); i++){
            String k = parts.get(i).kind();
            if(k.equals("crafter") || k.equals("generator")) consumerParts.add(i);
        }
        findPorts();
        spec.inlets.addAll(inlets);
        spec.outlets.addAll(outlets);
        spec.consumers = consumerParts.size();
        assign(spec);
    }

    boolean in(int x, int y){ return x >= 0 && y >= 0 && x < bp.width && y < bp.height; }

    static boolean marker(Part p){
        return p.kind().equals("itemsource") || p.kind().equals("liquidsource") || p.kind().equals("itemvoid");
    }

    Part partAt(int x, int y){ return in(x, y) && grid[x][y] >= 0 ? parts.get(grid[x][y]) : null; }

    void linkBridges(){
        for(Part p : parts){
            if(!(p.kind().equals("bridge") || p.kind().equals("liquidbridge")) || !(p.t.config instanceof int[] d)) continue;
            if((d[0] == 0) == (d[1] == 0) || Math.abs(d[0] + d[1]) > p.b.range) continue;
            Part q = partAt(p.t.x + d[0], p.t.y + d[1]);
            if(q == null || !q.t.block.equals(p.t.block)) continue;
            p.link = grid[q.t.x][q.t.y];
            p.linkDir = d[0] > 0 ? 0 : d[1] > 0 ? 1 : d[0] < 0 ? 2 : 3;
            q.incoming |= 1 << opposite(p.linkDir);
        }
    }

    // ---- ports ----

    void findPorts(){
        for(Part p : parts){
            int x = p.t.x, y = p.t.y, r = p.t.rot & 3;
            switch(p.kind()){
                case "conveyor", "conduit" -> {
                    boolean liquid = p.kind().equals("conduit");
                    double cap = liquid ? Double.MAX_VALUE : p.b.speed;
                    if(!in(x - DX[r], y - DY[r])) inlets.add(new Port(x - DX[r], y - DY[r], r, liquid, false, cap));
                    else if(!in(x + DX[r], y + DY[r])) outlets.add(new Port(x + DX[r], y + DY[r], r, liquid, false, cap));
                }
                case "bridge", "liquidbridge" -> {
                    if(p.link < 0 || fedInside(p)) break;
                    boolean liquid = p.kind().equals("liquidbridge");
                    for(int s = 0; s < 4; s++){
                        if(s == p.linkDir || in(x + DX[s], y + DY[s])) continue;
                        inlets.add(new Port(x + DX[s], y + DY[s], opposite(s), liquid, false, liquid ? Double.MAX_VALUE : defaultCap));
                        break;
                    }
                }
                case "itemsource", "liquidsource" -> {
                    boolean liquid = p.kind().equals("liquidsource");
                    if(!(p.t.config instanceof String item)) throw new Failure("source marker at (" + x + "," + y + ") has no item");
                    int dir = -1;
                    for(int e = 0; e < 4 && dir < 0; e++){
                        Part q = partAt(x + DX[e], y + DY[e]);
                        if(q != null && accepts(q, e, item, liquid, false)) dir = e;
                    }
                    if(dir < 0) throw new Failure("source marker at (" + x + "," + y + ") feeds nothing");
                    Port port = new Port(x, y, dir, liquid, true, liquid ? Double.MAX_VALUE : defaultCap);
                    port.items.add(item);
                    inlets.add(port);
                }
                case "itemvoid" -> {
                    int dir = -1;
                    for(int e = 0; e < 4 && dir < 0; e++){
                        Part q = partAt(x - DX[e], y - DY[e]);
                        if(q != null && q.kind().equals("conveyor") && (q.t.rot & 3) == e) dir = e;
                    }
                    for(int e = 0; e < 4 && dir < 0; e++) if(partAt(x - DX[e], y - DY[e]) != null) dir = e;
                    outlets.add(new Port(x, y, Math.max(dir, 0), false, true, defaultCap));
                }
                default -> {}
            }
        }
        if(inlets.isEmpty()) throw new Failure("no inlet");
    }

    /** a block stands next to the bridge on a side other than its link: the bridge is fed from inside */
    boolean fedInside(Part p){
        for(int s = 0; s < 4; s++) if(s != p.linkDir && partAt(p.t.x + DX[s], p.t.y + DY[s]) != null) return true;
        return false;
    }

    // ---- flow ----

    boolean consumes(Part q, String item, boolean liquid){
        if(q.kind().equals("crafter")) return liquid ? item.equals(q.b.liquid) : q.b.in.get(item) > 0;
        if(q.kind().equals("generator")){
            if(liquid) return item.equals(q.b.liquid);
            Catalog.ItemInfo it = cat.item(item);
            return q.b.fuel && it != null && it.flammability > 0;
        }
        return false;
    }

    /** q takes the item moving in direction `travel` (arriving over a bridge link: always) */
    boolean accepts(Part q, int travel, String item, boolean liquid, boolean viaBridge){
        int r = q.t.rot & 3;
        switch(q.kind()){
            case "conveyor": return !liquid && travel != opposite(r);
            case "conduit": return liquid && travel != opposite(r);
            case "bridge": return !liquid && (viaBridge || q.link < 0 || travel != opposite(q.linkDir));
            case "liquidbridge": return liquid && (viaBridge || q.link < 0 || travel != opposite(q.linkDir));
            case "sorter", "gate", "junction", "router": return !liquid;
            case "liquidrouter": return liquid;
            case "itemvoid": return !liquid;
            default: return consumes(q, item, liquid);
        }
    }

    Reach flowFromPort(Port port, String item){
        Reach reach = new Reach();
        Deque<int[]> queue = new ArrayDeque<>();
        boolean[] seen = new boolean[parts.size() * 8];
        push(port.x, port.y, port.x + DX[port.dir], port.y + DY[port.dir], port.dir, item, port.liquid, false, reach, queue, seen);
        drain(item, port.liquid, reach, queue, seen);
        return reach;
    }

    Reach flowFromCrafter(Part c, String item){
        Reach reach = new Reach();
        Deque<int[]> queue = new ArrayDeque<>();
        boolean[] seen = new boolean[parts.size() * 8];
        for(int x = c.x0; x <= c.x1; x++){
            push(x, c.y1, x, c.y1 + 1, 1, item, false, false, reach, queue, seen);
            push(x, c.y0, x, c.y0 - 1, 3, item, false, false, reach, queue, seen);
        }
        for(int y = c.y0; y <= c.y1; y++){
            push(c.x1, y, c.x1 + 1, y, 0, item, false, false, reach, queue, seen);
            push(c.x0, y, c.x0 - 1, y, 2, item, false, false, reach, queue, seen);
        }
        drain(item, false, reach, queue, seen);
        return reach;
    }

    void push(int fx, int fy, int tx, int ty, int travel, String item, boolean liquid, boolean viaBridge,
              Reach reach, Deque<int[]> queue, boolean[] seen){
        if(!in(tx, ty)){
            int o = outletAt(tx, ty, travel, false);
            if(o >= 0) reach.outlets.set(o);
            else reach.leak = true;
            return;
        }
        int pi = grid[tx][ty];
        if(pi < 0) return;
        Part q = parts.get(pi);
        if(!accepts(q, travel, item, liquid, viaBridge)) return;
        if(q.kind().equals("itemvoid")){
            reach.outlets.set(outletAt(tx, ty, travel, true));
            return;
        }
        if(consumes(q, item, liquid)){
            reach.consumers.set(pi);
            return;
        }
        int key = pi * 8 + travel + (viaBridge ? 4 : 0);
        if(seen[key]) return;
        seen[key] = true;
        queue.add(new int[]{pi, travel, viaBridge ? 1 : 0});
    }

    int outletAt(int x, int y, int travel, boolean marker){
        for(int i = 0; i < outlets.size(); i++){
            Port o = outlets.get(i);
            if(o.x == x && o.y == y && o.marker == marker && (marker || o.dir == travel)) return i;
        }
        return -1;
    }

    void drain(String item, boolean liquid, Reach reach, Deque<int[]> queue, boolean[] seen){
        while(!queue.isEmpty()){
            int[] s = queue.poll();
            Part p = parts.get(s[0]);
            int d = s[1], x = p.t.x, y = p.t.y;
            switch(p.kind()){
                case "conveyor", "conduit" -> {
                    int r = p.t.rot & 3;
                    push(x, y, x + DX[r], y + DY[r], r, item, liquid, false, reach, queue, seen);
                }
                case "junction" -> push(x, y, x + DX[d], y + DY[d], d, item, liquid, false, reach, queue, seen);
                case "sorter" -> {
                    if(Objects.equals(item, p.t.config) != p.b.invert) push(x, y, x + DX[d], y + DY[d], d, item, liquid, false, reach, queue, seen);
                    else for(int e : new int[]{(d + 1) & 3, (d + 3) & 3}) push(x, y, x + DX[e], y + DY[e], e, item, liquid, false, reach, queue, seen);
                }
                case "gate" -> {
                    for(int e : new int[]{d, (d + 1) & 3, (d + 3) & 3}) push(x, y, x + DX[e], y + DY[e], e, item, liquid, false, reach, queue, seen);
                }
                case "router", "liquidrouter" -> {
                    for(int e = 0; e < 4; e++) push(x, y, x + DX[e], y + DY[e], e, item, liquid, false, reach, queue, seen);
                }
                case "bridge", "liquidbridge" -> {
                    if(p.link >= 0){
                        Part q = parts.get(p.link);
                        push(x, y, q.t.x, q.t.y, p.linkDir, item, liquid, true, reach, queue, seen);
                    }else{
                        for(int e = 0; e < 4; e++) if((p.incoming & (1 << e)) == 0) push(x, y, x + DX[e], y + DY[e], e, item, liquid, false, reach, queue, seen);
                    }
                }
                default -> {}
            }
        }
    }

    // ---- items and rates ----

    /** per second at full speed, by item (liquids included) */
    Map<String, Double> needs(Part c){
        Map<String, Double> m = new TreeMap<>();
        if(c.kind().equals("crafter")) for(String i : c.b.in.items()) m.put(i, c.b.in.get(i) * 60.0 / c.b.craft);
        if(c.kind().equals("generator") && c.b.fuel) m.put(FUEL, 60.0 / c.b.duration);
        if(c.b.liquid != null) m.put(c.b.liquid, c.b.liquidAmount * 60.0);
        return m;
    }

    void assign(ModuleSpec spec){
        Set<String> itemCands = new TreeSet<>(), liquidCands = new TreeSet<>();
        for(int ci : consumerParts){
            Part c = parts.get(ci);
            for(String n : needs(c).keySet()) (cat.isLiquid(n) ? liquidCands : itemCands).add(n);
        }
        int n = inlets.size();
        List<List<String>> valid = new ArrayList<>();
        List<Map<String, Reach>> reach = new ArrayList<>();
        for(Port in : inlets){
            Map<String, Reach> byItem = new TreeMap<>();
            Set<String> cands = in.marker ? in.items : in.liquid ? liquidCands : itemCands;
            for(String c : cands) byItem.put(c, flowFromPort(in, c));
            List<String> ok = new ArrayList<>();
            byItem.forEach((c, r) -> { if(!r.consumers.isEmpty()) ok.add(c); });
            if(ok.isEmpty()) throw new Failure("inlet at (" + in.x + "," + in.y + ") feeds no crafter");
            valid.add(ok);
            reach.add(byItem);
        }
        long combos = 1;
        for(List<String> v : valid) combos *= v.size();
        if(combos > MAX_ASSIGNMENTS) throw new Failure("too many ways to feed the inlets");

        String[] cur = new String[n], best = null;
        double bestScore = -1;
        int bestLeaks = Integer.MAX_VALUE;
        boolean tie = false;
        int[] idx = new int[n];
        for(long k = 0; k < combos; k++){
            for(int i = 0; i < n; i++) cur[i] = valid.get(i).get(idx[i]);
            double f = factor(cur, reach, null);
            int alive = alive(cur, reach).cardinality();
            double score = f * alive;
            int leaks = 0;
            for(int i = 0; i < n; i++){
                Reach r = reach.get(i).get(cur[i]);
                if(r.leak || !r.outlets.isEmpty()) leaks++;
            }
            if(score > bestScore + EPS || (Math.abs(score - bestScore) <= EPS && leaks < bestLeaks)){
                bestScore = score;
                bestLeaks = leaks;
                best = cur.clone();
                tie = false;
            }else if(Math.abs(score - bestScore) <= EPS && leaks == bestLeaks){
                tie = true;
            }
            for(int i = 0; i < n && ++idx[i] == valid.get(i).size(); i++) idx[i] = 0;
        }
        if(bestScore <= EPS) throw new Failure("no crafter can run");
        if(tie) throw new Failure("can't tell which inlet takes which item");

        for(int i = 0; i < n; i++){
            inlets.get(i).items.clear();
            inlets.get(i).items.add(best[i]);
        }
        BitSet alive = alive(best, reach);
        Map<String, double[]> demand = new TreeMap<>();
        double f = factor(best, reach, demand);

        double totalOut = 0;
        for(int ci = alive.nextSetBit(0); ci >= 0; ci = alive.nextSetBit(ci + 1)){
            Part c = parts.get(ci);
            if(!c.kind().equals("crafter")) continue;
            for(String product : c.b.out.items()){
                Reach r = flowFromCrafter(c, product);
                if(r.outlets.isEmpty()) throw new Failure(product + " from (" + c.t.x + "," + c.t.y + ") reaches no outlet");
                for(int o = r.outlets.nextSetBit(0); o >= 0; o = r.outlets.nextSetBit(o + 1)) outlets.get(o).items.add(product);
                double rate = c.b.out.get(product) * 60.0 / c.b.craft;
                spec.outputs.merge(product, rate, Double::sum);
                totalOut += rate;
            }
        }
        double outCap = 0;
        for(Port o : outlets) if(!o.items.isEmpty()) outCap += o.cap;
        if(totalOut * f > outCap) f = outCap / totalOut;

        double ff = f;
        spec.outputs.replaceAll((k, v) -> v * ff);
        for(Port o : outlets) if(!o.items.isEmpty()) o.rate = totalOut * f * o.cap / outCap;
        for(int i = 0; i < n; i++){
            double[] ds = demand.get(best[i]);
            Port in = inlets.get(i);
            in.rate = ds[0] * f * (in.cap == Double.MAX_VALUE ? 1.0 / ds[2] : in.cap / ds[1]);
        }
        for(int ci = alive.nextSetBit(0); ci >= 0; ci = alive.nextSetBit(ci + 1)){
            Part c = parts.get(ci);
            if(c.kind().equals("crafter")) spec.powerUse += c.b.power * 60.0;
            else spec.powerGen += c.b.gen * 60.0 * f;
        }
        spec.working = alive.cardinality();
        spec.factor = f;
    }

    /** consumers that get every input they need from some inlet under this assignment */
    BitSet alive(String[] items, List<Map<String, Reach>> reach){
        BitSet out = new BitSet();
        for(int ci : consumerParts){
            boolean ok = true;
            for(String need : needs(parts.get(ci)).keySet()){
                boolean fed = false;
                for(int i = 0; i < items.length && !fed; i++) fed = need.equals(items[i]) && reach.get(i).get(items[i]).consumers.get(ci);
                ok &= fed;
            }
            if(ok) out.set(ci);
        }
        return out;
    }

    /**
     * Share of capacity the inlets allow: min over needed items of supply / demand (at most 1).
     * Fills `demand` (if given) with item -> {demand per second, supply per second, inlets}.
     */
    double factor(String[] items, List<Map<String, Reach>> reach, Map<String, double[]> demand){
        Map<String, double[]> d = demand != null ? demand : new TreeMap<>();
        BitSet alive = alive(items, reach);
        for(int ci = alive.nextSetBit(0); ci >= 0; ci = alive.nextSetBit(ci + 1)){
            needs(parts.get(ci)).forEach((item, rate) -> d.computeIfAbsent(item, k -> new double[3])[0] += rate);
        }
        for(int i = 0; i < items.length; i++){
            double[] e = d.computeIfAbsent(items[i], k -> new double[3]);
            double cap = inlets.get(i).cap;
            e[1] = e[1] == Double.MAX_VALUE || cap == Double.MAX_VALUE ? Double.MAX_VALUE : e[1] + cap;
            e[2]++;
        }
        double f = 1;
        for(double[] e : d.values()) if(e[0] > 0) f = Math.min(f, e[1] / e[0]);
        return f;
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./test.sh bootstrap`
Expected: `BOOTSTRAP OK`。预期的分析结果（测试已断言）：

| 蓝图 | 入口 | 出口 | 满速 |
|---|---|---|---|
| Silicon Smelter (8) | 西 煤、北 沙 ×2 | 东 硅 | 10 硅/秒（8 台可产 12，钛带只能运 10） |
| generator1 | 西 水、南 煤 | — | 1320 电/秒，24 水/秒，2.67 煤/秒 |
| Full scale Metaglass factory | 南 沙、南 铅 | 南 钢化玻璃 | 10/秒（5 台窑） |
| Small Graphite Factory | 北 煤 | 南 石墨 | 2.67/秒 |
| Starter Factory | 标记：煤、沙、铅 | 标记：石墨+硅+钢化玻璃 | 自供电 |

- [ ] **Step 5: 提交**

```bash
git add src/autobuild/bootstrap/domain/module test/autobuild/bootstrap
git commit -m "bootstrap M1: module analysis - ports, item inference by flow simulation, full-speed rates

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 勘探

**Files:**
- Create: `src/autobuild/bootstrap/domain/survey/{Terrain,PathField,OrePatch,WaterBody,DangerMask,SurveyService}.java`
- Create: `test/autobuild/bootstrap/GridTerrain.java`, `test/autobuild/bootstrap/SurveyTest.java`
- Modify: `test/autobuild/bootstrap/BootstrapTest.java`

**Interfaces:**
- Consumes: `Dir`
- Produces: `Terrain{width(),height(),ore(x,y),buildable(x,y),liquid(x,y),core()}`；`PathField{w,h,at(x,y)}`，`PathField.UNREACHABLE`、`BRIDGE_COST = 2`；`OrePatch{item,tiles(int[] 索引 x + y*w),x0,y0,x1,y1,distance,size()}`；`WaterBody{liquid,tiles,distance,size()}`；`DangerMask.around(int w, int h, Iterable<int[]> route, float radius) -> boolean[][]`；`SurveyService.survey(Terrain, Options) -> Report`，`Options{bridgeRange=4, base(int[4] 或 null), danger(boolean[][] 或 null)}`，`Report{field, patches（按距离升序）, waters, patches(String item)}`。

- [ ] **Step 1: 写测试**

`test/autobuild/bootstrap/GridTerrain.java`：

```java
package autobuild.bootstrap;

import autobuild.bootstrap.domain.survey.Terrain;

/**
 * A map drawn as text, top row first: '.' floor, '#' wall, 'C' core, ores 'c' copper, 'l' lead,
 * 's' sand, 'k' coal, 't' titanium, '~' shallow water (buildable), 'W' deep water (not buildable).
 */
final class GridTerrain implements Terrain{
    final char[][] g;
    final int w, h;
    final int[] core = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};

    GridTerrain(String... rows){
        h = rows.length;
        w = rows[0].length();
        g = new char[w][h];
        for(int y = 0; y < h; y++) for(int x = 0; x < w; x++){
            char c = rows[h - 1 - y].charAt(x);
            g[x][y] = c;
            if(c == 'C'){
                core[0] = Math.min(core[0], x);
                core[1] = Math.min(core[1], y);
                core[2] = Math.max(core[2], x);
                core[3] = Math.max(core[3], y);
            }
        }
    }

    public int width(){ return w; }

    public int height(){ return h; }

    public String ore(int x, int y){
        return switch(g[x][y]){
            case 'c' -> "copper";
            case 'l' -> "lead";
            case 's' -> "sand";
            case 'k' -> "coal";
            case 't' -> "titanium";
            default -> null;
        };
    }

    public boolean buildable(int x, int y){ return g[x][y] != '#' && g[x][y] != 'C' && g[x][y] != 'W'; }

    public String liquid(int x, int y){ return g[x][y] == '~' || g[x][y] == 'W' ? "water" : null; }

    public int[] core(){ return core; }
}
```

`test/autobuild/bootstrap/SurveyTest.java`：

```java
package autobuild.bootstrap;

import autobuild.bootstrap.domain.survey.*;

import java.util.List;

import static autobuild.bootstrap.Check.*;

final class SurveyTest{
    static final GridTerrain MAP = new GridTerrain(
        "cc......~W",
        "cc..CC..~W",
        "....CC....",
        "ss......kk",
        "ss......kk");

    static void run(){
        SurveyService.Report r = SurveyService.survey(MAP, new SurveyService.Options());
        eq("next to core", 1, r.field.at(3, 2));
        eq("one step out", 2, r.field.at(2, 2));
        eq("patches", 3, r.patches.size());
        eq("copper", "copper x4 d3", one(r.patches("copper")));
        eq("sand", "sand x4 d3", one(r.patches("sand")));
        eq("coal (bridge from the core's ring)", "coal x4 d3", one(r.patches("coal")));
        eq("water bodies", 1, r.waters.size());
        eq("water size (deep included)", 4, r.waters.get(0).size());
        eq("water distance", 3, r.waters.get(0).distance);

        SurveyService.Options danger = new SurveyService.Options();
        danger.danger = DangerMask.around(MAP.width(), MAP.height(), List.of(new int[]{9, 0}), 1.5f);
        eq("coal in danger", 0, SurveyService.survey(MAP, danger).patches("coal").size());

        SurveyService.Options base = new SurveyService.Options();
        base.base = new int[]{0, 0, 6, 4};
        SurveyService.Report inBase = SurveyService.survey(MAP, base);
        eq("coal outside base", 0, inBase.patches("coal").size());
        eq("water outside base", 0, inBase.waters.size());

        // a wall between the core and the ore: only a bridge gets over it
        GridTerrain walled = new GridTerrain("C.##..k.");
        eq("bridge over wall", 3, SurveyService.survey(walled, new SurveyService.Options()).field.at(4, 0));
        eq("ore behind wall", "coal x1 d4", one(SurveyService.survey(walled, new SurveyService.Options()).patches("coal")));
        SurveyService.Options noBridge = new SurveyService.Options();
        noBridge.bridgeRange = 1;
        eq("no bridge, no way", PathField.UNREACHABLE, SurveyService.survey(walled, noBridge).field.at(4, 0));
    }

    static String one(List<OrePatch> ps){
        if(ps.size() != 1) return ps.size() + " patches";
        return ps.get(0).item + " x" + ps.get(0).size() + " d" + ps.get(0).distance;
    }
}
```

`BootstrapTest.main` 里加：

```java
        SurveyTest.run();
```

- [ ] **Step 2: 运行，确认失败**

Run: `./test.sh bootstrap`
Expected: 编译失败，`package autobuild.bootstrap.domain.survey does not exist`。

- [ ] **Step 3: 实现**

`src/autobuild/bootstrap/domain/survey/Terrain.java`：

```java
package autobuild.bootstrap.domain.survey;

/** Port: the map as the survey sees it (the game adapter reads a snapshot of the world). */
public interface Terrain{
    int width();

    int height();

    /** item a drill would mine here (ore overlay, else the floor's drop; ore under a wall: null) */
    String ore(int x, int y);

    /** nothing stands here and blocks can be built */
    boolean buildable(int x, int y);

    /** liquid a pump would pump here, or null */
    String liquid(int x, int y);

    /** the core's footprint {x0, y0, x1, y1} */
    int[] core();
}
```

`src/autobuild/bootstrap/domain/survey/PathField.java`：

```java
package autobuild.bootstrap.domain.survey;

import java.util.*;

import static autobuild.bootstrap.domain.shared.Dir.*;

/**
 * Line cost from the core to every tile: Dijkstra over usable tiles from the tiles touching the core.
 * A belt step costs 1; a bridge jump of 2..range tiles over anything costs 2 (it lands on a usable
 * tile). {@link #UNREACHABLE} where no line can get.
 */
public final class PathField{
    public static final int UNREACHABLE = Integer.MAX_VALUE;
    public static final int BRIDGE_COST = 2;

    public final int w, h;
    final int[] dist;

    PathField(int w, int h){
        this.w = w;
        this.h = h;
        dist = new int[w * h];
        Arrays.fill(dist, UNREACHABLE);
    }

    public int at(int x, int y){ return x < 0 || y < 0 || x >= w || y >= h ? UNREACHABLE : dist[x + y * w]; }

    static PathField compute(int w, int h, boolean[] usable, int[] core, int bridgeRange){
        PathField f = new PathField(w, h);
        PriorityQueue<long[]> q = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
        for(int x = core[0] - 1; x <= core[2] + 1; x++) for(int y = core[1] - 1; y <= core[3] + 1; y++){
            boolean ring = (x == core[0] - 1 || x == core[2] + 1) != (y == core[1] - 1 || y == core[3] + 1);
            if(ring && x >= 0 && y >= 0 && x < w && y < h && usable[x + y * w]){
                f.dist[x + y * w] = 1;
                q.add(new long[]{1, x + y * w});
            }
        }
        while(!q.isEmpty()){
            long[] e = q.poll();
            int i = (int)e[1];
            if(e[0] > f.dist[i]) continue;
            int x = i % w, y = i / w;
            for(int d = 0; d < 4; d++){
                for(int k = 1; k <= Math.max(1, bridgeRange); k++){
                    int nx = x + DX[d] * k, ny = y + DY[d] * k;
                    if(nx < 0 || ny < 0 || nx >= w || ny >= h) break;
                    int n = nx + ny * w;
                    if(!usable[n]) continue;
                    long nd = e[0] + (k == 1 ? 1 : BRIDGE_COST);
                    if(nd < f.dist[n]){
                        f.dist[n] = (int)nd;
                        q.add(new long[]{nd, n});
                    }
                }
            }
        }
        return f;
    }
}
```

`src/autobuild/bootstrap/domain/survey/OrePatch.java`：

```java
package autobuild.bootstrap.domain.survey;

/** Usable tiles of one ore, 8-connected. */
public final class OrePatch{
    public final String item;
    public final int[] tiles;
    public final int x0, y0, x1, y1;
    /** line cost from the core to its nearest tile */
    public final int distance;

    OrePatch(String item, int[] tiles, int w, int distance){
        this.item = item;
        this.tiles = tiles;
        int ax = Integer.MAX_VALUE, ay = Integer.MAX_VALUE, bx = Integer.MIN_VALUE, by = Integer.MIN_VALUE;
        for(int t : tiles){
            ax = Math.min(ax, t % w);
            ay = Math.min(ay, t / w);
            bx = Math.max(bx, t % w);
            by = Math.max(by, t / w);
        }
        x0 = ax;
        y0 = ay;
        x1 = bx;
        y1 = by;
        this.distance = distance;
    }

    public int size(){ return tiles.length; }

    @Override public String toString(){ return item + "x" + tiles.length + "@(" + x0 + "," + y0 + ")-(" + x1 + "," + y1 + ") d=" + distance; }
}
```

`src/autobuild/bootstrap/domain/survey/WaterBody.java`：

```java
package autobuild.bootstrap.domain.survey;

/** Pumpable tiles of one liquid, 4-connected. */
public final class WaterBody{
    public final String liquid;
    public final int[] tiles;
    /** line cost from the core to its nearest tile (or a usable tile next to it, + 1) */
    public final int distance;

    WaterBody(String liquid, int[] tiles, int distance){
        this.liquid = liquid;
        this.tiles = tiles;
        this.distance = distance;
    }

    public int size(){ return tiles.length; }
}
```

`src/autobuild/bootstrap/domain/survey/DangerMask.java`：

```java
package autobuild.bootstrap.domain.survey;

/** Tiles too close to enemy routes to build on. */
public final class DangerMask{
    private DangerMask(){}

    /** every tile within `radius` (tiles, Euclidean) of a route tile {x, y} */
    public static boolean[][] around(int w, int h, Iterable<int[]> route, float radius){
        boolean[][] m = new boolean[w][h];
        int r = (int)Math.ceil(radius);
        for(int[] p : route){
            for(int x = Math.max(0, p[0] - r); x <= Math.min(w - 1, p[0] + r); x++){
                for(int y = Math.max(0, p[1] - r); y <= Math.min(h - 1, p[1] + r); y++){
                    if((x - p[0]) * (x - p[0]) + (y - p[1]) * (y - p[1]) <= radius * radius) m[x][y] = true;
                }
            }
        }
        return m;
    }
}
```

`src/autobuild/bootstrap/domain/survey/SurveyService.java`：

```java
package autobuild.bootstrap.domain.survey;

import java.util.*;

/**
 * Explores the map from the core: the line-cost field, ore patches and water bodies, all limited to
 * usable tiles - buildable, inside the base area if one is set, outside the danger mask (enemy routes
 * and their reach).
 */
public final class SurveyService{
    public static final class Options{
        public int bridgeRange = 4;
        /** base area {x0, y0, x1, y1}, or null for the whole map */
        public int[] base;
        /** tiles nothing may be built on (enemy routes and range), or null */
        public boolean[][] danger;
    }

    public static final class Report{
        public final PathField field;
        /** nearest first */
        public final List<OrePatch> patches;
        public final List<WaterBody> waters;

        Report(PathField field, List<OrePatch> patches, List<WaterBody> waters){
            this.field = field;
            this.patches = patches;
            this.waters = waters;
        }

        public List<OrePatch> patches(String item){
            return patches.stream().filter(p -> p.item.equals(item)).toList();
        }
    }

    private SurveyService(){}

    public static Report survey(Terrain t, Options o){
        int w = t.width(), h = t.height();
        boolean[] usable = new boolean[w * h];
        for(int x = 0; x < w; x++) for(int y = 0; y < h; y++){
            boolean inBase = o.base == null || (x >= o.base[0] && y >= o.base[1] && x <= o.base[2] && y <= o.base[3]);
            usable[x + y * w] = inBase && t.buildable(x, y) && (o.danger == null || !o.danger[x][y]);
        }
        PathField field = PathField.compute(w, h, usable, t.core(), o.bridgeRange);
        List<OrePatch> patches = new ArrayList<>();
        boolean[] seen = new boolean[w * h];
        for(int i = 0; i < w * h; i++){
            String ore = t.ore(i % w, i / w);
            if(seen[i] || !usable[i] || ore == null) continue;
            int[] tiles = flood(i, w, h, true, j -> usable[j] && ore.equals(t.ore(j % w, j / w)), seen);
            int d = PathField.UNREACHABLE;
            for(int j : tiles) d = Math.min(d, field.dist[j]);
            patches.add(new OrePatch(ore, tiles, w, d));
        }
        List<WaterBody> waters = new ArrayList<>();
        Arrays.fill(seen, false);
        for(int i = 0; i < w * h; i++){
            String liquid = t.liquid(i % w, i / w);
            boolean inBase = o.base == null || (i % w >= o.base[0] && i / w >= o.base[1] && i % w <= o.base[2] && i / w <= o.base[3]);
            if(seen[i] || liquid == null || !inBase) continue;
            int[] tiles = flood(i, w, h, false, j -> liquid.equals(t.liquid(j % w, j / w)), seen);
            int d = PathField.UNREACHABLE;
            for(int j : tiles){
                d = Math.min(d, field.dist[j]);
                int x = j % w, y = j / w;
                for(int[] n : new int[][]{{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}}){
                    int nd = field.at(n[0], n[1]);
                    if(nd != PathField.UNREACHABLE) d = Math.min(d, nd + 1);
                }
            }
            waters.add(new WaterBody(liquid, tiles, d));
        }
        patches.sort(Comparator.comparingInt(p -> p.distance));
        waters.sort(Comparator.comparingInt(b -> b.distance));
        return new Report(field, patches, waters);
    }

    static int[] flood(int start, int w, int h, boolean eight, java.util.function.IntPredicate same, boolean[] seen){
        List<Integer> out = new ArrayList<>();
        Deque<Integer> q = new ArrayDeque<>();
        q.add(start);
        seen[start] = true;
        while(!q.isEmpty()){
            int i = q.poll();
            out.add(i);
            int x = i % w, y = i / w;
            for(int dx = -1; dx <= 1; dx++) for(int dy = -1; dy <= 1; dy++){
                if((dx == 0 && dy == 0) || (!eight && dx != 0 && dy != 0)) continue;
                int nx = x + dx, ny = y + dy;
                if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int n = nx + ny * w;
                if(!seen[n] && same.test(n)){
                    seen[n] = true;
                    q.add(n);
                }
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./test.sh bootstrap`
Expected: `BOOTSTRAP OK`。

- [ ] **Step 5: 提交**

```bash
git add src/autobuild/bootstrap/domain/survey test/autobuild/bootstrap
git commit -m "bootstrap M1: survey - line-cost field from the core, ore patches, water, danger mask, base area

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 配平

**Files:**
- Create: `src/autobuild/bootstrap/domain/production/{ProductionGoal,SupplyLine,ProductionPlan,ChainSolver}.java`
- Create: `test/autobuild/bootstrap/ChainSolverTest.java`
- Modify: `test/autobuild/bootstrap/BootstrapTest.java`

**Interfaces:**
- Consumes: `ModuleSpec`（`inlets[i].item()/rate/liquid`、`powerUse/powerGen`）、`SurveyService.Report`、`OrePatch`、`WaterBody`、`PathField.UNREACHABLE`、`Catalog.item(name).hardness`、`BlockInfo.tier/drillTime/pump`
- Produces: `ProductionGoal{modules(List<ModuleOrder>), raw(Map<String,Integer>), power, drills, pump, belt, coverage=0.8}`，`ProductionGoal.ModuleOrder(ModuleSpec, int)`；`ChainSolver.solve(ProductionGoal, SurveyService.Report, Catalog) -> ProductionPlan`；`ProductionPlan{instances, lines, powerDemand, powerSupply, noPower, beltLimited(List<double[]{instance, inlet, wanted}>), shortfalls()}`，`ProductionPlan.Instance{spec, power, factor}`；`SupplyLine{item, liquid, instance, inlet, wanted, target, supplied, limiting, sources, toCore()}`，`SupplyLine.Share{patch, water, rate}`。M2 的布局与 M3 的分步都只读这些结果。

- [ ] **Step 1: 写测试**

`test/autobuild/bootstrap/ChainSolverTest.java`：

```java
package autobuild.bootstrap;

import autobuild.bootstrap.domain.module.*;
import autobuild.bootstrap.domain.production.*;
import autobuild.bootstrap.domain.survey.*;

import static autobuild.bootstrap.Check.*;

final class ChainSolverTest{
    /** 40 x 20: sand x0..9 (200 tiles), coal x30..34 y0..9 (50), copper x30..39 y12..19 (80), water x36..39 y0..9 (40) */
    static GridTerrain map(){
        String[] rows = new String[20];
        for(int y = 0; y < 20; y++){
            StringBuilder s = new StringBuilder();
            for(int x = 0; x < 40; x++){
                char c = '.';
                if(x < 10) c = 's';
                else if(x >= 30 && x <= 34 && y < 10) c = 'k';
                else if(x >= 30 && y >= 12) c = 'c';
                else if(x >= 36 && y < 10) c = '~';
                else if((x == 20 || x == 21) && (y == 10 || y == 11)) c = 'C';
                s.append(c);
            }
            rows[19 - y] = s.toString();
        }
        return new GridTerrain(rows);
    }

    static ProductionGoal goal(double belt) throws Exception{
        ProductionGoal g = new ProductionGoal();
        g.modules.add(new ProductionGoal.ModuleOrder(ModuleAnalyzer.analyze(blueprint(FIXTURES + "silicon8.txt"), catalog(), 10), 1));
        g.power = ModuleAnalyzer.analyze(blueprint(FIXTURES + "generator1.txt"), catalog(), 10);
        g.raw.put("copper", 1);
        g.drills.add(catalog().block("mechanical-drill"));
        g.drills.add(catalog().block("pneumatic-drill"));
        g.pump = catalog().block("mechanical-pump");
        g.belt = belt;
        return g;
    }

    static void run() throws Exception{
        SurveyService.Report survey = SurveyService.survey(map(), new SurveyService.Options());
        ProductionPlan p = ChainSolver.solve(goal(10), survey, catalog());
        eq("instances: silicon + one plant", 2, p.instances.size());
        yes("second is the plant", p.instances.get(1).power);
        near("power demand", 240, p.powerDemand);
        near("power supply", 1320, p.powerSupply);
        eq("lines", 6, p.lines.size());

        // coal: 50 tiles x 0.8 x 60/500 (pneumatic) = 4.8/s; the plant's 2.67 first, silicon gets the rest
        SupplyLine plantCoal = line(p, 1, 1), siCoal = line(p, 0, 0), sand = line(p, 0, 1), copper = line(p, -1, -1);
        near("plant coal", 4 * 60 / 90.0, plantCoal.supplied);
        near("silicon coal", 4.8 - 4 * 60 / 90.0, siCoal.supplied);
        yes("silicon coal limits", siCoal.limiting);
        near("silicon factor", (4.8 - 4 * 60 / 90.0) / 10, p.instances.get(0).factor);
        near("sand scaled to the coal", 4.8 - 4 * 60 / 90.0, sand.target);
        yes("sand not limiting", !sand.limiting);
        near("sand wanted", 10, sand.wanted);
        near("plant water", 24, line(p, 1, 0).supplied);
        // copper: 80 tiles x 0.8 x 60/450 = 8.53/s for a 10/s line
        near("copper", 80 * 0.8 * 60 / 450, copper.supplied);
        yes("copper limiting", copper.limiting);
        eq("shortfalls", 2, p.shortfalls().size());
        eq("no belt limit at 10/s", 0, p.beltLimited.size());

        ProductionPlan slow = ChainSolver.solve(goal(5), survey, catalog());
        eq("basic belts: silicon inlets limited", 3, slow.beltLimited.size());
        near("silicon sand line at belt speed", 5, line(slow, 0, 1).wanted);

        ProductionGoal noPlant = goal(10);
        noPlant.power = null;
        ProductionPlan np = ChainSolver.solve(noPlant, survey, catalog());
        yes("no power plant", np.noPower);
        eq("only silicon", 1, np.instances.size());
    }

    static SupplyLine line(ProductionPlan p, int instance, int inlet){
        for(SupplyLine l : p.lines) if(l.instance == instance && l.inlet == inlet) return l;
        throw new AssertionError("no line " + instance + "." + inlet);
    }
}
```

`BootstrapTest.main` 里加：

```java
        ChainSolverTest.run();
```

- [ ] **Step 2: 运行，确认失败**

Run: `./test.sh bootstrap`
Expected: 编译失败，`package autobuild.bootstrap.domain.production does not exist`。

- [ ] **Step 3: 实现**

`src/autobuild/bootstrap/domain/production/ProductionGoal.java`：

```java
package autobuild.bootstrap.domain.production;

import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.module.ModuleSpec;

import java.util.*;

/** What the player asks for: modules by count, raw items mined straight to the core, and the means. */
public final class ProductionGoal{
    public static final class ModuleOrder{
        public final ModuleSpec spec;
        public final int count;

        public ModuleOrder(ModuleSpec spec, int count){
            this.spec = spec;
            this.count = count;
        }
    }

    public final List<ModuleOrder> modules = new ArrayList<>();
    /** item -> full-belt lines mined to the core */
    public final Map<String, Integer> raw = new TreeMap<>();
    /** power plant module, built as often as the modules need (null: none available) */
    public ModuleSpec power;
    /** drills that may be built */
    public final List<BlockInfo> drills = new ArrayList<>();
    /** pump for water inlets (null: none available) */
    public BlockInfo pump;
    /** items per second of one outside line */
    public double belt;
    /** share of a patch's ore tiles its drills end up covering */
    public double coverage = 0.8;
}
```

`src/autobuild/bootstrap/domain/production/SupplyLine.java`：

```java
package autobuild.bootstrap.domain.production;

import autobuild.bootstrap.domain.survey.OrePatch;
import autobuild.bootstrap.domain.survey.WaterBody;

import java.util.*;

/** One outside line: an item (or liquid) from ore patches (a water body) to a module inlet or the core. */
public final class SupplyLine{
    public static final class Share{
        public final OrePatch patch;
        public final WaterBody water;
        public final double rate;

        Share(OrePatch patch, WaterBody water, double rate){
            this.patch = patch;
            this.water = water;
            this.rate = rate;
        }
    }

    public final String item;
    public final boolean liquid;
    /** index into {@link ProductionPlan#instances} and inlet index, or -1, -1 for the core */
    public final int instance, inlet;
    /** per second: asked for (one belt at most), aimed at after its module slowed down, found */
    public double wanted, target, supplied;
    /** too little ore / water for this line: it is what slows its module down (or the core gets less) */
    public boolean limiting;
    public final List<Share> sources = new ArrayList<>();

    SupplyLine(String item, boolean liquid, int instance, int inlet, double target){
        this.item = item;
        this.liquid = liquid;
        this.instance = instance;
        this.inlet = inlet;
        this.wanted = this.target = target;
    }

    public boolean toCore(){ return instance < 0; }

    @Override public String toString(){
        return item + (toCore() ? "->core" : "->" + instance + "." + inlet) + String.format(" %.2f/%.2f", supplied, wanted) + (limiting ? " limiting" : "");
    }
}
```

`src/autobuild/bootstrap/domain/production/ProductionPlan.java`：

```java
package autobuild.bootstrap.domain.production;

import autobuild.bootstrap.domain.module.ModuleSpec;

import java.util.*;

/** Quantities only, no coordinates: module copies, their supply lines and the power balance. */
public final class ProductionPlan{
    public static final class Instance{
        public final ModuleSpec spec;
        public final boolean power;
        /** share of its full speed it gets after supply shortfalls */
        public double factor = 1;

        Instance(ModuleSpec spec, boolean power){
            this.spec = spec;
            this.power = power;
        }
    }

    public final List<Instance> instances = new ArrayList<>();
    public final List<SupplyLine> lines = new ArrayList<>();
    /** power per second the modules use beyond their own generation, and the plants' output */
    public double powerDemand, powerSupply;
    /** modules need power but no plant is available */
    public boolean noPower;
    /** inlets wanting more than one outside line carries: instance, inlet, wanted per second */
    public final List<double[]> beltLimited = new ArrayList<>();

    public List<SupplyLine> shortfalls(){ return lines.stream().filter(l -> l.limiting).toList(); }
}
```

`src/autobuild/bootstrap/domain/production/ChainSolver.java`：

```java
package autobuild.bootstrap.domain.production;

import autobuild.bootstrap.domain.catalog.Catalog;
import autobuild.bootstrap.domain.catalog.Catalog.BlockInfo;
import autobuild.bootstrap.domain.module.ModuleSpec;
import autobuild.bootstrap.domain.module.ModuleSpec.Port;
import autobuild.bootstrap.domain.production.ProductionPlan.Instance;
import autobuild.bootstrap.domain.survey.*;

import java.util.*;

/**
 * Balances a goal against what the survey found.
 * <ol>
 * <li>Module copies; power plants added until they cover what the modules use beyond their own
 *     generation (peak use).</li>
 * <li>A supply line per module inlet (at most one outside belt's worth) and per raw line to the core.</li>
 * <li>Allocation - power plants' lines first (nothing runs without power), then modules', then the
 *     core's, biggest first: nearest ore patches (water bodies) with capacity left; a patch gives
 *     tiles x coverage x the best allowed drill's rate, a water body tiles x pump rate. Lines left
 *     short are marked limiting.</li>
 * <li>A module short on one input runs slower, so its other lines need less: the targets are scaled to
 *     its slowest line and everything is allocated again.</li>
 * </ol>
 */
public final class ChainSolver{
    private ChainSolver(){}

    public static ProductionPlan solve(ProductionGoal goal, SurveyService.Report survey, Catalog cat){
        ProductionPlan plan = new ProductionPlan();
        double use = 0, own = 0;
        for(ProductionGoal.ModuleOrder o : goal.modules){
            for(int i = 0; i < o.count; i++) plan.instances.add(new Instance(o.spec, false));
            use += o.spec.powerUse * o.count;
            own += o.spec.powerGen * o.count;
        }
        plan.powerDemand = Math.max(0, use - own);
        if(plan.powerDemand > 0){
            if(goal.power == null || goal.power.powerGen <= 0){
                plan.noPower = true;
            }else{
                int n = (int)Math.ceil(plan.powerDemand / goal.power.powerGen - 1e-9);
                for(int i = 0; i < n; i++) plan.instances.add(new Instance(goal.power, true));
                plan.powerSupply = n * goal.power.powerGen;
            }
        }
        for(int m = 0; m < plan.instances.size(); m++){
            ModuleSpec spec = plan.instances.get(m).spec;
            for(int i = 0; i < spec.inlets.size(); i++){
                Port in = spec.inlets.get(i);
                double target = in.liquid ? in.rate : Math.min(in.rate, goal.belt);
                if(!in.liquid && in.rate > goal.belt + 1e-6) plan.beltLimited.add(new double[]{m, i, in.rate});
                plan.lines.add(new SupplyLine(in.item(), in.liquid, m, i, target));
            }
        }
        goal.raw.forEach((item, n) -> {
            for(int i = 0; i < n; i++) plan.lines.add(new SupplyLine(item, false, -1, -1, goal.belt));
        });

        allocate(plan, goal, survey, cat);
        for(SupplyLine l : plan.lines) l.limiting = l.supplied < l.target - 1e-6;
        boolean scaled = false;
        for(int m = 0; m < plan.instances.size(); m++){
            double k = 1;
            for(SupplyLine l : plan.lines) if(l.instance == m && l.target > 0) k = Math.min(k, l.supplied / l.target);
            plan.instances.get(m).factor = k;
            if(k < 1 - 1e-6){
                scaled = true;
                for(SupplyLine l : plan.lines) if(l.instance == m) l.target *= k;
            }
        }
        if(scaled) allocate(plan, goal, survey, cat);
        return plan;
    }

    /** items per second one ore tile gives with the best drill that can mine it (0: none can) */
    static double perTile(String item, ProductionGoal goal, Catalog cat){
        Catalog.ItemInfo it = cat.item(item);
        double best = 0;
        for(BlockInfo d : goal.drills){
            Float t = d.drillTime.get(item);
            if(it != null && d.tier >= it.hardness && t != null && t > 0) best = Math.max(best, 60.0 / t);
        }
        return best;
    }

    static void allocate(ProductionPlan plan, ProductionGoal goal, SurveyService.Report survey, Catalog cat){
        Map<Object, Double> left = new HashMap<>();
        for(OrePatch p : survey.patches) left.put(p, p.size() * goal.coverage * perTile(p.item, goal, cat));
        double pumpRate = goal.pump == null ? 0 : goal.pump.pump * 60.0;
        for(WaterBody b : survey.waters) left.put(b, b.size() * pumpRate);

        List<SupplyLine> order = new ArrayList<>(plan.lines);
        order.sort(Comparator.comparingInt((SupplyLine l) -> l.toCore() ? 2 : plan.instances.get(l.instance).power ? 0 : 1)
            .thenComparingDouble(l -> -l.target));
        for(SupplyLine l : order){
            l.sources.clear();
            l.supplied = 0;
            List<?> cands = l.liquid
                ? survey.waters.stream().filter(b -> b.liquid.equals(l.item) && b.distance != PathField.UNREACHABLE).toList()
                : survey.patches(l.item).stream().filter(p -> p.distance != PathField.UNREACHABLE).toList();
            for(Object c : cands){
                double need = l.target - l.supplied;
                if(need <= 1e-9) break;
                double take = Math.min(need, left.get(c));
                if(take <= 1e-9) continue;
                left.merge(c, -take, Double::sum);
                l.supplied += take;
                l.sources.add(new SupplyLine.Share(c instanceof OrePatch p ? p : null, c instanceof WaterBody b ? b : null, take));
            }
        }
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./test.sh bootstrap`
Expected: `BOOTSTRAP OK`。

- [ ] **Step 5: 提交**

```bash
git add src/autobuild/bootstrap/domain/production test/autobuild/bootstrap
git commit -m "bootstrap M1: balancing - power plants, supply lines, ore allocation, slowdown on shortfalls

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 全量验证与开发日志

**Files:**
- Modify: `DEVLOG.md`（在 `## 范围说明（2026-10-15）` 之前插入新小节）, `CHANGELOG.md`（`[Unreleased]` 下加条目）

- [ ] **Step 1: 全量离线测试**

Run: `./test.sh`（约 4–5 分钟）
Expected: 依次出现 `DEFENSE OK`、`ALL OK`、`BOOTSTRAP OK`。

- [ ] **Step 2: 模组构建**

Run: `./gradlew jar`
Expected: `BUILD SUCCESSFUL`（`src/autobuild/bootstrap/**` 一并编译进模组；目前没有任何入口调用它们，游戏内行为不变）。

- [ ] **Step 3: 写开发日志**

`DEVLOG.md` 在 `## 范围说明（2026-10-15）` 之前插入：

```markdown
## 开局产线 M1 — 领域核心（未发布）

**目标**：为"开局产线"打好纯算法地基：游戏数据目录、蓝图解码、模块分析、勘探、配平，全部离线可测。设计见 `docs/superpowers/specs/2026-10-31-bootstrap-production-design.md`。

**算法方案**
1. **游戏数据**：`CatalogDump` 用 Gradle 缓存里的游戏 jar 无界面加载内容，导出造价、配方、耗电、带速、矿机速度等；领域层只读这份文本，因此离线测试与游戏内用同一套数值。
2. **蓝图解码**：自己解码 msch（v0 的 int 配置按方块类型映射；v1 读 TypeIO 对象，支持 contentMap 重映射）。
3. **模块分析**：在蓝图内按游戏推送规则模拟物品流动（传送带不从正面收、分类器/反向分类器、溢流门、交叉器、路由器、桥的连接与尾端倾倒）。边缘入口不写物品，于是枚举候选分配，取满速最高者。
4. **勘探**：从核心出发的 Dijkstra（走一格代价 1，桥跳代价 2），矿团 8 连通、水体 4 连通；危险区与基地范围直接从可用格中剔除。
5. **配平**：电厂按峰值用电配数量；供给线优先级：电厂 > 模块 > 核心，同级按速率从大到小分配最近的矿团；某模块缺料时按最慢的线降速、重算一遍，真正卡住的线标记为 limiting。

**游戏数据带来的修正**：物品桥只要铜铅（设计初稿以为要石墨）；Silicon Smelter (8) 的 8 台冶炼厂可产 12 硅/秒，但出口钛带只有 10/秒，满速按 10 计；普通传送带 5/秒、钛带 10/秒；"Full scale Metaglass factory" 实为 5 台窑。

**验证**：`./test.sh bootstrap` 覆盖目录数值、两种蓝图版本解码、五份蓝图的端口与满速、勘探（桥跨墙、危险区、基地范围）、配平（电厂数量、缺煤降产、带速受限、无电厂）。
```

`CHANGELOG.md` 的 `## [Unreleased]` 标题下插入：

```markdown
### Added
- Bootstrap production (in development), milestone 1: game data catalog, schematic decoding and module analysis, survey, balancing. Offline tests only; nothing in the game uses it yet.
```

- [ ] **Step 4: 提交**

```bash
git add DEVLOG.md CHANGELOG.md
git commit -m "docs: bootstrap M1 in DEVLOG and CHANGELOG

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
