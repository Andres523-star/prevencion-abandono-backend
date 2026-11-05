/**
 * Módulo <b>realtime</b>: infraestructura de tiempo real con WebSocket + STOMP (SockJS como respaldo)
 * para la campanita y el chat.
 *
 * <ul>
 *   <li>Autenticación en el frame CONNECT con el JWT de la plataforma (cabecera {@code Authorization}),
 *       nunca por query string.</li>
 *   <li>Autorización: un usuario solo se suscribe a su cola personal ({@code /user/queue/...}) y a los
 *       temas que un {@link co.edu.retencion.realtime.SubscriptionAuthorizer} autorice (p. ej. los chats
 *       donde es participante); solo puede enviar a {@code /app/**}.</li>
 *   <li>Límite de tamaño de mensaje, rate limit por usuario, heartbeats, orígenes estrictos y cierre de
 *       la conexión cuando el token vence o la sesión se revoca.</li>
 *   <li>Broker simple en desarrollo y pruebas; STOMP broker relay sobre RabbitMQ en producción con
 *       varias instancias ({@code app.realtime.broker=relay}).</li>
 * </ul>
 *
 * <p>API pública: {@link co.edu.retencion.realtime.RealtimePublisher} y la SPI
 * {@link co.edu.retencion.realtime.SubscriptionAuthorizer}.
 *
 * <p>Asistido por IA. Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Tiempo real")
package co.edu.retencion.realtime;
