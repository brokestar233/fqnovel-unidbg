package com.anjia.unidbgserver.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.conn.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.ProxySelector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 DoH (DNS over HTTPS) 的 DNS 解析器
 * <p>
 * 通过环境变量 DOH_SERVER 配置 DoH 服务器地址，默认 https://1.12.12.12/dns-query
 * 使用 JSON API (application/dns-json) 格式进行 DNS 查询
 * <p>
 * 同时查询 A (IPv4) 和 AAAA (IPv6) 记录，IPv6 优先（适配 IPv6 SOCKS5 代理场景）
 */
@Slf4j
public class DohDnsResolver implements DnsResolver {

    private final String dohServer;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, InetAddress[]> cache = new ConcurrentHashMap<>();

    public DohDnsResolver(String dohServer) {
        this.dohServer = dohServer;
        this.objectMapper = new ObjectMapper();

        // DoH 请求直连，不走 SOCKS5 代理（DoH 服务器如 1.12.12.12 是国内公共 DNS，应直连）
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .proxy(ProxySelector.of(null))
            .build();
        log.info("DoH DNS 解析器已初始化，服务器: {}", dohServer);
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        // 先检查缓存
        InetAddress[] cached = cache.get(host);
        if (cached != null) {
            return cached;
        }

        try {
            InetAddress[] addresses = dohResolve(host);
            if (addresses != null && addresses.length > 0) {
                cache.put(host, addresses);
                return addresses;
            }
        } catch (Exception e) {
            log.warn("DoH DNS 解析失败，回退到系统 DNS - host: {}, error: {}", host, e.getMessage());
        }

        // 回退到系统 DNS
        return InetAddress.getAllByName(host);
    }

    /**
     * 通过 DoH 解析域名
     * 同时查询 A 和 AAAA 记录，IPv6 优先
     */
    private InetAddress[] dohResolve(String host) throws Exception {
        List<InetAddress> ipv4Addresses = new ArrayList<>();
        List<InetAddress> ipv6Addresses = new ArrayList<>();

        // 查询 A 记录 (IPv4)
        try {
            queryDoh(host, "A", ipv4Addresses);
        } catch (Exception e) {
            log.debug("DoH A 记录查询失败 - host: {}, error: {}", host, e.getMessage());
        }

        // 查询 AAAA 记录 (IPv6)
        try {
            queryDoh(host, "AAAA", ipv6Addresses);
        } catch (Exception e) {
            log.debug("DoH AAAA 记录查询失败 - host: {}, error: {}", host, e.getMessage());
        }

        // IPv6 优先（适配 IPv6 SOCKS5 代理场景）
        List<InetAddress> allAddresses = new ArrayList<>();
        allAddresses.addAll(ipv6Addresses);
        allAddresses.addAll(ipv4Addresses);

        if (allAddresses.isEmpty()) {
            return null;
        }

        log.debug("DoH DNS 解析成功 - host: {}, ipv6: {}, ipv4: {}, result: {}",
            host, ipv6Addresses.size(), ipv4Addresses.size(), allAddresses);
        return allAddresses.toArray(new InetAddress[0]);
    }

    /**
     * 执行单次 DoH 查询
     */
    private void queryDoh(String host, String type, List<InetAddress> results) throws Exception {
        // type 参数: A=1, AAAA=28
        String dohType = "AAAA".equals(type) ? "28" : "1";
        String url = dohServer + "?name=" + host + "&type=" + dohType;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/dns-json")
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.debug("DoH 查询返回非 200 状态码: {}, host: {}, type: {}", response.statusCode(), host, type);
            return;
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode answer = root.get("Answer");
        if (answer == null || !answer.isArray() || answer.isEmpty()) {
            return;
        }

        int expectedType = "AAAA".equals(type) ? 28 : 1;
        for (JsonNode record : answer) {
            int recordType = record.has("type") ? record.get("type").asInt() : 0;
            String data = record.has("data") ? record.get("data").asText() : null;

            if (recordType == expectedType && data != null) {
                try {
                    results.add(InetAddress.getByName(data));
                } catch (Exception ignored) {
                }
            }
        }
    }
}
