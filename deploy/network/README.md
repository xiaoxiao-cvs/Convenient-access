# Minecraft 服务器多线路接入 —— DNS 解析规划与部署说明

本目录是整套多线路接入方案的基础设施配置。目标是让玩家可以从三条独立线路
进入同一个 Minecraft 服务端，任意一条挂掉不影响其余两条。

配置按 frp **v0.70.1** 的 TOML 字段名编写，最低要求 v0.52
（v0.52 是 ini 转 TOML 的分水岭，字段名整体重命名过，低于此版本无法使用本目录的文件）。

---

## 零、部署前必读：本目录与现网的冲突（2026-08-14 实测）

**本目录的 frps 配置是按"全新部署"写的，直接覆盖现网会打断正在服务的隧道。**
下面是登录六台机器实测到的现状，部署前必须逐条处理。

现网 frps 分布：

| 机器 | bindPort | 配置文件路径 | 现有 proxy | 25565 状态 |
|------|----------|--------------|------------|------------|
| 阿里云ECS-杭州1 47.118.28.70 | 7000 | `/usr/local/frp/frps.toml` | `mc_java_main`、`mc_extra_24444` | frps 已监听，但后端 frpc 未连接 |
| 阿里云轻量-武汉1 47.122.120.140 | 7000 | `/etc/frp/frps.toml` | `win-ssh-7777`（离线） | 空闲 |
| 阿里云轻量-广州1 8.148.217.146 | **48124** | `/etc/frp/frps.toml` | `win25h2-rdp`（在线） | 空闲 |
| 阿里云轻量-杭州1 47.114.79.114 | 7000 | `/etc/frp/frps.toml` | 无，服务 **inactive** | 空闲 |
| 阿里云ECS-上海1 101.133.234.218 | 7000 | `/etc/frp/frps.toml` | 无 | 空闲 |
| 阿里云ECS-深圳1 120.24.184.115 | 7000 | `/opt/frp/frps.toml` | 25565、22222 均在监听 | **正在服务** |

必须处理的冲突：

1. **深圳 25565 是当前唯一在服务的 MC 线路。** 用 Minecraft 协议实测可正常返回服务器状态
   （1.20.1，实测时 2 人在线）。本目录的 `frps-sz.toml` 若覆盖过去会直接打断线上玩家。
2. **深圳 22222 是 `api.mcwok.cn` 的后端**（mod 的 HTTP API 经此暴露）。同一个 frps 进程同时
   承载它与 25565，重配该 frps 会一并挂掉面板 API。
3. **深圳 frps 的配置在 `/opt/frp/frps.toml`**，不是本目录假设的 `/etc/frp/frps.toml`。
   按本目录部署会新建一份互相冲突的配置。
4. **广州 frps 的 bindPort 是 48124 而非 7000**，且 `allowPorts` 白名单只开了
   24507-24509、24600。`frpc-gz.toml` 里的 `serverPort = 7000` 连不上；即使改对端口，
   申请 25565/25610 也会被白名单拒绝，必须先在 frps 侧放行。
5. **广州 frps 上跑着在线的 `win25h2-rdp`**，`frps-gz.toml` 整份覆盖会打断这条远程桌面隧道。

因此本目录的 `frps-gz.toml` / `frps-sz.toml` **不要整份覆盖**，应作为字段参考，
把 `allowPorts` 等必要项增量合并进现有配置。`frpc-*.toml` 需按上表校正 `serverAddr`
端口与 token 后再用。

另外三点实测修正：

- **深圳这台是阿里云 ECS，不是腾讯云**，下方拓扑图沿用了早先的错误标注。
- **家宽直连尚未开通**：`175.44.0.36` 的 25565 与 25603 均为连接拒绝，路由器端口映射还没做。
- **广州机到 Cloudflare 已经通了**（实测 TLS 握手成功）。早先"广州出境不通、需经深圳中转"的
  结论是 2026-07-26 的观测，现已不成立，选节点时不必再为此绕路。

---

## 一、拓扑

```
                              玩家
                               |
        +----------------------+----------------------+
        |                      |                      |
   gz.mcwok.cn            sz.mcwok.cn           home.mcwok.cn
   8.148.217.146         120.24.184.115         175.44.0.36（动态）
   阿里云广州 :25565      腾讯云深圳 :25565       家宽直连 :25565
        |                      |                      |
     [frps]                 [frps]                    | 不经过 frp
        |                      |                      | 路由器端口映射
        +--- frp 隧道 ---------+                      | 25565 -> 10.103:25603
        |                      |                      |
    [frpc@gz]              [frpc@sz]                  |
        |                      |                      |
        v                      v                      v
     :25601                 :25602                 :25603
        +----------------------+----------------------+
                               |
              [Forge mod 内置 TCP 前置转发器]
              按入口端口区分线路归属，解析 PROXY protocol 取玩家真实 IP
                               |
                               v
                  Minecraft 服务端 127.0.0.1:25565
```

