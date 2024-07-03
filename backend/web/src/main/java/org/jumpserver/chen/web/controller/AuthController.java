package org.jumpserver.chen.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.entity.AuthRequest;
import org.jumpserver.chen.web.entity.AuthResponse;
import org.jumpserver.chen.web.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private SessionService sessionService;

    @PostMapping("")
    public AuthResponse auth(HttpServletRequest request, @RequestBody AuthRequest authRequest) {
        String token = authRequest.getToken();
        Session sess = sessionService.createNewSession(token, getRemoteAddr(request));

        sess.setEnableAutoComplete(!authRequest.isDisableAutoHash());

        var lang = getLanguage(request);
        sess.setLocale(lang);

        var chenToken = SessionManager.registerSession(sess);
        return new AuthResponse(chenToken, lang.toLanguageTag());
    }

    private String getRemoteAddr(HttpServletRequest request) {
        if (request.getHeader("x-forwarded-for") == null) {
            return request.getRemoteAddr();
        }
        var remotes = request.getHeader("x-forwarded-for").split(",");
        if (remotes.length > 0) {
            return remotes[0];
        }
        return request.getHeader("x-forwarded-for");
    }

    private Locale getLanguage(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies != null) {
            for (var cookie : cookies) {
                if (cookie.getName().equals("django_language")) {
                    switch (cookie.getValue()) {
                        case "en":
                            return Locale.US;
                        case "ja":
                            return Locale.JAPAN;
                    }
                }
            }
        }
        return Locale.CHINA;
    }
}
