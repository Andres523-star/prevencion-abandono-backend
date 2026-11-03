package co.edu.retencion.auth.domain;

/** Motivo por el que se revocó una familia de refresh tokens. */
public enum RevocationReason {
    LOGOUT,
    REUSE_DETECTED,
    ACCOUNT_DISABLED,
    MFA_REQUIRED
}
