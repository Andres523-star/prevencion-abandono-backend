package co.edu.retencion.users;

import java.util.UUID;

/**
 * Evento de dominio: se creó un usuario interno nuevo (primer ingreso por SSO o cuenta local).
 * Solo lleva el id, nunca datos personales.
 *
 * @param userId   id del usuario creado
 * @param authType origen de la identidad
 */
public record UserProvisioned(UUID userId, AuthType authType) {
}
