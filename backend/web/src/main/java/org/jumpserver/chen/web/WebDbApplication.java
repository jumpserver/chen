package org.jumpserver.chen.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;


@EnableAsync
@SpringBootApplication
@ComponentScan(basePackages = {"org.jumpserver.chen"})
public class WebDbApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebDbApplication.class, args);
    }

}
