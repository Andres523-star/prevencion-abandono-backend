package co.edu.retencion.auth.infrastructure.supabase;

import java.util.function.Predicate;

import co.edu.retencion.auth.domain.AuthProblems;
import co.edu.retencion.common.error.DomainException;

/**
 * Qué cuenta como fallo para el circuit breaker de Supabase: solo errores de red o 5xx.
 * Un token inválido es culpa del cliente y no debe abrir el circuito.
 */
public class SupabaseFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable ex) {
        return !(ex instanceof DomainException de) || AuthProblems.AUTH_PROVIDER_ERROR.equals(de.problemCode());
    }
}
