# Despliegue en Railway: demo-backend, frontend y PostgreSQL

Un solo proyecto de Railway con tres servicios:

| Servicio | Origen | Qué es |
|---|---|---|
| `demo-backend` | repo `prevencion-abandono-backend`, carpeta `/demo-backend` | API del demo (Spring Boot, datos en memoria) |
| `frontend` | repo `prevencion-abandono-frontend` | React + Vite servido por Caddy |
| `Postgres` | base gestionada de Railway | Base para la siguiente etapa. **El demo hoy no la usa** (guarda todo en memoria) |

Los nombres importan: las variables de abajo se refieren a `demo-backend` y `frontend` por su nombre.

## Memoria del demo

El demo genera los estudiantes en memoria al arrancar. Medido localmente:

| `DEMO_POBLACION` | Heap mínimo | Memoria del proceso | Contenedor sugerido |
|---|---|---|---|
| 200000 | 384 MB | ~520 MB | 1 GB |
| sin definir (1.109.845) | 2 GB | ~2,2 GB | 3 GB |

Railway cobra la RAM por uso: la población completa cuesta varias veces más que 200.000. Las cuentas
de la demo funcionan igual con cualquier población.

## Pasos en el panel de Railway

1. **Proyecto:** *New Project → Deploy from GitHub repo →* `prevencion-abandono-backend`. Railway
   pide autorizar su app de GitHub para ese repositorio.
2. **Servicio `demo-backend`** (el que se creó con el repo). En *Settings*:
   - *Service name:* `demo-backend`
   - *Source → Root Directory:* `/demo-backend`
   - *Source → Branch:* `mejoras-seguridad` (cambiar a `main` cuando se fusione el PR)
   - *Config-as-code → Railway Config File:* `/demo-backend/railway.json` (Dockerfile, healthcheck `/api/salud` con 300 s de margen para el arranque)
   - *Networking → Generate Domain*
3. **Servicio `frontend`:** *+ Create → GitHub Repo →* `prevencion-abandono-frontend`. En *Settings*:
   - *Service name:* `frontend`
   - *Source → Branch:* `mejoras-seguridad`
   - *Networking → Generate Domain*
4. **Base de datos:** *+ Create → Database → PostgreSQL*. Railway crea `DATABASE_URL` en ese servicio.
5. **Variables** (pestaña *Variables* de cada servicio; las `${{...}}` son referencias que Railway resuelve):

   `demo-backend`:
   ```
   DEMO_CORS_ORIGINS=https://${{frontend.RAILWAY_PUBLIC_DOMAIN}}
   DEMO_POBLACION=200000
   DEMO_CHAT_LINK_SECRET=<generar con el botón de Railway o un valor aleatorio de 48+ caracteres>
   ```
   `frontend`:
   ```
   VITE_API_URL=https://${{demo-backend.RAILWAY_PUBLIC_DOMAIN}}/api
   ```
   `VITE_API_URL` se usa al **compilar** el frontend: si cambia el dominio del backend hay que
   volver a desplegar el frontend.
6. **Clave de Anthropic** (la pone una persona del equipo, nunca en el repositorio): en
   `demo-backend → Variables`, `ANTHROPIC_API_KEY=<clave>`. El modelo por defecto es
   `claude-sonnet-5-5` (`AI_MODEL` para cambiarlo). Sin clave el demo usa su motor local.

## Comprobación

- `https://<dominio del backend>/api/salud` → `{"estado":"ok","estudiantes":...}`
- Abrir el dominio del frontend, entrar con una cuenta de la demo y pulsar "Analizar con IA" en la
  ficha de un estudiante: con la clave puesta, la fuente dice `Claude · claude-sonnet-5-5`.
- Si dice "Motor de recomendaciones local", revisar los *Deploy Logs* del backend: la línea
  `No se pudo usar Claude (...)` trae el tipo de error (clave inválida, modelo, cuota).

## Notas

- Una sola réplica: sesiones, cuentas registradas y chat viven en memoria; con varias réplicas cada
  una tendría los suyos y al reiniciar se pierden.
- La IP real del cliente (para los límites de intentos) llega por `X-Forwarded-For` desde el proxy
  de Railway; si los límites por IP saltan para todos a la vez, ajustar las variables
  `DEMO_LOGIN_MAX_FAILURES_IP` y `DEMO_REGISTER_MAX_PER_IP_HOUR` (ver `SEGURIDAD_DEMO.md`).
