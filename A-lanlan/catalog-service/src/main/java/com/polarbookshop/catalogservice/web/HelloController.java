package com.polarbookshop.catalogservice.web;

import com.polarbookshop.catalogservice.config.K8sProperties;
import com.polarbookshop.catalogservice.config.PolarProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Slf4j
@RequestMapping("hello")
public class HelloController {

    private final PolarProperties polarProperties;


    private final K8sProperties k8sProperties;

    public HelloController(PolarProperties polarProperties, K8sProperties k8sProperties) {
        this.polarProperties = polarProperties;
        this.k8sProperties = k8sProperties;
    }


    @GetMapping
    public String getGreeting(@RequestHeader Map<String, String> headers) {

        String region = headers.get("X-Gateway-Region");

        String info = "Catalog HomeController: req region={"+region+"},  POD={"+ k8sProperties.getPodName() +"}, namespace={"+k8sProperties.getNamespace()+"}\n";

        log.info("Catalog HomeController: req region={},  POD={}, namespace={}", region,  k8sProperties.getPodName(), k8sProperties.getNamespace());
        return info + polarProperties.getGreeting();
    }
}
