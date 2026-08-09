package dev.martin.paycore.identity.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bff/auth")
public class AuthenticationController {

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName(), token.getParameterName());
    }

    @GetMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void session() {
    }

    record CsrfResponse(String token, String headerName, String parameterName) {
    }
}
