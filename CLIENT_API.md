# 温室数据服务 · 客户端接口（v1）

与 [`PROTOCOL.md`](PROTOCOL.md) 配套使用：

| 文档 | 覆盖范围 |
|---|---|
| `PROTOCOL.md` | 设备 ↔ Broker ↔ 你（实时消息） |
| **本文** | 你 ↔ 数据服务（HTTP 只读查询） |

设备产生的数据走两条通道：你订阅 MQTT 拿到的是**实时流**，数据服务提供的是**当前快照和历史**。
两边是同一份数据，靠 `(deviceId, b, seq)` 对齐，见第 7 节。

---

## 1. 约定

| 项 | 约定 |
|---|---|
| 基址 | `http://<vps>:8080/api/v1`（端口以部署为准） |
| 方法 | 全部 `GET`，服务是**只读**的 |
| 编码 | UTF-8 JSON |
| 认证 | 请求头 `Authorization: Bearer <token>` |
| 时间 | 一律 UTC epoch **毫秒**；入参也接受 ISO-8601 |
| 分页 | `limit` + `cursor`，**正序**（旧 → 新） |
| 默认窗口 | `from` 缺省 = `to` 往前 24 小时；`to` 缺省 = 现在 |
| 可选字段 | 不存在时**整个键缺失**，不会给 `null`。唯一例外是 `nextCursor`（显式 `null`） |
| 传输 | 明文 HTTP（与 Broker 一致），**不要放到公网裸奔** |

下文所有路径都拼在上面的基址后面，例如 `/devices` 实际是 `http://<vps>:8080/api/v1/devices`。

`/health` 不需要认证，其余端点缺少或错误 token 一律 `401`。

### 1.1 时间参数

`from` / `to` 接受两种写法：

```
?from=1763000000000&to=1763100000000
?from=2026-11-13T00:00:00Z&to=2026-11-14T00:00:00Z
?from=2026-11-13T08:00:00%2B08:00
```

时间戳是**服务器收到报文的时刻**。设备没有绝对时间，`up` 只是开机秒数，不能换算成时钟。
客户端展示时按本地时区格式化。

### 1.2 错误

```json
{"error": "bad_request", "message": "limit 必须在 1..5000 之间: 0"}
```

| HTTP | `error` | 含义 |
|---|---|---|
| 400 | `bad_request` | 参数非法（时间、limit、cursor） |
| 401 | `unauthorized` | token 缺失或无效 |
| 404 | `device_not_found` | 这个 `deviceId` 从未上报过数据 |
| 404 | `not_found` | 路径不存在 |
| 405 | `method_not_allowed` | 用了 GET 以外的方法 |
| 500 | `internal_error` | 服务内部错误（可重试，仍失败请反馈） |
| 503 | — | `/health` 专用：服务活着但数据库不可用 |

`message` 是给人看的，**不要**用它做程序判断，判断请用 `error`。

---

## 2. 端点总览

| 用途 | 路径 |
|---|---|
| 健康检查（免认证） | `GET /health` |
| 设备列表 + 当前摘要 | `GET /devices` |
| **最近数据** | `GET /devices/{deviceId}/latest` |
| **历史遥测** | `GET /devices/{deviceId}/telemetry` |
| 告警事件流 | `GET /devices/{deviceId}/alarms` |
| 在线状态变化 | `GET /devices/{deviceId}/status` |

`{deviceId}` 形如 `GH-0001`，与 MQTT 主题第二段完全一致。

> 首版没有聚合/统计接口（平均值、按小时汇总）。需要的话在客户端侧对原始数据做，或提需求排进 v2。

---

## 3. 健康检查

```
GET /health
```

不需要 token，返回服务自身状态：

```json
{
  "status": "ok",
  "db": true,
  "mqttConnected": true,
  "uptimeMs": 193,
  "stats": {
    "received": 4, "inserted": 2, "duplicates": 0, "badPayloads": 0,
    "retained": 0, "alarmEvents": 1, "statusEvents": 1
  }
}
```

| 字段 | 说明 |
|---|---|
| `status` | `ok` / `degraded`（数据库不可用） |
| `db` | 数据库是否可读 |
| `mqttConnected` | 数据服务自身到 Broker 的连接状态 |
| `stats` | 累计计数：收到 / 入库 / 重复丢弃 / 坏报文 / retained / 告警事件 / 状态事件 |

