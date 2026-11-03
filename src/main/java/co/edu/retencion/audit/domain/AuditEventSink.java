package co.edu.retencion.audit.domain;

/**
 * Destino donde se escriben las entradas de auditoría. Es el "componente" del patrón Decorator:
 * la implementación base persiste y los decoradores añaden depuración de PII y métricas sin
 * modificarla.
 */
public interface AuditEventSink {

    /** Persiste la entrada y devuelve la versión encadenada (con seq y hash). */
    AuditEntry append(AuditEntry entry);
}
