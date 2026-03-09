package com.polarbookshop.edgeserver.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
@Slf4j
public class HelloConfigController {

    @Value("${polar.greeting}")
    private String greeting;

    @GetMapping("/")
    public String hello() {
        log.info("hello");
        return greeting;
    }
}
