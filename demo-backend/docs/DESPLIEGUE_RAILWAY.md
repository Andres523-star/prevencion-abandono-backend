# Despliegue en Railway: demo-backend, frontend y PostgreSQL

Proyecto de Railway `courteous-dedication` (entorno `production`) con tres servicios:

| Servicio | Origen | Dominio |
|---|---|---|
| `demo-backend` | `Hurtado43/prevencion-abandono-backend`, carpeta `/demo-backend`, rama `mejoras-seguridad` | https://demo-backend-production-e8f02b28.up.railway.app |
| `frontend` | `Hurtado43/prevencion-abandono-frontend`, rama `mejoras-seguridad` | https://frontend-production-d28c0668.up.railway.app |
| `Postgres` | base gestionada de Railway (con volumen) | interno. **El demo hoy no la usa**: guarda todo en memoria |

Railway despliega desde los **forks de Hurtado43** porque su app de GitHub no tiene acceso a los
repositorios de Andres523-star. Para publicar cambios: subirlos a la rama `mejoras-seguridad` del
fork (remoto `hurtado` en las copias locales); el despliegue es automático.

## Configuración (se hace en el panel)

Railway ya no admite *Config as Code* (`railway.json`) en servicios creados después del
2026-08-28, así que todo se configura en *Settings* y *Variables* de cada servicio. Ambos servicios
se construyen con su `Dockerfile` (Railway lo detecta solo).

**`demo-backend`**
- *Root Directory:* `/demo-backend` · *Branch:* `mejoras-seguridad` · despliegue automático activado
- *Healthcheck Path:* `/api/salud` (el tiempo de espera por defecto de Railway, 300 s, alcanza para el arranque)
- *Networking:* dominio público al puerto `8080`
- Variables:
  ```
  PORT=8080
  DEMO_POBLACION=200000
  DEMO_CORS_ORIGINS=https://frontend-production-d28c0668.up.railway.app
  SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES=.*
  ANTHROPIC_API_KEY=<la pone una persona del equipo; nunca en el repositorio>
  ```

**`frontend`**
- *Branch:* `mejoras-seguridad` · *Networking:* dominio público al puerto `8080`
- Variables (`VITE_API_URL` se usa al **compilar**: si cambia el dominio del backend, hay que volver a desplegar el frontend):
  ```
  PORT=8080
  VITE_API_URL=https://demo-backend-production-e8f02b28.up.railway.app/api
  ```

Si se cambia el dominio de alguno de los dos servicios, actualizar `DEMO_CORS_ORIGINS` o
`VITE_API_URL` en el otro.

## Memoria del demo

El demo genera los estudiantes en memoria al arrancar. Medido localmente:

| `DEMO_POBLACION` | Heap mínimo | Memoria del proceso | Contenedor sugerido |
|---|---|---|---|
| 200000 | 384 MB | ~520 MB | 1 GB |
| sin definir (1.109.845) | 2 GB | ~2,2 GB | 3 GB |

Railway cobra la RAM por uso: la población completa cuesta varias veces más que 200.000. Las cuentas
de la demo funcionan igual con cualquier población.

## Comprobación

- `https://demo-backend-production-e8f02b28.up.railway.app/api/salud` → `{"estado":"ok","estudiantes":200000}`
- Abrir el frontend, entrar con una cuenta de la demo y pulsar "Analizar con IA" en la ficha de un
  estudiante: con la clave puesta, la fuente dice `Claude · claude-sonnet-5-5`.
- Si dice "Motor de recomendaciones local", revisar los *Deploy Logs* del backend: la línea
  `No se pudo usar Claude (...)` trae el tipo de error (clave inválida, modelo, cuota).

## Notas

- Una sola réplica: sesiones, cuentas registradas y chat viven en memoria; con varias réplicas cada
  una tendría los suyos, y al reiniciar o redesplegar se pierden.
- **IP real del cliente (límites de intentos).** El borde de Railway se conecta desde IP públicas que
  cambian entre peticiones (no están en los rangos internos que Tomcat trae por defecto), así que sin
  configurar nada cada intento parecía venir de otra IP y el bloqueo por correo + IP nunca saltaba.
  `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES=.*` hace que Tomcat tome la IP de `X-Forwarded-For`.
  Es seguro **solo detrás de Railway**: se comprobó (2026-11-20) que su borde sobrescribe
  `X-Forwarded-For` y `X-Real-IP`, de modo que un cliente no puede falsearlas, y el servicio no
  tiene otra entrada pública. No usar ese valor en un hosting sin un proxy que sobrescriba esas
  cabeceras. Para volver a comprobarlo: `DEMO_DEBUG_CLIENT_IP=true` muestra en `/api/salud` la IP que
  ve el servidor (apagarlo después).
