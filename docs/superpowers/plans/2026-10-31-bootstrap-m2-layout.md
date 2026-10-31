# 开局产线 M2（终局布局）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 本计划在同一会话内直接执行（inline）。与 M1 计划不同，这里每个任务给出接口、算法与验收用例，代码在实现时写；原因是布局层大量适配既有算法库，逐行代码先写进计划等于写两遍。

**Goal:** 把 M1 的配平结果落到地图上：模块摆放（含旋转）、矿区、干线走廊、水区、电网，得到一份完整的终局 `BasePlan` 和按实际布局重算的产量预测；接上游戏（地形、目录、蓝图、建造计划），在"开局产线"标签页预览并一次性下发，并在专用服务器上实测产量与预测对比。

**Architecture:** 新增领域上下文 `layout`（聚合根 `BasePlan`、`Reservation`、`Stamp`、`ModulePlacer`、`CorridorRouter`、`WaterSiteSolver`、`BasePlanner`、`Forecast`）、`mining`（`MiningSiteSolver`，`DrillLayoutPlanner` 的防腐层）、`power`（`PowerGridSolver`，`PowerNetwork` 的防腐层），全部纯 Java、离线可测。`infra` 实现游戏适配；`app.PlanBaseUseCase` 串起勘探→分析→配平→布局；`ui.BootstrapJob` 是第三个标签页。设计依据：`docs/superpowers/specs/2026-10-31-bootstrap-production-design.md` 第 3、4.4 节与 M1 DEVLOG 的"留给 M2"。

**Tech Stack:** Java 17；离线测试沿用 `BootstrapTest`（计失败数的 main）；服务器实测用官方 `server-release.jar` v160.7。

## Global Constraints

