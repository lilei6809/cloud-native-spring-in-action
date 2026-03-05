package com.polarbookshop.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    public Exception decode(String s, Response response) {
        try {
            String body = Util.toString(response.body().asReader(StandardCharsets.UTF_8));

            ResultBox<?> result = objectMapper.readValue(body, ResultBox.class);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
