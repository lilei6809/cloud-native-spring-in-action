package com.polarbookshop.orderservice.domain;

import com.polarbookshop.orderservice.config.CatalogClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private CatalogClient catalogClient;

    @InjectMocks
    private OrderService orderService;

    @Test
    void getAllOrders() {
    }

    @Test
    void submitOrder() {
    }

    @Test
    void getLongRequest() {
    }
}
