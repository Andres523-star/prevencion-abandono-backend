package co.edu.retencion.mailing.application;

/**
 * Puerto de salida de correo. Cada proveedor (Microsoft Graph, Resend, registro en log, memoria)
 * es un Adapter de esta interfaz.
 */
public interface EmailSender {

    /**
     * Envía el mensaje.
     *
     * @return id del mensaje en el proveedor (para asociar rebotes)
     * @throws EmailDeliveryException si el proveedor no aceptó el mensaje
     */
    String send(EmailMessage message);

    /** Nombre corto del proveedor (métricas). */
    String name();

    /**
     * Mensaje listo para enviar. {@code toString} oculta la dirección (los logs no llevan PII).
     *
     * @param correlationId id propio del envío (se manda como cabecera {@code X-Retencion-Outbox-Id})
     */
    record EmailMessage(String to, String subject, String html, String correlationId) {
        @Override
        public String toString() {
            return "EmailMessage[to=***, correlationId=" + correlationId + "]";
        }
    }

    /**
     * Error de entrega con un motivo apto para logs (sin direcciones ni contenido).
     *
     * @param permanent {@code true} si reintentar no tiene sentido (p. ej. 400 del proveedor)
     */
    class EmailDeliveryException extends RuntimeException {

        private final boolean permanent;

        public EmailDeliveryException(String safeReason, boolean permanent) {
            super(safeReason);
            this.permanent = permanent;
        }

        public boolean isPermanent() {
            return permanent;
        }
    }
}
