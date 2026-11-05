package co.edu.retencion.users.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;

class AppointmentLinkPolicyTest {

    static final List<String> DOMAINS = List.of("calendar.google.com", "outlook.office.com");

    @Test
    void acceptsHttpsLinksOnAllowedDomains() {
        assertThat(AppointmentLinkPolicy.validate(" https://calendar.google.com/calendar/appointments/schedules/AbC ",
                DOMAINS)).isEqualTo("https://calendar.google.com/calendar/appointments/schedules/AbC");
        assertThat(AppointmentLinkPolicy.validate("https://OUTLOOK.office.com/bookwithme/user/x", DOMAINS)).isNotNull();
    }

    @Test
    void rejectsOtherSchemesDomainsAndTricks() {
        for (String bad : List.of("javascript:alert(1)", "http://calendar.google.com/x", "https://evil.com/x",
                "https://calendar.google.com.evil.com/x", "https://user:pass@calendar.google.com/x",
                "https://calendar.google.com:8443/x", "data:text/html,hola", "https://calendar.google.com/ x",
                "ftp://calendar.google.com", "", "https://" + "a".repeat(600))) {
            assertThatThrownBy(() -> AppointmentLinkPolicy.validate(bad, DOMAINS)).as(bad)
                    .isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> AppointmentLinkPolicy.validate(null, DOMAINS)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> AppointmentLinkPolicy.validate("https://calendar.google.com/x", List.of()))
                .hasMessageContaining("no está permitido");
    }
}