转发器的 PROXY protocol 策略按线路区分：

| 入口端口 | 线路 | PROXY protocol v2 | 原因 |
| --- | --- | --- | --- |
| 25601 | gz | **开** | 经 frp 隧道，源地址已被改写成回环，不带头就丢失真实 IP |
| 25602 | sz | **开** | 同上 |
| 25603 | home | **关** | 直连线路，玩家的 TCP 源地址本身就是真实地址，多加一层头反而要额外剥 |

延迟探针是另一条独立通路。gz / sz 走云节点终结 TLS，home 因为不经过任何云节点，
TLS 必须在家里那台机器上终结：

```
gz / sz 线路:
浏览器 --wss://<节点域名>/probe--> 节点 nginx:443
       --http/1.1--> 节点 127.0.0.1:25610（frps 暴露，防火墙锁本机）
       --frp 隧道--> 家里 :25610（mod 内置 WebSocket 探针）

home 线路（不经过云节点）:
浏览器 --wss://home.mcwok.cn/probe--> 家里:443 Caddy 或 nginx
       --http/1.1--> 家里 127.0.0.1:25610（mod 内置 WebSocket 探针）
```

探针是标准 WebSocket（RFC 6455 升级握手 + 文本帧回显），所以三份反代配置都必须
正确透传 Upgrade / Connection 头。

### 为什么是两个 frpc 实例

**一个 frpc 进程只能连接一个 frps。** frpc 的配置里 `serverAddr` / `serverPort`
是全局字段而非每个 proxy 的字段，没有任何写法能让单个进程同时挂上广州和深圳两台
frps。所以家里必须跑**两个互相独立的 frpc 进程**，各自吃一份配置文件：

| 进程 | 配置文件 | 连接的 frps | frpc 管理端口 |
| --- | --- | --- | --- |
| frpc@gz | `frpc-gz.toml` | 8.148.217.146:7000 | 127.0.0.1:7400 |
| frpc@sz | `frpc-sz.toml` | 120.24.184.115:7000 | 127.0.0.1:7401 |

两个实例的 `webServer.port` 必须错开（7400 / 7401），否则同机第二个进程会因端口
占用直接启动失败。Linux 下用 `frpc@.service` 这个 template unit 管理，
Windows 见本文第九节。

---

## 二、文件清单

| 文件 | 部署位置 | 作用 |
| --- | --- | --- |
| `frps-gz.toml` | 广州节点 `/etc/frp/frps.toml` | 广州 frps 服务端配置 |
| `frps-sz.toml` | 深圳节点 `/etc/frp/frps.toml` | 深圳 frps 服务端配置 |
| `frpc-gz.toml` | 家里 `/etc/frp/frpc-gz.toml` | gz 线路客户端，暴露 25601 -> 25565 |
| `frpc-sz.toml` | 家里 `/etc/frp/frpc-sz.toml` | sz 线路客户端，暴露 25602 -> 25565 |
| `nginx-probe.conf` | 两个节点 `/etc/nginx/conf.d/probe.conf` | 节点侧 443 上的 wss 探针反代 |
| `Caddyfile-home` | 家里 `/etc/caddy/Caddyfile` | 家宽侧 wss 探针反代（**推荐**，含 ACME 自动签发） |
| `nginx-probe-home.conf` | 家里 `/etc/nginx/conf.d/probe-home.conf` | 家宽侧 wss 探针反代（Caddy 的备选，二选一） |
| `issue-wildcard-cert.sh` | 两个节点，手工执行 | 签发 `*.mcwok.cn` 泛域名证书 |
| `frps.service` | 两个节点 `/etc/systemd/system/` | frps 开机自启 |
| `frpc@.service` | 家里 `/etc/systemd/system/` | frpc 双实例开机自启（template unit） |
| `ddns-dnspod.sh` | 家里 `/usr/local/bin/` | 家宽动态 IP 同步到 home.mcwok.cn |

`Caddyfile-home` 和 `nginx-probe-home.conf` **二选一，不要同时部署** ——
两者都要监听 443，同时起会导致后启动的那个 bind 失败。

---

## 三、DNS 解析规划

域名 `mcwok.cn` 托管在腾讯云 DNSPod。

| 主机记录 | 类型 | 线路 | 记录值 | TTL | 说明 |
| --- | --- | --- | --- | --- | --- |
| `gz` | A | 默认 | 8.148.217.146 | 600 | 广州节点，玩家填 `gz.mcwok.cn` |
| `sz` | A | 默认 | 120.24.184.115 | 600 | 深圳节点，玩家填 `sz.mcwok.cn` |
| `home` | A | 默认 | 175.44.0.36 | 最低值 | 家宽直连，由 `ddns-dnspod.sh` 自动维护 |
| `@` | A | 默认 | 120.24.184.115 | 600 | 裸域名，作为默认入口指向深圳 |
| `_acme-challenge` | TXT | 默认 | acme.sh 自动写入 | - | **不要手工创建**，签发时自动增删 |

