# Cuenta de emergencia (break-glass)

Cuenta local **solo para ADMIN**, para cuando Supabase Auth no esté disponible. Usa contraseña
argon2id + código TOTP (Microsoft/Google Authenticator) y bloqueo progresivo.

## Generar los secretos

```bash
./mvnw -q package -DskipTests
java -cp target/retencion-backend-0.1.0-SNAPSHOT.jar \
  -Dloader.main=co.edu.retencion.auth.infrastructure.BreakGlassSecretGenerator \
  org.springframework.boot.loader.launch.PropertiesLauncher emergencia
```

La herramienta pide la contraseña sin mostrarla (mínimo 16 caracteres) e imprime:

```
BREAK_GLASS_USERNAME=emergencia
BREAK_GLASS_PASSWORD_HASH=$argon2id$v=19$m=19456,t=2,p=1$...
BREAK_GLASS_TOTP_SECRET=JBSW...
URI para la app autenticadora: otpauth://totp/...
```

1. Registra la URI `otpauth://` en la app autenticadora de 2 custodios.
2. Carga las tres variables y `BREAK_GLASS_ENABLED=true` en el gestor de secretos del entorno.
3. Guarda la contraseña en sobre sellado / bóveda física según el procedimiento de TI.

Al arrancar, `BreakGlassBootstrap` crea la cuenta con rol ADMIN. Si cambian el hash o el secreto en
el entorno, los rota y reinicia el bloqueo.

## Bloqueo progresivo (configurable en caliente, `PUT /api/v1/settings/{clave}`)

| Clave | Por defecto | Efecto |
|---|---|---|
| `auth.lockout.max-attempts` | 3 | Fallos antes del primer bloqueo |
| `auth.lockout.base-lock-seconds` | 60 | Primer bloqueo; se duplica con cada fallo adicional |
| `auth.lockout.max-lock-seconds` | 86400 | Tope del bloqueo |

Los errores siempre dicen "Usuario, contraseña o código incorrectos" (no revelan si el usuario existe
o está bloqueado) y cada intento queda en la auditoría (`AUTH_BREAK_GLASS_LOGIN`).
