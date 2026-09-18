# livingRoom 磁导车：VDA 5050 2.1.0

本目录对应 `mag_agv_controller` 实车。openTCS 的 2.x 驱动共用实现，保留 2.0，并新增 2.1.0 消息校验、地图库存解析、地图动作和行驶就绪检查。不是仅替换协议版本字符串，也不表示已经完成实车验收。

## 已配置的车辆与地图

[living_room.xml](living_room.xml) 中 `agv-001` 的属性：

| 属性 | 值 |
| --- | --- |
| `vda5050:version` | `2.1`（驱动选择值；消息头为 `2.1.0`） |
| `vda5050:interfaceName` | `uagv` |
| `vda5050:manufacturer` | `dx` |
| `vda5050:serialNumber` | `agv-001` |
| `vda5050:mapId` | `living_room` |
| `vda5050:mapVersion` | `1` |
| `vda5050:mapDownloadLink` | `http://192.168.4.4:8080/living_room-v1.xml` |
| `vda5050:autoPrepareMap` | `true` |

MQTT Topic 前缀为 `uagv/v2/dx/agv-001/`，包括 `connection`、`state`、`visualization`、`factsheet`、`order`、`instantActions`。**2.1.0 仍使用 `v2` Topic 层级。** MQTT 的 Broker 地址和认证在 Kernel 配置文件中，不放在地图属性内。

所有 path 已设置正反向 `rotationAllowed=false`：车辆在进入 path 前完成所需旋转。2.1 订单显式使用 `orientationType=TANGENTIAL`；未指定路径朝向属性时，前进为 `0`、倒车为 `pi`。保留地图原有坐标、RFID 和速度上限，车端仍应用自身限速。

地图发布文件是 [docker/deploy-map/living_room-v1.xml](../docker/deploy-map/living_room-v1.xml)，与本目录 XML 内容一致。现有 `docker/compose.yaml` 的 `map-server` 提供 HTTP 下载。车辆属性中的 HTTP 地址必须能从开发板访问；如果地图服务器地址变化，应修改此属性并重新发布地图。

## 切换到已构建的新版

本次构建到了 **`build/install/opentcs-vda21/`**，原 `build/install/opentcs/` 和正在运行的程序保持原样。新版 Kernel 已带入当前 `local.yaml` 的 MQTT 地址、端口、用户名和密码，启用 `1.1, 2.0, 2.1`，并预置更新后的 livingRoom 地图。

1. 停止旧 Kernel、Kernel Control Center、Operations Desk 和 Model Editor。
2. 确认 Broker 和地图服务运行：

   ```bash
   cd /home/lebi/opentcs_ws/opentcs
   docker compose -f docker/compose.yaml up -d mosquitto map-server
   ```

3. 在独立终端启动新版 Kernel：

   ```bash
   cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs-vda21/opentcs-kernel
   sh startKernel.sh


    cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs-vda21/opentcs-operationsdesk
    sh startOperationsDesk.sh

cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs-vda21/opentcs-kernelcontrolcenter
sh startKernelControlCenter.sh
   ```

4. 分别进入新版目录内的 `opentcs-kernelcontrolcenter`、`opentcs-operationsdesk`、`opentcs-modeleditor`，使用各自的 `startKernelControlCenter.sh`、`startOperationsDesk.sh`、`startModelEditor.sh` 启动。四个程序应使用同一套新版库。
5. 在 Kernel Control Center 的 Vehicle Driver 中选择 `agv-001`，将适配器设为 **VDA5050** 并启用。原来只有 Loopback 的问题由版本启用配置和车辆属性共同修复。
6. 开发板继续运行 `mag-agv-controller --config config/local.yaml`。观察车辆连接、地图准备状态和 Position。确认车辆 Integration Level 为可参与调度的 `TO_BE_UTILIZED` 后再创建行驶任务。

新版已预置地图。如通过 Model Editor 重新上传，请打开本目录的 `living_room.xml`，不要再次上传仍缺少 VDA 属性的旧文件。

## 地图准备与定位

收到车辆有效且停止的反馈后，驱动按状态推进：

```text
缺少目标版本 → downloadMap → FINISHED 且 state.maps 出现该版本
            → enableMap   → FINISHED 且该版本 ENABLED
            → 等待 positionInitialized、mapId 和 lastNodeId
            → READY → 允许行驶订单
```

车辆已经启用目标版本时跳过下载和启用。地图动作失败或等待超过 60 秒会锁定为失败，避免重复自动操作；修复原因后，通过 Operations Desk 的车辆适配器消息功能发送类型 `prepareMap`（无参数）重试。需持续收到 state 才能检查超时；断线时行驶就绪状态清除。

车辆属性 `vda5050:mapPreparation` 显示准备进度：`WAITING_FOR_STATE`、`WAITING_FOR_IDLE`、`DOWNLOADING`、`ENABLING`、`WAITING_FOR_LOCALIZATION`、`READY` 或 `FAILED: ...`。`vda5050:maps` 保存地图库存，`vda5050:factsheet` 保存收到的有效事实表。Control Center 动作列表也提供 `downloadMap`、`enableMap`、`deleteMap` 和 `factsheetRequest`。

`WAITING_FOR_LOCALIZATION` 不是 MQTT 故障。车端初始化仍要求：新鲜底盘反馈、非空 RFID、前后磁导恰有一侧检测到磁线，以及 RFID 点只有一条相连 path。当前地图可用于初始化的点为 `Point-0001`、`Point-0006`、`Point-0012`。也可根据真实车辆位置发送 `initPosition`；本配置不会自动伪造位置。完成定位前，Operations Desk 可能没有可显示的车辆位置。

第一版本联调请使用行驶任务。地图保留的 `Load`/`Unload` 操作不代表车端已实现装卸动作。

## 后续重新构建

在 `opentcs/` 下执行：

```bash
./gradlew -I livingRoom/build-vda21.init.gradle installDist
python3 livingRoom/prepare-runtime.py
```

`prepare-runtime.py` 需要 PyYAML，可用已安装车端依赖的 Python 运行。它只准备独立新版目录，复制原安装的应用配置，将当前 `mag_agv_controller/config/local.yaml` 中的 MQTT 配置写入新版 Kernel，并预置本目录地图。可通过 `--controller-config /path/to/local.yaml` 指定另一份车端配置。认证信息写入权限为 `0600` 的 `opentcs-kernel.properties`，不输出到终端。

重新构建和准备前应停止已在使用的新版程序；准备脚本会拒绝覆盖检测到仍在运行的新版配置。再次准备会重置新版的启动地图和 MQTT 配置。

发布后不要覆盖已被车辆缓存的同一地图版本。地图内容变化时，请增加 `mapVersion`、使用新的下载文件名，并同步更新 Kernel 地图与发布文件。可选属性 `vda5050:mapHash` 用于下载文件的 SHA-256 校验；哈希应来自最终发布文件，不能把文件自身的哈希再写回同一文件。

## 验证范围

本次 Java 适配器 499 项测试通过，包括 2.1 schema、地图状态转换、失败重试、版本启用隔离、前后退订单方向，以及真实适配器生成的 2.1 MQTT 消息。Python 车端 53 项测试通过，其中包含本地 MQTT、HTTP 和模拟串口集成测试。Checkstyle 检查通过，无 error；项目仍有既有的 warning/info。

livingRoom 已通过车端地图解析：14 个 point、16 条 path；HTTP 服务返回文件与模型文件一致。以上不等同于新版 openTCS 与真实底盘的行驶验收，仍需按上述启动步骤完成现场联调。
