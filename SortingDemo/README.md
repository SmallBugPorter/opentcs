# SortingDemo：25 车 MQTT / VDA5050 分拣仿真

分拣地图中全部 25 辆车使用 openTCS 的 VDA5050 2.0 通信适配器。独立进程 `SortingMqttFleet` 为每辆车运行一个模拟器，通过真实 MQTT Broker 接收订单、上报位置、电量、装卸货及连接状态。

模拟器由项目 `opentcs-commadapter-vda5050/.../v2_0/simulation/VehicleSimulator.java` 示例改编，复用其 VDA5050 消息类型和 JSON 序列化器。示例中的固定单车身份、`v1` topic、交互式标准输入和不完整动作处理已由本目录的多车实现替代。没有修改上游示例，也不需要重新构建整个 openTCS。

```text
SortingSimulation（作业生成、统计）
    ↓ Kernel API
openTCS Kernel（Router / Scheduler / VDA5050 适配器）
    ↕ MQTT
MQTT Broker
    ↕ MQTT
SortingMqttFleet（25 个独立 Client ID，各自持有车辆状态）
```

车辆端程序不调用 Kernel API，也不会通过 Loopback 消息修改位置或电量。地图在 Operations Desk 上显示的车辆状态来自 MQTT `state` 消息。

车队数量统一配置在 `simulation.properties` 的 `fleet.size=25`。地图生成、派单、统计和 MQTT 校验均使用该值；增加数量时必须存在对应 `init_N` 点，重新初始化会备份旧统计。

## 首次迁移 / 初始化

在 `/home/lebi/opentcs_ws/opentcs` 项目根目录执行，需要 JDK 21、Python 3 / PyYAML、已构建的发行目录、运行中的 Kernel 和 MQTT Broker。

在 `build/install/opentcs/opentcs-kernel` 中执行 `sh startKernel.sh` 启动 Kernel。使用发行目录自身的 `config/`、`data/` 和 `log/`，不重定向到 SortingDemo。Kernel 在前台运行，请保持终端打开，并在另一个终端启动车队和派单器。

Kernel 的 `build/install/opentcs/opentcs-kernel/config/opentcs-kernel.properties` 应配置：

```properties
commadapter.vehicle.vda5050.enabledVersions = 2.0
commadapter.vehicle.vda5050.mqtt.brokerHost = 127.0.0.1
commadapter.vehicle.vda5050.mqtt.brokerPort = 1883
commadapter.vehicle.vda5050.mqtt.connectionEncrypted = false
commadapter.vehicle.vda5050.mqtt.username = YOUR_BROKER_USER
commadapter.vehicle.vda5050.mqtt.password = YOUR_BROKER_PASSWORD
commadapter.vehicle.vda5050.mqtt.clientId = opentcs-kernel-01
```

按实际 Broker 配置填写地址、认证和 TLS 参数，修改后重启 Kernel。模拟器读取同一组配置（基线默认值 → 自定义默认值 → `opentcs-kernel.properties`），密码不会复制到地图、命令行或日志。模拟器和 Kernel 在同一主机环境下运行，故使用相同 Broker 地址；将模拟器搬到另一台主机/容器时，需要提供该环境可访问的配置。

初始化流程：

```bash
SortingDemo/simulation.sh stop
SortingDemo/mqtt-vehicles.sh stop
python3 SortingDemo/convert_sorting.py
SortingDemo/simulation.sh check
SortingDemo/mqtt-vehicles.sh check
SortingDemo/simulation.sh setup
SortingDemo/mqtt-vehicles.sh start
SortingDemo/simulation.sh status
SortingDemo/simulation.sh start
```

`setup` 会替换 Kernel 地图并重置本次分拣统计，要求所有现有任务结束、派单器和 MQTT 模拟器都已停止。旧地图、派单检查点、小时统计、车辆模拟器检查点会备份到 `SortingDemo/runtime/`。旧 Loopback 的检查点不能直接迁移到 MQTT 模式。不要在有进行中运输任务时直接上传地图或重置车辆。

`mqtt-vehicles.sh start` 启动一个后台 Java 进程，其中有 25 个 MQTT 客户端；启动日志应出现 25 条 `ONLINE Sorting-AGV-xx`。`simulation.sh status` 应显示 25 辆车驱动已启用、位置为各自 `init_N`、状态为 `IDLE`，此后启动派单。

