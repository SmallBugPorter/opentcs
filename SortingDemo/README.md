# SortingDemo：openTCS 原生 Scheduler 分拣仿真

`Sorting.xml` 从 `Sorting.building.yaml` 生成，包含 253 个导航点、424 条路径、34 个下料位置、3 个上料位置、25 个充电位和 24 台 Loopback 车辆。地图中的 `droppff_1` 至 `droppff_12` 拼写保持原样，与 `dropoff_13` 至 `dropoff_34` 一起作为下料目标。

## 调度方式

使用 openTCS 原生 Router、DefaultVehicleController 和 Scheduler。脚本只生成作业任务，不再执行时间步预约，也不等待整个车队到齐后才发送下一步。

每台车辆独立执行：

1. 上料任务：按预计行驶成本和排队数量动态选择上料位，经过其四个等待点，最后执行 `Load cargo`。
2. 送货任务：一个目标位置、一个 `Unload cargo` 操作。中间道路由 Router 规划，普通中间点不再分别生成运输订单。
3. 落料完成后，电量大于 20% 时继续上料；电量小于等于 20% 时前往自己的充电位。

车辆控制器向 Scheduler 声明路线资源序列，逐段申请资源，收到执行反馈后释放已驶离的资源。资源被占用时，相应车辆等待；脚本不会强制所有车辆同步等待。上料等待点仍是显式任务途经点，可能出现命令切换停顿。Loopback 已扩展节点处的旋转仿真；直线运动仍为简化模型，不代表真实车辆完整的连续速度曲线。

| 上料位 | 队尾到上料位 |
| --- | --- |
| pickup_1 | waite_4 → waite_3 → waite_2 → waite_1 → pickup_1 |
| pickup_2 | waite_8 → waite_7 → waite_6 → waite_5 → pickup_2 |
| pickup_3 | waite_12 → waite_11 → waite_10 → waite_9 → pickup_3 |

这不是提前独占整条送货路线，也不是未来时刻的通行时间表。原生资源互斥不等于全局死锁规避。车辆超过 5 秒没有路线进展且处于 IDLE 等资源状态时，脚本用订单的 `tcs:resourcesToAvoid` 属性避开其他车辆当前点位，并调用 Dispatcher 的 `REGULAR` 重规划；同一车辆两次重规划至少间隔 10 秒。必经上料、等待和目标点，以及车辆自身已获分配的前方资源不会被排除，避免将常规重规划的起点从路由图中移除。因占用快照变化导致找不到路径时恢复原避让属性，留待下一次尝试，不停止全车队派单。重规划保留已执行/已分配路线前缀，资源仍由 Scheduler 控制，不撤单、不跳点。若没有可行绕行，车辆继续等待；超过 60 秒输出 `WAITING`，应检查占用和地图拥堵。暂停一台车可能通过道路占用影响相邻车辆，但不会触发脚本层面的全车队屏障。

## 随机目标、电量和统计

每次分拣从全部 34 个下料点等概率随机选择，允许连续重复。随机种子、已选目标和当前订单名称保存在检查点，重启及创建订单重试不改变该包裹目标；`setup` 开始新仿真时生成新种子。

车辆最大前进/倒车速度均为 1.8 m/s，加速度 1.0 m/s²，减速度属性为 -1000 mm/s²。车身运行尺寸为 500 × 500 × 400 mm，Loopback 的空载及载货长度均配置为 500 mm。道路未指定单独限速时使用 1.8 m/s，地图方向保持 YAML 设置。正常仿真时间倍率为 1。

每行驶 1 米消耗 0.01 个百分点。耗电依据 Kernel 的已执行路线步骤和实际路径长度累计，不根据两次轮询位置之间的直线距离估算；已扣除距离随检查点保存，重启不重复扣电。当前位置尚未反馈的一段路程在下一次确认时计入。等待和装卸不耗电。

20% 阈值在接下一次上料任务之前判断。已经接下的上料、送货任务完成落料后再充电；充电期间其他车辆独立工作。充电速度由 Kernel 的 `virtualvehicle.rechargePercentagePerSecond=1.0` 控制，充满 100% 恢复工作。Kernel 停止普通派单的临界阈值由脚本设为 0%，20% 返充策略由脚本负责。

