/**
 * Módulo <b>ai</b> ("ai-analysis" en CLAUDE.md): única puerta de salida hacia Claude (SDK oficial
 * de Anthropic). Explica factores de riesgo, redacta planes de apoyo y borradores de retención.
 *
 * <p>Reglas de privacidad que este módulo hace cumplir:
 * <ul>
 *   <li>El payload se construye con una <b>lista blanca</b> de campos ({@code PayloadWhitelist}):
 *       solo {@code anon_code}, rangos y métricas. Cualquier campo fuera de la lista detiene el envío.</li>
 *   <li>El texto libre (observaciones) solo sale si {@code ai.free-text.enabled = true} y siempre pasa
 *       por el redactor de PII (regex + diccionario de nombres). Las evidencias y el chat nunca salen.</li>
 *   <li>El estrato y las variables socioeconómicas sensibles no forman parte del payload; las señales
 *       de bienestar solo salen como conteos y si {@code ai.payload.include-sensitive-signals = true}.</li>
 *   <li>La IA recomienda, no decide: toda salida se valida con JSON Schema, solo puede referenciar
 *       recursos reales del catálogo y queda sujeta a revisión humana.</li>
 * </ul>
 *
 * <p>API pública: {@link co.edu.retencion.ai.Anonymizer}, {@link co.edu.retencion.ai.RiskExplainer},
 * {@link co.edu.retencion.ai.SupportPlanWriter}, {@link co.edu.retencion.ai.ResourceCatalog},
 * {@link co.edu.retencion.ai.RetentionAgent} y la SPI {@link co.edu.retencion.ai.RetentionAgentTools}
 * (la implementa el módulo risk, para no crear un ciclo).
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Análisis con IA")
package co.edu.retencion.ai;
