# openTCS 分拣仿真启动与停止

项目目录：`/home/lebi/opentcs_ws/opentcs`。Kernel 使用发行目录自身的 `config/`、`data/` 和 `lib/`，通过原版 `sh startKernel.sh` 启动。

## 编译（仅首次安装或更新代码时需要）

编译不是每次启动的必要步骤。重新构建前，先按下方流程停止系统，并备份发行目录中的 `config/`、`data/`，避免 MQTT 认证配置和地图被覆盖。

```bash
cd /home/lebi/opentcs_ws/opentcs
bash ./gradlew installDist
```

## 日常启动顺序

顺序：**Broker → Kernel → MQTT 车队 → 派单器**。以下命令假设已经完成地图和仿真初始化；日常启动不要执行 `simulation.sh setup`，它会重置地图、车辆检查点和本轮统计。

### 1. 启动 MQTT Broker

```bash
cd /home/lebi/opentcs_ws/opentcs
docker compose -f docker/compose.yaml up -d mosquitto
docker compose -f docker/compose.yaml ps mosquitto
```

确认 Broker 显示为运行状态。如果需要地图 HTTP 服务，再执行：

```bash
docker compose -f docker/compose.yaml up -d map-server
```

### 2. 启动 Kernel（独立终端，保持打开）

```bash
cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs/opentcs-kernel
sh startKernel.sh
```

等待 Kernel 启动完成后继续。只启动一个 Kernel；已运行时不要重复执行。MQTT 配置位于该目录的 `config/opentcs-kernel.properties`。

### 3. 启动 MQTT 车队，再启动派单器（另开终端）

```bash
cd /home/lebi/opentcs_ws/opentcs
SortingDemo/mqtt-vehicles.sh start
```

车队启动成功后再执行：

```bash
SortingDemo/simulation.sh start
SortingDemo/mqtt-vehicles.sh verify
SortingDemo/simulation.sh status
```

`verify` 应确认收到 25 台车的 MQTT 状态；`status` 应显示 `mqttBroker=true`、`vehicleConnection=ONLINE`。如果任一步启动失败，先检查日志，不要继续启动下一步或直接执行 `setup`。

日志：

- 车队：`SortingDemo/runtime/mqtt-vehicles.log`
- 派单器：`SortingDemo/runtime/simulation.log`

### 4. 打开界面（按需，每个程序使用独立终端）

Operations Desk：

```bash
cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs/opentcs-operationsdesk
sh startOperationsDesk.sh
```

Kernel Control Center：

```bash
cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs/opentcs-kernelcontrolcenter
sh startKernelControlCenter.sh
```

Model Editor（编辑地图时使用）：

```bash
cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs/opentcs-modeleditor
sh startModelEditor.sh
```

## 完整停止顺序（重启 Kernel 或关机前也必须执行）

顺序：**请求回库 → 等待全部回库 → 停派单器 → 停车队 → 停 Kernel → 按需停 Broker**。

### 1. 请求回库，保持 Kernel、车队、派单器运行

```bash
cd /home/lebi/opentcs_ws/opentcs
SortingDemo/simulation.sh drain
tail -n 0 -F SortingDemo/runtime/simulation.log
```

等待本次回库出现：

```text
DRAINED all vehicles parked; safe to restart Kernel
```

如果日志中未看到该行，另开终端执行 `SortingDemo/simulation.sh status` 检查：应为 `active orders=0`，25 台车均已到各自的 `init_N`，没有未完成的运输任务。不要把历史日志里的 `DRAINED` 当成本次回库完成。

确认完成后，按 `Ctrl+C` 退出上面的日志查看命令。这里的 `Ctrl+C` 只退出 `tail`，不要提前关闭 Kernel 终端。

### 2. 停止派单器和 MQTT 车队

```bash
cd /home/lebi/opentcs_ws/opentcs
SortingDemo/simulation.sh stop
SortingDemo/mqtt-vehicles.sh stop
```

### 3. 停止 Kernel

回到运行 `sh startKernel.sh` 的终端，按 `Ctrl+C`，等待进程退出。按需关闭 Operations Desk、Kernel Control Center 和 Model Editor。

### 4. 停止 Broker（可选）

如果本机没有其他客户端需要该 Broker：

```bash
cd /home/lebi/opentcs_ws/opentcs
docker compose -f docker/compose.yaml stop mosquitto
```

如果地图 HTTP 服务也不再需要：

```bash
docker compose -f docker/compose.yaml stop map-server
```

## 只暂停派单，不关闭 Kernel

```bash
cd /home/lebi/opentcs_ws/opentcs
SortingDemo/simulation.sh stop
```

已提交任务仍会执行，需保持 Kernel、Broker 和车队运行。恢复派单：

```bash
SortingDemo/simulation.sh start
```

## 两类常见启动失败

- `连接被拒绝`：先检查 Broker 是否运行，以及 Kernel 配置中的 Broker 地址和端口是否正确。
- `Submitted order missing from Kernel`：Kernel 中旧任务已丢失，但仿真检查点仍引用它们。当前系统不能直接续跑这类任务；先备份运行数据，再处理重新初始化。`setup` 会开始新一轮统计，不是普通启动步骤。

当前 Kernel 重启不会恢复未完成的运输任务，所以关闭前必须先完成回库。正常回库后按上述顺序重新启动，无需 `setup`。
