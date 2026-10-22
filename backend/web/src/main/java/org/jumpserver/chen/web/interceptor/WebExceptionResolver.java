package org.jumpserver.chen.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.sql.SQLException;


@Component
@Slf4j
public class WebExceptionResolver implements HandlerExceptionResolver {
    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        boolean permissionDenied = isDatabasePermissionDenied(ex);
        if (permissionDenied || ex instanceof ChenException) {
            response.setStatus(permissionDenied ? 403 : 500);
            response.setContentType("text/plain");
            response.setCharacterEncoding("UTF-8");
            try {
                response.getWriter().write(permissionDenied
                        ? "权限不足，无法访问该数据库或资源，请联系数据库管理员授权。"
                        : ex.getMessage());
            } catch (IOException e) {
                log.error(e.getMessage());
            }
        } else {
            log.error(ex.getMessage(), ex);
        }
        return new ModelAndView();
    }

    private boolean isDatabasePermissionDenied(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof SQLException sqlException)) {
                continue;
            }
            for (SQLException sql = sqlException; sql != null; sql = sql.getNextException()) {
                // SQL Server cannot open a database for this login (4060),
                // cannot access it (916), or denies an operation (229).
                int code = sql.getErrorCode();
                if (code == 4060 || code == 916 || code == 229
                        || "42501".equals(sql.getSQLState())
                        || (code == 1031 && "42000".equals(sql.getSQLState()))) {
                    return true;
                }
            }
        }
        return false;
    }
}
