#!/usr/bin/env bash
#
# 签发 *.mcwok.cn + mcwok.cn 泛域名证书（acme.sh + DNSPod DNS-01）
#
# 在每台需要 TLS 的节点上各跑一次（gz、sz）。证书装到 nginx-probe.conf
# 指定的路径，并注册自动续期。
#
# ============================================================================
# 为什么必须走 DNS-01，不能走 HTTP-01
# ============================================================================
# 1. 泛域名（*.mcwok.cn）本身就只能用 DNS-01 验证，ACME 协议不允许用
#    HTTP-01 签发泛域名 —— 这一条是硬性的，没有变通办法。
# 2. 就算退一步只签单域名: 广州这台机器出境到 Cloudflare 不通，
#    而 acme.sh 默认 CA（ZeroSSL）的接口在 Cloudflare 后面，会直接超时。
#    所以下面显式 --server letsencrypt，把 CA 固定到 Let's Encrypt。
# 3. 家宽那条线（home.mcwok.cn）的 80 端口通常被运营商封，
#    HTTP-01 需要 CA 回连 80 端口，走不通。
# DNS-01 只需要本机能出站访问 dnsapi.cn 和 Let's Encrypt，不依赖入站端口。
# ============================================================================
#
# 需要的环境变量（占位符，运行前自行导出）:
#   DP_Id  —— DNSPod API Token 的 ID（纯数字）
#   DP_Key —— DNSPod API Token 的 Token 字符串
# 获取路径: DNSPod 控制台 -> 用户中心 -> API 密钥 -> DNSPod Token -> 创建密钥。
# 变量名是 acme.sh 的 dns_dp 插件写死的，不能改名。
#
# 用法:
#   export DP_Id='<DNSPOD_API_ID>'
#   export DP_Key='<DNSPOD_API_TOKEN>'
#   bash issue-wildcard-cert.sh

set -euo pipefail

DOMAIN="mcwok.cn"
CERT_DIR="/etc/nginx/ssl/${DOMAIN}"
ACME="${HOME}/.acme.sh/acme.sh"

# ---- 前置检查 ----

if [[ -z "${DP_Id:-}" || -z "${DP_Key:-}" ]]; then
    echo "FAIL: 未设置 DP_Id / DP_Key，无法调用 DNSPod API 写 TXT 记录。" >&2
    echo "      export DP_Id='<DNSPOD_API_ID>'" >&2
    echo "      export DP_Key='<DNSPOD_API_TOKEN>'" >&2
    exit 1
fi

if [[ ! -x "${ACME}" ]]; then
    echo "FAIL: 未找到 acme.sh（${ACME}）。先安装:" >&2
    echo "      curl https://get.acme.sh | sh -s email=<YOUR_EMAIL>" >&2
    echo "      安装时给的邮箱会用于证书到期提醒，填一个真实可收信的。" >&2
    exit 1
fi

# ---- 固定 CA ----

# acme.sh 3.x 默认 CA 是 ZeroSSL，其接口走 Cloudflare，广州节点连不通。
# --set-default-ca 会写进 acme.sh 的配置，之后的自动续期也会沿用 Let's Encrypt，
# 不加这一步的话续期时又会跑回 ZeroSSL 然后卡死。
"${ACME}" --set-default-ca --server letsencrypt

# ---- 签发 ----

# 同时签 mcwok.cn 和 *.mcwok.cn: 泛域名证书不覆盖裸域名（*.mcwok.cn 匹配
# gz.mcwok.cn 但不匹配 mcwok.cn），两个都要显式列出来。
# --keylength ec-256 显式指定 ECDSA，证书目录会带 _ecc 后缀，
# 下面 --install-cert 必须配套加 --ecc，否则找不到证书文件。
"${ACME}" --issue \
    --dns dns_dp \
    -d "${DOMAIN}" \
    -d "*.${DOMAIN}" \
    --keylength ec-256 \
    --server letsencrypt

# 如果这一步报 DNS 验证失败，多半是 DNSPod 记录还没生效，
# 重跑时加 --dnssleep 120 强制等 2 分钟再让 CA 查询。

# ---- 安装到 nginx ----

mkdir -p "${CERT_DIR}"

# 不要直接把 nginx 指向 ~/.acme.sh 下的文件: 那里的文件名在 acme.sh 升级时
# 变过，而且续期是"生成新文件 + 改软链"，nginx 可能读到半成品。
# --install-cert 会在续期后原子地拷过来再执行 reloadcmd。
"${ACME}" --install-cert \
    -d "${DOMAIN}" \
    --ecc \
    --key-file       "${CERT_DIR}/privkey.pem" \
    --fullchain-file "${CERT_DIR}/fullchain.pem" \
    --reloadcmd      "systemctl reload nginx"

chmod 600 "${CERT_DIR}/privkey.pem"

echo "DONE: 证书已安装到 ${CERT_DIR}"
echo "      验证: openssl x509 -in ${CERT_DIR}/fullchain.pem -noout -subject -dates"

# ============================================================================
# 自动续期
# ============================================================================
#
# acme.sh 在安装时（curl https://get.acme.sh | sh）就已经自动写好 cron 了，
# 正常情况下不需要额外配置。确认命令:
#
#   crontab -l | grep acme
#
# 应当看到类似（具体分钟数随机，避开整点雪崩）:
#   26 0 * * * "/root/.acme.sh"/acme.sh --cron --home "/root/.acme.sh" > /dev/null
#
# 注意 cron 环境不加载 shell 的 profile，但 acme.sh 会把 DP_Id / DP_Key
# 持久化到 ~/.acme.sh/account.conf，续期时自动读取，不需要在 crontab 里再导出。
#
# ---- 如果这台机器禁用了 cron，改用 systemd timer ----
#
# /etc/systemd/system/acme-renew.service:
#   [Unit]
#   Description=acme.sh certificate renewal
#   After=network-online.target
#   Wants=network-online.target
#
#   [Service]
#   Type=oneshot
#   ExecStart=/root/.acme.sh/acme.sh --cron --home /root/.acme.sh
#
# /etc/systemd/system/acme-renew.timer:
#   [Unit]
#   Description=Run acme.sh renewal daily
#
#   [Timer]
#   OnCalendar=daily
#   # 随机延迟避免所有机器同一秒打 CA，也防止被 CA 限流
#   RandomizedDelaySec=6h
#   # 机器关机错过的任务开机后补跑，否则长时间断电会导致证书过期
#   Persistent=true
#
#   [Install]
#   WantedBy=timers.target
#
# 启用:
#   systemctl daemon-reload
#   systemctl enable --now acme-renew.timer
#   systemctl list-timers acme-renew.timer
#
# 用 timer 的话记得删掉 acme.sh 自带的 cron，避免两套续期打架:
#   /root/.acme.sh/acme.sh --uninstall-cronjob
#
# ---- 手工验证续期链路（不实际签发，只走一遍流程）----
#   /root/.acme.sh/acme.sh --renew -d mcwok.cn --ecc --force --dry-run
# ============================================================================
