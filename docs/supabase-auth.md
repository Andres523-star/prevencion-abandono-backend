# Inicio de sesión con Supabase Auth

Supabase gestiona las cuentas (registro, confirmación de correo, contraseña olvidada, MFA y
proveedores como Google o Microsoft). El backend **no guarda contraseñas** de esas cuentas: recibe
el token de Supabase, lo valida y lo canjea por la sesión propia de la plataforma, donde viven los
roles, el alcance y la auditoría.

```
Frontend (supabase-js) ── signInWithPassword / signInWithOtp / signInWithOAuth ──► Supabase Auth
Frontend ◄── session.access_token (JWT)
Frontend ── POST /api/v1/auth/supabase/exchange {accessToken} ──► Backend
     Backend: valida firma (JWKS), iss, aud=authenticated, exp, role, no anónimo
     Backend: anti-replay (cada token se canjea una sola vez)
     Backend ── GET /auth/v1/user ──► Supabase  (correo confirmado + sesión viva)
     Backend: vincula/crea el usuario interno y exige MFA (aal2) a roles privilegiados
Frontend ◄── {accessToken (15 min), refreshToken (rotativo), userId}
```

Después del canje, el frontend usa **solo** los tokens de la plataforma (`/api/v1/auth/refresh` para
renovar). Puede cerrar la sesión de Supabase si quiere; la de la plataforma sigue hasta su vencimiento
o hasta `POST /api/v1/auth/logout`.

## Configurar el proyecto de Supabase

1. Crea un proyecto en https://supabase.com.
2. **Authentication → Sign In / Providers → Email**: activado, con **Confirm email = ON**
   (recomendado, ver abajo). Opcional: Google, Microsoft (Azure), etc.
3. **Authentication → URL Configuration**: *Site URL* = URL del frontend y sus *Redirect URLs*
   (para el enlace de confirmación y el de recuperar contraseña).
4. **Authentication → Multi-Factor**: activa **TOTP** (app autenticadora). Los roles ADMIN,
   CONSEJERO, PSICOLOGO y COORDINADOR la necesitan: el backend exige `aal = aal2` y si no responde
   **403 `mfa_required`**.
5. **Project Settings → API**: copia la *Project URL* → `SUPABASE_URL` y la clave
   *anon / publishable* → `SUPABASE_ANON_KEY` (es pública; va también en el frontend).
6. **JWT Signing Keys**: con las claves asimétricas (por defecto en proyectos nuevos) no hay que
   configurar nada más: el backend descarga el JWKS público. Si el proyecto aún usa el secreto
   heredado (HS256), cárgalo en `SUPABASE_JWT_SECRET`.
7. Correo de Supabase: el servidor de correo integrado tiene límites bajos; para uso real configura
   un SMTP propio en **Authentication → Emails → SMTP Settings** (p. ej. Resend, Brevo, SES).

## ¿Por qué exigir el correo verificado?

`APP_REQUIRE_VERIFIED_EMAIL=true` (por defecto). Sin verificación:

- alguien podría registrarse con el correo de otra persona (p. ej. un docente que ya cargó la
  universidad, o un correo de `APP_BOOTSTRAP_ADMIN_EMAILS`) y quedarse con su cuenta y sus permisos;
- los avisos de faltas al estudiante (Fase 3) saldrían hacia correos que nadie controla.

Supabase envía el correo de confirmación gratis, así que el costo para el usuario es un clic.

## Quién puede entrar

| Variable | Por defecto | Efecto |
|---|---|---|
| `APP_ALLOWED_EMAIL_DOMAINS` | vacío | Vacío = **cualquier correo**. Con valores, solo esos dominios. |
| `APP_DEFAULT_ROLES_BY_DOMAIN` | vacío | Rol inicial por dominio, p. ej. `estudiantes.u.edu.co=ESTUDIANTE` (nunca roles con MFA). |
| `APP_BOOTSTRAP_ADMIN_EMAILS` | vacío | Correos que reciben ADMIN en su primer ingreso. |

Un usuario nuevo entra **sin roles**: ve `/api/v1/users/me` y nada más hasta que un ADMIN le asigne
roles con `PUT /api/v1/users/{id}/roles`. Los roles nunca se leen del token de Supabase.

## Ejemplo de frontend

```js
import { createClient } from '@supabase/supabase-js'
const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY)

const { data, error } = await supabase.auth.signInWithPassword({ email, password })
const res = await fetch(`${API}/api/v1/auth/supabase/exchange`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ accessToken: data.session.access_token }),
})
const { accessToken, refreshToken } = await res.json()   // tokens de la plataforma
```

## Errores del canje

| `code` | HTTP | Causa |
|---|---|---|
| `invalid_provider_token` | 401 | Firma, emisor, audiencia o vigencia inválidos; usuario anónimo; sesión de Supabase revocada |
| `provider_token_already_used` | 401 | Ese token ya se canjeó (pide uno nuevo con `supabase.auth.refreshSession()`) |
| `email_not_verified` | 403 | El usuario no confirmó su correo |
| `email_domain_not_allowed` | 403 | Dominio fuera de `APP_ALLOWED_EMAIL_DOMAINS` |
| `mfa_required` | 403 | Rol privilegiado sin `aal2` |
| `account_disabled` | 403 | Cuenta desactivada en la plataforma |
| `auth_provider_error` / `auth_provider_unavailable` | 502 / 503 | Supabase caído o circuito abierto |

## Pruebas

`FakeSupabase` (WireMock) publica un JWKS ES256, firma tokens y responde `/auth/v1/user`, así que
`./mvnw verify` no necesita un proyecto real de Supabase.
