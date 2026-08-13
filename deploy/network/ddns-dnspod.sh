#!/usr/bin/env bash
#
# ============================================================================
# 家宽直连线路，最后接入
# ============================================================================
# 这条线不经过 frp，玩家直连家里公网 IP。gz / sz 两条 frp 线路验证稳定之后
# 再启用本脚本，详见 README.md 的部署顺序。
#
# 作用: 把家宽的动态公网 IP 同步到 home.mcwok.cn 的 A 记录。
#
# 需要的环境变量（占位符，运行前自行导出或写进 /etc/default/ddns-dnspod）:
#   DP_Id  —— DNSPod API Token 的 ID（纯数字）
#   DP_Key —— DNSPod API Token 的 Token 字符串
# 与 issue-wildcard-cert.sh 共用同一对密钥，变量名保持一致便于统一管理。
#
# 用法:
#   export DP_Id='<DNSPOD_API_ID>'
#   export DP_Key='<DNSPOD_API_TOKEN>'
#   bash ddns-dnspod.sh

set -euo pipefail

DOMAIN="mcwok.cn"
SUB_DOMAIN="home"
RECORD_TYPE="A"
# DNSPod 的线路名 "默认" 的 URL 编码。Record.Ddns 要求必须带线路，
# 直接传中文在某些 curl / locale 组合下会编码错误导致 API 报参数非法。
RECORD_LINE="%E9%BB%98%E8%AE%A4"

API_BASE="https://dnsapi.cn"

# ============================================================================
# 关键: 彻底绕开本地代理
# ============================================================================
# 这台机器上跑着 Clash 之类的透明/系统代理。如果查询公网 IP 的请求走了代理，
# 拿到的会是代理机房的出口 IP，然后被写进 home.mcwok.cn ——
# 玩家会被解析到一个根本没有 Minecraft 的地址，且故障现象很隐蔽
# （DNS 有记录、能 ping 通、就是连不上服）。
#
# 三重保险，缺一不可:
#   1. unset 掉所有代理环境变量（systemd / cron 也可能从 environment 继承）；
#   2. no_proxy='*' 覆盖 libcurl 内部可能已缓存的配置；
#   3. curl 显式 --noproxy '*' —— 因为 ~/.curlrc 里也可能写了 proxy=，
#      那个文件不受环境变量影响。
# ============================================================================
unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY
export no_proxy='*'
export NO_PROXY='*'

CURL_OPTS=(--noproxy '*' --silent --show-error --max-time 15 --retry 2 --retry-delay 3)

# ---- 取本机公网 IP ----

# 只用国内查询源: 一是不受出境链路影响，二是它们看到的就是运营商分配给
# 家宽的真实地址。ip.3322.net 返回裸 IP 文本，不带换行以外的任何东西。
IP_SOURCES=(
    "http://ip.3322.net"
    "http://members.3322.org/dyndns/getip"
    "https://4.ipw.cn"
)

