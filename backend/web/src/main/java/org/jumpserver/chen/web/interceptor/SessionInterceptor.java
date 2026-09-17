package org.jumpserver.chen.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.jumpserver.chen.framework.session.SessionManager;


public class SessionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        //0. 如果是登录请求，直接放行
        if (!req.getServletPath().startsWith("/api") || req.getServletPath().equals("/api/auth")) {
            return true;
        }
        //1. 从header 中获取 token
        String token = req.getHeader("token");
        //2. /api/profile 等请求发生在 websocket 建立前，此时 session 已创建但尚未 active。
        if (token == null || token.isEmpty() || SessionManager.getSession(token) == null) {
            //2.1 认证失败，返回错误信息
            resp.setStatus(401);
            resp.getWriter().write("Unauthorized");
            return false;
        }

        SessionManager.setContext(token);
        return true;
    }

}
