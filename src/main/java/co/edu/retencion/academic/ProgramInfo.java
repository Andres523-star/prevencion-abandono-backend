package co.edu.retencion.academic;

import java.util.UUID;

/**
 * Programa académico.
 *
 * @param id       id
 * @param code     código corto (ENF, MED, DER, ISW, IIN, ODO)
 * @param name     nombre
 * @param clinical si admite prácticas clínicas
 */
public record ProgramInfo(UUID id, String code, String name, boolean clinical) {
}
