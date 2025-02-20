package org.jumpserver.chen.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/healthy")
public class HealthCheckController {

    @GetMapping("")
    public String healthCheck() {
        return "ok";
    }
}
