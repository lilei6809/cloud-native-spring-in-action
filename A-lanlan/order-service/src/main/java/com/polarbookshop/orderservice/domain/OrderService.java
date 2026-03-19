package com.polarbookshop.orderservice.domain;

import com.polarbookshop.commoncore.exception.BusinessException;
import com.polarbookshop.commoncore.exception.ResultBox;
import com.polarbookshop.commoncore.exception.SystemException;
import com.polarbookshop.orderservice.config.CatalogClient;
import com.polarbookshop.orderservice.config.CatalogLongRequestClient;
import com.polarbookshop.orderservice.model.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
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

        ResponseEntity<ResultBox<Book>> response = getBookByIsbn(isbn);
        ResultBox<Book> box = response.getBody();
        Book book = box.getData();

        // catalog 返回 200 但 data=null，说明书不存在 (业务异常，非系统故障)
        if (book == null) {
            Order rejectedOrder = buildRejectedOrder(isbn, quantity);
            repo.save(rejectedOrder);
            throw new BusinessException(box.getMessage(), box.getCode());
        }

        log.info("Book found: {}", box);
        Order completedOrder = Order.builder()
                .bookIsbn(isbn)
                .quantity(quantity)
                .bookName(book.title() + "-" + book.author())
                .bookPrice(book.price())
                .status(OrderStatus.ACCEPTED)
                .build();

        return repo.save(completedOrder);
    }


    private ResponseEntity<ResultBox<Book>> getBookByIsbn(String isbn) {
        return catalogClient.getBookByIsbn(isbn);
    }

    private Order buildRejectedOrder(String isbn, Integer quantity) {
        return Order.of(isbn, null, null, quantity, OrderStatus.REJECTED);
    }


    @Autowired
    CatalogLongRequestClient c;
    // 测试用
    public String getLongRequest() {
        ResultBox<String> box = c.longReadTimeOut();
        if (box.isSuccess()) {
            return box.getData();
        } else {
            throw new SystemException(box.getMessage(), box.getCode());
        }
    }

}
