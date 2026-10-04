package com.anjia.unidbgserver.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Configuration
public class HttpClientConfig {

    @Value("${application.http-client.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${application.http-client.read-timeout-ms:15000}")
    private int readTimeoutMs;

    @Value("${application.http-client.max-connections:50}")
    private int maxConnections;

    @Value("${application.http-client.max-connections-per-route:20}")
    private int maxConnectionsPerRoute;

    @Value("${DOH_SERVER:https://1.12.12.12/dns-query}")
    private String dohServer;

    // 通过环境变量 PROXY_HOST / PROXY_PORT 配置（而非 -D JVM 属性，避免与 JDK SOCKS 实现冲突）
    @Value("${PROXY_HOST:}")
    private String socksProxyHost;

    @Value("${PROXY_PORT:}")
    private String socksProxyPort;

    // SOCKS5 用户名/密码认证（RFC 1929），适配 rusty_proxy：
    // 用户名即 session id，代理侧按 session id 绑定出口 IPv6（空闲超时后释放）。
    // PROXY_USERNAME 配置时作为默认固定 session；未配置时无设备上下文的连接使用随机 session。
    @Value("${PROXY_USERNAME:}")
    private String socksProxyUsername;

    @Value("${PROXY_PASSWORD:}")
    private String socksProxyPassword;

    /**
     * session id -> 合成 localAddress（127.x.y.z，仅作连接池路由键，从不真正 bind）。
     * Apache HttpClient 的 HttpRoute 包含 localAddress，连接池按 route 分池，
     * 因此每个设备（session）拿到独立的连接桶，设备之间不会复用彼此的代理连接。
     */
    private static final ConcurrentHashMap<String, InetAddress> SESSION_TO_ADDRESS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<InetAddress, String> ADDRESS_TO_SESSION = new ConcurrentHashMap<>();
    private static final AtomicInteger SESSION_ADDRESS_SEQ = new AtomicInteger(2);

    static InetAddress syntheticAddressFor(String sessionId) {
        return SESSION_TO_ADDRESS.computeIfAbsent(sessionId, id -> {
            // 序号映射到 127.0.a.b.c（跳过 127.0.0.0/1），设备池规模远小于地址空间
            int seq = SESSION_ADDRESS_SEQ.getAndIncrement() & 0xFFFF;
            if (seq < 2) {
                seq += 2;
            }
            byte[] addr = new byte[]{127, 0, (byte) ((seq >> 8) & 0xFF), (byte) (seq & 0xFF)};
            try {
                InetAddress address = InetAddress.getByAddress(addr);
                ADDRESS_TO_SESSION.putIfAbsent(address, id);
                return address;
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException("构造合成 localAddress 失败", e);
            }
        });
    }

    static String sessionIdOf(InetAddress address) {
        return address == null ? null : ADDRESS_TO_SESSION.get(address);
    }

    @Bean
    public RestTemplate restTemplate() throws Exception {
        SSLContext sslContext = SSLContextBuilder.create()
            .build();

        SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext);

        ConnectionSocketFactory plainSocketFactory;
        ConnectionSocketFactory httpsSocketFactory;
        HttpComponentsClientHttpRequestFactory requestFactory;

        RequestConfig baseRequestConfig = RequestConfig.custom()
            .setConnectTimeout(connectTimeoutMs)
            .setSocketTimeout(readTimeoutMs)
            .setConnectionRequestTimeout(connectTimeoutMs)
            .build();

        boolean useProxy = socksProxyHost != null && !socksProxyHost.isEmpty();
        boolean useAuth = socksProxyPassword != null && !socksProxyPassword.isEmpty();

