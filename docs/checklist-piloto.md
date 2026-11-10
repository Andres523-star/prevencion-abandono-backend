# Checklist de salida a piloto

✅ hecho y verificado en este repositorio · 🧑‍⚖️ requiere una decisión o acción de personas fuera del código.

## Técnico

- ✅ `./mvnw verify` verde (unitarias, integración, arquitectura, cobertura de dominio ≥ 80 %).
- ✅ Recorrido de autorización negativa de todos los endpoints (`SecurityMatrixIT`).
- ✅ Límites de petición, inyección y enumeración (`HardeningIT`).
- ✅ Imagen sin root, dependencias fijadas, SBOM, escaneo de vulnerabilidades y de secretos en CI.
- ✅ Pruebas de carga documentadas: analítica (p95 17 ms) y pico de asistencia a escala completa (612 tomas de lista, 0 fallas, p95 110 ms) en [rendimiento.md](rendimiento.md).
- ✅ Restauración de backup probada (`BackupRestoreIT`, [backups.md](backups.md)).
- ✅ Migraciones controladas en el despliegue y validación del esquema al arrancar.
- ✅ Tablero de Grafana, alertas y runbook.
- 🧑‍⚖️ Ejecutar CI en GitHub con Docker (Redis y relay STOMP solo se probaron allí) — requiere hacer push.
- 🧑‍⚖️ Crear los proyectos de Render (staging y producción), cargar secretos y deploy hooks.
- 🧑‍⚖️ Restaurar un backup real de Render en staging y repetir la prueba de carga sobre PostgreSQL gestionado.
- 🧑‍⚖️ Elegir y configurar el proveedor de correo (SPF/DKIM/DMARC) y el almacenamiento de objetos.
- 🧑‍⚖️ Probar el cliente de IA con una clave real (hoy simulado con WireMock).
- 🧑‍⚖️ Prueba de penetración externa.

## Datos y cumplimiento

- ✅ Registro de actividades de tratamiento, consentimientos por finalidad y derechos del titular con plazos.
- 🧑‍⚖️ Validación jurídica (lista en [cumplimiento-ley-1581.md](cumplimiento-ley-1581.md)), registro en el RNBD.
- 🧑‍⚖️ Contratos con encargados (Render, Supabase, Anthropic, correo, almacenamiento).
- 🧑‍⚖️ Formato real de los archivos de importación y ajuste de alias ([conectar-datos-reales.md](conectar-datos-reales.md)).
- 🧑‍⚖️ Pesos de las reglas de riesgo y políticas de asistencia revisados con el equipo académico.

## Operación

- ✅ Runbook de incidentes, incluida la brecha de datos personales.
- 🧑‍⚖️ Designar responsables de guardia y del canal de alertas.
- 🧑‍⚖️ Capacitar a docentes, consejería y bienestar; definir el canal de soporte del piloto.
- 🧑‍⚖️ Definir el alcance del piloto (programas, número de estudiantes) y los criterios de éxito.
