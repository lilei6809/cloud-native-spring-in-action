package com.polarbookshop.orderservice.demo;

import com.polarbookshop.orderservice.domain.Order;
import com.polarbookshop.orderservice.domain.OrderRepository;
import com.polarbookshop.orderservice.domain.OrderStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Profile("testdata")
public class OrderDataLoader {

    private final OrderRepository orderRepository;
    public OrderDataLoader(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOrderTestData(){
        Order o1 = Order.builder()
                .bookIsbn("1234567890")
                .bookName("GREAT LANLAN")
                .bookPrice(123.0)
                .status(OrderStatus.ACCEPTED)
                .quantity(3)
                .createdDate(Instant.now())
                .lastModifiedDate(Instant.now())
                .build();

        Order o2 = Order.builder()
                .bookIsbn("0123456789")
                .bookName("BEST LANLAN")
                .bookPrice(321.0)
                .status(OrderStatus.ACCEPTED)
                .quantity(5)
                .createdDate(Instant.now())
                .lastModifiedDate(Instant.now())
                .build();

        orderRepository.save(o1);
        orderRepository.save(o2);

    }
}
