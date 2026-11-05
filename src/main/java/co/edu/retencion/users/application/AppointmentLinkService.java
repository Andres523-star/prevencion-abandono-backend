package co.edu.retencion.users.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.settings.SettingsProvider;
import co.edu.retencion.users.domain.AppointmentLinkPolicy;
import co.edu.retencion.users.domain.User;
import co.edu.retencion.users.domain.UserRepository;

/**
 * Enlace de la agenda de citas del docente (Google Calendar, Microsoft Bookings...). Solo el propio
 * docente lo edita; el backend solo lo guarda y lo entrega, sin integrarse con la API de calendario.
 * Dominios permitidos: {@code users.appointment-link-domains} (editable sin redeploy).
 */
@Service
public class AppointmentLinkService {

    static final String DOMAINS_KEY = "users.appointment-link-domains";

    private final UserRepository users;
    private final SettingsProvider settings;
    private final Clock clock;

    public AppointmentLinkService(UserRepository users, SettingsProvider settings, Clock clock) {
        this.users = users;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<String> mine() {
        return users.findById(teacher()).map(User::getAppointmentLink);
    }

    @Transactional
    public String update(String url) {
        String link = AppointmentLinkPolicy.validate(url, allowedDomains());
        User user = users.findById(teacher()).orElseThrow(() -> DomainException.notFound("Usuario no encontrado"));
        user.changeAppointmentLink(link, clock.instant());
        users.saveAndFlush(user);
        return link;
    }

    @Transactional
    public void clear() {
        User user = users.findById(teacher()).orElseThrow(() -> DomainException.notFound("Usuario no encontrado"));
        user.changeAppointmentLink(null, clock.instant());
        users.saveAndFlush(user);
    }

    List<String> allowedDomains() {
        List<String> domains = new ArrayList<>();
        settings.value(DOMAINS_KEY).filter(v -> v.isArray()).ifPresent(v -> v.forEach(d -> domains.add(d.asText())));
        return domains;
    }

    private static UUID teacher() {
        var user = CurrentUser.require();
        if (!user.hasRole(Role.DOCENTE)) {
            throw new org.springframework.security.access.AccessDeniedException("Solo el docente gestiona su enlace de citas");
        }
        return user.userId();
    }
}
