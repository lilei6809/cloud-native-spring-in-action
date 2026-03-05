package com.polarbookshop.orderservice.domain;

import com.polarbookshop.orderservice.config.CatalogClient;
import com.polarbookshop.orderservice.config.CatalogLongRequestClient;
import com.polarbookshop.orderservice.config.ClientProperties;
import com.polarbookshop.orderservice.domain.exception.BookNotFoundException;
import com.polarbookshop.orderservice.model.Book;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class OrderService {

    private final OrderRepository repo;

    private final CatalogClient  catalogClient;


    public OrderService(OrderRepository orderRepository, CatalogClient catalogClient) {
        this.repo = orderRepository;
        this.catalogClient = catalogClient;
    }

    public List<Order> getAllOrders() {
        return StreamSupport.stream(repo.findAll().spliterator(), false).collect(Collectors.toList());

    }

    public Order submitOrder(String isbn, Integer quantity){

        Book book = catalogClient.getBookByIsbn(isbn);

        if(book == null){
            Order order = buildRejectedOrder(isbn, quantity);
            repo.save(order);
            throw new BookNotFoundException(isbn);
        }

        Order completedOrder = Order.builder()
                .bookIsbn(isbn)
                .quantity(quantity)
                .bookName(book.title() + "-" + book.author())
                .bookPrice(book.price())
                .status(OrderStatus.ACCEPTED)
                .build();

        return repo.save(completedOrder);
    }

    private Order buildRejectedOrder(String isbn, Integer quantity) {
        return Order.of(isbn, null, null, quantity, OrderStatus.REJECTED);
    }


    @Autowired
    CatalogLongRequestClient c;
    // 测试用
    public String getLongRequest(){
        return c.longReadTimeOut();
    }

}
