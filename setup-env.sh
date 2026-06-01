#!/bin/bash
# ============================================================
# 自动生成 .env 文件，包含随机 Redis 密码
# 用法: ./setup-env.sh
# ============================================================

ENV_FILE=".env"

if [ -f "$ENV_FILE" ]; then
    read -p "⚠️  .env 文件已存在，是否覆盖？(y/N): " confirm
    if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
        echo "已取消。"
        exit 0
    fi
fi

# 生成 32 位随机密码（仅含字母数字，避免特殊字符导致的转义问题）
REDIS_PASSWORD=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 32)

cat > "$ENV_FILE" <<EOF
# 由 setup-env.sh 自动生成于 $(date '+%Y-%m-%d %H:%M:%S')

# Redis 密码（自动生成，请勿泄露）
REDIS_PASSWORD=${REDIS_PASSWORD}

# 服务端口
SERVER_PORT=8099

# 番茄小说 Cookie（可选，有默认值）
# FQ_API_COOKIE=store-region=cn-zj; store-region-src=did; install_id=2420480337007787;

# JVM 参数
# JAVA_OPTS=-Xms512m -Xmx1g
EOF

echo "✅ .env 文件已生成！"
echo ""
echo "📋 配置摘要:"
echo "   Redis 密码: ${REDIS_PASSWORD}"
echo "   服务端口:   8099"
echo ""
echo "🚀 启动服务:"
echo "   docker compose up -d"
echo ""
echo "🌐 访问地址:"
echo "   http://localhost:8099"
