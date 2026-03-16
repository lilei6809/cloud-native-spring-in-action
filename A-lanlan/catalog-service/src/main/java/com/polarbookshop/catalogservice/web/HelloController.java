package com.polarbookshop.catalogservice.web;

import com.polarbookshop.catalogservice.config.JvmResources;
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

    private final JvmResources jvmResources;

    public HelloController(PolarProperties polarProperties, K8sProperties k8sProperties, JvmResources jvmResources) {
        this.polarProperties = polarProperties;
        this.k8sProperties = k8sProperties;
        this.jvmResources = jvmResources;
    }


    @GetMapping
    public String getGreeting(@RequestHeader("X-Gateway-Region") String region) {

        Integer cpuLimit = System.getenv("CPU_LIMIT") == null ? 1 : Integer.parseInt(System.getenv("CPU_LIMIT"));
        Integer cpuReq = System.getenv("CPU_REQ") == null ? 1 : Integer.parseInt(System.getenv("CPU_REQ"));
        Integer memLimit = System.getenv("MEM_LIMIT") == null ? 1 : Integer.parseInt(System.getenv("MEM_LIMIT"));
        Integer memReq =  System.getenv("MEM_REQ") == null ? 1 : Integer.parseInt(System.getenv("MEM_REQ"));

        log.info("cpu limit : {}, cpuReq: {}, memLimit: {}, memReq: {}", cpuLimit, cpuReq, memLimit, memReq);

        String info = "Catalog HomeController: req region={"+region+"},  POD={"+ k8sProperties.getPodName() +"}, namespace={"+k8sProperties.getNamespace()+"}\n";
        String info2 = String.format("cpu_request: %s, cpu_limit: %s,  mem_request: %s,  mem_limit: %s",
                jvmResources.getCpuRequest(),
                jvmResources.getCpuLimit(),
                jvmResources.getMemRequest(),
                jvmResources.getMemLimit());
        log.info("Catalog HomeController: req region={},  POD={}, namespace={}", region,  k8sProperties.getPodName(), k8sProperties.getNamespace());
        return info + polarProperties.getGreeting() +"\n" + info2;
    }
}
