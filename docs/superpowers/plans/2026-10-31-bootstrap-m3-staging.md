# 开局产线 M3（分步建造）实现计划

> 本计划在同一会话内直接执行（inline）。与 M2 计划相同，每个任务给出接口、算法与验收用例，代码在实现时写。

**Goal:** 把 M2 的终局 `BasePlan` 拆成步骤，按核心库存与收入贪心排序（含种子步骤、降级建造与升级步骤），由运行期 Director 一步一步下发给玩家单位；界面预览步骤顺序、确认后在角落显示进度并可暂停/取消；离线模拟测试、服务器上从近空核心跑通整套分步建造、客户端自测。

**Architecture:** 新增领域上下文 `staging`（`Step`、`StepGraph`、`StageState`、`StageOptions`、`StagePlanner`、`Schedule`），纯 Java；`layout.Forecast` 新增"只算已建部分"的入口（`Forecast.Built`）。`app` 新增 `Director`（状态机，依赖端口 `Site`：库存、单位队列、方块状态）与 `DirectorEvents`；`PlanBaseUseCase` 在布局后做分步模拟，必要时加种子模块重新规划。`infra.GameSite` 实现端口（客户端：玩家单位；服务器：按造价扣核心库存后即时放置）。`ui.BootstrapJob` 提交时启动 Director，`ui.DirectorHud` 显示进度。设计依据：设计文档第 4.5、5、6 节与 M2 DEVLOG 的"留给 M3"。

## Global Constraints

同 M2：游戏 v160.7、只考虑 Serpulo；`bootstrap/domain/**` 不 import `mindustry.*`/`arc.*`；`autobuild.plan` 只新增不改行为；代码风格与仓库一致；速率单位物品/秒；时间单位游戏秒；在 `feat/bootstrap-m3` 分支工作。

## 关键设计决定

1. **步骤**（`StepGraph.of(BasePlan, ProductionPlan, LayoutOptions)`）：
   - `MODULE m`：一个工厂模块的全部方块 + 它的出口走廊；`PLANT m`：一个电厂模块；
   - `MINE z`：一片矿区连同它的走廊；`WATER z`：泵区连同导管；
   - `NODES`：全部电力节点（便宜：每个 2 铜 6 铅；节点先建，后建的用电方块被游戏自动连上——`Building.placed()` 连射程内的节点）；
   - `UPGRADE s`：把降级建成的步骤 s 换回原方块（钛带、气动矿机；游戏允许原地替换同组同尺寸方块）。
   - 依赖：用电/发电的模块与电厂 → `NODES`（规划里有节点时）；送模块的矿区与泵区 → 该模块；一条线的第二个及以后的矿区（侧接到第一条走廊）→ 第一个矿区；`UPGRADE s` → s 以降级方式建成。
   - 不变式：`BasePlan.ordered()` 的每个方块恰好属于一个非升级步骤。
2. **降级建造**（D5 推广）：`StageOptions.downgrade`：钛带 → 所选普通带，气动矿机 → 机械矿机（同尺寸）。一步被选中时，若升级专用物品（钛、石墨）既无库存也无收入，就降级建造并解锁对应 `UPGRADE`。降级矿区产量按矿机挖掘时间比缩小（如铜 450/650），降级的线和模块端口按普通带速率封顶。钛矿只能用气动矿机，不降级。
3. **部分建成的预测**（`Forecast.compute(plan, prod, o, Built)`）：`Built` 给出每个分区的实际产量（未建为 0）、模块是否建成、每条线和每个模块端口的带速。原入口等价于"全部按原样建成"。
4. **收益与造价**：价值 = Σ 物品权重 × 进核心速率（默认权重：铜铅沙煤废料 1，钛石墨 2，钢化玻璃 2.5，硅 3）；造价价值同权重。候选 = 每个依赖已满足的未建步骤的**闭包**：加上未建依赖；模块缺某种原料（已建供给为 0）时加该原料最便宜的可建矿区/泵区；需要电而电不够时加电厂（及其燃料线、水）直到够或没有电厂；零收益的升级加上同一网络（同一模块及其线，或同一条核心线）的其他可做升级。
5. **贪心**：模拟时钟 t、库存（不超过核心容量）、收入 = 已建部分的预测。候选的等待时间 = max（缺额 ÷ 收入），缺额物品无收入即不可行。在可行候选中优先等待 ≤ 视界（默认 600 秒）者，取"新增价值 ÷ 造价价值"最高者（并列取等待短者）；都超视界时取等待最短者。没有正收益的剩余步骤按造价从小到大补齐。一个候选的步骤连续执行（`group` 相同），Director 只在组边界重排。
6. **种子**：模拟结束仍有步骤因缺石墨（无库存、无收入）建不了，且目标里没有种子 → 目标加一台内置石墨压缩机模块重新规划（只加一次）。其他缺料（如铅、硅）写进摘要"需要手动补充"。
7. **Director**（app）：`Idle → Waiting(group) → Building(step) → … → Done`，`Paused`、`Blocked`、`Cancelled`。每 2 秒（游戏时间）：
   - Waiting：当前步骤未建方块的造价被库存覆盖（无限资源时总是）→ 交给单位，进入 Building；否则记下缺的材料。
   - Building：全部建成 → 完成；组结束时用实际库存与已建部分重新排序剩余步骤。有方块不可放置（被别的东西占了）→ `Blocked`（可"重试"或"跳过此步"，跳过连带依赖它的步骤）。单位队列里已没有本步的方块（单位死亡、重生、被玩家清空）→ 重新下发缺的部分。
   - 已完成步骤每 10 秒检查一次，缺失的方块重新下发（修复），不重排。
   - 我们的物品桥建成后清掉游戏的"上一座桥"记忆（M2 的修正）。
