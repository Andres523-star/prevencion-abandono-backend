package co.edu.permanencia.demo.auth;

import co.edu.permanencia.demo.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Login endurecido: hash, bloqueo por intentos, vencimiento y cierre de sesión, registro acotado. */
class AuthServiceTest {

    /** Reloj que avanza a mano para probar vencimientos sin esperar. */
    static final class RelojManual extends Clock {
        private Instant ahora = Instant.parse("2026-11-19T15:00:00Z");

        void avanzar(Duration d) {
            ahora = ahora.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    private final CatalogoRegistro catalogo = () -> Map.of(
            "Bogotá", List.of("Ingeniería de Software", "Derecho"),
            "Pasto", List.of("Medicina"));
    private RelojManual reloj;
    private AuthService auth;

    @BeforeEach
    void preparar() {
        reloj = new RelojManual();
        auth = new AuthService(catalogo, new LimitadorIntentos(reloj), reloj,
                Duration.ofMinutes(120), Duration.ofHours(12), new AuthService.Limites(5, 50, 30, 10));
    }

    @Test
    void lasCuentasDeLaDemoSiguenEntrandoConSusContrasenas() {
        assertThat(auth.login("profesor@ucc.edu.co", "Profesor2026", "PROFESOR", "Bogotá").rol()).isEqualTo("PROFESOR");
        assertThat(auth.login("estudiante@ucc.edu.co", "Estudiante2026", "ESTUDIANTE", "Pasto").rol()).isEqualTo("ESTUDIANTE");
        assertThat(auth.login("rector@ucc.edu.co", "Rector2026", "RECTOR", null).rol()).isEqualTo("RECTOR");
    }

    @Test
    void laSedeEquivocadaSigueRechazandose() {
        assertThatThrownBy(() -> auth.login("profesor@ucc.edu.co", "Profesor2026", "PROFESOR", "Pasto"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void correoInexistenteYClaveMalaDanElMismoMensaje() {
        String inexistente = mensaje(() -> auth.login("nadie@ucc.edu.co", "Lo-que-sea1", null, null));
        String claveMala = mensaje(() -> auth.login("profesor@ucc.edu.co", "Lo-que-sea1", null, null));
        assertThat(inexistente).isEqualTo(claveMala).isEqualTo("Correo o contraseña incorrectos");
    }

    @Test
    void cincoFallosBloqueanElCorreoYUnLoginCorrectoReiniciaElContador() {
        for (int i = 0; i < 4; i++) {
            fallar("profesor@ucc.edu.co");
        }
        auth.login("profesor@ucc.edu.co", "Profesor2026", "PROFESOR", "Bogotá");
        for (int i = 0; i < 5; i++) {
            fallar("profesor@ucc.edu.co");
        }
        assertThatThrownBy(() -> auth.login("profesor@ucc.edu.co", "Profesor2026", "PROFESOR", "Bogotá"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(((ApiException) e).getRetryAfterSeconds()).isPositive();
                });
        reloj.avanzar(Duration.ofMinutes(16));
        assertThat(auth.login("profesor@ucc.edu.co", "Profesor2026", "PROFESOR", "Bogotá").token()).isNotBlank();
    }

    @Test
    void elBloqueoDeUnaIpNoDejaSinCuentaAlRestoDelSalon() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.login("profesor@ucc.edu.co", "incorrecta1", null, null, "10.0.0.66"))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> auth.login("profesor@ucc.edu.co", "Profesor2026", "PROFESOR", "Bogotá", "10.0.0.66"))
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(auth.login("profesor@ucc.edu.co", "Profesor2026", "PROFESOR", "Bogotá", "10.0.0.7").token())
                .as("otra persona con la misma cuenta pública sigue entrando").isNotBlank();
    }

    @Test
    void unAtaqueRepartidoEntreMuchasIpTopaConElLimiteGlobalDelCorreo() {
        for (int i = 0; i < 50; i++) {
            String ip = "10.1.0." + i;
            assertThatThrownBy(() -> auth.login("rector@ucc.edu.co", "incorrecta1", null, null, ip))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> auth.login("rector@ucc.edu.co", "Rector2026", "RECTOR", null, "10.2.0.1"))
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void laSesionVencePorInactividadYPorVidaMaxima() {
        String token = auth.login("profesor@ucc.edu.co", "Profesor2026", "PROFESOR", "Bogotá").token();
        reloj.avanzar(Duration.ofMinutes(119));
        assertThat(auth.porToken(token)).isPresent();
        reloj.avanzar(Duration.ofMinutes(121));
        assertThat(auth.porToken(token)).isEmpty();

        String otro = auth.login("profesor@ucc.edu.co", "Profesor2026", "PROFESOR", "Bogotá").token();
        for (int i = 0; i < 13; i++) {
            reloj.avanzar(Duration.ofMinutes(60));
            auth.porToken(otro);
        }
        assertThat(auth.porToken(otro)).as("vida máxima de 12 h aunque se use").isEmpty();
    }

    @Test
    void cerrarSesionInvalidaElTokenAlInstante() {
        String token = auth.login("rector@ucc.edu.co", "Rector2026", "RECTOR", null).token();
        auth.cerrarSesion(token);
        assertThat(auth.porToken(token)).isEmpty();
    }

    @Test
    void elTokenEsLargoYAleatorio() {
        String a = auth.login("rector@ucc.edu.co", "Rector2026", "RECTOR", null).token();
        String b = auth.login("rector@ucc.edu.co", "Rector2026", "RECTOR", null).token();
        assertThat(a).hasSizeGreaterThanOrEqualTo(43).isNotEqualTo(b);
    }

    @Test
    void elRegistroNoPermiteDarseRolDeCoordinador() {
        assertThatThrownBy(() -> auth.registrar("Ana", "Pérez", "ana@ejemplo.test", "clave1234", "COORDINADOR",
                "Bogotá", "Derecho"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("solo crea cuentas de profesor");
    }

    @Test
    void elRegistroExigeSedeYProgramaDelCatalogo() {
        assertThatThrownBy(() -> auth.registrar("Ana", "Pérez", "ana@ejemplo.test", "clave1234", null, null, "Derecho"))
                .hasMessageContaining("sede");
        assertThatThrownBy(() -> auth.registrar("Ana", "Pérez", "ana@ejemplo.test", "clave1234", null, "Pasto", "Derecho"))
                .hasMessageContaining("no se dicta");
    }

    @Test
    void elProfesorRegistradoQuedaLimitadoASuSedeYPrograma() {
        SesionResponse sesion = auth.registrar("Ana", "Pérez", "ana@ejemplo.test", "clave1234", "PROFESOR",
                "bogota", "derecho");
        assertThat(sesion.rol()).isEqualTo("PROFESOR");
        assertThat(sesion.sede()).isEqualTo("Bogotá");
        assertThat(sesion.programa()).isEqualTo("Derecho");
    }

    @Test
    void elRegistroPideUnaContrasenaRazonable() {
        assertThatThrownBy(() -> auth.registrar("Ana", "Pérez", "ana@ejemplo.test", "abc12", null, "Bogotá", "Derecho"))
                .hasMessageContaining("8 caracteres");
    }

    @Test
    void elRegistroSeLimitaPorIp() {
        for (int i = 0; i < 10; i++) {
            auth.registrar("Ana", "Pérez", "ana" + i + "@ejemplo.test", "clave1234", null, "Bogotá", "Derecho", "1.2.3.4");
        }
        assertThatThrownBy(() -> auth.registrar("Ana", "Pérez", "otra@ejemplo.test", "clave1234", null,
                "Bogotá", "Derecho", "1.2.3.4"))
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private void fallar(String correo) {
        assertThatThrownBy(() -> auth.login(correo, "incorrecta1", null, null)).isInstanceOf(ApiException.class);
    }

    private static String mensaje(Runnable accion) {
        try {
            accion.run();
            return null;
        } catch (ApiException ex) {
            return ex.getMessage();
        }
    }
}