关于 `home` 的 TTL：应当填套餐允许的最小值。DNSPod 免费版最低 600 秒，
专业版及以上可降到 60 秒。填不下去就是套餐限制，不是配置错误。
TTL 越小，家宽换 IP 后玩家恢复得越快。

关于 `@`：裸域名指向深圳只是一个默认选择，玩家输 `mcwok.cn` 会走深圳线。
想换成广州就改成 8.148.217.146。三条线路本身互不依赖这条记录。

`_acme-challenge` 必须留给 acme.sh 自动管理。手工建了同名记录会和自动写入的
TXT 冲突，导致 DNS-01 校验拿到错误的值而签发失败。

### 为什么不使用 SRV 记录

SRV 记录（`_minecraft._tcp.mcwok.cn`）的唯一价值是**把非标准端口藏起来**，
让玩家不用输 `域名:端口`。本方案里用不上，理由：

1. 三条线路是**三个不同的 IP**，且每条线路都监听**标准端口 25565**。
   玩家直接输 `gz.mcwok.cn` / `sz.mcwok.cn` / `home.mcwok.cn`，
   Minecraft 客户端默认就连 25565，本来就不需要输端口号。
2. 一条 SRV 记录只能指向一个目标。要用 SRV 表达三条线路，得配三条不同名字的
   SRV，玩家还是得记三个不同的域名 —— 和直接用 A 记录相比零收益，
   却多引入一层解析和一层故障点。
3. SRV 在部分第三方启动器和旧版客户端上解析行为不一致，
   而 A 记录是所有版本都稳定支持的。

结论：**只用 A 记录，不配 SRV。**

---

## 四、端口规划总表

### 节点机（gz 广州 / sz 深圳，两台配置相同）

| 端口 | 协议 | 用途 | 对公网开放 |
| --- | --- | --- | --- |
| 7000 | TCP | frps 接受 frpc 接入（`bindPort`） | 是 |
| 7500 | TCP | frps dashboard | **否**，`webServer.addr` 已绑 127.0.0.1 |
| 25565 | TCP | Minecraft 入口（frps `remotePort`） | 是 |
| 25610 | TCP | 探针（frps `remotePort`） | **否**，必须用防火墙锁到本机 |
| 443 | TCP | nginx，`/probe` 的 wss 入口 | 是 |

节点上只放行 **443 / 7000 / 25565** 三个端口，云厂商安全组和主机防火墙都要一致。

**25610 是本方案最容易出错的地方**：frps 把 `remotePort` 绑在 `bindAddr`
（0.0.0.0）上，所以 25610 默认裸奔在公网，任何人都能绕开 nginx 直连
`<节点IP>:25610` 打探针。

不能靠 frps 的 `proxyBindAddr = "127.0.0.1"` 解决 —— 那个设置对**所有** proxy
一起生效，会把 25565 也绑到回环上，玩家直接进不来。唯一正确的做法是防火墙，
具体命令见 `nginx-probe.conf` 文件头。

### 家里的 Minecraft 主机（内网 10.103）

| 端口 | 协议 | 用途 | 对公网开放 |
| --- | --- | --- | --- |
| 25565 | TCP | Minecraft 服务端本体 | 否，仅本机/内网 |
| 25601 | TCP | 转发器 gz 线路入口 | 否，仅 frpc 本机访问 |
| 25602 | TCP | 转发器 sz 线路入口 | 否，仅 frpc 本机访问 |
| 25603 | TCP | 转发器 home 线路入口 | **是**，路由器映射公网 25565 -> 10.103:25603 |
| 25610 | TCP | mod 内置 WebSocket 探针 | 否，仅本机反代访问 |
| 443 | TCP | 家宽侧 TLS 反代（Caddy 或 nginx），`/probe` 的 wss 入口 | **是**，路由器映射公网 443 -> 10.103:443 |
| 7400 | TCP | frpc@gz 管理接口 | 否 |
| 7401 | TCP | frpc@sz 管理接口 | 否 |

家宽这条线**也走转发器**（走 25603 而不是直连 25565），目的是让三条线路的
人数统计口径完全一致。路由器上做的是**端口转换映射**：公网 25565 映射到内网
10.103 的 **25603**，不是 25565。映射错成 25565 的话，玩家能进服但这条线路的
人数统计恒为 0。

这台机器上**只有 443 和 25603 两个端口需要经路由器映射到公网**，
其余一律不能映射，完整红线清单见第八节。

`frpc-*.toml` 里 `localIP = "127.0.0.1"` 的前提是 **frpc 与 Minecraft 服务端跑在
同一台机器上**。如果把 frpc 挪到家里另一台机器，两处都要改：配置里改成 10.103，
同时转发器要改成监听 0.0.0.0 而不是仅回环。

---

## 五、token 生成

