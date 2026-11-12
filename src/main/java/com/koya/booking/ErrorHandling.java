package com.koya.booking;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Makes every error, including framework ones, look like {"error": "..."}. */
public final class ErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandling.class);

    private ErrorHandling() {}

    @RestControllerAdvice
    public static class Advice {
        @ExceptionHandler(ApiException.class)
        ResponseEntity<String> api(ApiException e) {
            return Json.error(e.status(), e.getMessage());
        }

        @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
        ResponseEntity<String> notFound(Exception e) {
            return Json.error(404, "not found");
        }

        @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
        ResponseEntity<String> methodNotAllowed(Exception e) {
            return Json.error(405, "method not allowed");
        }

        @ExceptionHandler(HttpMediaTypeException.class)
        ResponseEntity<String> mediaType(Exception e) {
            return Json.error(415, "unsupported media type");
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<String> internal(Exception e) {
            log.error("internal error", e);
            return Json.error(500, "internal server error");
        }
    }

    /** Replaces Spring Boot's /error page for errors raised outside controllers. */
    @RestController
    public static class Fallback implements ErrorController {
        @RequestMapping("/error")
        ResponseEntity<String> error(HttpServletRequest req) {
            Object s = req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            int status = s instanceof Integer i ? i : 500;
            String msg = switch (status) {
                case 400 -> "bad request";
                case 404 -> "not found";
                case 405 -> "method not allowed";
                default -> status >= 500 ? "internal server error" : "request failed";
            };
            return Json.error(status, msg);
        }
    }
}
