package co.edu.retencion.realtime;

import java.util.UUID;

/**
 * SPI: un módulo que publica en temas propios (p. ej. {@code /topic/chats.<id>}) decide quién puede
 * suscribirse. Si ningún autorizador acepta el destino, la suscripción se rechaza (falla cerrado).
 *
 * <p><b>Patrón Strategy</b> (complementario): una estrategia por familia de destinos; así realtime no
 * depende del chat ni de los módulos que agreguen temas en el futuro.
 */
public interface SubscriptionAuthorizer {

    boolean supports(String destination);

    boolean canSubscribe(UUID userId, String destination);
}
