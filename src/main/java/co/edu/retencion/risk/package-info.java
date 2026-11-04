/**
 * Módulo <b>risk</b> ("risk-engine" en CLAUDE.md): motor de riesgo de deserción con diseño híbrido
 * de tres capas desacopladas por interfaces:
 * <ol>
 *   <li>{@code RiskScorer}: puntaje por reglas configurables ({@code RulesScorer}, {@code scoreType=RULES},
 *       un puntaje y no una probabilidad) o por el modelo propio exportado a ONNX
 *       ({@code MlScorer}, {@code scoreType=ML_CALIBRATED}, probabilidad calibrada).</li>
 *   <li>{@code RiskExplainer} (módulo ai): Claude explica factores y recomienda recursos reales.</li>
 *   <li>{@code RiskOrchestrator}: decide cuándo llamar a cada uno y aplica el fallback a reglas.</li>
 * </ol>
 *
 * <p>Guardarraíles: la IA recomienda, no decide (el nivel lo fija el motor; la IA solo puede subirlo
 * si {@code risk.ai-can-escalate}); toda salida muestra sus factores; los planes de apoyo exigen
 * aprobación humana; el estrato nunca suma puntaje ni entra al modelo ML.
 *
 * <p>API pública: {@link co.edu.retencion.risk.RiskQueries}, el evento
 * {@link co.edu.retencion.risk.RiskAssessed} (lo consumirán alertas y campanita en la Fase 5) y los
 * enums {@link co.edu.retencion.risk.RiskLevel}, {@link co.edu.retencion.risk.ScoreType} y
 * {@link co.edu.retencion.risk.AssessmentSource}.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Motor de riesgo")
package co.edu.retencion.risk;
