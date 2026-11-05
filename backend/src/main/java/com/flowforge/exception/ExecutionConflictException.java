package com.flowforge.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ExecutionConflictException extends RuntimeException {
    public ExecutionConflictException(String message) {
        super(message);
    }
}
