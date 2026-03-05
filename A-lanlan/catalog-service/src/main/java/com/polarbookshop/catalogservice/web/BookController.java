package com.polarbookshop.catalogservice.web;

import com.polarbookshop.commoncore.exception.ResultBox;
import jakarta.validation.Valid;

import com.polarbookshop.catalogservice.domain.Book;
import com.polarbookshop.catalogservice.domain.BookService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping
    public ResponseEntity<ResultBox<Iterable<Book>>> get() {
        Iterable<Book> books = bookService.viewBookList();
        return ResponseEntity.ok().body(ResultBox.success(books));
    }

    @GetMapping("{isbn}")
    public ResponseEntity<ResultBox<Book>> getByIsbn(@PathVariable String isbn) {
        Book book = bookService.viewBookDetails(isbn);
        return ResponseEntity.ok().body(ResultBox.success(book));
    }

    @PostMapping
    public ResponseEntity<ResultBox<Book>> post(@Valid @RequestBody Book book) {
        Book bookSaved = bookService.addBookToCatalog(book);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResultBox.success(bookSaved));
    }

    @DeleteMapping("{isbn}")
    public ResponseEntity<ResultBox<Void>> delete(@PathVariable String isbn) {
        bookService.removeBookFromCatalog(isbn);
        // 资源删除成功 无返回内容 204, 有内容返回 200
        return ResponseEntity.ok().body(ResultBox.success(null));
    }

    @PutMapping("{isbn}")
    public ResponseEntity<ResultBox<Book>> put(@PathVariable String isbn, @Valid @RequestBody Book book) {
        Book bookUpdated = bookService.editBookDetails(isbn, book);
        return ResponseEntity.status(HttpStatus.valueOf(202)).body(ResultBox.success(bookUpdated));
    }

    // 测试用的接口
    @GetMapping("/longReadTimeOut")
    public ResponseEntity<ResultBox<String>> longReadTimeOut() {
        try {
            Thread.sleep(3000);
            return ResponseEntity.ok().body(ResultBox.success("回信~~~~~~"));

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
