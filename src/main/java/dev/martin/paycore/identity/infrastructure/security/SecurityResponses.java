package dev.martin.paycore.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

final class SecurityResponses {

    private SecurityResponses() {
    }

    static void unauthorized(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
    }

    static void forbidden(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "Access denied");
    }

    private static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(message);
    }
}
