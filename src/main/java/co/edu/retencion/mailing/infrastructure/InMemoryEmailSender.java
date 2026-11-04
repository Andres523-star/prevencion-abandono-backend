package co.edu.retencion.mailing.infrastructure;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import co.edu.retencion.mailing.application.EmailSender;

/**
 * Adaptador falso para tests ({@code app.mail.provider=memory}): guarda los mensajes en memoria y
 * permite simular fallos del proveedor.
 */
public class InMemoryEmailSender implements EmailSender {

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresToSimulate = new AtomicInteger();

    @Override
    public String send(EmailMessage message) {
        if (failuresToSimulate.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new EmailDeliveryException("simulated_failure", false);
        }
        sent.add(message);
        return "mem-" + message.correlationId();
    }

    @Override
    public String name() {
        return "memory";
    }

    public List<EmailMessage> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
        failuresToSimulate.set(0);
    }

    /** Los próximos {@code count} envíos fallan con un error transitorio. */
    public void failNext(int count) {
        failuresToSimulate.set(count);
    }
}
