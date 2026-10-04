package com.anjia.unidbgserver.config;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 请求结束时清理代理 session 上下文。
 * <p>
 * Tomcat 线程会被复用，若不清理，上一个请求选中的设备 session
 * 会泄漏到同线程后续未选择设备的请求，导致出口 IPv6 绑定错乱。
 */
@Component
public class ProxySessionContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            ProxySessionContext.clear();
        }
    }
}
