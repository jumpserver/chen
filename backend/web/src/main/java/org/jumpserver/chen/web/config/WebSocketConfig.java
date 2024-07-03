package org.jumpserver.chen.web.config;

import org.jumpserver.chen.framework.ws.ConsoleWebSocketHandler;
import org.jumpserver.chen.framework.ws.DBConsoleWebsocketHandler;
import org.jumpserver.chen.framework.ws.SessionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Objects;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
                .addHandler(new ConsoleWebSocketHandler(), "/ws/console")
                .addHandler(new SessionWebSocketHandler(), "/ws/session")
                .addHandler(new DBConsoleWebsocketHandler(), "/ws/db-console")
                .addInterceptors(new ServletWebSocketHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    public static class ServletWebSocketHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
            var token = request.getHeaders().get("Sec-WebSocket-Protocol").get(0);
            attributes.put("token", token);
            response.getHeaders().put("Sec-WebSocket-Protocol", Objects.requireNonNull(request.getHeaders().get("Sec-WebSocket-Protocol")));
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
            //握手之后
        }
    }
}