frps 与 frpc 的 `auth.token` 必须逐字符一致。用足够长的随机串，不要用可猜的口令：

```bash
# Linux / macOS
openssl rand -base64 32

# Windows PowerShell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
```

建议 **gz 和 sz 用两个不同的 token**，这样单个节点被拿下不会连带另一个节点。
`allowPorts` 白名单是第二道防线：即使 token 泄露，攻击者也只能申请 25565 和
25610，无法把节点当成任意端口的跳板机。

### 需要替换的占位符汇总

| 占位符 | 出现在 | 说明 |
| --- | --- | --- |
| `<FRP_TOKEN>` | `frps-gz.toml`、`frps-sz.toml`、`frpc-gz.toml`、`frpc-sz.toml` | 同一节点的 frps/frpc 必须相同；建议 gz 与 sz 用不同值 |
| `<FRP_DASHBOARD_PASSWORD>` | `frps-gz.toml`、`frps-sz.toml` | frps dashboard 密码 |
| `<FRPC_ADMIN_PASSWORD>` | `frpc-gz.toml`、`frpc-sz.toml` | frpc 管理接口密码 |
| `<NODE_DOMAIN>` | `nginx-probe.conf` | 广州填 `gz.mcwok.cn`，深圳填 `sz.mcwok.cn` |
| `<DNSPOD_API_ID>` | `issue-wildcard-cert.sh`、`ddns-dnspod.sh`、`Caddyfile-home` 注释 | DNSPod API Token 的 ID（纯数字） |
| `<DNSPOD_API_TOKEN>` | `issue-wildcard-cert.sh`、`ddns-dnspod.sh`、`Caddyfile-home` 注释 | DNSPod API Token 的 Token 串 |
| `<YOUR_EMAIL>` | `issue-wildcard-cert.sh` 注释、`Caddyfile-home` | ACME 注册邮箱，用于证书到期提醒 |

Caddy 用的环境变量是 `DNSPOD_TOKEN`，格式是 **`<DNSPOD_API_ID>,<DNSPOD_API_TOKEN>`
两段用逗号拼接**，与 DNSPod API 的 `login_token` 是同一个东西。
acme.sh 用的则是分开的 `DP_Id` / `DP_Key` 两个变量 —— 名字是插件写死的，不能改。

DNSPod API 密钥获取路径：DNSPod 控制台 -> 用户中心 -> API 密钥 ->
DNSPod Token -> 创建密钥。`DP_Id` / `DP_Key` 这两个变量名是 acme.sh 的
`dns_dp` 插件写死的，不能改名。

---

## 六、部署顺序

**先节点，后家宽。** 两条 frp 线路验证稳定之后再接家宽直连，理由是家宽那条线
涉及路由器改动和动态 IP，故障因素最多；先把可控的两条打通，出问题时才有稳定的
对照组。

### 阶段 0：准备（本地）

- [ ] 在 DNSPod 建好 `gz`、`sz`、`@` 三条 A 记录（`home` 先不建，阶段 3 再说）
- [ ] 生成两个 token，替换四份 frp 配置里的 `<FRP_TOKEN>`
- [ ] 申请 DNSPod API 密钥

### 阶段 1：广州节点（gz）

- [ ] 装 frp v0.70.1，二进制放 `/usr/local/bin/frps`
- [ ] `useradd --system --no-create-home --shell /usr/sbin/nologin frp`
- [ ] `mkdir -p /var/log/frp && chown frp:frp /var/log/frp`
- [ ] `frps-gz.toml` -> `/etc/frp/frps.toml`，替换占位符
- [ ] `frps.service` -> `/etc/systemd/system/`，`systemctl enable --now frps`
- [ ] 安全组 + 主机防火墙放行 443 / 7000 / 25565，**确认 25610 和 7500 未放行**
- [ ] 跑 `issue-wildcard-cert.sh` 签证书
- [ ] `nginx-probe.conf` -> `/etc/nginx/conf.d/probe.conf`，
      `<NODE_DOMAIN>` 替换为 `gz.mcwok.cn`，`nginx -t` 后 reload

### 阶段 2：深圳节点（sz）

同阶段 1，把 `frps-sz.toml` 和 `<NODE_DOMAIN>` = `sz.mcwok.cn` 换进去。

注意：深圳这台机器如果已经跑着别的站点（比如面板），443 端口已被占用。
`nginx-probe.conf` 是一个独立的 `server` 块，放进 `conf.d` 后由 SNI 按
`server_name` 分流，与既有 vhost 共存，不会冲突。文件里的 `map` 变量刻意叫
`$probe_connection_upgrade` 而不是通用的 `$connection_upgrade`，就是为了避免和
既有站点里的同名 map 撞车 —— nginx 里重复定义同名 map 会直接拒绝启动。

### 阶段 3：家里的 frpc 双实例

- [ ] 确认 Forge mod 的转发器已在 25601 / 25602 / 25603 上监听，
      且 **25601 / 25602 已启用 PROXY protocol v2 解析**
