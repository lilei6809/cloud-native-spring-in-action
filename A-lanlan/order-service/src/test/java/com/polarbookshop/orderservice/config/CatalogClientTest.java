package com.polarbookshop.orderservice.config;

import com.polarbookshop.commoncore.exception.ResultBox;
import com.polarbookshop.orderservice.model.Book;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CatalogClient 集成测试。
 *
 * 测试范围：Feign 序列化/反序列化、HTTP 状态码映射、fallback 触发。
 * 不测试：OrderService 业务逻辑（见 OrderServiceTest）。
 *
 * 使用最小 Spring 上下文，只装配 Feign 相关 Bean，避免数据库/Flyway/Config Server
 * 干扰客户端测试。
 */
@SpringBootTest(
        classes = CatalogClientTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.config.enabled=false",
                "spring.config.import=",
                "spring.cloud.openfeign.circuitbreaker.enabled=true",
                "spring.cloud.openfeign.client.config.catalogClient.connect-timeout=100",
                "spring.cloud.openfeign.client.config.catalogClient.read-timeout=100",
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
                        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration," +
                        "org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration," +
                        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        }
)
class CatalogClientTest {

    private static final String ISBN_EXISTS    = "1234567891";
    private static final String ISBN_NOT_FOUND = "0000000000";
    private static MockWebServer mockWebServer;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (mockWebServer == null) {
            mockWebServer = new MockWebServer();
            try {
                mockWebServer.start();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to start MockWebServer", e);
            }
        }
        registry.add("polar.catalog-service-uri",
                () -> mockWebServer.url("/").toString().replaceAll("/$", ""));
    }

    @AfterAll
    static void shutdownServer() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Autowired
    CatalogClient catalogClient;

    // ---------------------------------------------------------------
    // 场景 1：catalog-service 正常返回书籍
    // ---------------------------------------------------------------
    @Test
    void getBookByIsbn_returns_book_when_found() {
        // given
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "success": true,
                          "code": "200",
                          "message": "success",
                          "data": {
                            "isbn": "1234567891",
                            "title": "Cloud Native Spring",
                            "author": "Thomas Vitale",
                            "price": 39.99
                          }
                        }
                        """));

        // when
        ResponseEntity<ResultBox<Book>> response = catalogClient.getBookByIsbn(ISBN_EXISTS);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResultBox<Book> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isTrue();

        Book book = body.getData();
        assertThat(book).isNotNull();
        assertThat(book.isbn()).isEqualTo(ISBN_EXISTS);
        assertThat(book.title()).isEqualTo("Cloud Native Spring");
        assertThat(book.price()).isEqualTo(39.99);
    }

    // ---------------------------------------------------------------
    // 场景 2：书不存在，catalog-service 返回业务失败（data = null）
    // ---------------------------------------------------------------
    @Test
    void getBookByIsbn_returns_null_data_when_book_not_found() {
        // given
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "success": false,
                          "code": "A0404",
                          "message": "Book not found",
                          "data": null
                        }
                        """));

        // when
        ResponseEntity<ResultBox<Book>> response = catalogClient.getBookByIsbn(ISBN_NOT_FOUND);

        // then
        ResultBox<Book> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isFalse();
        assertThat(body.getCode()).isEqualTo("A0404");
        assertThat(body.getData()).isNull();  // OrderService.submitOrder 检测到 null 后会 reject 订单
    }

    // ---------------------------------------------------------------
    // 场景 3：catalog-service 网络故障，触发 FallbackFactory
    // read-timeout=100ms，延迟 300ms 触发超时 → Feign 抛异常 → fallback 生效
    // ---------------------------------------------------------------
    @Test
    void getBookByIsbn_returns_fallback_when_catalog_service_is_down() {
        // given: 延迟 300ms 响应，超过 read-timeout=100ms，触发超时 → fallback 生效
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBodyDelay(300, TimeUnit.MILLISECONDS) // 模拟延迟 300ms
                .setBody("{}"));

        // when
        ResponseEntity<ResultBox<Book>> response = catalogClient.getBookByIsbn(ISBN_EXISTS);

        // then: fallback 返回 500 + ResultBox.fail("3010", ...)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        ResultBox<Book> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isFalse();
        assertThat(body.getCode()).isEqualTo("3010");
        assertThat(body.getData()).isNull();
    }



    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableFeignClients(clients = CatalogClient.class)
    @Import({
            CatalogClientFallbackFactory.class,
            FeignExceptionDecoder.class,
            FeignHeaderInteceptor.class
    })
    static class TestApplication {
    }
}
