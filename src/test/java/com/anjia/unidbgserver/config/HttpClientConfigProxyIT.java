package com.anjia.unidbgserver.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实连 SOCKS5 代理（rusty_proxy）的集成测试，验证：
 * 1. RFC 1929 用户名/密码认证握手可用；
 * 2. "一个设备一个用户名"：同一 session id 始终映射同一出口 IPv6，
 *    不同 session id 出口不同，且连接池按设备分区互不串线。
 * <p>
 * 需要能访问代理服务器，默认跳过；通过环境变量 PROXY_IT=1 开启：
 * <pre>PROXY_IT=1 ./mvnw test -Dtest=HttpClientConfigProxyIT -Dmaven.test.skip=false</pre>
 */
@EnabledIfEnvironmentVariable(named = "PROXY_IT", matches = "1")
class HttpClientConfigProxyIT {

    private static final String PROXY_HOST = System.getenv().getOrDefault("PROXY_HOST", "192.168.50.3");
    private static final String PROXY_PORT = System.getenv().getOrDefault("PROXY_PORT", "10086");
    private static final String PROXY_PASSWORD = System.getenv().getOrDefault("PROXY_PASSWORD", "114514");
    private static final String ECHO_URL = "https://api64.ipify.org/";

    @AfterEach
    void tearDown() {
        ProxySessionContext.clear();
    }

    @Test
    void deviceSessionBindsStableExitIp() throws Exception {
        HttpClientConfig config = new HttpClientConfig();
        setField(config, "connectTimeoutMs", 8000);
        setField(config, "readTimeoutMs", 20000);
        setField(config, "maxConnections", 20);
        setField(config, "maxConnectionsPerRoute", 10);
        setField(config, "dohServer", "https://1.12.12.12/dns-query");
        setField(config, "socksProxyHost", PROXY_HOST);
        setField(config, "socksProxyPort", PROXY_PORT);
        setField(config, "socksProxyUsername", "");
        setField(config, "socksProxyPassword", PROXY_PASSWORD);

        RestTemplate restTemplate = config.restTemplate();

        // 设备 A：两次请求应始终走同一出口 IPv6
        ProxySessionContext.set("it-device-A");
        String ipA1 = restTemplate.getForObject(ECHO_URL, String.class);
        ProxySessionContext.set("it-device-B");
        String ipB1 = restTemplate.getForObject(ECHO_URL, String.class);
        // 切回 A：验证连接池分区后不会复用 B 的连接、session 重新绑定回 A 的 IP
        ProxySessionContext.set("it-device-A");
        String ipA2 = restTemplate.getForObject(ECHO_URL, String.class);
        ProxySessionContext.set("it-device-B");
        String ipB2 = restTemplate.getForObject(ECHO_URL, String.class);

        assertThat(ipA1).as("设备 A 两次出口 IP 应一致").isEqualTo(ipA2);
        assertThat(ipB1).as("设备 B 两次出口 IP 应一致").isEqualTo(ipB2);
        assertThat(ipA1).as("设备 A/B 出口 IP 应不同").isNotEqualTo(ipB1);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
