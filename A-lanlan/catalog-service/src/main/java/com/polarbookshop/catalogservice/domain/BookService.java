package com.polarbookshop.catalogservice.domain;

import com.polarbookshop.commoncore.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class BookService {

    private final BookRepository bookRepository;

    public BookService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    public Iterable<Book> viewBookList() {
        return bookRepository.findAll();
    }

    public Book viewBookDetails(String isbn) {
        return bookRepository.findByIsbn(isbn)
                .orElseThrow(() -> new BusinessException("Book with isbn: " + isbn + " not found", "A1020"));
    }

    public Book addBookToCatalog(Book book) {
        if (bookRepository.existsByIsbn(book.isbn())) {
            throw new BusinessException("Book with isbn: " + book.isbn() + " already exists", "A1021");
        }
        return bookRepository.save(book);
    }

    public void removeBookFromCatalog(String isbn) {
        bookRepository.deleteByIsbn(isbn);
    }

	public Book editBookDetails(String isbn, Book book) {
		return bookRepository.findByIsbn(isbn)
				.map(existingBook -> {
					var bookToUpdate = new Book(
							existingBook.id(),
							existingBook.isbn(),
							book.title(),
							book.author(),
							book.price(),
							book.publisher(),
							existingBook.createdDate(),
							existingBook.lastModifiedDate(),
							existingBook.version());
					return bookRepository.save(bookToUpdate);
				})
				.orElseGet(() -> addBookToCatalog(book));
	}

}
