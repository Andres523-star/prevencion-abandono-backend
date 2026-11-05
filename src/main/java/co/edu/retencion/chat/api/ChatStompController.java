package co.edu.retencion.chat.api;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import co.edu.retencion.chat.application.ChatService;
import co.edu.retencion.chat.domain.ChatMessage;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.PlatformAuthentication;

/**
 * Envío en tiempo real: {@code SEND /app/chat.send} (valida participación, longitud y solo lectura;
 * el rate limit lo aplica el interceptor STOMP), persiste y luego entrega en {@code /user/queue/chat}.
 * Los errores vuelven solo al emisor en {@code /user/queue/errors}.
 */
@Controller
public class ChatStompController {

    private final ChatService chat;

    public ChatStompController(ChatService chat) {
        this.chat = chat;
    }

    public record StompSend(UUID conversationId, ChatMessage.Type type, String text, UUID attachmentId) {
        @Override
        public String toString() {
            return "StompSend[" + conversationId + "]";
        }
    }

    public record StompTyping(UUID conversationId) {
    }

    @MessageMapping("/chat.send")
    public void send(@Payload StompSend message, Principal principal) {
        chat.send(user(principal), message.conversationId(),
                message.type() == null ? ChatMessage.Type.TEXTO : message.type(), message.text(), message.attachmentId());
    }

    @MessageMapping("/chat.typing")
    public void typing(@Payload StompTyping typing, Principal principal) {
        chat.typing(user(principal), typing.conversationId());
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public Map<String, String> error(Exception ex) {
        String code = ex instanceof co.edu.retencion.common.error.DomainException d ? d.problemCode()
                : ex instanceof org.springframework.security.access.AccessDeniedException ? "forbidden" : "error";
        return Map.of("code", code);
    }

    private static AuthenticatedUser user(Principal principal) {
        if (principal instanceof PlatformAuthentication auth) {
            return auth.getPrincipal();
        }
        throw new org.springframework.security.access.AccessDeniedException("Sesión STOMP sin autenticar");
    }
}