## 日常启动、停止与恢复

已完成 `setup` 后，平时无需再次初始化。`setup` 必须在 MQTT 模拟器停止时执行；运行中的车队不允许重置。

`simulation.sh start` 会先校验车队与任务检查点，再启用 Kernel 重启后处于禁用状态的 VDA5050 驱动，并将这些恢复的车辆设为 `TO_BE_UTILIZED`。已启用车辆的集成级别和暂停状态不变。收到 MQTT 状态后才为可用车辆派单：

```bash
# 先启动 Broker，再在另一终端启动原版 Kernel
docker compose -f deploy-server/compose.yaml up -d mosquitto
# cd build/install/opentcs/opentcs-kernel && sh startKernel.sh
SortingDemo/mqtt-vehicles.sh start
SortingDemo/simulation.sh start
SortingDemo/simulation.sh status
SortingDemo/simulation.sh stats
```

只暂停派单：

```bash
SortingDemo/simulation.sh stop
```

这会停止新增作业，已提交任务仍由 Kernel 和模拟器执行。再次 `start` 从派单检查点核对现有任务，不重复创建订单。

`status` 还会显示每辆车的 `mqttBroker=true` 和 `vehicleConnection=ONLINE`，可分别判断 Kernel 到 Broker 及车辆端连接状态。

计划关闭或重启 Kernel / 电脑时，必须先回库。当前 Kernel 重启不会恢复未完成的运输任务，直接关闭会使仿真检查点引用不存在的任务。先执行：

```bash
SortingDemo/simulation.sh drain
# 等待 simulation.log 出现 DRAINED，表示全部车辆回到各自 init_N
SortingDemo/simulation.sh stop
SortingDemo/mqtt-vehicles.sh stop
```

确认日志出现 `DRAINED`，停止派单器和车队后，才关闭 Kernel。下次先启动 Broker、Kernel、车队，最后启动派单器。回库完成后再次 `simulation.sh start` 会清除回库请求，恢复正常分拣。

模拟器在 MQTT 断线时冻结运动，重连后重新订阅并上报当前状态。正常 `stop` 保存每辆车的待执行路段、动作进度、位置、电量及载货状态；在 Kernel 未重置的情况下可用 `start` 恢复。不要删除模拟器检查点后继续旧 Kernel 的订单，否则车辆位置会不一致。强制杀进程/断电可能丢失最后一次检查点后的进度，这不是生产级故障恢复协议。

Kernel 重启后必须确认原运输任务是否仍存在。若任务历史已丢失，旧派单器会拒绝续跑，应在无未完成任务时重新 `setup`；不能只保留车辆检查点而假定调度任务可恢复。

## 车辆标识和 MQTT topic

地图转换脚本自动为所有车辆写入配置，不需要手动填写 25 次：

| 配置 | 值 |
| --- | --- |
| 协议版本 | `2.0`，消息 header 为 `2.0.0` |
| interfaceName | `uagv` |
| manufacturer | `SortingDemo` |
| serialNumber | `Sorting-AGV-01` 至 `Sorting-AGV-25` |
| mapId | `Sorting` |
| MQTT Client ID | `sorting-sim-Sorting-AGV-01` 至 `sorting-sim-Sorting-AGV-25` |
| 初始点 | `init_1` 至 `init_25` |
| 最大 base 步数 / horizon 步数 | `1` / `0` |

例如第一辆车：

```text
uagv/v2/SortingDemo/Sorting-AGV-01/order
uagv/v2/SortingDemo/Sorting-AGV-01/instantActions
uagv/v2/SortingDemo/Sorting-AGV-01/state
uagv/v2/SortingDemo/Sorting-AGV-01/connection
```

模拟器只执行 `released=true` 的节点和边；路段是否获准仍由 openTCS Scheduler 决定。重复 `orderId/orderUpdateId` 幂等处理，订单更新只能在旧 base 末端追加。断线遗嘱为 `CONNECTIONBROKEN`，正常退出发布 `OFFLINE`，连接成功发布 `ONLINE`；状态每秒更新，收到命令也会立即反馈。

## 分拣行为

地图由 `Sorting.building.yaml` 生成，包含 253 个导航点、425 条路径、34 个下料位置、3 个上料位置、25 个充电位置和 25 辆车。`droppff_1` 至 `droppff_12` 保留原地图拼写。

