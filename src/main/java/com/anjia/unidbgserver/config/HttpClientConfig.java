package com.anjia.unidbgserver.config;

import lombok.extern.slf4j.Slf4j;
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

import org.apache.http.client.config.RequestConfig;

import javax.net.ssl.SSLContext;
import java.util.concurrent.TimeUnit;

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

    @Bean
    public RestTemplate restTemplate() throws Exception {
        SSLContext sslContext = SSLContextBuilder.create()
            .build();

        SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext);

        ConnectionSocketFactory plainSocketFactory;
        ConnectionSocketFactory httpsSocketFactory;

        if (socksProxyHost != null && !socksProxyHost.isEmpty()) {
            int port = 1080;
            if (socksProxyPort != null && !socksProxyPort.isEmpty()) {
                try {
                    port = Integer.parseInt(socksProxyPort);
                } catch (NumberFormatException ignored) {
                }
            }
            log.info("检测到 SOCKS5 代理配置: {}:{}，Apache HttpClient 将通过代理连接", socksProxyHost, port);

            plainSocketFactory = new SocksProxyConnectionSocketFactory(socksProxyHost, port);
            httpsSocketFactory = new SocksProxySSLConnectionSocketFactory(socksProxyHost, port, sslContext);
        } else {
            plainSocketFactory = PlainConnectionSocketFactory.getSocketFactory();
            httpsSocketFactory = sslSocketFactory;
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

        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(connectTimeoutMs)
            .setSocketTimeout(readTimeoutMs)
            .setConnectionRequestTimeout(connectTimeoutMs)
            .build();

        CloseableHttpClient httpClient = HttpClientBuilder.create()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .evictIdleConnections(60, TimeUnit.SECONDS)
            .setConnectionTimeToLive(120, TimeUnit.SECONDS)
            .disableCookieManagement()
            .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        factory.setConnectionRequestTimeout(connectTimeoutMs);

        return new RestTemplate(factory);
    }

    /**
     * 通过 SOCKS5 代理建立 HTTP 连接的 ConnectionSocketFactory
     * <p>
     * 手动实现 SOCKS5 协议握手，不依赖 java.net.Socket(Proxy)，
     * 避免与 JVM 系统属性 -DsocksProxyHost 产生冲突。
     */
    private static class SocksProxyConnectionSocketFactory implements ConnectionSocketFactory {
        private final String proxyHost;
        private final int proxyPort;

        SocksProxyConnectionSocketFactory(String proxyHost, int proxyPort) {
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
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
            if (localAddress != null) {
                sock.bind(localAddress);
            }
            try {
                socks5Connect(sock, host.getHostName(), host.getPort(), connectTimeout, this.proxyHost, this.proxyPort);
            } catch (java.io.IOException e) {
                try {
                    sock.close();
                } catch (java.io.IOException ignored) {
                }
                throw e;
            }
            return sock;
        }
    }

    /**
     * 通过 SOCKS5 代理建立 HTTPS 连接的 ConnectionSocketFactory
     * <p>
     * 手动实现 SOCKS5 协议握手，不依赖 java.net.Socket(Proxy)，
     * 避免与 JVM 系统属性 -DsocksProxyHost 产生冲突导致 TLS 连接到错误目标。
     */
    private static class SocksProxySSLConnectionSocketFactory extends SSLConnectionSocketFactory {
        private final String proxyHost;
        private final int proxyPort;

        SocksProxySSLConnectionSocketFactory(String proxyHost, int proxyPort, SSLContext sslContext) {
            super(sslContext);
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
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
            if (localAddress != null) {
                sock.bind(localAddress);
            }
            try {
                socks5Connect(sock, host.getHostName(), host.getPort(), connectTimeout, this.proxyHost, this.proxyPort);
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
     * 手动执行 SOCKS5 协议握手（无认证模式）。
     * <ol>
     *   <li>TCP 连接到代理服务器</li>
     *   <li>发送 SOCKS5 握手请求（版本+认证方法）</li>
     *   <li>接收代理的认证方法选择</li>
     *   <li>发送 CONNECT 命令到目标主机</li>
     *   <li>接收代理的连接确认</li>
     * </ol>
     *
     * @param sock           未连接的 socket
     * @param targetHost     目标主机名
     * @param targetPort     目标端口
     * @param connectTimeout 连接超时（毫秒）
     */
    private static void socks5Connect(java.net.Socket sock, String targetHost, int targetPort, int connectTimeout,
            String proxyHost, int proxyPort) throws java.io.IOException {
        // 1. 连接到 SOCKS5 代理服务器
        java.net.SocketAddress proxyAddr = new java.net.InetSocketAddress(proxyHost, proxyPort);
        sock.connect(proxyAddr, connectTimeout);
        sock.setSoTimeout(connectTimeout);

        java.io.InputStream in = sock.getInputStream();
        java.io.OutputStream out = sock.getOutputStream();

        // 2. SOCKS5 握手: 版本 5，1 种认证方法（无认证 = 0x00）
        out.write(new byte[]{0x05, 0x01, 0x00});
        out.flush();

        // 3. 读取代理响应: 版本 + 选择的认证方法
        byte[] authResponse = new byte[2];
        readFully(in, authResponse);
        if (authResponse[0] != 0x05) {
            throw new java.io.IOException("SOCKS5 代理返回异常版本: 0x" + Integer.toHexString(authResponse[0] & 0xFF));
        }
        if (authResponse[1] != 0x00) {
            throw new java.io.IOException("SOCKS5 代理要求不支持的认证方法: 0x" + Integer.toHexString(authResponse[1] & 0xFF));
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
        log.debug("SOCKS5 隧道已建立: {} -> {}:{}", proxyHost + ":" + proxyPort, targetHost, targetPort);
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
