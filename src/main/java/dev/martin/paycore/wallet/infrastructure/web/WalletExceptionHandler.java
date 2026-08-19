package dev.martin.paycore.wallet.infrastructure.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WalletController.class)
public class WalletExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> walletNotAvailable() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "wallet_unavailable"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> walletServiceUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "wallet_unavailable"));
    }
}
