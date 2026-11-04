# Modelo predictivo propio (Fase 4, punto 9)

Asistido por IA (Claude, Anthropic). Ver `BITACORA_IA.md`.

## Flujo

1. **Exportar el dataset** (ADMIN, auditado, motivo obligatorio):
   `POST /api/v1/ml/dataset-exports {"reason": "...", "termCodes": ["2025-1", ...]}` → CSV.
   - Una fila por estudiante y periodo cerrado, con las variables de `MlFeatureVector.FEATURES` y la
     etiqueta `label_dropout` (1 si no aparece inscrito en el periodo siguiente; se omiten graduados).
   - `row_id` es un HMAC con clave aleatoria de un solo uso: no es el `anon_code` ni se puede enlazar
     entre exportaciones. Cada exportación queda en `ml_dataset_exports` (quién, motivo, filas, hash).
   - `audit_program` y `audit_stratum_band` solo sirven para medir el desempeño por grupo; el script de
     entrenamiento no las usa como variables.
   - El semestre histórico se aproxima (semestre actual menos periodos cursados después).
2. **Entrenar fuera de línea** (`ml/train.py`, ver `ml/requirements.txt`):
   ```
   python ml/train.py --dataset dataset-desercion.csv --test-terms 2026-1 --version lgbm-2026-11-04
   ```
   - Validación temporal: entrenamiento con periodos anteriores, calibración isotónica con el último
     periodo de entrenamiento, prueba con los más recientes.
   - Métricas: AUC, recall en riesgo alto (p ≥ 0.6), Brier, ECE y curva de calibración, globales,
     por programa (`byProgram`) y por banda de estrato (`byGroup`).
   - Exporta `<versión>.onnx` (salida `probabilities` [N,2], sin ZipMap) y `<versión>.card.json`.
3. **Registrar** (ADMIN): `POST /api/v1/ml/models` multipart con `card` (JSON) y `model` (ONNX). Se
   rechaza (422) si declara variables prohibidas o desconocidas, si los periodos de prueba se cruzan con
   los de entrenamiento o si ONNX Runtime no lo puede cargar o su salida no es una probabilidad. El
   artefacto se guarda en el almacenamiento de objetos con su SHA-256.
4. **Comparar** con el modelo activo: `GET /api/v1/ml/models/{versión}/comparison` → `MEJOR`,
   `EQUIVALENTE`, `PEOR` o `SIN_REFERENCIA`, con diferencias de métricas, bloqueos y advertencias.
   Bloquean: AUC < 0.60, caída de AUC > 0.01, caída de recall > 0.05, ECE +0.02, brecha de AUC entre
   grupos de estrato > 0.10.
5. **Aprobar**: `POST /api/v1/ml/models/{versión}/approve`. Con bloqueos exige `override=true` y motivo
   (queda en el registro del modelo y en la auditoría).
6. **Activar por configuración**: `PUT /api/v1/settings/risk.ml.active-version` con la versión. Desde el
   siguiente análisis `scoreType = ML_CALIBRATED` y se muestra `probability`; los factores siguen
   saliendo de las reglas y Claude sigue explicando.
7. **Volver atrás**: poner la versión anterior (o `""` para reglas). Un artefacto cuyo hash no coincida,
   o una versión no aprobada, nunca se usa: el motor cae a reglas.

## Variables (sin sesgo socioeconómico)

`semester`, `semester_average` (−1 sin notas), `subjects_below_passing`, `subject_count`,
`max_absence_ratio`, `total_counted_absences`, `subjects_at_critical_or_lost`, `total_tardies`,
`subjects_trend_down`, `conduct_observations`, `academic_observations`, `open_academic_causes`,
`open_personal_causes`, `open_institutional_causes`.

Prohibidas (rechazo al registrar): cualquier nombre con estrato, trabajo, personas a cargo,
transporte, ingreso, socioeconómico, económico, bienestar, salud, género, edad o `audit_*`.

## Revisión pendiente con el equipo

- Confirmar con datos reales la definición de deserción (hoy: no inscrito en el periodo siguiente).
- Revisar el desempeño por programa y por banda de estrato antes del primer piloto.
- El umbral de "riesgo alto" (0.6) para el recall es configurable en el script y en `risk.ml.levels`.
