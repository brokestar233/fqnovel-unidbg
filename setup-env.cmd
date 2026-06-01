@echo off
REM ============================================================
REM 自动生成 .env 文件（Windows 版本）
REM 用法: setup-env.cmd
REM ============================================================

setlocal enabledelayedexpansion

set "ENV_FILE=.env"

if exist "%ENV_FILE%" (
    set /p "confirm=⚠️  .env 文件已存在，是否覆盖？(y/N): "
    if /i "!confirm!" neq "y" (
        echo 已取消。
        exit /b 0
    )
)

REM 使用 PowerShell 生成随机密码
for /f "delims=" %%i in ('powershell -Command "[System.Web.Security.Membership]::GeneratePassword(32,0) -replace '[^a-zA-Z0-9]',''"') do set "REDIS_PASSWORD=%%i"

(
    echo # 由 setup-env.cmd 自动生成于 %date% %time%
    echo.
    echo # Redis 密码（自动生成，请勿泄露）
    echo REDIS_PASSWORD=!REDIS_PASSWORD!
    echo.
    echo # 服务端口
    echo SERVER_PORT=8099
    echo.
    echo # 番茄小说 Cookie（可选，有默认值^）
    echo # FQ_API_COOKIE=store-region=cn-zj; store-region-src=did; install_id=2420480337007787;
    echo.
    echo # JVM 参数
    echo # JAVA_OPTS=-Xms512m -Xmx1g
) > "%ENV_FILE%"

echo.
echo ✅ .env 文件已生成！
echo.
echo 📋 配置摘要:
echo    Redis 密码: !REDIS_PASSWORD!
echo    服务端口:   8099
echo.
echo 🚀 启动服务:
echo    docker compose up -d
echo.
echo 🌐 访问地址:
echo    http://localhost:8099

endlocal