`stats.badPayloads` 持续增长说明设备在发不合协议的报文，找嵌入端排查。
`stats.duplicates` 增长是正常的（QoS1 重投）。

---

## 4. 设备列表

```
GET /devices
```

```json
{
  "count": 1,
  "devices": [
    {
      "deviceId": "GH-0001",
      "online": true,
      "lastStatusTs": 1794630055623,
      "lastSeenTs": 1794630055640,
      "lastTelemetryTs": 1794630055639,
      "recvTs": 1794630055639,
      "b": "7f3a91c4",
      "seq": 1024,
      "up": 86400,
      "values": {"tempC": 23.5, "ahumPct": 56.8, "shumPct": 31.2, "lux": 812,
                 "water": true, "fan": false, "light": true, "heat": false}
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `online` | 最近一次状态报文里的在线值，**可能缺失**（从未收到 status） |
| `lastStatusTs` | 这条在线状态是什么时候收到的。设备不会定时刷新它，别拿它当心跳 |
| `lastSeenTs` | 最后一次收到该设备任何报文的时刻，在线判定主要看它（见 7.2） |
| `lastTelemetryTs` | 最后一次遥测的时刻 |
| `recvTs` / `b` / `seq` / `up` / `values` | **仅当有遥测时出现**（见 5 节末尾） |

`count` 是设备数量。设备从未上报过遥测时，后五个字段整组缺失——客户端要能处理这种情况。

---

## 5. 最近数据（主界面用这个）

```
GET /devices/{deviceId}/latest
```

一次拿到：当前在线状态 + 最新一条遥测 + 当前告警。

```json
{
  "deviceId": "GH-0001",
  "online": true,
  "lastStatusTs": 1794630055623,
  "recvTs": 1794630055639,
  "b": "7f3a91c4",
  "seq": 1024,
  "up": 86400,
  "proto": 2,
  "values": {"tempC": 23.5, "ahumPct": 56.8, "shumPct": 31.2, "lux": 812,
             "water": true, "fan": false, "light": true, "heat": false},
  "extra": {"co2": 420},
  "alarms": [
    {"code": "water_lack", "active": true, "firstTs": 1794630055640, "lastTs": 1794630055640,
     "b": "7f3a91c4"}
  ]
}
```

| 字段 | 说明 |
|---|---|
| `online` / `lastStatusTs` | 在线判定见 7.2 |
| `recvTs` | 这条遥测的接收时刻，用来显示"数据多久没更新" |
| `b` / `seq` | 会话号与序号，与实时消息去重用 |
| `proto` | 报文里的 `v` |
| `values` | 物理量，见第 6 节 |
| `extra` | 报文里本服务尚未单独建列的键，**原样转发**，可能缺失 |
| `alarms` | **当前处于激活状态**的告警，恒存在（无告警时为 `[]`） |
| `alarms[].firstTs` / `lastTs` | 这条告警首次被收到的时刻 / 最近一次收到的时刻 |

没有任何遥测时（设备上线了但还没发过数据，或只发过告警），
`recvTs`、`b`、`seq`、`proto`、`values`、`extra` 全部缺失，客户端要能处理，
不要假设这些键一定存在。

**建议轮询间隔 30–60 秒**（数据本身约 1 条/分钟）。

---

## 6. 字段与单位

`values` 里的键是换算过、带单位语义的；原始报文的键是设备侧的。

| JSON 键 | 报文键 | 单位 | 换算 |
|---|---|---|---|
| `tempC` | `t` | ℃ | 设备发 0.01 ℃，这里已 ÷100（`2350` → `23.5`） |
| `ahumPct` | `ah` | %RH | 已 ÷100 |
| `shumPct` | `sh` | %RH | 已 ÷100 |
| `lux` | `lux` | lx | 原值，不换算 |
| `water` | `water` | 布尔 | `1` → `true` |
| `fan` | `fan` | 布尔 | `1` → `true` |
| `light` | `light` | 布尔 | `1` → `true` |
| `heat` | `heat` | 布尔 | `1` → `true` |

三条容易踩的：

1. **`water: true` 表示水充足**，`false` 才是缺水（对应 `water_lack` 告警）。名字和语义是反的，别搞混。
2. **缺测字段不会出现**，也不会是 `null`。比如设备没发 `ah`，`values` 里就没有 `ahumPct` 这个键。
3. **`extra` 里的值不做任何换算**，是设备原样发的整数，含义以协议为准。

客户端要**忽略不认识的所有键**——将来加字段不会通知你。

---

## 7. 客户端必须做的四件事

### 7.1 按 `(deviceId, b, seq)` 去重

历史接口的每一条遥测都带 `b` 和 `seq`，就是为了这个：

```
GET /devices/GH-0001/telemetry?limit=1
```

```json
{
  "deviceId": "GH-0001",
  "count": 1,
  "from": 1794543655740,
  "to": 1794630055740,
  "items": [
    {
      "recvTs": 1794630055636,
      "b": "7f3a91c4",
      "seq": 1023,
      "up": 86340,
      "proto": 2,
      "values": {"tempC": 23.4, "ahumPct": 56.9, "shumPct": 31.1, "lux": 806,
                 "water": true, "fan": false, "light": true, "heat": false}
    }
  ],
  "nextCursor": 1
}
```

翻下一页：

```
GET /devices/GH-0001/telemetry?limit=1&cursor=1
```

```json
{
  "deviceId": "GH-0001",
  "count": 1,
  "from": 1794543655750,
  "to": 1794630055750,
  "items": [
    {"recvTs": 1794630055639, "b": "7f3a91c4", "seq": 1024, "up": 86400, "proto": 2,
     "values": {"tempC": 23.5, "ahumPct": 56.8, "shumPct": 31.2, "lux": 812,
                "water": true, "fan": false, "light": true, "heat": false},
     "extra": {"co2": 420}}
  ],
  "nextCursor": null
}
```

| 字段 | 说明 |
|---|---|
| `count` | **本次返回的条数**，不是总数 |
| `from` / `to` | 实际生效的窗口，回显默认值，排查时看这里 |
| `nextCursor` | 下一页的游标；`null` 表示没有更多 |
| `items[].recvTs` | 接收时刻 |
| `items[].b` / `seq` | 去重键 |
| `items[].up` | 设备开机秒数（判断重启，不是时间） |
| `items[].proto` | 报文版本，可能缺失 |
| `items[].values` | 同第 6 节 |
| `items[].extra` | 同第 5 节，可能缺失 |

去重逻辑（PROTOCOL 附录 B 要求的）：

```
history = 拉取历史
live    = 订阅期间缓存的实时消息
合并时若 (deviceId, b, seq) 已经出现过 → 只保留一条
```

`b` 变了说明设备重新上电，`seq` 会从 0 重新开始，**这不是重复**，是新一轮会话。

### 7.2 在线判定不能只看 `online`

| 观察 | 结论 |
|---|---|
| `online == false` | 离线。来自 LWT 或设备主动发的下线报文，可信 |
| `online == true` 且 `lastSeenTs` 是近期的 | 在线 |
| `online == true` 但 `lastSeenTs` 也很旧 | **存疑**：可能是设备断电后 Broker 保留的旧状态 |
| `online` 缺失 | 从未收到过状态报文 |

两个时间戳的分工，很容易看反：

- **`lastStatusTs`**：这条在线状态是什么时候**收到的**。设备只在上下线时发一次 status，
  在线期间**不会定时刷新**，所以它可能一直是很久以前的时间——这**不代表**设备离线。
- **`lastSeenTs`**（或 `latest` 里的 `recvTs`）：最后一次收到该设备**任何**报文
  （遥测 / 告警 / 状态）的时刻。正常运行时每分钟都会更新，**这才是判断"设备还活着"的依据**。

所以合理的写法是：`online == false` 直接判离线；`online == true` 时再看 `lastSeenTs`，
超过若干个遥测周期（比如 5 分钟）没有新数据就显示"状态未知"，而不是对外宣称在线。

`/devices/{id}/status` 能拿到状态变化历史：

```json
{
  "deviceId": "GH-0001", "count": 1,
  "from": 1794543655766, "to": 1794630055766,
  "items": [{"recvTs": 1794630055623, "online": true, "b": "7f3a91c4", "up": 3}],
  "nextCursor": null
}
```

`b` 可能缺失（LWT 由 Broker 代发，载荷里没有会话号）。

### 7.3 告警：只记发生，不记解除

`/devices/{id}/alarms` 是**状态变化**的事件流：

```json
{
  "deviceId": "GH-0001", "count": 1,
  "from": 1794543655759, "to": 1794630055759,
  "items": [{"recvTs": 1794630055640, "code": "water_lack", "active": true, "b": "7f3a91c4"}],
  "nextCursor": null
}
```

设备目前**只发"发生"、不发"解除"**。数据库忠实记录收到的报文，不做业务推断。
"缺水是否恢复"要客户端自己从后续遥测判断（例如 `water: true`），规则同 PROTOCOL §3.2。

`/latest` 里的 `alarms` 数组是**当前仍然激活**的告警，可以直接渲染成告警栏。

### 7.4 启动顺序

```
① 连 MQTT + 订阅（消息先缓存，不渲染）
② 调 /latest 初始化界面
③ 需要曲线再拉 /telemetry 历史
④ 渲染历史 → 回放①缓存的实时消息 → 按 (deviceId, b, seq) 去重
⑤ 之后纯实时 + 定时刷新 /latest
```

反过来（先取历史再订阅）会在两步之间丢消息，报文间隔以分钟计，很难发现。

---

## 8. 分页与窗口

- `limit` 默认 **500**，范围 **1..5000**，超范围返回 400。
- `cursor` 传上一页的 `nextCursor`，不传表示从头开始。游标是服务端内部编号，**不要解析或自己构造**。
- 结果是**正序**（时间从旧到新），同一窗口内不会重复也不会跳。
- 默认窗口是 24 小时。**要更早的数据必须显式传 `from`**，否则只能拿到最近一天。
- `limit` 截断的是窗口的**开头**，不是末尾。想要"最近 N 条"就把 `from` 设近一点
  （例如 `from = now - N × 采样间隔`），而不是指望 `limit=N` 帮你取末尾。
  `limit` 永远是"从窗口起点开始返回这么多条"。

单次不要贪大：5000 条已经是上限，`values` 全字段的话响应约 1 MB。
画一天曲线用 `limit=500` 分页即可（一天 1440 条，3 页）。

---

## 9. 联调与排查

```bash
# 服务活着吗
curl -s http://<vps>:8080/api/v1/health