        if (useProxy) {
            int port = 1080;
            if (socksProxyPort != null && !socksProxyPort.isEmpty()) {
                try {
                    port = Integer.parseInt(socksProxyPort);
                } catch (NumberFormatException ignored) {
                }
            }
            log.info("检测到 SOCKS5 代理配置: {}:{}，Apache HttpClient 将通过代理连接", socksProxyHost, port);
            if (useAuth) {
                String sessionMode = (socksProxyUsername == null || socksProxyUsername.isEmpty())
                        ? "设备池每设备一个 session（固定出口 IPv6）"
                        : "固定 session: " + socksProxyUsername;
                log.info("SOCKS5 代理启用用户名/密码认证，session 策略: {}", sessionMode);
            }

            plainSocketFactory = new SocksProxyConnectionSocketFactory(socksProxyHost, port,
                    socksProxyUsername, socksProxyPassword);
            httpsSocketFactory = new SocksProxySSLConnectionSocketFactory(socksProxyHost, port,
                    socksProxyUsername, socksProxyPassword, sslContext);
            if (useAuth) {
                requestFactory = new SessionRoutingRequestFactory(baseRequestConfig);
            } else {
                requestFactory = new HttpComponentsClientHttpRequestFactory();
            }
        } else {
            plainSocketFactory = PlainConnectionSocketFactory.getSocketFactory();
            httpsSocketFactory = sslSocketFactory;
            requestFactory = new HttpComponentsClientHttpRequestFactory();
        }

        Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
            .register("http", plainSocketFactory)
            .register("https", httpsSocketFactory)
            .build();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
            socketFactoryRegistry, new DohDnsResolver(dohServer));
        connectionManager.setMaxTotal(maxConnections);
        connectionManager.setDefaultMaxPerRoute(maxConnectionsPerRoute);
        connectionManager.setValidateAfterInactivity(30000);

        CloseableHttpClient httpClient = HttpClientBuilder.create()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(baseRequestConfig)
            .evictIdleConnections(60, TimeUnit.SECONDS)
            .setConnectionTimeToLive(120, TimeUnit.SECONDS)
            .disableCookieManagement()
            .build();

        requestFactory.setHttpClient(httpClient);
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        requestFactory.setConnectionRequestTimeout(connectTimeoutMs);

        return new RestTemplate(requestFactory);
    }

    /**
     * 按当前线程的代理 session（设备 id）为请求附加合成 localAddress，
     * 使连接池按设备分桶、SOCKS5 认证用户名与设备一一对应。
     */
    private static final class SessionRoutingRequestFactory extends HttpComponentsClientHttpRequestFactory {

        private final RequestConfig baseRequestConfig;

        SessionRoutingRequestFactory(RequestConfig baseRequestConfig) {
            this.baseRequestConfig = baseRequestConfig;
        }

        @Override
        protected void postProcessHttpRequest(HttpUriRequest request) {
            String sessionId = ProxySessionContext.get();
            if (sessionId == null || sessionId.isEmpty()
                    || !(request instanceof org.apache.http.client.methods.HttpRequestBase)) {
                return;
            }
            InetAddress synthetic = syntheticAddressFor(sessionId);
            ((org.apache.http.client.methods.HttpRequestBase) request).setConfig(RequestConfig.copy(baseRequestConfig)
                .setLocalAddress(synthetic)
                .build());
        }
    }

    /**
     * 通过 SOCKS5 代理建立 HTTP 连接的 ConnectionSocketFactory
     * <p>
     * 手动实现 SOCKS5 协议握手，不依赖 java.net.Socket(Proxy)，
     * 避免与 JVM 系统属性 -DsocksProxyHost 产生冲突。
     */
    private static class SocksProxyConnectionSocketFactory implements ConnectionSocketFactory {
        protected final String proxyHost;
        protected final int proxyPort;
        protected final String proxyUsername;
        protected final String proxyPassword;

        SocksProxyConnectionSocketFactory(String proxyHost, int proxyPort, String proxyUsername, String proxyPassword) {
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.proxyUsername = proxyUsername;
            this.proxyPassword = proxyPassword;
        }

        @Override
        public java.net.Socket createSocket(org.apache.http.protocol.HttpContext context) {
            return new java.net.Socket();
        }

        @Override
        public java.net.Socket connectSocket(int connectTimeout, java.net.Socket socket,
                org.apache.http.HttpHost host, java.net.InetSocketAddress remoteAddress,
                java.net.InetSocketAddress localAddress, org.apache.http.protocol.HttpContext context)
                throws java.io.IOException {
            java.net.Socket sock = socket != null ? socket : createSocket(context);
            try {
                // localAddress 仅是 session 路由键（127.x.y.z），代理模式下不真正 bind
                String sessionId = resolveSessionId(localAddress);
                socks5Connect(sock, host.getHostName(), host.getPort(), connectTimeout, this.proxyHost, this.proxyPort,
                        sessionId, this.proxyPassword);
            } catch (java.io.IOException e) {
                try {
                    sock.close();
                } catch (java.io.IOException ignored) {
                }
                throw e;
            }
            return sock;
        }

        protected String resolveSessionId(java.net.InetSocketAddress localAddress) {
            String sessionId = sessionIdOf(localAddress == null ? null : localAddress.getAddress());
            return sessionId != null ? sessionId : nextSessionId(proxyUsername);
        }
    }

    /**
     * 通过 SOCKS5 代理建立 HTTPS 连接的 ConnectionSocketFactory
     * <p>
     * 手动实现 SOCKS5 协议握手，不依赖 java.net.Socket(Proxy)，
     * 避免与 JVM 系统属性 -DsocksProxyHost 产生冲突导致 TLS 连接到错误目标。
     */
    private static class SocksProxySSLConnectionSocketFactory extends SSLConnectionSocketFactory {
        private final SocksProxyConnectionSocketFactory delegate;

        SocksProxySSLConnectionSocketFactory(String proxyHost, int proxyPort, String proxyUsername,
                String proxyPassword, SSLContext sslContext) {
            super(sslContext);
            this.delegate = new SocksProxyConnectionSocketFactory(proxyHost, proxyPort, proxyUsername, proxyPassword);
        }

        @Override
        public java.net.Socket createSocket(org.apache.http.protocol.HttpContext context) {
            return new java.net.Socket();
        }

        @Override
        public java.net.Socket connectSocket(int connectTimeout, java.net.Socket socket,
                org.apache.http.HttpHost host, java.net.InetSocketAddress remoteAddress,
                java.net.InetSocketAddress localAddress, org.apache.http.protocol.HttpContext context)
                throws java.io.IOException {
            java.net.Socket sock = socket != null ? socket : createSocket(context);
            try {
                String sessionId = delegate.resolveSessionId(localAddress);
                socks5Connect(sock, host.getHostName(), host.getPort(), connectTimeout,
                        delegate.proxyHost, delegate.proxyPort, sessionId, delegate.proxyPassword);
            } catch (java.io.IOException e) {
                try {
                    sock.close();
                } catch (java.io.IOException ignored) {
                }
                throw e;
            }
            return super.createLayeredSocket(sock, host.getHostName(), host.getPort(), context);
        }
    }

    /**
     * 计算无设备上下文连接使用的 session id（即 SOCKS5 认证用户名）。
     * 未配置固定用户名时每条连接生成随机 session id，等价于"每条新连接换一个出口 IPv6"。
     */
    private static String nextSessionId(String configuredUsername) {
        if (configuredUsername != null && !configuredUsername.isEmpty()) {
            return configuredUsername;
        }
        return "fq-" + Long.toHexString(System.nanoTime()) + "-"
                + Integer.toHexString(new java.security.SecureRandom().nextInt());
    }

    /**
     * 手动执行 SOCKS5 协议握手（支持无认证与 RFC 1929 用户名/密码认证）。
     * <ol>
     *   <li>TCP 连接到代理服务器</li>
     *   <li>发送 SOCKS5 握手请求（版本+候选认证方法）</li>
     *   <li>接收代理的认证方法选择；若选择用户名/密码则执行 RFC 1929 子协商</li>
     *   <li>发送 CONNECT 命令到目标主机</li>
     *   <li>接收代理的连接确认</li>
     * </ol>
     *
     * @param sock           未连接的 socket
     * @param targetHost     目标主机名
     * @param targetPort     目标端口
     * @param connectTimeout 连接超时（毫秒）
     * @param username       认证用户名（session id）
     * @param password       认证密码，为 null/空表示仅尝试无认证
     */
    private static void socks5Connect(java.net.Socket sock, String targetHost, int targetPort, int connectTimeout,
            String proxyHost, int proxyPort, String username, String password) throws java.io.IOException {
        boolean useAuth = password != null && !password.isEmpty();

        // 1. 连接到 SOCKS5 代理服务器
        java.net.SocketAddress proxyAddr = new java.net.InetSocketAddress(proxyHost, proxyPort);
        sock.connect(proxyAddr, connectTimeout);
        sock.setSoTimeout(connectTimeout);

        java.io.InputStream in = sock.getInputStream();
        java.io.OutputStream out = sock.getOutputStream();

        // 2. SOCKS5 握手: 版本 5，候选认证方法（无认证 0x00 + 用户名/密码 0x02）
        if (useAuth) {
            out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        } else {
            out.write(new byte[]{0x05, 0x01, 0x00});
        }
        out.flush();

        // 3. 读取代理响应: 版本 + 选择的认证方法
        byte[] authResponse = new byte[2];
        readFully(in, authResponse);
        if (authResponse[0] != 0x05) {
            throw new java.io.IOException("SOCKS5 代理返回异常版本: 0x" + Integer.toHexString(authResponse[0] & 0xFF));
        }
        int selectedMethod = authResponse[1] & 0xFF;
        if (selectedMethod == 0xFF) {
            throw new java.io.IOException("SOCKS5 代理不接受任何候选认证方法"
                    + (useAuth ? "（已提供无认证+用户名/密码）" : "（仅提供无认证，若代理需要认证请配置 PROXY_PASSWORD）"));
        }
        if (selectedMethod == 0x02) {
            if (!useAuth) {
                throw new java.io.IOException("SOCKS5 代理要求用户名/密码认证，但未配置 PROXY_PASSWORD");
            }
            performUsernamePasswordAuth(in, out, username, password);
        } else if (selectedMethod != 0x00) {
            throw new java.io.IOException("SOCKS5 代理要求不支持的认证方法: 0x" + Integer.toHexString(selectedMethod));
        }

        // 4. 发送 CONNECT 命令（域名类型 0x03）
        byte[] hostBytes = targetHost.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (hostBytes.length > 255) {
            throw new java.io.IOException("目标主机名过长: " + targetHost);
        }
        byte[] connectRequest = new byte[7 + hostBytes.length];
        connectRequest[0] = 0x05; // 版本
        connectRequest[1] = 0x01; // CONNECT
        connectRequest[2] = 0x00; // 保留
        connectRequest[3] = 0x03; // 地址类型: 域名
        connectRequest[4] = (byte) hostBytes.length; // 域名长度
        System.arraycopy(hostBytes, 0, connectRequest, 5, hostBytes.length);
        connectRequest[5 + hostBytes.length] = (byte) (targetPort >> 8);   // 端口高字节
        connectRequest[6 + hostBytes.length] = (byte) (targetPort & 0xFF); // 端口低字节
        out.write(connectRequest);
        out.flush();

        // 5. 读取 CONNECT 响应（前 4 字节头部 + 地址 + 端口）
        byte[] connectResponseHeader = new byte[4];
        readFully(in, connectResponseHeader);
        if (connectResponseHeader[0] != 0x05) {
            throw new java.io.IOException("SOCKS5 CONNECT 响应版本异常: 0x" + Integer.toHexString(connectResponseHeader[0] & 0xFF));
        }
        int replyCode = connectResponseHeader[1] & 0xFF;
        if (replyCode != 0x00) {
            String errorMsg;
            switch (replyCode) {
                case 0x01: errorMsg = "通用失败"; break;
                case 0x02: errorMsg = "连接被拒绝（规则集）"; break;
                case 0x03: errorMsg = "网络不可达"; break;
                case 0x04: errorMsg = "主机不可达"; break;
                case 0x05: errorMsg = "连接被拒绝"; break;
                case 0x06: errorMsg = "TTL 过期"; break;
                case 0x07: errorMsg = "不支持的命令"; break;
                case 0x08: errorMsg = "不支持的地址类型"; break;
                default: errorMsg = "未知错误码: " + replyCode; break;
            }
            throw new java.io.IOException("SOCKS5 代理连接目标失败 [" + errorMsg + "]: " + targetHost + ":" + targetPort);
        }

        // 跳过剩余的地址/端口字段（根据地址类型可变长度）
        int addrType = connectResponseHeader[3] & 0xFF;
        int skipBytes;
        switch (addrType) {
            case 0x01: skipBytes = 4; break; // IPv4: 4 bytes
            case 0x03: skipBytes = 1 + (in.read() & 0xFF); break; // 域名: 1 + len bytes
            case 0x04: skipBytes = 16; break; // IPv6: 16 bytes
            default: skipBytes = 0; break;
        }
        skipFully(in, skipBytes + 2); // +2 for port (2 bytes)

        // 恢复默认超时（后续 SSL 握手会自行设置）
        sock.setSoTimeout(0);
        log.debug("SOCKS5 隧道已建立: {} (session={}) -> {}:{}", proxyHost + ":" + proxyPort, username, targetHost, targetPort);
    }

    /**
     * RFC 1929 用户名/密码子协商。
     * 发送 VER(0x01) + ULEN + UNAME + PLEN + PASSWD，期待响应 VER(0x01) + STATUS(0x00=成功)。
     */
    private static void performUsernamePasswordAuth(java.io.InputStream in, java.io.OutputStream out,
            String username, String password) throws java.io.IOException {
        if (username == null || username.isEmpty()) {
            throw new java.io.IOException("SOCKS5 用户名（session id）不能为空");
        }
        byte[] userBytes = username.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] passBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (userBytes.length > 255 || passBytes.length > 255) {
            throw new java.io.IOException("SOCKS5 用户名或密码超过 255 字节限制");
        }

        byte[] authRequest = new byte[3 + userBytes.length + passBytes.length];
        int idx = 0;
        authRequest[idx++] = 0x01; // 子协商版本
        authRequest[idx++] = (byte) userBytes.length;
        System.arraycopy(userBytes, 0, authRequest, idx, userBytes.length);
        idx += userBytes.length;
        authRequest[idx++] = (byte) passBytes.length;
        System.arraycopy(passBytes, 0, authRequest, idx, passBytes.length);
        out.write(authRequest);
        out.flush();

        byte[] authResult = new byte[2];
        readFully(in, authResult);
        if ((authResult[1] & 0xFF) != 0x00) {
            throw new java.io.IOException("SOCKS5 代理认证失败（用户名/密码错误，status=0x"
                    + Integer.toHexString(authResult[1] & 0xFF) + "）");
        }
    }

    /**
     * 从 InputStream 读取指定字节数，不足则抛出异常。
     */
    private static void readFully(java.io.InputStream in, byte[] buf) throws java.io.IOException {
        int offset = 0;
        int len = buf.length;
        while (len > 0) {
            int read = in.read(buf, offset, len);
            if (read < 0) {
                throw new java.io.IOException("SOCKS5 代理连接意外关闭");
            }
            offset += read;
            len -= read;
        }
    }

    /**
     * 跳过指定数量的字节。
     */
    private static void skipFully(java.io.InputStream in, long n) throws java.io.IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                int read = in.read();
                if (read < 0) {
                    throw new java.io.IOException("SOCKS5 代理连接意外关闭");
                }
                n--;
            } else {
                n -= skipped;
            }
        }
    }
}
