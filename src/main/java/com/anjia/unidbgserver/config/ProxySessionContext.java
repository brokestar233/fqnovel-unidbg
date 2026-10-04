package com.anjia.unidbgserver.config;

/**
 * SOCKS5 代理 session 上下文（ThreadLocal）。
 * <p>
 * rusty_proxy 以 SOCKS5 认证用户名作为 session id，同一 session id 绑定同一出口 IPv6。
 * 设备池选择设备时（{@code DevicePoolService#nextDevice} 等）将设备标识写入当前线程，
 * 后续同线程的 RestTemplate 调用在建立代理连接时读取该值作为认证用户名，
 * 实现"一个设备一个用户名（一个出口 IPv6）"。
 * <p>
 * 连接池按设备分区：每个 session id 分配一个合成 localAddress（127.x.y.z），
 * 通过 RequestConfig.localAddress 进入 HttpRoute，使各设备的连接互不复用。
 */
public final class ProxySessionContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ProxySessionContext() {
    }

    public static void set(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(sessionId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
