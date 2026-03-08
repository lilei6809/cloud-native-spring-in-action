package com.polarbookshop.edgeserver.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class HelloConfigController {

    @Value("${polar.greeting}")
    private String greeting;

    @GetMapping("/")
    public String hello() {
        return greeting;
    }
}
