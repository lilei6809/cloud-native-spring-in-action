package com.polarbookshop.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polarbookshop.commoncore.exception.BusinessException;
import com.polarbookshop.commoncore.exception.ResultBox;
import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class FeignExceptionDecoder implements ErrorDecoder {

    ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Exception decode(String methodKey, Response response) {
        String defaultCode = "B1001";
        String defaultMessage = "Remote call failed: " + methodKey + ", status=" + response.status();

        if (response.body() == null) {
            return new BusinessException(defaultMessage, defaultCode);
        }

        try {
            String body = Util.toString(response.body().asReader(StandardCharsets.UTF_8));
            if (body == null || body.isBlank()) {
                return new BusinessException(defaultMessage, defaultCode);
            }

            ResultBox<?> result = objectMapper.readValue(body, ResultBox.class);
            String code = result.getCode() == null || result.getCode().isBlank() ? defaultCode : result.getCode();
            String message = result.getMessage() == null || result.getMessage().isBlank() ? defaultMessage : result.getMessage();
            return new BusinessException(message, code);
        } catch (IOException e) {
            return new BusinessException(defaultMessage, defaultCode);
        }
    }
}
