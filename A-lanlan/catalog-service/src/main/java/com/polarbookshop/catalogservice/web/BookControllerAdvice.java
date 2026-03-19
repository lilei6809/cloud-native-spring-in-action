package com.polarbookshop.catalogservice.web;

import com.polarbookshop.catalogservice.domain.BookAlreadyExistsException;
import com.polarbookshop.catalogservice.domain.BookNotFoundException;
import com.polarbookshop.commoncore.exception.ResultBox;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class BookControllerAdvice {

    // 业务异常: 书不存在。HTTP 200 + ResultBox.fail，data=null
    // 调用方 (order-service) 通过 box.getData()==null 感知，不触发 FeignExceptionDecoder
    @ExceptionHandler(BookNotFoundException.class)
    public ResponseEntity<ResultBox<Void>> bookNotFoundHandler(BookNotFoundException ex) {
        return ResponseEntity.ok(ResultBox.fail("A0404", ex.getMessage()));
    }

    // 业务异常: 书已存在。HTTP 200 + ResultBox.fail
    @ExceptionHandler(BookAlreadyExistsException.class)
    public ResponseEntity<ResultBox<Void>> bookAlreadyExistsHandler(BookAlreadyExistsException ex) {
        return ResponseEntity.ok(ResultBox.fail("A0409", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResultBox<Map<String, String>>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return ResponseEntity.badRequest().body(ResultBox.fail("A0400", "Validation failed: " + errors));
    }
}