- 游戏版本 Mindustry v8 build 160.7，只考虑 Serpulo。
- `src/autobuild/bootstrap/domain/**` 不得 import `mindustry.*` / `arc.*`（`test.sh` 检查）。
- `autobuild.plan` 算法库只允许**新增**入口，不改变既有行为（采矿场、防御工事的离线测试必须照常通过）。
- 代码风格与仓库一致：4 空格缩进，`){`、`if(`、`for(` 不加空格，类与非显然方法写 Javadoc。
- 速率单位：物品（液体）每秒。
- 坐标约定与游戏一致：方块的 (x, y) 是它的中心格，占地 `x + lo() .. x + hi()`（偶数尺寸偏左下）。
- 创意工坊蓝图只放 `test/local/blueprints/`（git 忽略），缺失时测试打印 SKIP。
- 提交信息结尾：`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；在 `feat/bootstrap-m2` 分支工作。

## 关键设计决定

1. **分区占用与"串料"规则**（`Reservation`）：每格记录占用者（网络 id）与方块的物品/液体行为位：收物品、推物品（矿机、工厂、路由器、尾端桥、分类器、溢流门向四周推）、收液体、推液体。一条走廊的方块不得贴着别的网络的推送方块；推送方块（尾端桥）不得贴着别的网络的接收方块；核心收任何物品，不算串料。
2. **模块四周留 1 格环**：环上只允许电力节点和该模块自己端口的接入格；工厂产物会推到模块边上任何收物品的方块，环保证走廊不贴边（M1 遗留项）。相邻模块的环可以重叠，即模块间至少 1 格通道。
3. **模块可旋转**：`Stamp` 把蓝图转 0–3 个四分之一圈后平移；方块中心按旋转后占地的最小角重算，桥的连接偏移、端口方向一起转。
4. **缺钛替换**：传送带等级低于钛带时，模块内的钛带换成所选传送带；预测里每个出口的上限取 min(端口带速, 走廊带速)。
5. **矿区按需裁剪**：从矿团里离目的地最近的、尚未占用的矿格开始取，取够"需求 × 1.15 ÷（覆盖率 × 每格速率）"格，取其外接矩形 + 2 格边距交给 `DrillLayoutPlanner`；输出口放在矩形朝向目的地的一边外侧。
6. **一条线多个矿团**：第一份来源的走廊接到模块入口；之后每份来源的走廊**侧接**到这条线已建走廊的普通传送带上（`GridRouter` 新增"目标集合"入口，原入口行为不变）。
7. **水区**：一段直的导管"脊"，两侧（起点还有背后）是可放机械泵的水格，泵数 = ⌈需求 ÷ 单泵速率⌉；脊的末端接导管走廊到电厂水入口。取离入口最近的若干候选逐个试路由，取总代价最低者。
8. **电网**：耗电或发电的模块才接入；有自带节点的模块以其一个节点作为被连接对象，没有的以模块里每个耗电/发电方块作为对象；在这些对象外接矩形（加节点射程边距）的局部窗口里运行 `PowerNetwork`，最后连成一组。
9. **预测**（`Forecast`）：每条线实际供给 = Σ 已建成来源（矿区 `sinkLoad`，受带速限制；泵数 × 单泵速率）；模块速度 = 最缺物品的供给比，再受出口带速与电力比例限制；电厂发电按燃料/水比例；送到核心的产物 = 模块产出 × 速度（出口走廊已建成时）+ 原料线供给。
10. **建造顺序**（供即时放置与 M3 分步）：电力节点 → 走廊与矿区物流 → 模块方块 → 矿机。
11. **内置模块**：燃烧发电电厂（3×6：一条入口带 + 4 个溢流门 + 8 台燃烧发电机，只用铜铅）、单台石墨压缩机（种子模块）用代码构造蓝图；硅（Silicon Smelter (8)）、涡轮电厂（generator1）作为资源文件随 mod 发布。

## File Structure

| 文件 | 职责 |
|---|---|
| `src/autobuild/bootstrap/domain/shared/Placement.java` | 一个要建的方块（名称、中心格、朝向、配置、所属网络） |
| `src/autobuild/bootstrap/domain/survey/PathField.java`（改） | 新增从任意格集合出发的代价场 |
| `src/autobuild/bootstrap/domain/survey/SurveyService.java`（改） | `Report` 暴露可用格掩码 |
| `src/autobuild/bootstrap/domain/module/ModuleSpec.java`（改） | 带上蓝图本身 |
| `src/autobuild/bootstrap/domain/module/BuiltinModules.java` | 内置燃烧电厂、石墨压缩机蓝图 |
| `src/autobuild/bootstrap/domain/layout/Reservation.java` | 格子占用与串料规则 |
| `src/autobuild/bootstrap/domain/layout/Stamp.java` | 蓝图旋转/平移后的方块与端口 |
| `src/autobuild/bootstrap/domain/layout/Zone.java`、`Corridor.java`、`BasePlan.java` | 分区、走廊、聚合根 |
| `src/autobuild/bootstrap/domain/layout/LayoutOptions.java` | 所用方块（带、桥、导管、液体桥、节点、泵、矿机）与参数 |
| `src/autobuild/bootstrap/domain/layout/ModulePlacer.java` | 工业区/电厂选址 |
| `src/autobuild/bootstrap/domain/layout/CorridorRouter.java` | `GridRouter` 防腐层 |
| `src/autobuild/bootstrap/domain/layout/WaterSiteSolver.java` | 水区 |
| `src/autobuild/bootstrap/domain/layout/Forecast.java` | 按布局重算产量 |
| `src/autobuild/bootstrap/domain/layout/BasePlanner.java` | 求解顺序编排 |
| `src/autobuild/bootstrap/domain/mining/MiningSite.java`、`MiningSiteSolver.java` | `DrillLayoutPlanner` 防腐层 |
| `src/autobuild/bootstrap/domain/power/PowerGridSolver.java` | `PowerNetwork` 防腐层 |
| `src/autobuild/plan/GridRouter.java`（改） | 新增 `Goal` 目标集合入口 |
| `src/autobuild/bootstrap/app/PlanBaseUseCase.java` | 勘探→分析→配平→布局 |
| `src/autobuild/bootstrap/infra/GameTerrain.java`、`GameCatalog.java`、`GameBlueprints.java`、`GameDanger.java`、`GamePlans.java` | 游戏适配 |
| `src/autobuild/bootstrap/ui/BootstrapJob.java` | 第三个标签页 |
| `src/autobuild/ServerBootstrapTest.java`、`servertest-boot.sh` | 服务器实测 `abboot` / `abbootcount` |
| `assets/bootstrap/*.txt` | 随 mod 发布的蓝图 |
| `test/autobuild/bootstrap/StampTest.java`、`LayoutTest.java`、`LayoutChecks.java` | 离线测试 |

---

### Task 1: 共享内核扩展、代价场、旋转与内置模块

**Interfaces — Produces:**
- `Placement(String block, int x, int y, int rot, Object config, int owner)`；`config` 取值同 `Blueprint.Tile`。
- `PathField.fromTiles(int w, int h, boolean[] usable, int[] seeds, int bridgeRange)`：种子格代价 0。
- `SurveyService.Report.usable`（`boolean[]`，x + y·w）、`allowed`。
- `ModuleSpec.blueprint`（分析时填）。
- `Stamp.of(ModuleSpec spec, int rot, int ox, int oy, Catalog cat, Map<String,String> swap)`：`w,h`（旋转后）、`x0,y0,x1,y1`、`List<Placement> blocks`（不含标记）、`List<StampPort> inlets/outlets`（世界坐标接入格、方向、对应 `Port`）。
- `BuiltinModules.combustionPlant(Catalog)`、`graphitePress(Catalog)` 返回 `Blueprint`。

**验收（StampTest）：** silicon8 四个朝向：端口数不变；每个方块占地在矩形内且互不重叠；边缘端口接入格恰在矩形外一格且方向指向矩形；转一圈回到原样。燃烧电厂分析：入口 1（煤）、无出口、发电 480/s、耗煤 4/s、需补节点；石墨压缩机：入口煤 1.33/s、出口石墨 0.67/s。

### Task 2: 占用、走廊路由、矿区

**Interfaces — Produces:**
- `Reservation(int w, int h, boolean[] usable)`：`free(x,y)`、`claim(Placement, BlockInfo)`、`ownerAt`、`ring(x,y)`、`setRing(...)`、`itemSafe(x,y,owner)`（不贴别的网络的推送方块）、`pushSafe(x,y,owner)`、液体同理。
- `GridRouter.Goal`（`hit(u,v,d)`、`estimate(u,v)`）与 `route(List<Start>, Goal)`；原 `route(starts, tu, tv, ts)` 委托给它，行为不变。
- `CorridorRouter.route(Reservation, LayoutOptions, boolean liquid, int owner, List<Start>, Goal, Set<Integer> allowed)` → `Corridor`（`steps`、`placements`、`cost`），失败为 null。
- `MiningSiteSolver.solve(Terrain, Catalog, Reservation, LayoutOptions, OrePatch, double need, int destX, int destY, int owner)` → `MiningSite`（`placements`、`sinkX/sinkY/sinkDir`、`rate`），失败带原因。

**验收（LayoutTest 部分）：** 走廊不越过占用格、不贴别的网络的推送方块；侧接目标只接普通带的侧面；矿区不压别的网络、输出口在区外且朝向目的地；`PlannerTest`、`DefenseTest` 不受影响。

### Task 3: 选址、水区、电网、预测与编排

**Interfaces — Produces:**
- `ModulePlacer.place(...)`：逐个实例枚举 4 个朝向 × 全图原点，评分 = Σ入口速率 × 来源代价场 + Σ出口速率 × 核心代价场 + 压矿惩罚 + 到已放模块重心的距离；占地全可用、扩一圈矩形内无他人方块、接入格可用。
- `WaterSiteSolver.solve(...)` → 泵、脊、导管走廊。
- `PowerGridSolver.solve(...)` → 节点 `Placement`。
- `Forecast.compute(BasePlan, ProductionPlan, LayoutOptions)` → 每线供给、每实例速度、`toCore`（物品 → 每秒）、电力供需。
- `BasePlanner.plan(Terrain, Catalog, SurveyService.Report, ProductionPlan, LayoutOptions)` → `BasePlan`（`zones`、`corridors`、`nodes`、`ordered()`、`forecast`、`notes`）。

**验收（LayoutTest，随机/构造地图上的不变式）：** 无重叠；全部在可用格（泵可在深水）；不进危险区与基地范围外；每条走廊步步相接、终点在目标里；无串料；每个需电方块被节点覆盖且节点连成一组；预测值不超过带速；核心收到硅与铜。

### Task 4: 游戏适配、用例与服务器实测

**Interfaces — Produces:**
- `GameTerrain.snapshot(Team)`、`GameCatalog.live()`、`GameBlueprints.schematics()/builtin()`（读失败捕获异常，M1 遗留项）、`GameDanger.mask(...)`、`GamePlans.toBuildPlans(List<Placement>)`。
- `PlanBaseUseCase.Input/Result`，`run(...)`。
- 服务器命令 `abboot [silicon=N] [graphite=PATH:N] [glass=PATH:N] [power=combustion|turbine|PATH] [raw=copper:1,lead:1] [belt=NAME]` 与 `abbootcount`；脚本 `servertest-boot.sh <map> <args>`。

**验收：** 至少两张内置地图上即时放下整套布局，预热后核心各产物实测速率与预测相差在 15% 以内（或给出偏差原因并修正）。

### Task 5: 界面

- `BootstrapJob`：选区即基地范围；设置里每个可用模块（内置 + 游戏蓝图）一个数量，电厂选择，铜铅钛原料线数，传送带等级；无端口，直接规划；结果以建造计划预览，摘要列出各产物预测、瓶颈、未建成的线与原因；"建造"一次性下发（分步在 M3）。
- 中英文 bundle。

**验收：** 编译通过；`./test.sh` 全部通过。

### Task 6: 文档

- DEVLOG 新增"开局产线 M2"一节（方案、试错、实测数据、留给 M3）；CHANGELOG；设计文档状态行；README 不动（未发布）。