- [ ] 确认探针在 25610 上监听
- [ ] 装 frp，二进制放 `/usr/local/bin/frpc`
- [ ] `frpc-gz.toml` / `frpc-sz.toml` -> `/etc/frp/`，替换占位符
- [ ] `frpc@.service` -> `/etc/systemd/system/`
- [ ] `systemctl enable --now frpc@gz frpc@sz`
- [ ] 走完第七节的验证清单，让玩家实测两条线路

Windows 主机跳过 systemd 部分，见第九节。

### 阶段 4：家宽直连（最后）

- [ ] 在 DNSPod 手工建一条 `home` 的 A 记录（值先随便填，脚本会接管）
- [ ] 确认转发器的 25603 入口**没有**启用 PROXY protocol（直连线不需要）
- [ ] 路由器端口映射：公网 TCP 25565 -> 10.103:**25603**
- [ ] 路由器端口映射：公网 TCP 443 -> 10.103:443（探针 TLS，见第八节）
- [ ] 核对第八节的"绝对不能映射"清单，确认没有多开
- [ ] `ddns-dnspod.sh` -> `/usr/local/bin/`，配好 `DP_Id` / `DP_Key`
- [ ] 手工跑一次，确认输出的是家宽真实 IP 而不是代理出口 IP
- [ ] 配置 cron 或 systemd timer（脚本尾部有现成模板）
- [ ] 部署家宽侧 TLS 反代（`Caddyfile-home` 或 `nginx-probe-home.conf`，见第八节）

---

## 七、验证清单

### 通用原则：不要用 ping 判断连通性

**这套环境 ICMP 不通是正常的。** 云厂商安全组默认不放行 ICMP，家宽也常被过滤。
`ping` 不通完全不代表端口不通，反过来 `ping` 通也不代表 frps 在监听。
一律用 **TCP 层**的探测手段：

```powershell
# Windows PowerShell
Test-NetConnection -ComputerName gz.mcwok.cn -Port 25565
# 只看 TcpTestSucceeded 那一行，True 即通。PingSucceeded 是 False 属于正常。
```

```bash
# Linux / macOS
nc -vz gz.mcwok.cn 25565
# 或
timeout 5 bash -c '</dev/tcp/gz.mcwok.cn/25565' && echo PASS || echo FAIL
```

### 1. DNS 解析

```bash
dig +short gz.mcwok.cn      # 期望 8.148.217.146
dig +short sz.mcwok.cn      # 期望 120.24.184.115
dig +short home.mcwok.cn    # 期望家宽当前公网 IP
```

```powershell
Resolve-DnsName gz.mcwok.cn -Type A
```

判定：解析值与第三节的表格一致即 PASS。刚改完记录没生效的话，
按 TTL 等待，或用 `dig @119.29.29.29 gz.mcwok.cn` 直接问权威 DNS 绕过本地缓存。

### 2. frps 是否起来了（在节点机上）

```bash
systemctl status frps
ss -lntp | grep -E '7000|7500|25565|25610'
```

判定：
- 7000 应当监听在 `0.0.0.0` -> PASS
- 7500 必须监听在 `127.0.0.1`，若显示 `0.0.0.0:7500` 说明
  `webServer.addr` 没生效 -> **FAIL，立即修**
- 25565 / 25610 只有在对应 frpc 连上来之后才会出现，
  frpc 没起时看不到是正常的

配置本身是否合法可以在启动前先验：

```bash
frps verify -c /etc/frp/frps.toml
frpc verify -c /etc/frp/frpc-gz.toml
```

### 3. frpc 是否挂上了（在家里的机器上）

```bash
systemctl status frpc@gz frpc@sz
frpc status -c /etc/frp/frpc-gz.toml
frpc status -c /etc/frp/frpc-sz.toml
```

判定：`frpc status` 里 `mc-gz` 和 `probe-gz` 两个 proxy 的状态都应当是
`running`。出现 `start error` 通常是这几种：

| 现象 | 成因 |
| --- | --- |
| `port not allowed` | `remotePort` 超出了 frps 的 `allowPorts` 白名单 |
| `proxy name conflict` | 同名 proxy 已存在，多半是旧进程没退干净 |
| `authorization failed` | token 与 frps 侧不一致 |
| 本地端口 connect refused | 转发器/探针没监听，先查 mod 是否加载 |

### 4. 端口连通性（从任意外网机器）

```powershell
Test-NetConnection -ComputerName gz.mcwok.cn   -Port 25565   # 期望 True
Test-NetConnection -ComputerName sz.mcwok.cn   -Port 25565   # 期望 True
Test-NetConnection -ComputerName home.mcwok.cn -Port 25565   # 期望 True（阶段 4 后）
Test-NetConnection -ComputerName gz.mcwok.cn   -Port 25610   # 期望 False
Test-NetConnection -ComputerName gz.mcwok.cn   -Port 7500    # 期望 False
```

