package com.paicli.platform.server.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiDtos.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiDtos.ErrorResponse("bad_request", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> conflict(IllegalStateException e, HttpServletRequest request) {
        if (acceptsEventStream(request)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiDtos.ErrorResponse("conflict", e.getMessage()));
    }

    /**
     * A browser can cancel an SSE request while Tomcat is flushing an event. At
     * that point the response is no longer writable, so do not attempt to emit
     * the normal JSON API envelope.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientDisconnected(AsyncRequestNotUsableException ignored) {
        // The caller has gone away; the SSE producer observes and closes itself.
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiDtos.ErrorResponse> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.badRequest().body(new ApiDtos.ErrorResponse("validation_error", message));
    }

    private static boolean acceptsEventStream(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}

