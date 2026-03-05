package com.polarbookshop.orderservice.domain;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

public interface OrderRepository extends CrudRepository<Order, Long> {
    @Modifying
    @Query("DELETE FROM orders WHERE 1=1")
    void deleteAll();
}