**25610 和 7500 必须探测失败。** 探测成功说明防火墙没配对，探针和 dashboard
正裸奔在公网上 -> FAIL，回到第四节处理。

### 5. TLS 证书

```bash
openssl s_client -connect gz.mcwok.cn:443 -servername gz.mcwok.cn </dev/null 2>/dev/null \
    | openssl x509 -noout -subject -dates
```

判定：`subject` 里应当能看到 `mcwok.cn`，且 `notAfter` 是将来的日期。
证书里应同时包含 `mcwok.cn` 和 `*.mcwok.cn` 两个名字：

```bash
openssl s_client -connect gz.mcwok.cn:443 -servername gz.mcwok.cn </dev/null 2>/dev/null \
    | openssl x509 -noout -ext subjectAltName
```

### 6. WebSocket 探针

```bash
curl -i --http1.1 \
     -H "Connection: Upgrade" \
     -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" \
     -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     https://gz.mcwok.cn/probe
```

三个域名都要测（`gz` / `sz` 在阶段 1、2 后测，`home` 在阶段 4 后测）。

判定：
- 返回 `HTTP/1.1 101 Switching Protocols` -> PASS
- 返回 `200` 或 `404` -> 反代的 location 没匹配上，或反代没到探针
- 返回 `502` -> 后端不可达。gz/sz 是 frps 的 25610 没监听（即 frpc 的
  `probe-*` proxy 没起来）；home 是本机探针没监听（查 mod 是否加载）
- 长时间挂起无响应 -> gz/sz 多半是探针那条 proxy 误开了 PROXY protocol，
  探针把 HTTP 请求行当协议头解析了
- `home` 证书报错 -> 家宽侧的 TLS 反代没起来或证书没签成，见第八节

### 7. 玩家真实 IP 与线路归属（最终验收）

从外网用真实客户端分别连三个域名进服，然后查服务端日志或 mod 的线路统计：

- 三次进服记录的**玩家 IP 都应当是客户端的真实公网 IP**。
  如果 gz/sz 两条线记录到的是 `127.0.0.1`，说明 PROXY protocol 链路断了 ->
  查 `frpc-*.toml` 里 `transport.proxyProtocolVersion = "v2"` 是否存在，
  以及转发器是否在 25601/25602 上启用了 PROXY protocol 解析。
- 三次进服应当分别归属到 gz / sz / home 三条不同线路。
  home 线归属错误，八成是路由器映射写成了 25565 -> 25565 而不是 -> 25603。

如果所有玩家一连就断，而转发器日志里出现协议解析错误，
是**转发器没开 PROXY protocol 解析**而 frpc 发了协议头 —— 两边必须同时开或同时关。

---

## 八、家宽侧 TLS 反代与路由器端口映射

home 这条线不经过任何云节点，所以 `https://home.mcwok.cn/probe` 的 TLS
**必须在家里那台机器上终结**。gz / sz 的探针由节点上的 nginx 处理，与本节无关。

两套方案二选一，**不要同时部署** —— 两者都要监听 443，同时起会导致后启动的
那个 bind 失败。

### 方案一：Caddy（推荐）

配置文件是 `Caddyfile-home`，部署到 `/etc/caddy/Caddyfile`。推荐理由：

1. `reverse_proxy` **原生支持 WebSocket**，自动完成 Upgrade 握手并转成双向隧道，
   不用手写 nginx 那套 `proxy_http_version` / `Upgrade` / `Connection` 三件套 ——
   那三行少任何一行 WS 握手都会失败，是这类配置最常见的翻车点。
2. **内建 ACME**，证书签发和续期由 Caddy 自己管，不需要额外的
   acme.sh + cron/timer + reloadcmd 一整套外部装置。
3. 家宽 80 端口通常被运营商封，HTTP-01 走不通，必须用 DNS-01 ——
   这一点和云节点完全相同。配上 dnspod 插件后 Caddy 直接走 DNS-01。

有效配置只有十来行：

```caddyfile
home.mcwok.cn {
	tls {
		dns dnspod {env.DNSPOD_TOKEN}
		resolvers 119.29.29.29 223.5.5.5
	}
	handle /probe* {
		reverse_proxy 127.0.0.1:25610 {
			flush_interval -1
		}
	}
	handle {
		respond 404
	}
}
```

**必须用带 dnspod 插件的定制版 Caddy。** 官方发行版不含任何 DNS 插件，
直接用会因为找不到 dnspod 模块而启动失败：

```bash
xcaddy build --with github.com/caddy-dns/dnspod
```

或到 https://caddyserver.com/download 勾选 dnspod 后下载定制版。

环境变量 `DNSPOD_TOKEN` 的格式是 `<DNSPOD_API_ID>,<DNSPOD_API_TOKEN>`，
两段用逗号拼接。写进 `/etc/default/caddy` 或 systemd 的 `EnvironmentFile`，
不要直接写进 Caddyfile（那个文件通常是 644 权限）。

