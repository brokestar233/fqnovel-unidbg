@echo off
REM ============================================================
REM 自动生成 .env 文件并启动服务（一条龙脚本，Windows 版本）
REM 用法: setup-env.cmd [--no-start]
REM   --no-start  仅生成 .env，不自动启动 docker compose
REM ============================================================

setlocal enabledelayedexpansion

set "ENV_FILE=.env"
set "NO_START=false"

if "%~1"=="--no-start" set "NO_START=true"

if exist "%ENV_FILE%" (
    set /p "confirm=⚠️  .env 文件已存在，是否覆盖？(y/N): "
    if /i "!confirm!" neq "y" (
        if "!NO_START!"=="false" (
            echo 跳过 .env 生成，直接启动服务...
            docker compose up -d
            echo.
            echo ✅ 服务已启动！
            echo 🌐 访问地址: http://localhost:8099
        ) else (
            echo 已取消。
        )
        endlocal
        exit /b 0
    )
)

REM ---- Redis 密码 ----
for /f "delims=" %%i in ('powershell -Command "[System.Web.Security.Membership]::GeneratePassword(32,0) -replace '[^a-zA-Z0-9]',''"') do set "REDIS_PASSWORD=%%i"

REM ---- 服务端口 ----
set "SERVER_PORT=8099"
set /p "input_port=🔧 服务端口 [8099]: "
if not "!input_port!"=="" set "SERVER_PORT=!input_port!"

REM ---- 代理配置 ----
echo.
echo ============================================================
echo   SOCKS5 代理配置（可选）
echo   留空跳过，不使用代理
echo ============================================================
set "PROXY_HOST=192.168.50.3"
set "PROXY_PORT=10086"
set /p "input_proxy_host=🌐 SOCKS5 代理地址 [192.168.50.3]: "
if not "!input_proxy_host!"=="" set "PROXY_HOST=!input_proxy_host!"
set /p "input_proxy_port=🌐 SOCKS5 代理端口 [10086]: "
if not "!input_proxy_port!"=="" set "PROXY_PORT=!input_proxy_port!"

REM ---- DoH DNS 配置 ----
echo.
echo ============================================================
echo   DoH (DNS over HTTPS) 配置（可选）
echo   用于绕过本地 DNS 污染，默认使用 1.12.12.12
echo ============================================================
set "DOH_SERVER=https://1.12.12.12/dns-query"
set /p "input_doh=🔍 DoH 服务器地址 [https://1.12.12.12/dns-query]: "
if not "!input_doh!"=="" set "DOH_SERVER=!input_doh!"

REM ---- HTTPS / TLS 配置 ----
echo.
echo ============================================================
echo   HTTPS / TLS 配置（可选）
echo   留空或输入 n 跳过，仅使用 HTTP
echo ============================================================
set "SSL_ENABLED=false"
set "SSL_CERTIFICATE=/etc/brokestar.crt"
set "SSL_KEY=/etc/brokestar.key"

set /p "enable_ssl=🔒 是否启用 HTTPS？(y/N): "
if /i "!enable_ssl!"=="y" (
    set "SSL_ENABLED=true"
    set /p "input_cert=   TLS 证书路径 [/etc/brokestar.crt]: "
    if not "!input_cert!"=="" set "SSL_CERTIFICATE=!input_cert!"
    set /p "input_key=   TLS 私钥路径 [/etc/brokestar.key]: "
    if not "!input_key!"=="" set "SSL_KEY=!input_key!"
)

REM ---- 生成 .env ----
(
    echo # 由 setup-env.cmd 自动生成于 %date% %time%
    echo.
    echo # Redis 密码（自动生成，请勿泄露）
    echo REDIS_PASSWORD=!REDIS_PASSWORD!
    echo.
    echo # 服务端口
    echo SERVER_PORT=!SERVER_PORT!
    echo.
    echo # 番茄小说 Cookie（可选，有默认值^）
    echo # FQ_API_COOKIE=store-region=cn-zj; store-region-src=did; install_id=2420480337007787;
    echo.
    echo # JVM 参数
    echo # JAVA_OPTS=-Xms512m -Xmx1g
    echo.
    echo # ============================================================
    echo # 代理配置
    echo # ============================================================
    echo.
    echo # SOCKS5 代理地址和端口
    echo PROXY_HOST=!PROXY_HOST!
    echo PROXY_PORT=!PROXY_PORT!
    echo.
    echo # ============================================================
    echo # DNS 配置
    echo # ============================================================
    echo.
    echo # DoH ^(DNS over HTTPS^) 服务器地址
    echo DOH_SERVER=!DOH_SERVER!
    echo.
    echo # ============================================================
    echo # HTTPS / TLS 配置
    echo # ============================================================
    echo.
    echo # 是否启用 HTTPS（true/false^）
    echo SSL_ENABLED=!SSL_ENABLED!
    echo.
    echo # TLS 证书文件路径（PEM 格式，宿主机路径会挂载到容器内对应路径^）
    echo SSL_CERTIFICATE=!SSL_CERTIFICATE!
    echo.
    echo # TLS 私钥文件路径（PEM 格式^）
    echo SSL_KEY=!SSL_KEY!
) > "%ENV_FILE%"

echo.
echo ✅ .env 文件已生成！
echo.
echo 📋 配置摘要:
echo    Redis 密码: !REDIS_PASSWORD!
echo    服务端口:   !SERVER_PORT!
echo    代理地址:   !PROXY_HOST!:!PROXY_PORT!
echo    DoH 服务器: !DOH_SERVER!
echo    HTTPS:     !SSL_ENABLED!
if "!SSL_ENABLED!"=="true" (
    echo    证书路径:   !SSL_CERTIFICATE!
    echo    私钥路径:   !SSL_KEY!
)

if "!NO_START!"=="false" (
    echo.
    echo 🚀 正在启动服务...
    docker compose up -d
    echo.
    echo ✅ 服务已启动！
    if "!SSL_ENABLED!"=="true" (
        echo 🌐 访问地址: https://localhost:!SERVER_PORT!
    ) else (
        echo 🌐 访问地址: http://localhost:!SERVER_PORT!
    )
) else (
    echo.
    echo 🚀 启动服务:
    echo    docker compose up -d
)

endlocal