get_public_ip() {
    local src ip
    for src in "${IP_SOURCES[@]}"; do
        # 单个源失败不算致命，换下一个。这里的 || continue 不是吞异常，
        # 而是多源冗余的正常控制流。
        ip="$(curl "${CURL_OPTS[@]}" "${src}" 2>/dev/null | tr -d '[:space:]')" || continue
        if [[ "${ip}" =~ ^((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$ ]]; then
            echo "${ip}"
            return 0
        fi
    done
    return 1
}

# ---- DNSPod API 调用 ----

dnspod_call() {
    local endpoint="$1"
    local params="$2"
    curl "${CURL_OPTS[@]}" \
        -X POST "${API_BASE}/${endpoint}" \
        -d "login_token=${DP_Id},${DP_Key}&format=json&lang=cn&${params}"
}

# 从 DNSPod 返回的 JSON 里抠字段。这里刻意不依赖 jq:
# 家里这台机器是 Minecraft 主机不是运维机，不该为一个 DDNS 脚本引额外依赖。
#
# 末尾的 || true 不是吞异常: grep 无匹配时返回 1，配合 set -e 会让脚本
# 在赋值那一行就直接死掉，下面针对空值的检查和报错信息永远执行不到。
# 让它返回空串，由调用方显式判断。
json_field() {
    local json="$1" key="$2"
    echo "${json}" | grep -o "\"${key}\":\"[^\"]*\"" | head -n 1 |
        sed "s/\"${key}\":\"\(.*\)\"/\1/" || true
}

# Record.List 的响应里 domain 和 records 两个对象都有 id 字段，
# 直接对整个响应抓第一个 "id" 会拿到域名 id 而不是记录 id，
# 结果是拿着错误的 record_id 去改记录（轻则失败，重则改错别的记录）。
# 先把 "records" 之前的内容切掉，再解析。
records_field() {
    local json="$1" key="$2"
    local records="${json#*\"records\"}"
    json_field "${records}" "${key}"
}

# ---- 主流程 ----

if [[ -z "${DP_Id:-}" || -z "${DP_Key:-}" ]]; then
    echo "FAIL: 未设置 DP_Id / DP_Key。" >&2
    exit 1
fi

CURRENT_IP="$(get_public_ip)" || {
    echo "FAIL: 所有公网 IP 查询源都不可用，本次跳过。" >&2
    exit 1
}

# 查到私网/CGNAT 地址说明这条线路根本没有可直连的公网 IP，
# 写进 DNS 只会把玩家导向一个连不上的地址。两种成因:
#   10/172.16/192.168 段 —— 代理没绕干净，查到的是内网出口；
#   100.64.0.0/10       —— 运营商上了 CGNAT，家宽没有独立公网 IP，
#                          这种情况必须找运营商开公网 IP，脚本无能为力。
case "${CURRENT_IP}" in
    10.*|192.168.*|127.*|169.254.*)
        echo "FAIL: 查到私网地址 ${CURRENT_IP}，代理未绕开或查询源异常，拒绝写入 DNS。" >&2
        exit 1
        ;;
    172.1[6-9].*|172.2[0-9].*|172.3[01].*)
        echo "FAIL: 查到私网地址 ${CURRENT_IP}，代理未绕开或查询源异常，拒绝写入 DNS。" >&2
        exit 1
        ;;
    100.6[4-9].*|100.[7-9][0-9].*|100.1[01][0-9].*|100.12[0-7].*)
        echo "FAIL: 查到 CGNAT 地址 ${CURRENT_IP}，家宽没有独立公网 IP，直连线路不可用。" >&2
        echo "      需联系运营商开通公网 IP，或放弃 home 线路只用 gz/sz 两条 frp 线。" >&2
        exit 1
        ;;
esac

# 查当前 A 记录。这一步同时拿到 record_id 和记录里现存的值。
#
# 为什么每次都查而不是用本地缓存文件对比:
# 缓存会和真实记录漂移 —— 只要有人在 DNSPod 控制台手工改过、
# 或者上一次 Record.Ddns 其实失败了但缓存已写入，
# 脚本就会认为"IP 没变"从而永远不再修正，故障可以潜伏数月。
# Record.List 是读操作，不受下面那条锁定规则约束，每 5 分钟查一次代价可忽略。
LIST_RESP="$(dnspod_call "Record.List" \
    "domain=${DOMAIN}&sub_domain=${SUB_DOMAIN}&record_type=${RECORD_TYPE}")"

LIST_CODE="$(json_field "${LIST_RESP}" "code")"
if [[ "${LIST_CODE}" != "1" ]]; then
    echo "FAIL: Record.List 失败 code=${LIST_CODE} message=$(json_field "${LIST_RESP}" "message")" >&2
    echo "      若 code=10，说明 ${SUB_DOMAIN}.${DOMAIN} 的 A 记录还不存在，" >&2
    echo "      先到 DNSPod 控制台手工建一条（值随便填，本脚本会接管）。" >&2
    exit 1
fi

RECORD_ID="$(records_field "${LIST_RESP}" "id")"
RECORD_VALUE="$(records_field "${LIST_RESP}" "value")"

if [[ -z "${RECORD_ID}" ]]; then
    echo "FAIL: 未能从 Record.List 响应中解析出 record_id。" >&2
    exit 1
