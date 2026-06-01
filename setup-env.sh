#!/bin/bash
# ============================================================
# 自动生成 .env 文件并启动服务（一条龙脚本）
# 用法: ./setup-env.sh [--no-start]
#   --no-start  仅生成 .env，不自动启动 docker compose
# ============================================================

ENV_FILE=".env"
NO_START=false

for arg in "$@"; do
    case "$arg" in
        --no-start) NO_START=true ;;
    esac
done

if [ -f "$ENV_FILE" ]; then
    read -p "⚠️  .env 文件已存在，是否覆盖？(y/N): " confirm
    if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
        if [ "$NO_START" = false ]; then
            echo "跳过 .env 生成，直接启动服务..."
            docker compose up -d
            echo ""
            echo "✅ 服务已启动！"
            echo "🌐 访问地址: http://localhost:8099"
            exit 0
        fi
        echo "已取消。"
        exit 0
    fi
fi

# ---- Redis 密码 ----
REDIS_PASSWORD=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 32)

# ---- 服务端口 ----
read -p "🔧 服务端口 [8099]: " input_port
SERVER_PORT=${input_port:-8099}

# ---- 代理配置 ----
echo ""
echo "============================================================"
echo "  SOCKS5 代理配置（可选）"
echo "  留空跳过，不使用代理"
echo "============================================================"
read -p "🌐 SOCKS5 代理地址 [192.168.50.3]: " input_proxy_host
PROXY_HOST=${input_proxy_host:-192.168.50.3}
read -p "🌐 SOCKS5 代理端口 [10086]: " input_proxy_port
PROXY_PORT=${input_proxy_port:-10086}

# ---- DoH DNS 配置 ----
echo ""
echo "============================================================"
echo "  DoH (DNS over HTTPS) 配置（可选）"
echo "  用于绕过本地 DNS 污染，默认使用 1.12.12.12"
echo "============================================================"
read -p "🔍 DoH 服务器地址 [https://1.12.12.12/dns-query]: " input_doh
DOH_SERVER=${input_doh:-https://1.12.12.12/dns-query}

# ---- HTTPS / TLS 配置 ----
echo ""
echo "============================================================"
echo "  HTTPS / TLS 配置（可选）"
echo "  留空或输入 n 跳过，仅使用 HTTP"
echo "============================================================"
read -p "🔒 是否启用 HTTPS？(y/N): " enable_ssl
SSL_ENABLED="false"
SSL_CERTIFICATE="/etc/brokestar.crt"
SSL_KEY="/etc/brokestar.key"

if [[ "$enable_ssl" == "y" || "$enable_ssl" == "Y" ]]; then
    SSL_ENABLED="true"
    read -p "   TLS 证书路径 [/etc/brokestar.crt]: " input_cert
    SSL_CERTIFICATE=${input_cert:-/etc/brokestar.crt}
    read -p "   TLS 私钥路径 [/etc/brokestar.key]: " input_key
    SSL_KEY=${input_key:-/etc/brokestar.key}
fi

# ---- 生成 .env ----
cat > "$ENV_FILE" <<EOF
# 由 setup-env.sh 自动生成于 $(date '+%Y-%m-%d %H:%M:%S')

# Redis 密码（自动生成，请勿泄露）
REDIS_PASSWORD=${REDIS_PASSWORD}

# 服务端口
SERVER_PORT=${SERVER_PORT}

# 番茄小说 Cookie（可选，有默认值）
# FQ_API_COOKIE=store-region=cn-zj; store-region-src=did; install_id=2420480337007787;

# JVM 参数
# JAVA_OPTS=-Xms512m -Xmx1g

# ============================================================
# 代理配置
# ============================================================

# SOCKS5 代理地址和端口
PROXY_HOST=${PROXY_HOST}
PROXY_PORT=${PROXY_PORT}

# ============================================================
# DNS 配置
# ============================================================

# DoH (DNS over HTTPS) 服务器地址
DOH_SERVER=${DOH_SERVER}

# ============================================================
# HTTPS / TLS 配置
# ============================================================

# 是否启用 HTTPS（true/false）
SSL_ENABLED=${SSL_ENABLED}

# TLS 证书文件路径（PEM 格式，宿主机路径会挂载到容器内对应路径）
SSL_CERTIFICATE=${SSL_CERTIFICATE}

# TLS 私钥文件路径（PEM 格式）
SSL_KEY=${SSL_KEY}
EOF

echo ""
echo "✅ .env 文件已生成！"
echo ""
echo "📋 配置摘要:"
echo "   Redis 密码: ${REDIS_PASSWORD}"
echo "   服务端口:   ${SERVER_PORT}"
echo "   代理地址:   ${PROXY_HOST}:${PROXY_PORT}"
echo "   DoH 服务器: ${DOH_SERVER}"
echo "   HTTPS:     ${SSL_ENABLED}"
if [ "$SSL_ENABLED" = "true" ]; then
    echo "   证书路径:   ${SSL_CERTIFICATE}"
    echo "   私钥路径:   ${SSL_KEY}"
fi

if [ "$NO_START" = false ]; then
    echo ""
    echo "🚀 正在启动服务..."
    docker compose up -d
    echo ""
    echo "✅ 服务已启动！"
    if [ "$SSL_ENABLED" = "true" ]; then
        echo "🌐 访问地址: https://localhost:${SERVER_PORT}"
    else
        echo "🌐 访问地址: http://localhost:${SERVER_PORT}"
    fi
else
    echo ""
    echo "🚀 启动服务:"
    echo "   docker compose up -d"
fi