8. **端口 `Site`**（app 定义，infra 实现）：`stock(item)`、`infinite()`、`status(Placement)`（`BUILT`、`PENDING`（在队列或在建）、`FREE`（可放、可替换）、`BLOCKED`）、`queue(List<Placement>)`、`unqueue(List<Placement>)`、`canBuild()`。客户端实现用玩家单位；服务器测试实现按造价扣核心物品后即时放置（`abboot stage`）。
9. **界面**：预览摘要列出前若干步（序号、名称、造价、预计开始时间、新增产量）、总时长与缺料提示；地图上每个步骤的中心画序号。"建造"启动 Director；角落面板显示当前步骤、状态、缺的材料、进度，暂停/继续/取消，阻塞时重试/跳过。
10. 不做（留给以后）：步骤内按材料逐块建造（Starter Factory 的窑）；阻塞时局部重算；Director 存档；界面里调物品权重；运行期实测收入代替预测收入。

## Tasks

### Task 1: 部分建成的预测与步骤图
- `Forecast.Built`、`Forecast.compute(plan, prod, o, built)`；`BasePlanner` 给泵区填 `rate`。
- `Step`（id、kind、label、placements、deps、zones、instance、line、upgradeOf、group key）、`StepGraph.of(...)`、`Step.cost(cat, plain, swap)`。
- **验收（StageTest）**：步骤覆盖全部方块且不重复；依赖无环；全部建成的 `Built` 预测与原预测相同；只建一个核心线矿区时核心只收到那条线。

### Task 2: 分步模拟
- `StageOptions`、`StageState`、`StagePlanner.next(graph, state)`（返回一组步骤与变体）与 `simulate(graph, state)` → `Schedule`（每项：步骤、是否降级、开始/完成时间、造价、新增价值；卡住的步骤与缺料）。
- **验收**：构造地图上从 铜 300 铅 200 开始：库存从不为负、依赖不违反、全部步骤排上；无石墨时矿机降级并在有石墨后出现升级；要钛线又无石墨时用例加种子（press）后全部排上；无铅开局时报告缺铅。

### Task 3: Director 与用例
- `Director`（上面的状态机）、`DirectorEvents`（步骤开始/完成/阻塞，用于日志与界面）、`Site` 端口。
- `PlanBaseUseCase.Input.stock`、`infinite`；`Result.steps`、`schedule`、`seeded`。
- **验收（DirectorTest，假的 Site：每 tick 按预测收入加库存、单位每 tick 建若干块）**：从开局跑到 Done，库存不为负；摧毁一块后被修复；占用一格后进入 Blocked，跳过后继续。

### Task 4: 游戏适配、界面、服务器与客户端实测
- `infra.GameSite`（客户端玩家单位；服务器即时放置并扣库存）、`ui.BootstrapJob`（提交启动 Director、摘要、步骤序号）、`ui.DirectorHud`、bundle 中英文。
- 服务器：`abboot ... stage start=copper:300,lead:200 speed=N`：核心清空后放入起始物品，Director 每 2 游戏秒 tick，日志打印每步开始/完成时间与预测；`abbootcount` 照常测收入。脚本 `servertest-boot.sh` 支持更长的测量（`STAGE=秒`）。
- 客户端：`./selftest.sh <map>:boot`：生存规则、关波次、起始物品，走真实界面流程（选区、规划、预览截图、建造），单位逐步建造到 Done 或超时，打印时间线，最终截图。
- **验收**：至少一张地图服务器上从近空核心跑到 Done，最终收入与预测相符；客户端自测 PASS。

### Task 5: 文档
- DEVLOG"开局产线 M3"、CHANGELOG、设计文档状态行；记忆更新。
