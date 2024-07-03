package org.jumpserver.chen.web.hook;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.reflections.Reflections;
import org.springframework.stereotype.Component;

import java.lang.reflect.Modifier;

@Component
@Slf4j
public class RegisterDBModules {
    @PostConstruct
    public void registerDBModules() {
        Reflections reflections = new Reflections("org.jumpserver.chen.modules");
        var subTypes = reflections.getSubTypesOf(Datasource.class);
        subTypes.stream()
                .filter(
                        (ds) -> !Modifier.isAbstract(ds.getModifiers())
                )
                .forEach(ds -> {
                    try {
                        Class.forName(ds.getName());
                    } catch (Exception e) {
                        log.error("Register datasource error: {}", e.getMessage(), e);
                    }
                });
    }
}

