package org.jumpserver.chen.framework.i18n;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.session.SessionManager;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MessageUtils {

    private static MessageSource messageSource;

    public MessageUtils(MessageSource messageSource) {
        MessageUtils.messageSource = messageSource;
    }

    public static String get(String msgKey, Object... args) {
        try {
            var locale = SessionManager.getCurrentSession().getLocale();
            var text = messageSource.getMessage(msgKey, null, locale);
            return String.format(text, args);
        } catch (Exception e) {
            log.warn("Message not found: {}", msgKey);
            return msgKey;
        }
    }
}