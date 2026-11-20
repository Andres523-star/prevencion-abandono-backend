# Seguridad del backend de demostración

El demo-backend sigue funcionando sin base de datos y con las mismas cuentas, pero ahora aplica
las prácticas de seguridad del backend principal (Fases 1 a 7). La API no cambió para el frontend,
salvo el registro (pide sede y programa) y los adjuntos del chat (enlace firmado).

## Qué se endureció

| Tema | Antes | Ahora |
|---|---|---|
| Contraseñas | En texto plano en memoria | Hash argon2id (mismos parámetros que el break-glass del backend principal) y hash de relleno para que el tiempo de respuesta no revele qué correos existen |
| Token de sesión | UUID que nunca vencía | 256 bits aleatorios; en memoria solo su SHA-256; vence a las 2 h sin uso y a las 12 h en todo caso |
| Cierre de sesión | Solo se borraba en el navegador | `POST /api/auth/logout` revoca el token en el servidor |
| Intentos de login | Ilimitados | En 15 minutos: 5 fallos de un correo desde una misma IP, 50 de un correo desde cualquier IP o 30 desde una IP responden 429 con `Retry-After`. El tope estricto es por correo + IP para que nadie bloquee una cuenta pública de la demo para todo el salón |
| Registro público | Cualquiera podía crearse un "Coordinador" que veía todas las sedes | Solo crea profesores, con sede y programa obligatorios y validados; máximo 30 cuentas por IP por hora (configurable); contraseña de 8 caracteres con letras y números |
| Alcance por rol | Una cuenta sin sede o programa veía todo | Falla cerrado: 403 si falta el alcance; un rol desconocido no ve nada |
| `POST /api/analizar/{id}` | Cualquier sesión analizaba a cualquier estudiante | Misma regla de alcance que la ficha del estudiante |
| `POST /api/estudiantes` | Abierto a cualquier sesión | Una cuenta de estudiante recibe 403 |
| Exportes CSV/ZIP | La cuenta de estudiante descargaba a todos los compañeros de su programa y sede (cédula, correo, teléfono) | Solo su propio registro |
| Adjuntos del chat | Tipo según el navegador; descarga pública para siempre | Tipo por contenido real (PDF, .docx sin macros, audio); se rechazan `.doc` y Word con macros; descarga con firma HMAC que dura entre 60 y 120 min y no cambia entre recargas (el audio no se reinicia) |
| Cabeceras | Ninguna | `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, CSP `default-src 'none'` y `Cache-Control: no-store` en la API JSON, HSTS bajo HTTPS |
| Errores | JSON mal formado o parámetros erróneos daban 500 | 400/404/405 con mensaje genérico, sin trazas |
| IP del cliente | La del proxy de Render | `server.forward-headers-strategy: native` (solo proxies de confianza) |

## IA (Claude Sonnet 5.5)

- Cliente: SDK oficial `anthropic-java` (el mismo del backend principal), en `risk/ClienteClaude`.
- Modelo por defecto: `claude-sonnet-5-5`, con esfuerzo bajo (un párrafo de máximo 120 palabras).
- Se lee el texto de todos los bloques de la respuesta (antes solo el primero).
- Payload por **lista blanca** (`AnalisisService.datosParaIa`): nivel, puntaje, programa, semestre,
  promedio, faltas, categoría y acciones sugeridas. Nunca nombre, cédula, correo, teléfono ni sede.
- El "motivo" (texto libre) **no se envía** salvo `AI_FREE_TEXT_ENABLED=true`, y entonces pasa por
  `RedactorPii` (correos, teléfonos, documentos, enlaces y el nombre del estudiante).
- Prueba obligatoria: `ClaudeSinDatosPersonalesTest` levanta un servidor local que imita la API y
  verifica el cuerpo enviado.

## Variables de entorno

| Variable | Por defecto | Uso |
|---|---|---|
| `ANTHROPIC_API_KEY` | (vacía) | Sin clave se usa el motor local. Nunca se sube al repositorio |
| `AI_MODEL` | `claude-sonnet-5-5` | Modelo de Claude |
| `AI_TIMEOUT_SECONDS` | `30` | Tiempo máximo por llamada |
| `AI_FREE_TEXT_ENABLED` | `false` | Enviar el motivo redactado a la IA |
| `DEMO_SESSION_IDLE_MINUTES` | `120` | Vencimiento por inactividad |
| `DEMO_SESSION_MAX_HOURS` | `12` | Vida máxima de la sesión |
| `DEMO_CHAT_LINK_SECRET` | aleatorio al arrancar | Secreto (≥ 32 caracteres) para firmar enlaces de adjuntos |
| `DEMO_CHAT_LINK_MINUTES` | `60` | Tramo de vigencia del enlace de un adjunto |
| `DEMO_LOGIN_MAX_FAILURES_IP_EMAIL` | `5` | Fallos de un correo desde una IP (15 min) |
| `DEMO_LOGIN_MAX_FAILURES_EMAIL` | `50` | Fallos de un correo desde cualquier IP (15 min) |
| `DEMO_LOGIN_MAX_FAILURES_IP` | `30` | Fallos desde una IP (15 min) |
| `DEMO_REGISTER_MAX_PER_IP_HOUR` | `30` | Cuentas nuevas por IP por hora |
| `DEMO_CORS_ORIGINS` | localhost de Vite | Orígenes permitidos (sin comodines) |

## Lo que sigue siendo de demo

- La IP real del cliente depende del proxy del hosting: si su IP no está en los rangos de confianza
  de Tomcat, todos los usuarios comparten una IP y los topes por IP se alcanzan antes. Verificarlo
  en el despliegue y ajustar las variables de arriba.

- Las cuentas de la demo y sus contraseñas están publicadas en el README a propósito (repositorio
  público para presentaciones). No reutilizar esas contraseñas en otro sistema.
- Todo vive en memoria: al reiniciar se pierden sesiones, cuentas registradas, mensajes y adjuntos.
- Sin MFA, sin antivirus de adjuntos y sin auditoría append-only: eso está en el backend principal.