# 有没有数据进来
curl -s -H "Authorization: Bearer <token>" http://<vps>:8080/api/v1/devices

# 最近一条
curl -s -H "Authorization: Bearer <token>" http://<vps>:8080/api/v1/devices/GH-0001/latest
```

| 现象 | 先看哪里 |
|---|---|
| 401 | token 是否与部署方给的一致，注意 `Bearer ` 后面有空格 |
| 404 `device_not_found` | `deviceId` 拼写；以及设备是否真的发过数据（`/devices` 里有没有） |
| `online` 一直 true 但数据不更新 | 看 `lastSeenTs` 和 `recvTs`，大概率是设备已断电而 Broker 还留着旧状态 |
| 告警不消失 | 正常：设备不发解除，见 7.3 |
| `/health` 里 `badPayloads` 在涨 | 设备报文不合协议，转嵌入端 |

**token 从哪来**：部署方在服务端配置里为每个客户端配一个 token（逗号分隔）。
本文不涉及 token 的签发与轮换机制，首版也没有"按 token 区分可见设备"，所有 token 权限相同。

---

## 10. 已知限制（首版）

| 限制 | 说明 |
|---|---|
| 只读 | 没有写入接口；配置下发走 MQTT（PROTOCOL §4） |
| 无聚合 | 没有平均值/统计，只有原始点位 |
| 无推送 | 只有 HTTP 轮询，实时数据走 MQTT |
| 无分页总数 | 不给 `total`，需要总数请按窗口遍历 |
| 无数据保留上限 | 数据永久保留、不自动清理，查询请务必带时间窗口 |
| 无多用户隔离 | 所有 token 看到同样的设备 |

以上任何一条影响你的方案，现在提出来，改起来还便宜。