只有成功完成的送货 UNLOAD 算一次分拣，依据订单实际完成时间计入小时窗口。统计包括等待和充电时间，暂停脚本期间的真实时间也计入。`runtime/hourly.csv` 包含每小时总数、各车数量和充电次数；`COMPLETE` 才是完整小时结果，`IN_PROGRESS` 和 `estimated_sorts_per_hour` 不能当成一小时实测值。CSV 时间为 UTC，北京时间加 8 小时。

## 使用

在项目根目录运行，需要 Python 3、PyYAML、JDK、本仓库已构建的发行目录，以及本机运行中的 Kernel：

```sh
python3 SortingDemo/convert_sorting.py
SortingDemo/simulation.sh check
SortingDemo/simulation.sh setup
SortingDemo/simulation.sh start
SortingDemo/simulation.sh status
SortingDemo/simulation.sh stats
SortingDemo/simulation.sh stop
```

旧版逐步调度检查点不能直接续跑，迁移到原生 Scheduler 需要 `setup`。此命令备份旧模型、检查点和小时 CSV，重置 24 台车辆及计数；有未完成任务时拒绝重置。

`stop` 只停止生成新任务，已经提交的完整上料/送货/充电任务仍可继续执行。再次 `start` 核对当前订单并恢复计数，不重复提交已经存在的订单。不要在暂停期间删除历史任务、手工派单、重新定位或重新规划当前任务路线，否则检查点和实际执行记录可能不一致。脚本不会自动修复失败任务或重置车辆。

`SortingDemo/simulation.sh measure` 从运行中的下一次轮询重新开始小时测量，不重置车辆累计数量。需要旧测量结果时请先保存 CSV。修改 `simulation.properties` 中电量参数后，需要 `setup` 开始新仿真。

## 文件和验证

- `SortingSimulation.java`：完整作业派单、Kernel 路线进度耗电、检查点和统计。
- `SortingTraffic.java`：地图解析及稳定随机目标选择；保留的旧时间步规划器不参与当前运行。
- `SortingMetrics.java`：真实小时窗口和 CSV。
- `SortingSchedulerTest.java`：作业粒度、执行距离、独立车辆状态、低电量策略及检查点恢复。
- `SortingTrafficTest.java`：地图全目标路线及随机选择检查；`check` 仅运行路线检查，不再运行旧时间步规划器的长时模拟。
- `runtime/simulation.log`：`SUBMIT`、`FINISHED`、`RECHARGED`、`REROUTE`、`WAITING`。
- `runtime/traffic-state.properties`：原生 Scheduler 模式检查点。
- `runtime/before-native-scheduler/`：切换前的逐步调度源码和说明。

旧地图、旧逐步调度的小时结果不能代表当前原生 Scheduler 的吞吐量。

本机 Kernel 验证：24 台车均完成分拣；发现并通过常规重规划恢复了一次循环等待，未撤销订单或重置车辆。检查到单个送货订单含多段道路，3 秒采样内释放 111 项资源；核对的 80 次已确认落料与 Kernel 完成订单一致。停止/续跑及后续分拣通过。此为短时功能验证，不代表完整一小时产能或任意地图无死锁保证。详见 `runtime/native-scheduler-verification.txt`。

## 旋转仿真

已扩展 `opentcs-commadapter-loopback`，SortingDemo 默认启用；其他地图未设置 `loopback:rotationEnabled=true` 时不增加转向等待。

| simulation.properties 配置 | 默认值 | XML 车辆属性 |
| --- | --- | --- |
| `rotation.enabled` | true | `loopback:rotationEnabled` |
| `rotation.maxVelocityDegPerSec` | 90°/s | `loopback:maxAngularVelocity` |
| `rotation.maxAccelerationDegPerSec2` | 180°/s² | `loopback:maxAngularAcceleration` |
| `rotation.maxDecelerationDegPerSec2` | 180°/s² | `loopback:maxAngularDeceleration` |

