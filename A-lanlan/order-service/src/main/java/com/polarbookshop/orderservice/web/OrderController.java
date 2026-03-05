package com.polarbookshop.orderservice.web;

import com.polarbookshop.commoncore.exception.ResultBox;
import com.polarbookshop.orderservice.domain.Order;
import com.polarbookshop.orderservice.domain.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("order")
@Slf4j
public class OrderController {
    private OrderService orderService;
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ResponseEntity<ResultBox<List<Order>>> getAllOrders() {
        return ResponseEntity.ok(ResultBox.success(orderService.getAllOrders()));
    }

    @PostMapping()
    public ResponseEntity<ResultBox<Order>> sumbitOrder(RequestEntity<OrderRequest> requestEntity) {
        OrderRequest orderRequest = requestEntity.getBody();
        Order orderSaved = orderService.submitOrder(orderRequest.isbn(), orderRequest.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResultBox.success(orderSaved));
    }


    // 测试接口
    @GetMapping("longRequest")
    public ResponseEntity<ResultBox<String>> longRequest(){
        int i = 0;
        return ResponseEntity.ok(ResultBox.success(orderService.getLongRequest()));
    }

}