fi

# 只有真的变了才写。
#
# DNSPod 有一条硬规则: 1 小时内提交超过 5 次"没有任何变动"的记录修改请求，
# 该记录会被系统锁定 1 小时。如果无脑每次都调 Record.Ddns，
# 5 分钟跑一次的话 25 分钟就会把记录锁死 —— 而这恰恰意味着真正换 IP 时
# 反而更新不上去。
if [[ "${CURRENT_IP}" == "${RECORD_VALUE}" ]]; then
    echo "DONE: IP 未变化（${CURRENT_IP}），跳过更新。"
    exit 0
fi

echo "INFO: 检测到 IP 变化 ${RECORD_VALUE} -> ${CURRENT_IP}，正在更新..."

# 用 Record.Ddns 而不是 Record.Modify: 前者就是为动态 IP 场景设计的，
# 参数更少（不需要重复提交 record_type），且不受上述锁定规则的常规限流影响。
DDNS_RESP="$(dnspod_call "Record.Ddns" \
    "domain=${DOMAIN}&record_id=${RECORD_ID}&sub_domain=${SUB_DOMAIN}&record_line=${RECORD_LINE}&value=${CURRENT_IP}")"

DDNS_CODE="$(json_field "${DDNS_RESP}" "code")"
if [[ "${DDNS_CODE}" != "1" ]]; then
    echo "FAIL: Record.Ddns 失败 code=${DDNS_CODE} message=$(json_field "${DDNS_RESP}" "message")" >&2
    exit 1
fi

echo "DONE: ${SUB_DOMAIN}.${DOMAIN} 已更新为 ${CURRENT_IP}"

# ============================================================================
# 定时执行
# ============================================================================
#
# ---- Linux: cron ----
# crontab -e 加入（每 5 分钟一次；家宽换 IP 通常发生在拨号重连时，
# 5 分钟的收敛窗口配合下面 60 秒的 TTL，玩家最坏等 6 分钟）:
#
#   */5 * * * * DP_Id='<DNSPOD_API_ID>' DP_Key='<DNSPOD_API_TOKEN>' /usr/local/bin/ddns-dnspod.sh >> /var/log/ddns-dnspod.log 2>&1
#
# 密钥直接写在 crontab 里会被 `ps` 和其他用户看到。更稳妥的做法是
# 放进 /etc/default/ddns-dnspod（chmod 600），然后:
#
#   */5 * * * * . /etc/default/ddns-dnspod && /usr/local/bin/ddns-dnspod.sh >> /var/log/ddns-dnspod.log 2>&1
#
# ---- Linux: systemd timer（推荐，能用 EnvironmentFile 管密钥）----
#
# /etc/systemd/system/ddns-dnspod.service:
#   [Unit]
#   Description=DNSPod DDNS for home.mcwok.cn
#   After=network-online.target
#   Wants=network-online.target
#
#   [Service]
#   Type=oneshot
#   EnvironmentFile=/etc/default/ddns-dnspod
#   ExecStart=/usr/local/bin/ddns-dnspod.sh
#
# /etc/systemd/system/ddns-dnspod.timer:
#   [Unit]
#   Description=Run DNSPod DDNS every 5 minutes
#
#   [Timer]
#   OnBootSec=1min
#   OnUnitActiveSec=5min
#   # 开机后补跑，避免断电重启期间换了 IP 却没人更新
#   Persistent=true
#
#   [Install]
#   WantedBy=timers.target
#
#   systemctl daemon-reload && systemctl enable --now ddns-dnspod.timer
#
# ---- Windows ----
# 本脚本是 bash，Windows 上需通过 Git Bash 执行。计划任务命令行:
#   "C:\Program Files\Git\bin\bash.exe" -lc "/d/Repo/Convenient-access/deploy/network/ddns-dnspod.sh"
# 触发器设为"重复任务间隔 5 分钟，持续时间无限期"，
# 并勾选"不管用户是否登录都要运行"，否则注销后不执行。
# 注意 Git Bash 会继承 Windows 的系统代理设置，脚本开头的 unset + --noproxy
# 正是为这种情况准备的。
# ============================================================================