三个角运动限制均为正数，角减速度填写正的减速度大小，与直线 `loopback:deceleration=-1000` 的符号约定不同。修改配置后运行转换脚本重新生成 XML，并重新载入模型/创建适配器；运行中的适配器不热更新这些参数。

每段道路开始之前，根据源点和目标点的连线计算方向。倒车时车头方向与移动方向相反；从当前朝向选择最短旋转角，180° 的并列情况固定顺时针转向。直行不增加旋转等待。初始朝向未知时，对齐第一段道路，不假定车辆面向 0°；之后持续保留和更新朝向，包括上下料和等待期间。

旋转采用静止起步、静止结束的三角形/梯形角速度曲线，短转角可能达不到最大角速度。默认参数下，90° 转向为 1.5 秒，180° 掉头为 2.5 秒；执行以 100 ms 仿真步更新并受时间倍率影响，时间可能向上取整一个仿真步。暂停冻结旋转进度，恢复后继续。

转向期间车辆位置保持在源点，不发送下一段的完成反馈，不提前报告到达目标点。Scheduler 保留源点和已分配道路资源，转完后才开始行驶，行驶完成后按原生机制释放资源。该逻辑使用点/道路资源，不新增车身旋转扫掠区域的几何碰撞模型。直线模型也未增加沿路提前制动的物理模型；在节点转向时将平移速度置零。

旋转耗时自然计入一小时分拣统计；当前电量模型仍只按行驶距离耗电，旋转本身未单独扣电。`simulation.sh status` 显示旋转参数、`rotating` 和当前 `angularVelocity`。

升级适配器需要重新构建并重启 Kernel。对应构建命令为：

```sh
./gradlew :opentcs-commadapter-loopback:test :opentcs-commadapter-loopback:jar
```

发布时应同步更新 Kernel、Kernel Control Center 和 Operations Desk 的 Loopback JAR，并重启使用旧类的进程。本次本机发行目录中的三份 JAR 均已更新，旧地图、检查点、统计和 JAR 保存在 `runtime/before-rotation-*`。

`simulation.sh drain` 可请求正在运行的调度器停止接新包裹，完成已接任务并回到各自停车位，待日志出现 `DRAINED` 后进行维护。该回停车位只用于维护，不改变正常运行时低电量才返充的策略；维护停车不计分拣或充电。迁移后执行 `setup` 清除维护标记并开始新一轮。

初次旋转扩展验证（旧角加减速度 180°/s²）：模块 17 项测试通过，格式检查及 Checkstyle 检查通过（无错误）；本机 24 台适配器均确认启用 90/180/180 参数。20 秒实机采样中，15 台车提供了 126 个旋转样本，每个样本均保持点位及相关道路资源占用，同时朝向发生变化。记录见 `runtime/rotation-verification.txt`。升级时原 Kernel 已停止，因此旧记录已备份，新仿真从新检查点开始；没有把旧未完成任务计为已完成分拣。

## 连续行驶坐标

Loopback 每 100 ms 按速度控制器的已行驶距离与道路长度之比，在源点和目标点之间插值更新 `Pose.position`（包含 X/Y/Z，毫米精度），保留当前朝向。暂停或坐标未变化时不重复发布位姿事件；到达时精确对齐目标坐标。该功能使用直线几何插值，适用于当前分拣地图，不模拟曲线道路形状。

中间坐标不更新逻辑顶点、不确认命令完成；Scheduler 仍在实际到达后的执行反馈中释放相关道路资源。加减速、旋转、耗电和分拣计数沿用现有模型。24 台车同时移动时，平移最多约每秒 240 次位姿更新；此数值是事件频率上界，不是 CPU 性能测量。

Operations Desk 使用精确坐标显示车辆；菜单中的“忽略精确位置”（Ignore precise position）应保持未勾选，发行配置默认已关闭此选项。

连续坐标升级验证：19 项适配器测试、格式检查及 Checkstyle 检查通过（Checkstyle 无错误，保留现有警告），分拣路由与调度检查通过。运行中的 24 台车运动/尺寸参数核对通过；短时采样取得 4 台车的 250 个中间坐标变化样本，所有样本都保留逻辑源点资源。详见 `runtime/continuous-pose-verification.txt`。升级前任务全部完成并停靠，115 次已完成分拣及检查点已备份到 `runtime/before-continuous-pose-*`；升级后从新一轮统计开始。未进行额外 CPU 开销对比测量。

