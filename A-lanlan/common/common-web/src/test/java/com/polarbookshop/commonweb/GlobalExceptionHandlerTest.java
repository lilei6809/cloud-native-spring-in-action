package com.polarbookshop.commonweb;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.polarbookshop.commoncore.exception.BusinessException;
import com.polarbookshop.commoncore.exception.SystemException;
import feign.Request;
import feign.RetryableException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void businessException_preserves_original_code() throws Exception {
        mockMvc.perform(get("/test/business").param("code", "A0404"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("A0404"))
                .andExpect(jsonPath("$.message").value("business message"));
    }

    @Test
    void businessException_falls_back_to_default_code_when_missing() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("A0001"))
                .andExpect(jsonPath("$.message").value("business message"));
    }

    @Test
    void validationException_returns_stable_validation_code() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("A0105"))
                .andExpect(jsonPath("$.message").value("name must not be blank"));
    }

    @Test
    void systemException_preserves_original_code() throws Exception {
        mockMvc.perform(get("/test/system").param("code", "B1001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("B1001"))
                .andExpect(jsonPath("$.message").value("服务器开小差了，请稍后再试"));
    }

    @Test
    void retryableException_uses_downstream_unavailable_contract() throws Exception {
        mockMvc.perform(get("/test/retryable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("B1002"))
                .andExpect(jsonPath("$.message").value("依赖服务暂不可用，请稍后再试"));
    }

    @Test
    void unknownException_returns_internal_server_error() throws Exception {
        mockMvc.perform(get("/test/unknown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("B0001"))
                .andExpect(jsonPath("$.message").value("服务器开小差了，请稍后再试"));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/business")
        void business(@RequestParam(name = "code", required = false) String code) {
            throw new BusinessException("business message", code);
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody SampleRequest request) {
        }

        @GetMapping("/system")
        void system(@RequestParam(name = "code", required = false) String code) {
            throw new SystemException("system message", code);
        }

        @GetMapping("/retryable")
        void retryable() {
            throw new RetryableException(
                    503,
                    "downstream unavailable",
                    Request.HttpMethod.GET,
                    0L,
                    Request.create(Request.HttpMethod.GET, "/test/retryable", Map.of(), null, StandardCharsets.UTF_8)
            );
        }

        @GetMapping("/unknown")
        void unknown() {
            throw new IllegalStateException("boom");
        }
    }

    record SampleRequest(
            @NotBlank(message = "name must not be blank")
            String name
    ) {
    }
}
