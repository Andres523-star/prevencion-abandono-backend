package co.edu.retencion.ai.infrastructure;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import co.edu.retencion.ai.AiFailure;
import co.edu.retencion.ai.application.AiUsageService;
import co.edu.retencion.ai.application.LlmGateway;

/**
 * <b>Patrón Decorator:</b> presupuesto diario de tokens. Antes de llamar revisa el consumo del día;
 * después suma el consumo de la respuesta. Es la capa más externa: sin presupuesto no se gasta ni un
 * intento de la resiliencia.
 */
public class BudgetedLlmGateway implements LlmGateway {

    private final LlmGateway delegate;
    private final AiUsageService usage;

    public BudgetedLlmGateway(LlmGateway delegate, AiUsageService usage) {
        this.delegate = delegate;
        this.usage = usage;
    }

    @Override
    public Message send(String purpose, MessageCreateParams params) {
        if (!usage.hasBudget()) {
            throw new LlmException(AiFailure.BUDGET_EXCEEDED, "Presupuesto diario de tokens agotado");
        }
        Message message = delegate.send(purpose, params);
        usage.record(purpose, message.model().asString(), message.usage().inputTokens(),
                message.usage().outputTokens());
        return message;
    }
}
