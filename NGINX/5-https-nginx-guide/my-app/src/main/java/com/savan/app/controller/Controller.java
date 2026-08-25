package com.savan.app.controller;

import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class Controller {

    private final ApplicationContext context;

    public Controller(ApplicationContext context) {
        this.context = context;
    }

    @GetMapping("/hello")
    public String hello() {
        String port = context.getEnvironment().getProperty("server.port", "8080");
        return "Hello from Spring Boot Application running on port " + port;
    }
}


