#!/usr/bin/env bash
#
# 为单个 frp 节点签发探针用的 TLS 证书（HTTP-01 挑战）。
#
# 为什么不用泛域名 + DNS-01:
#   每台节点只需要自己那一个子域名的证书，泛域名是多余的。而泛域名是唯一强制
#   走 DNS-01 的场景 —— 走 DNS-01 就得给每台机器配 DNS 服务商的 API 密钥，
#   等于把一份能改整个域名解析的凭据散布到六台机器上，风险远大于收益。
#   HTTP-01 只需要 80 端口可达，本机已用同样方式签过其它 mcwok.cn 子域名，路径已验证。
#
# 家宽那条线是例外: 个人宽带的 80 端口通常被运营商封，HTTP-01 走不通，
# 只能用 DNS-01，见 README 第八节。
#
# 前置条件（缺一不可）:
#   1. 该子域名的 A 记录已在阿里云 DNS 解析到本机公网 IP，且已生效
#      （用 dig +short <域名> 确认返回的是本机 IP）
#   2. 80 端口对公网可达，安全组已放行
#   3. 本机已装 certbot
#
# 用法:
#   CERT_EMAIL=you@example.com ./issue-node-cert.sh hz1.mcwok.cn

set -euo pipefail

DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then
  echo "用法: CERT_EMAIL=<邮箱> $0 <本节点子域名>" >&2
  echo "例如: CERT_EMAIL=admin@mcwok.cn $0 hz1.mcwok.cn" >&2
  exit 1
fi

EMAIL="${CERT_EMAIL:-}"
if [ -z "$EMAIL" ]; then
  echo "错误: 需要设置 CERT_EMAIL 环境变量，Let's Encrypt 用它发送到期提醒" >&2
  exit 1
fi

if ! command -v certbot >/dev/null 2>&1; then
  echo "错误: 未安装 certbot。Ubuntu 上执行: apt-get update && apt-get install -y certbot python3-certbot-nginx" >&2
  exit 1
fi

# 解析必须先指到本机，否则 Let's Encrypt 的校验请求会打到别的机器上，
# 签发失败还会消耗当周的失败次数配额。
RESOLVED="$(dig +short "$DOMAIN" A | tail -1 || true)"
MYIP="$(curl -fsS -4 -m 10 http://ip.3322.net 2>/dev/null || true)"
if [ -z "$RESOLVED" ]; then
  echo "错误: $DOMAIN 尚无 A 记录解析，请先在阿里云 DNS 控制台添加" >&2
  exit 1
fi
if [ -n "$MYIP" ] && [ "$RESOLVED" != "$MYIP" ]; then
  echo "错误: $DOMAIN 解析到 $RESOLVED，但本机公网 IP 是 $MYIP，两者必须一致" >&2
  exit 1
fi
echo "解析校验通过: $DOMAIN -> $RESOLVED"

# 优先用 nginx 插件（不中断已有站点）；没装 nginx 时回退到 standalone，
# standalone 需要临时占用 80 端口，若 80 上跑着东西会失败。
if systemctl is-active --quiet nginx 2>/dev/null; then
  echo "检测到 nginx 正在运行，使用 --nginx 插件签发（不中断现有站点）"
  certbot certonly --nginx -d "$DOMAIN" \
    --non-interactive --agree-tos -m "$EMAIL" --keep-until-expiring
else
  echo "未检测到运行中的 nginx，使用 --standalone 签发（将临时占用 80 端口）"
  certbot certonly --standalone -d "$DOMAIN" \
    --non-interactive --agree-tos -m "$EMAIL" --keep-until-expiring
fi

echo
echo "证书路径:"
echo "  fullchain: /etc/letsencrypt/live/$DOMAIN/fullchain.pem"
echo "  privkey:   /etc/letsencrypt/live/$DOMAIN/privkey.pem"
echo
echo "把这两个路径填进 nginx-probe.conf 的 ssl_certificate / ssl_certificate_key。"
echo
echo "续期: certbot 安装时已自带 certbot.timer，无需额外配置 cron。"
echo "验证续期链路: certbot renew --dry-run"
echo "注意 nginx 需要在证书更新后重载才会加载新证书，确认 deploy hook 存在:"
echo "  ls /etc/letsencrypt/renewal-hooks/deploy/"
