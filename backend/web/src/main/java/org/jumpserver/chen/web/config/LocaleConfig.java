package org.jumpserver.chen.web.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractMessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Configuration
public class LocaleConfig {

    @Bean
    public LocaleResolver localeResolver() {
        SessionLocaleResolver localeResolver = new SessionLocaleResolver();
        localeResolver.setDefaultLocale(Locale.CHINA);
        return localeResolver;
    }

    @Bean
    public WebMvcConfigurer localeInterceptor() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                LocaleChangeInterceptor localeInterceptor = new LocaleChangeInterceptor();
                localeInterceptor.setParamName("lang");
                registry.addInterceptor(localeInterceptor);
            }
        };
    }

    @Bean
    public MessageSource messageSource(@Value("${i18n.endpoint}") String endpoint) {
        return new JsonMessageSource(endpoint);
    }
}

@Slf4j
@Component
class JsonMessageSource extends AbstractMessageSource {

    private final Map<String, Map<String, String>> messages = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate = new RestTemplate();
    private final String endpoint;


    public JsonMessageSource(@Value("${i18n.endpoint}") String endpoint) {
        // 首先从环境变量中读取 core 的地址

        String coreHost = System.getenv("CORE_HOST");
        if (StringUtils.isNotBlank(coreHost)) {
            this.endpoint = coreHost;
        } else {
            this.endpoint = endpoint;
        }

        var languages = new String[]{
                "en", "ja", "zh", "zh_hant"
        };
        for (var lang : languages) {
            loadMessages(lang);
        }
    }

    private void loadMessages(String lang) {
        try {

            var url = String.format("%s/api/v1/settings/i18n/chen/?lang=%s&flat=1", this.endpoint, lang);

            String jsonData = this.restTemplate.getForObject(url, String.class);
            Map<String, Object> jsonMap = objectMapper.readValue(jsonData, new TypeReference<>() {
            });
            Map<String, String> flattenedMap = new HashMap<>();
            flattenJson("", jsonMap, flattenedMap);

            log.info(String.format("load locale : %s", lang));
            messages.put(lang, flattenedMap);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load messages from " + lang, e);
        }
    }

    private String extractLocaleFromFilename(String filename) {
        String basename = filename.substring(filename.lastIndexOf('/') + 1);
        int localeStart = basename.indexOf('_') + 1;
        int localeEnd = basename.lastIndexOf('.');
        return basename.substring(localeStart, localeEnd);
    }

    private void flattenJson(String prefix, Map<String, Object> map, Map<String, String> resultMap) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String newPrefix = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                flattenJson(newPrefix, (Map<String, Object>) entry.getValue(), resultMap);
            } else {
                resultMap.put(newPrefix, entry.getValue().toString());
            }
        }
    }

    private String getLangTag(Locale locale) {
        if (locale.equals(Locale.US)) {
            return "en";
        } else if (locale.equals(Locale.CHINA)) {
            return "zh";
        } else if (locale.equals(Locale.JAPAN)) {
            return "ja";
        } else if (locale.equals(Locale.TAIWAN) || locale.equals(Locale.TRADITIONAL_CHINESE)) {
            return "zh_hant";
        }
        return "en";
    }

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        String languageTag = getLangTag(locale);
        Map<String, String> localeMessages = messages.get(languageTag);
        if (localeMessages != null) {
            String message = localeMessages.get(code);
            if (StringUtils.isNotBlank(message)) {
                return new MessageFormat(message, locale);
            }
        }
        return null;
    }
}