package org.jumpserver.chen.web.config;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.ws.ConsoleWebSocketHandler;
import org.jumpserver.chen.framework.ws.DBConsoleWebsocketHandler;
import org.jumpserver.chen.framework.ws.SessionWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Configuration
@Slf4j
public class WebSocketConfig {


    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024 * 1024);
        // 可选：异步发送超时
        // container.setAsyncSendTimeout(20_000L);
        return container;
    }


    @Bean
    public WebSocketHandlerMapping chenWebSocketHandlerMapping() {
        var handlers = new LinkedHashMap<String, Object>();
        handlers.put("/ws/session", createRequestHandler(new SessionWebSocketHandler()));
        handlers.put("/ws/console", createRequestHandler(new ConsoleWebSocketHandler()));
        handlers.put("/ws/db-console", createRequestHandler(new DBConsoleWebsocketHandler()));

        var mapping = new WebSocketHandlerMapping();
        // 与 Spring 默认 WebSocket 映射一致，确保 WS 请求优先于普通 MVC 映射处理。
        mapping.setOrder(1);
        mapping.setUrlMap(handlers);
        return mapping;
    }

    private WebSocketHttpRequestHandler createRequestHandler(WebSocketHandler webSocketHandler) {
        var requestHandler = new WebSocketHttpRequestHandler(webSocketHandler);
        // 仅使用 Chen 的动态校验，避免 WebSocketHandlerRegistry 追加第二个 Origin 拦截器。
        requestHandler.setHandshakeInterceptors(List.of(new ServletWebSocketHandshakeInterceptor()));
        return requestHandler;
    }

    // 仅用于跨 Host 请求的精确白名单，格式为逗号分隔的 host 或 host:port。
    private static final Set<String> TRUSTED_DOMAINS =
            Arrays.stream(
                            Optional.ofNullable(System.getenv("DOMAINS"))
                                    .orElse("")
                                    .split(",")
                    )
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());


    static boolean checkOrigin(String origin, InetSocketAddress requestHost, Set<String> trustedDomains) {
        if (origin == null || origin.isBlank()) {
            return true;
        }

        if (trustedDomains.contains("*")) {
            return true;
        }

        try {
            URI uri = URI.create(origin);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }

            String originHost = normalizeHost(uri.getHost());
            if (originHost == null) {
                return false;
            }

            String normalizedRequestHost = requestHost == null
                    ? null
                    : normalizeHost(requestHost.getHostString());
            // 仅比较 Host，兼容 TLS 在代理终止后转发到 Chen 内部端口的场景。
            if (originHost.equals(normalizedRequestHost)) {
                return true;
            }

            // 本机访问直接放行
            if (isLocalhost(originHost)) {
                return true;
            }

            return matchesTrustedDomains(uri, originHost, trustedDomains);

        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean matchesTrustedDomains(URI origin, String originHost, Set<String> trustedDomains) {
        // 跨 Host 请求必须精确匹配 DOMAINS，禁止使用后缀或子串匹配。
        if (containsIgnoreCase(trustedDomains, originHost)) {
            return true;
        }

        int port = origin.getPort();
        return port >= 0 && containsIgnoreCase(trustedDomains, formatHostAndPort(originHost, port));
    }

    private static boolean containsIgnoreCase(Set<String> domains, String value) {
        return domains.stream()
                .map(String::trim)
                .anyMatch(domain -> domain.equalsIgnoreCase(value));
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }

        String normalizedHost = host.trim();
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        return normalizedHost.toLowerCase(Locale.ROOT);
    }

    private static boolean isLocalhost(String host) {
        return "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    private static String formatHostAndPort(String host, int port) {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }

    public static class ServletWebSocketHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {

            String origin = request.getHeaders().getOrigin();
            InetSocketAddress requestHost = request.getHeaders().getHost();
            // 某些 Servlet 请求对象未填充 Host header，回退到容器解析到的主机和端口。
            if (requestHost == null && request instanceof ServletServerHttpRequest servletRequest) {
                requestHost = new InetSocketAddress(
                        servletRequest.getServletRequest().getServerName(),
                        servletRequest.getServletRequest().getServerPort());
            }

            if (!checkOrigin(origin, requestHost, TRUSTED_DOMAINS)) {
                log.warn("Reject WebSocket handshake: untrusted or invalid origin");
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }

            var protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
            // 拒绝缺失子协议的异常握手，避免读取空列表导致 NPE。
            if (protocols == null || protocols.isEmpty()) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }

            var token = protocols.get(0);
            var session = SessionManager.getSession(token);
            var servletRequest = request instanceof ServletServerHttpRequest servletRequestWrapper
                    ? servletRequestWrapper.getServletRequest() : null;
            var httpSession = servletRequest == null ? null : servletRequest.getSession(false);
            var sessionBound = session != null && httpSession != null && Objects.equals(
                    session.getAttribute(SessionManager.WEB_SESSION_ID_ATTRIBUTE), httpSession.getId());
            // token 必须仍存活，且必须来自创建它的同一浏览器 HTTP session。
            if (!sessionBound) {
                // 仅输出校验维度，不记录 token、Cookie 或 HTTP session ID 等敏感信息。
                log.warn("Reject WebSocket handshake: tokenExists={}, httpSessionExists={}, sessionBound={}",
                        session != null, httpSession != null, sessionBound);
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            log.info("Accept WebSocket handshake: HTTP session binding verified");
            attributes.put("token", token);
            response.getHeaders().put("Sec-WebSocket-Protocol", protocols);
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
            //握手之后
        }
    }
}
