# Observabilidad y operación

## Salud

| Endpoint | Uso |
|---|---|
| `/actuator/health/liveness` | ¿Hay que reiniciar? Solo el estado de la JVM |
| `/actuator/health/readiness` | ¿Puede recibir tráfico? En prod incluye base de datos y Redis (Render lo usa como `healthCheckPath`) |
| `/actuator/prometheus` | Métricas (exige rol ADMIN; usar un token de servicio) |

## Métricas propias

| Métrica | Qué mide |
|---|---|
| `retencion_events_pending` | Eventos de dominio sin completar (listener en curso o fallido) |
| `retencion_mail_outbox{status}` | Avisos de correo pendientes, fallidos y rebotados |
| `retencion_mail_sent_total`, `retencion_mail_failed_total` | Correos enviados y fallidos |
| `retencion_risk_queue` | Trabajos pendientes del motor de riesgo |
| `risk_assessments_total{level,source,trigger}` | Análisis de riesgo (por minuto en el tablero) |
| `ai_tokens_total`, `ai_calls_total` | Tokens y llamadas a la IA (costo estimado en el tablero) |
| `retencion_reports_queue`, `retencion_alerts_open`, `retencion_privacy_requests_overdue` | Colas y bandejas |
| `analytics_cache_total{result}` | Aciertos y fallos de la caché de analítica |
| `hikaricp_connections_*`, `executor_*` (applicationTaskExecutor) | Pool de conexiones y cola de listeners |

Las métricas de colas son conteos con caché de 15 s (`OperationalGauges`): un scrape no golpea la base.

## Tablero y alertas

- Grafana: `observability/grafana/dashboards/retencion-operacion.json` (latencia, errores, colas, tokens y costo de
  IA, correos, análisis por minuto, pool). El costo usa la constante `usd_per_million_tokens`: ajustarla al precio
  del modelo contratado.
- Alertas de Prometheus: `observability/prometheus/alerts.yml`, cada una enlazada a su sección del
  [runbook](runbook-incidentes.md).
- Local: `docker compose -f docker-compose.observability.yml up -d` (Prometheus, Grafana, recolector OTel, Tempo).

## Trazas

OpenTelemetry con Micrometer Tracing: `OTEL_TRACING_ENABLED=true`, `OTEL_EXPORTER_OTLP_ENDPOINT`,
`OTEL_SAMPLING_PROBABILITY` (0.1). El recolector (`observability/otel/collector.yml`) borra atributos de usuario y
cabeceras de autorización antes de exportar. El `requestId` de los logs y la traza se correlacionan.
