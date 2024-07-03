package org.jumpserver.chen.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;


@Component
@Slf4j
public class WebExceptionResolver implements HandlerExceptionResolver {
    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (ex instanceof ChenException) {
            response.setStatus(500);
            response.setHeader("Content-Type", "application/json");
            try {
                response.getWriter().write(ex.getMessage());
            } catch (IOException e) {
                log.error(e.getMessage());
            }
        } else {
            log.error(ex.getMessage(), ex);
        }
        return new ModelAndView();
    }
}
