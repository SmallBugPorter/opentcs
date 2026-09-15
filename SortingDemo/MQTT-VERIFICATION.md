# MQTT 分拣联调记录（2026-09-15）

环境：本机 openTCS 7.6.0-SNAPSHOT / Java 21、已运行的 Mosquitto、Sorting 地图，24 辆独立 MQTT 模拟车。

## 已验证

- 24 个独立 Client ID 连接 Broker，24 个 `uagv/v2/SortingDemo/+/state` topic 均收到实时状态。
- Kernel 的 24 辆车全部绑定 VDA5050 2.0 驱动；`mqttBroker=true`、`vehicleConnection=ONLINE`，位置已知。
- 全部 24 辆车至少完成一次完整的上料、送货和卸货；Kernel 确认累计 47 次 DELIVERY 完成。
- 随后请求回库，24 辆车全部到达各自 `init_N`；118 条运输任务全部 FINISHED，未撤销订单或强制跳点。
- 对一辆运动中的车经 Kernel 发送暂停，MQTT 返回 paused，持续 2.2 秒位置不变；恢复后继续执行。
- 有任务运行时正常停止派单器和模拟器，再启动后从检查点继续完成已有任务。
- 回库后再次重启最终版本模拟器，保留车辆状态；派单器保持停止，模拟车队在线待命。
- 24 份 state 消息通过仓库自带 VDA5050 2.0 state JSON Schema 校验。
- 两份地图各有 24 组唯一 VDA5050 标识；与修改前相比，非车辆的点、道路、位置和布局数据完全一致。

## 自动化检查

```bash
SortingDemo/mqtt-vehicles.sh check
SortingDemo/simulation.sh check
SortingDemo/mqtt-vehicles.sh verify
```

状态机测试覆盖：未释放 horizon 不执行、QoS 重复订单不重做、运行中及执行完成后的 base 追加、装卸货反馈、暂停恢复、运动中检查点恢复、充电达到满电后恢复空闲、取消和起点不匹配拒绝。

原分拣检查覆盖 24×34 路线、等待点顺序、24000 次随机目标选择、独立派单状态、检查点/统计恢复、先送货后充电、动态上料选择。

充电经过状态机测试；本次实机软件联调电量仍为 98–99%，没有自然触发 20% 返充。此次为短时功能验证，包含暂停、重启和回库，不能作为一小时吞吐量或真实车辆动力学结果。

迁移前的地图、原 Loopback 派单检查点及小时统计备份在 `SortingDemo/runtime/` 下，以 `1789463360241` 为时间戳。实时证据位于 `runtime/mqtt-vehicles.log`、`runtime/simulation.log` 及 Kernel 日志。

## 扩容到 25 台（同日后续验证）

- 车队数量由 `simulation.properties` 中的 `fleet.size=25` 统一控制，地图、派单循环、状态数组、CSV 统计和 MQTT 检查均读取同一数量。
- 新增 `Sorting-AGV-25`，序列号同名、初始点 `init_25`；25/25 MQTT 客户端在线，Kernel 均收到状态。
- 路线检查覆盖 25×34 个目标和 25000 次随机目标选择；第 25 台车计数与充电计数分离，CSV 包含 `vehicle_25`。
- 新车实际经 MQTT 完成 `waite_12 → waite_11 → waite_10 → waite_9 → pickup_3 (Load cargo) → dropoff_32 (Unload cargo) → init_25`，验证订单 FINISHED。
- 验证后 25 辆车在线待命，派单器停止。此次单车验证订单不计入自动分拣统计。
- 重新初始化前的 24 车地图、47 次分拣统计、检查点和部署旧图备份在 `runtime/before-25-vehicles-1789464240/`。25 车自动分拣统计从新一轮开始。
- 当前主地图非车辆部分未变；部署旧图没有 `init_25`，备份后同步为当前主地图。

单车联调过程见 `runtime/expansion-25-verification.log`。
