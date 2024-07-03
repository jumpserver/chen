package org.jumpserver.chen.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IndexController {
    @GetMapping("/connect")
    public String index() {
        return "/index.html";
    }

}