```bash
caddy validate --config /etc/caddy/Caddyfile
systemctl enable --now caddy
journalctl -u caddy -f     # 首次签发会在这里刷 ACME 过程
```

### 方案二：nginx + acme.sh（备选）

不想引入 Caddy、或这台机器上已经跑着 nginx 的话，用 `nginx-probe-home.conf`，
部署到 `/etc/nginx/conf.d/probe-home.conf`，证书用 `issue-wildcard-cert.sh`
在本机签一次（同样走 DNS-01，原因与云节点一致：家宽 80 被封，HTTP-01 走不通）。

也可以把节点上签好的证书拷过来（`*.mcwok.cn` 同样覆盖 `home.mcwok.cn`），
但两边的续期是各管各的，节点续期了不会自动同步到家里 ——
更稳妥的做法还是在本机独立跑一次 `issue-wildcard-cert.sh`。

### 路由器端口映射

家里这台机器需要在路由器上映射的端口**只有两个**：

| 公网端口 | 映射到 | 用途 |
| --- | --- | --- |
| TCP 443 | 10.103:443 | 探针 TLS 入口（Caddy 或 nginx） |
| TCP 25565 | 10.103:**25603** | Minecraft 直连入口 |

注意 25565 是**端口转换映射**：公网 25565 映射到内网的 **25603**，不是 25565。

### 绝对不能映射的端口

| 端口 | 为什么不能映射 |
| --- | --- |
| **25601** | gz 线路的转发器本地入口，只允许本机 frpc 访问。暴露出去等于开了一个不受 frps 白名单和 token 保护的旁路，而且它期待 PROXY protocol 头，外部直连会被当成畸形协议 |
| **25602** | 同上，sz 线路 |
| **25565**（内网真实端口） | Minecraft 服务端本体。一旦被直接暴露，玩家就绕过了转发器，**线路统计会漏人**，玩家真实 IP 记录和按线路的封禁/风控也一并失效 |
| **25610** | 探针本体，明文 WebSocket。已经由 443 上的 TLS 反代对外提供，再映射一次就是把明文口裸露在公网 |
| **7400 / 7401** | 两个 frpc 实例的管理接口，能读取配置和 token |

最容易犯的错是把公网 25565 直接映射到内网 25565 —— 服能进，一切看起来正常，
但 home 这条线的人数统计恒为 0，且这些玩家在 mod 里没有线路归属。
排查时会因为"功能完全正常"而极难想到映射写错了。

如果运营商封了 443，这条线的探针只能退回明文 `ws://home.mcwok.cn:25610`
直连（需要额外映射 25610，并接受明文传输）。Minecraft 主线路不受影响。

### 家宽侧防火墙

frpc 是**主动出站**连接节点的 7000 端口，不需要任何入站规则。
需要放行入站的只有映射进来的 443 和 25603 两个端口。

---

## 九、Windows 上把 frpc 注册成服务

家里那台机器如果是 Windows，`frpc@.service` 用不上，改用下面两种方式之一。
无论哪种，都要记住**两条线路是两个独立进程**，需要注册两个服务。

配置文件里 `log.to = "./frpc-gz.log"` 是相对路径，日志会落在服务的工作目录下，
下面两种方式都显式指定了工作目录。

### 方式一：nssm（推荐）

nssm 能把任意 exe 包装成 Windows 服务，带自动重启和日志重定向，
比计划任务更适合常驻进程。从 https://nssm.cc 下载后：

```powershell
# 注册 gz 线路
nssm install frpc-gz "C:\frp\frpc.exe" "-c" "C:\frp\frpc-gz.toml"
nssm set frpc-gz AppDirectory "C:\frp"
nssm set frpc-gz DisplayName "frp client (gz 线路)"
nssm set frpc-gz Start SERVICE_AUTO_START
# 进程退出后 10 秒重启，对应 systemd 的 RestartSec=10s
nssm set frpc-gz AppRestartDelay 10000
nssm set frpc-gz AppStdout "C:\frp\logs\frpc-gz.out.log"
nssm set frpc-gz AppStderr "C:\frp\logs\frpc-gz.err.log"

# 注册 sz 线路（服务名和配置文件都要换）
nssm install frpc-sz "C:\frp\frpc.exe" "-c" "C:\frp\frpc-sz.toml"
nssm set frpc-sz AppDirectory "C:\frp"
nssm set frpc-sz DisplayName "frp client (sz 线路)"
nssm set frpc-sz Start SERVICE_AUTO_START
nssm set frpc-sz AppRestartDelay 10000
nssm set frpc-sz AppStdout "C:\frp\logs\frpc-sz.out.log"
nssm set frpc-sz AppStderr "C:\frp\logs\frpc-sz.err.log"

# 启动
Start-Service frpc-gz, frpc-sz
Get-Service frpc-gz, frpc-sz
```

