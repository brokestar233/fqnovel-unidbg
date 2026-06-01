package com.anjia.unidbgserver.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.*;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTPS / TLS 动态配置
 * <p>
 * 通过环境变量控制，仅在 SSL_ENABLED=true 且证书文件存在时启用 SSL。
 * 使用 openssl 将任意格式私钥转换为 PKCS#8，再构建 PKCS12 keystore 配置 Tomcat。
 * <p>
 * 环境变量：
 * <ul>
 *   <li>SSL_ENABLED - 是否启用 HTTPS（true/false，默认 false）</li>
 *   <li>SSL_CERTIFICATE - TLS 证书文件路径（PEM 格式，默认 /etc/brokestar.crt）</li>
 *   <li>SSL_KEY - TLS 私钥文件路径（PEM 格式，默认 /etc/brokestar.key）</li>
 * </ul>
 */
@Slf4j
@Configuration
public class SslConfig {

    private static final String PKCS12_PASSWORD = "changeit";

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> sslCustomizer(
            @Value("${SSL_ENABLED:false}") boolean sslEnabled,
            @Value("${SSL_CERTIFICATE:/etc/brokestar.crt}") String certPath,
            @Value("${SSL_KEY:/etc/brokestar.key}") String keyPath) {

        return factory -> {
            if (!sslEnabled) {
                log.info("SSL 未启用（SSL_ENABLED=false），使用 HTTP 模式");
                return;
            }

            File certFile = new File(certPath);
            File keyFile = new File(keyPath);

            if (!certFile.exists() || !certFile.isFile()) {
                log.warn("SSL 证书文件不存在或不是文件: {}，回退到 HTTP 模式", certPath);
                return;
            }
            if (!keyFile.exists() || !keyFile.isFile()) {
                log.warn("SSL 私钥文件不存在或不是文件: {}，回退到 HTTP 模式", keyPath);
                return;
            }

            try {
                log.info("正在配置 HTTPS/TLS 1.3...");
                log.info("  证书: {}", certPath);
                log.info("  私钥: {}", keyPath);

                // 从 PEM 文件加载证书链
                List<Certificate> certChain = loadCertificates(certFile);

                // 使用 openssl 将私钥转换为 PKCS#8 格式，再加载
                PrivateKey privateKey = loadPrivateKeyWithOpenSsl(keyFile);

                // 创建 PKCS12 keystore
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(null, null);
                keyStore.setKeyEntry("server", privateKey, PKCS12_PASSWORD.toCharArray(),
                        certChain.toArray(new Certificate[0]));

                // 写入临时文件
                File tempKeyStore = File.createTempFile("ssl-keystore-", ".p12");
                tempKeyStore.deleteOnExit();
                try (FileOutputStream fos = new FileOutputStream(tempKeyStore)) {
                    keyStore.store(fos, PKCS12_PASSWORD.toCharArray());
                }

                // 配置 SSL
                Ssl ssl = new Ssl();
                ssl.setEnabled(true);
                ssl.setKeyStore(tempKeyStore.getAbsolutePath());
                ssl.setKeyStoreType("PKCS12");
                ssl.setKeyStorePassword(PKCS12_PASSWORD);
                ssl.setEnabledProtocols(new String[]{"TLSv1.3"});

                factory.setSsl(ssl);

                log.info("HTTPS/TLS 1.3 配置完成");
            } catch (Exception e) {
                log.error("SSL 配置失败，回退到 HTTP 模式: {}", e.getMessage(), e);
            }
        };
    }

    /**
     * 从 PEM 文件加载证书链
     */
    private List<Certificate> loadCertificates(File certFile) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (FileInputStream fis = new FileInputStream(certFile)) {
            List<Certificate> certs = new ArrayList<>();
            for (Certificate cert : cf.generateCertificates(fis)) {
                certs.add(cert);
            }
            if (certs.isEmpty()) {
                throw new IllegalArgumentException("PEM 证书文件中未找到任何证书: " + certFile);
            }
            return certs;
        }
    }

    /**
     * 使用 openssl 将私钥转换为 PKCS#8 格式后加载
     * <p>
     * 支持所有 openssl 能识别的私钥格式（PKCS#1 RSA、SEC 1 EC、PKCS#8 等）。
     * openssl pkcs8 -topk8 -nocrypt 会自动处理格式转换。
     */
    private PrivateKey loadPrivateKeyWithOpenSsl(File keyFile) throws Exception {
        // 使用 openssl 将私钥转换为 PKCS#8 DER 格式
        ProcessBuilder pb = new ProcessBuilder(
                "openssl", "pkcs8", "-topk8", "-nocrypt",
                "-in", keyFile.getAbsolutePath(),
                "-outform", "DER"
        );
        pb.redirectErrorStream(false);

        Process process = pb.start();

        // 读取 stdout（PKCS#8 DER 字节）
        byte[] pkcs8Bytes;
        try (InputStream is = process.getInputStream()) {
            pkcs8Bytes = is.readAllBytes();
        }

        // 读取 stderr（openssl 错误信息）
        String stderr;
        try (InputStream es = process.getErrorStream()) {
            stderr = new String(es.readAllBytes());
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("openssl pkcs8 转换失败 (exit=" + exitCode + "): " + stderr);
        }

        if (pkcs8Bytes.length == 0) {
            throw new RuntimeException("openssl pkcs8 输出为空");
        }

        log.debug("openssl 已将私钥转换为 PKCS#8 DER 格式 ({} bytes)", pkcs8Bytes.length);

        // 使用 PKCS8EncodedKeySpec 解析
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Bytes);

        // 先尝试 RSA，失败再尝试 EC
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (Exception e) {
            log.debug("RSA KeyFactory 失败，尝试 EC: {}", e.getMessage());
            return KeyFactory.getInstance("EC").generatePrivate(keySpec);
        }
    }
}