上一轮地图更新：294 个导航点、465 条道路、42 个下料点，24 × 42 条作业路线及随机目标/调度检查通过。旧地图仿真曾出现全车等待资源，未能正常维护停靠；检查点和小时统计已保存于 `runtime/map-update-*`，切换新地图后从新一轮计数开始，旧未完成任务未计为分拣完成。

上一轮地图更新：288 个导航点、459 条道路、36 个下料点，移除下料点 35、36、37、40、41、42；24 × 36 条路线及随机目标/调度检查通过。旧仿真维护停靠期间出现全车资源等待，125 次已完成分拣及检查点已备份到 `runtime/map-update-*`，新地图已重新启动 24 台车辆并单独统计。

上一轮地图更新：252 个导航点、423 条道路、34 个下料点；24 × 34 条路线及随机目标/调度检查通过。更新时 Kernel 已停止，旧检查点和统计已备份到 `runtime/map-update-*`。新地图重新启动 24 台车，保留车辆参数并单独计数；连续坐标及资源占用采样见备份目录 `pose-verification.txt`。

2026-09-15 参数更新：直线加速度 1000 mm/s²、减速度 -1000 mm/s²，角加速度和角减速度均为 180°/s²；最大线速度 1800 mm/s、最大角速度 90°/s，其余参数沿用。24 台运行适配器的运动与车身参数核对通过，连续坐标/资源占用采样通过。更新时 Kernel 已停止，旧检查点及统计保存在 `runtime/before-acceleration-*`，新仿真单独计数。

最新地图更新：253 个导航点、424 条道路、34 个下料点、25 个充电位。新增 `init_25` 作为备用，24 台车仍按现有策略返回各自充电位。24 × 34 作业路线和备用充电位往返可达性检查通过。旧仿真正常完成维护停靠，775 次分拣及统计已备份到 `runtime/map-update-*`，新地图单独计数。

后续地图更新：调整 pickup_2、pickup_3 位置及相关道路，仍为 253 个导航点、424 条道路、34 个下料点、25 个充电位。路线检查通过，24 台车正常停靠后载入新地图，旧轮次 97 次分拣及检查点已备份到 `runtime/map-update-*`，新轮次独立计数。

## 动态上料选择（仅选择，不限流）

新上料任务按 `预计行驶秒数 + 队列车辆数 × pickup.queuePenaltySeconds` 评分，选择可达且评分最低的上料位。默认排队权重为每车 8 秒；在 `simulation.properties` 修改后重启分拣脚本生效。距离来自有向道路长度，并结合道路/车辆限速、加减速换算时间；从当前逻辑点依次估算到四个等待点和上料位的成本。每段路按静止起步、静止结束估算，不包含精确旋转耗时或未来交通变化，且实际路线仍由 Kernel Router 计算，因此此分值不是准确 ETA。

队列包括已分配到该区的在途/等待/上料任务，以及仍处于四个等待点或上料点的其他车辆；同一车辆只计一次，排除自身。同一轮每次确定选择后，立即更新并保存任务意图，下一台车据此评分。缓存静态道路成本，避免反复搜索全图。

不限制每个区的准入数量，不创建 `STANDBY` 或满区返停车位任务。车辆按原有规则持续接上料/送货任务，低电量才按充电策略返充。已接上料任务保持既定目的地；新任务才重新选择。选择结果保存在检查点中，重启不重选，旧固定分组的未完成任务按原分组恢复。此功能不提供死锁保证，实际产能需运行对比。

`status` 显示每区 `queuedOrPresent`；日志 `PICKUP_SELECT` 包含所选上料位、道路距离（米）、预计行驶秒数、排队数及总评分。检查命令覆盖距离与队列选择、超过 4 台仍继续派单、不生成停车任务、队列去重、重启与旧任务兼容，以及所有下料点/停车位到三个上料区的可达性。