每台车独立运行：选择上料位置 → 按顺序经过四个等待点 → `Load cargo` → 随机下料点 `Unload cargo` → 继续上料或低电量回库 `CHARGE`。上料选择依据预计行驶时间和排队代价；34 个下料点等概率随机，随机种子和选定目标保存在检查点。

| 上料位 | 队尾到上料位 |
| --- | --- |
| pickup_1 | waite_4 → waite_3 → waite_2 → waite_1 → pickup_1 |
| pickup_2 | waite_8 → waite_7 → waite_6 → waite_5 → pickup_2 |
| pickup_3 | waite_12 → waite_11 → waite_10 → waite_9 → pickup_3 |

保留原有 Scheduler 资源互斥和等待时的 `REGULAR` 重规划策略；不会为了展示运动直接跳到目标点，也不绕过路段分配。默认平移速度上限 1.8 m/s，平移加减速度 1 m/s²；节点转向采用静止起止的角速度曲线耗时，旋转限制从 `simulation.properties` 读取。装卸货默认 1 秒。

支持的节点动作：`Load cargo`、`Unload cargo`、`CHARGE`、`NOP`。支持的 instant action：`stateRequest`、`startPause`、`stopPause`、`cancelOrder`。这是分拣联调模拟器，未实现 factsheet、复杂边动作、障碍感知、任意车辆动作或完整物理动力学，不能当作 VDA5050 一致性认证工具。

电量由车辆模拟器按已走完路段扣除，每米 0.01 个百分点；等待和装卸不耗电。派单器从 Kernel 接收 MQTT 电量反馈，完成已装载货物的投递后，电量不高于 20% 时前往自己的充电位。默认充电每秒 1 个百分点，充满后恢复工作。配置均位于 `simulation.properties`，修改后重启模拟器；电量策略变更建议在回库后重新初始化。

只有 Kernel 确认 `FINISHED` 的下料任务才计为一次分拣，小时统计包括等待和充电时间。`runtime/hourly.csv` 时间为 UTC；未满一小时的估算不等于实测产能。此前 Loopback 结果不代表当前 MQTT 仿真性能。

## 文件与验证

- `SortingMqttVehicle.java`：基于自带示例的车辆状态机、VDA5050 消息、运动/动作和电量。
- `SortingMqttFleet.java`：25 个 MQTT 客户端、Broker 配置、断线重连及车辆检查点。
- `mqtt-vehicles.sh`：模拟车队启动、停止和检查。
- `SortingSimulation.java` / `simulation.sh`：Kernel 任务生成、动态上料选择、统计及恢复。
- `SortingMqttTest.java`：未释放 horizon、重复订单、base 拼接、装卸、暂停、充电、取消及运动中检查点恢复。
- `runtime/mqtt-vehicles.log`：`ONLINE`、`ORDER`、协议或连接错误。
- `runtime/mqtt-vehicles/`：各车检查点。
- `runtime/simulation.log`：`SUBMIT`、`FINISHED`、`RECHARGED`、`REROUTE`、`WAITING`。

验证命令：

```bash
SortingDemo/mqtt-vehicles.sh check
SortingDemo/simulation.sh check
# Kernel 和车队运行时，核对 25 个 MQTT 实时状态与 Kernel 中的车辆
SortingDemo/mqtt-vehicles.sh verify
```

`deploy-server/deploy-map/Sorting.xml` 已同步为当前包含 `init_25` 的分拣地图，与 `SortingDemo/Sorting.xml` 一致。之前部署目录中不含 `init_25` 的旧图已备份到 `runtime/before-25-vehicles-1789464240/deployment-Sorting.xml`。

## 构建目录与备份

Kernel 使用原版 `startKernel.sh` 和发行目录中的配置、地图、日志。MQTT 模拟器也读取发行目录 `config/opentcs-kernel.properties`。重新执行 `installDist` 前请备份该目录的 `config/` 和 `data/`。

若 Kernel 地图变为 `unnamed` 或旧任务已丢失，车辆检查点不能直接恢复那些任务。应先备份运行数据，停止派单器和车队，再执行 `setup` 开始新一轮；日常启动不执行 `setup`。

2026-09-15 之前恢复时的 806 次分拣记录已备份到 `SortingDemo/runtime/recovery-1789470327/`。Broker 的独立账号为 `opentcs-sorting`，密码保存在本机 Kernel 配置中。
