# 部署服务

使用 Docker Compose 部署 Mosquitto 2.0.22，启用密码认证、数据持久化和自动重启。

## 地图下载 HTTP 服务

Nginx 将部署目录内的 `deploy-map/` 只读挂载为网站根目录，通过 HTTP 提供地图文件，无需认证。
当前地图路径为 `/home/lebi/opentcs_ws/opentcs/deploy-server/deploy-map/`。

- 地图目录：`http://<服务器IP>:8080/`
- 当前地图：`http://<服务器IP>:8080/Sorting.xml`
- 添加或更新 `deploy-map/` 中的文件后即可下载，无需重启容器。
- 支持 GET、HEAD 和范围下载；客户端复用缓存前需向服务器验证文件是否更新。

在本目录启动或更新地图服务：

```bash
docker compose up -d map-server
docker compose logs -f map-server
```

## 首次部署

在本目录执行以下命令创建账号，按提示输入约定的 admin 密码：

```bash
docker compose run --rm --no-deps --user "$(id -u):$(id -g)" \
  -v "$PWD/mosquitto/config:/setup" \
  mosquitto mosquitto_passwd -c /setup/passwords admin
chmod 600 mosquitto/config/passwords
docker compose up -d
```

密码文件保存哈希并已被 Git 忽略；当前工作目录已初始化时，只需执行 `docker compose up -d`。
`-c` 会重建密码文件。容器启动后会调整配置文件的归属，后续修改密码使用：

```bash
docker compose exec mosquitto mosquitto_passwd /mosquitto/config/passwords admin
docker compose restart mosquitto
```

## 连接

- 地址：`mqtt://<服务器IP>:1883`（本机使用 `localhost`）
- 用户名：`admin`
- 密码：初始化时设置的密码
- 禁止匿名连接；当前为 MQTT TCP，未配置 TLS。

## 修改部署路径

相对挂载路径以 `compose.yaml` 所在目录为基准：`./deploy-map` 是其所在目录内的地图目录，
`./nginx` 和 `./mosquitto/config` 位于其所在目录内。迁移时需要一起复制地图、Nginx 配置和
Mosquitto 配置（包含原有 `passwords` 文件），或把 `source` 改为实际的绝对路径。
配置已禁用自动创建宿主机挂载路径，路径不存在时启动会报错，避免静默生成空目录。

`docker restart`、`docker compose restart` 或重启 Docker 都会继续使用已有容器的挂载路径，
不会应用 Compose 文件中的路径变更。迁移完成后，明确指定新配置文件并重建容器：

```bash
docker compose -p deploy-server -f /新路径/deploy-server/compose.yaml config
docker compose -p deploy-server -f /新路径/deploy-server/compose.yaml up -d --force-recreate
docker inspect deploy-server-map-server-1 deploy-server-mosquitto-1 --format '{{.Name}} {{json .Mounts}}'
```

保持项目名 `deploy-server` 可以复用原有 `deploy-server_mosquitto-data` 数据卷。
确认实际挂载路径和服务正常后，再处理旧目录。不要使用 `down -v`，以免删除 MQTT 持久化数据。

## 常用命令

```bash
docker compose ps
docker compose logs -f mosquitto
docker compose restart mosquitto
docker compose down
```

数据保存在 `mosquitto-data` 命名卷中，普通停止或重建容器会保留数据。
修改密码后执行 `docker compose restart mosquitto` 生效。

配置参考：[Mosquitto 官方镜像](https://hub.docker.com/_/eclipse-mosquitto)、[配置文档](https://mosquitto.org/man/mosquitto-conf-5.html)。
