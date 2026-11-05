package co.edu.retencion.chat.application;

import java.util.UUID;

import org.springframework.stereotype.Component;

import co.edu.retencion.realtime.SubscriptionAuthorizer;

/** Solo los participantes se suscriben al tema de una conversación ({@code /topic/chats.<id>}). */
@Component
class ChatSubscriptionAuthorizer implements SubscriptionAuthorizer {

    static final String PREFIX = "/topic/chats.";

    private final ChatAccess access;

    ChatSubscriptionAuthorizer(ChatAccess access) {
        this.access = access;
    }

    @Override
    public boolean supports(String destination) {
        return destination.startsWith(PREFIX);
    }

    @Override
    public boolean canSubscribe(UUID userId, String destination) {
        try {
            return access.isParticipant(userId, UUID.fromString(destination.substring(PREFIX.length())));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