卸载：`nssm remove frpc-gz confirm`

### 方式二：Windows 计划任务

不想装第三方工具就用计划任务。注意计划任务**没有进程守护能力**，
frpc 崩了不会自动拉起，只能靠"启动时"触发器在重启后恢复。
所以优先选 nssm。

```powershell
$action  = New-ScheduledTaskAction -Execute "C:\frp\frpc.exe" `
                                   -Argument "-c C:\frp\frpc-gz.toml" `
                                   -WorkingDirectory "C:\frp"
$trigger = New-ScheduledTaskTrigger -AtStartup
# 用 SYSTEM 账户运行，这样不登录也执行；RunLevel Highest 避免权限问题
$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" `
                                        -LogonType ServiceAccount `
                                        -RunLevel Highest
# 默认策略会在任务跑满 3 天后强杀，对常驻进程必须关掉
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries `
                                         -DontStopIfGoingOnBatteries `
                                         -ExecutionTimeLimit ([TimeSpan]::Zero) `
                                         -RestartCount 999 `
                                         -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName "frpc-gz" -Action $action -Trigger $trigger `
                       -Principal $principal -Settings $settings

# sz 线路重复一遍，把 frpc-gz 换成 frpc-sz
```

`-ExecutionTimeLimit ([TimeSpan]::Zero)` 这一行不能省。计划任务默认最长执行
3 天就强制结束，对 frpc 这种要长期常驻的进程，表现是**跑了三天线路突然消失**，
排查起来极难定位。

### Windows 防火墙

frpc 是**主动出站**连接节点的 7000 端口，所以 gz / sz 两条线不需要在 Windows
防火墙上开任何入站规则。

需要开入站的只有家宽直连那条线用到的两个端口，与第八节的映射表一一对应：

```powershell
New-NetFirewallRule -DisplayName "MC home 线路入口" -Direction Inbound `
                    -Protocol TCP -LocalPort 25603 -Action Allow
New-NetFirewallRule -DisplayName "探针 TLS" -Direction Inbound `
                    -Protocol TCP -LocalPort 443 -Action Allow
```

25601 / 25602 / 25565 / 25610 / 7400 / 7401 一律不开入站，
理由见第八节的"绝对不能映射的端口"。

---

## 十、常见故障速查

| 现象 | 优先怀疑 |
| --- | --- |
| 玩家进服即断，转发器报协议解析错 | PROXY protocol 一边开一边没开。frpc 的 `transport.proxyProtocolVersion` 与转发器的解析开关必须一致 |
| 玩家 IP 全是 127.0.0.1 | gz/sz 线路漏了 `transport.proxyProtocolVersion = "v2"` |
| 某条线路人数统计恒为 0 | 入口端口串了。检查 frpc 的 `localPort` 和路由器映射的目标端口。home 线最常见的成因是路由器把公网 25565 映射到了内网 25565 而不是 25603 |
| home 线玩家进服即断 | 25603 误开了 PROXY protocol。直连线的源地址本来就是真实的，不需要也不能加这层头 |
| 探针 502 | frps 的 25610 没监听，即 frpc 的 `probe-*` proxy 没起来 |
| 探针连上就断/一直挂起 | 探针那条 proxy 误开了 PROXY protocol |
| 探针每分钟断一次 | nginx 的 `proxy_read_timeout` 太短（默认 60s） |
| frpc 报 `port not allowed` | frps 的 `allowPorts` 白名单没包含该 `remotePort` |
| 证书签发卡住/超时 | acme.sh 跑到了 ZeroSSL（走 Cloudflare，广州节点不通）。确认已 `--set-default-ca --server letsencrypt` |
| DNS-01 校验失败 | DNSPod 记录未生效，重跑时加 `--dnssleep 120` |
| home.mcwok.cn 指向了陌生 IP | DDNS 查询走了本地代理。脚本已做三重防护，检查是否被绕过 |
| home 记录改不动，API 报锁定 | 1 小时内提交了超过 5 次无变动的修改请求，被 DNSPod 锁 1 小时。等待即可，脚本的"仅变化时更新"逻辑正是为规避它 |
| 家宽线路完全连不上，DDNS 报 CGNAT | 运营商没给独立公网 IP，需联系运营商开通 |
| nginx 启动报 map 重复定义 | 节点上已有站点定义了同名 map 变量，本文件已用 `$probe_connection_upgrade` 规避 |
| home 探针证书签不下来 | Caddy 没带 dnspod 插件（官方发行版不含 DNS 插件），或 `DNSPOD_TOKEN` 没写成 `ID,Token` 逗号拼接的格式 |
| 家里 443 起不来 / bind 失败 | Caddy 和 nginx 同时部署了，两者都要占 443。二选一 |

关于带宽：家宽上行 500Mbps，20 人满载 Minecraft 约 16Mbps，
带宽在本方案里不构成任何约束，出现卡顿请往上表的方向排查，不要往带宽上想。
