package co.edu.permanencia.demo.common;

import co.edu.permanencia.demo.auth.Usuario;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El alcance por rol falla cerrado: sin sede o programa no se ve nada. */
class AlcanceTest {

    private static Usuario usuario(String rol, String programa, String sede, String facultad) {
        return new Usuario(1, "N", "A", "x@ucc.edu.co", rol, programa, sede, null, facultad, null);
    }

    @Test
    void unProfesorSinSedeNoVeTodasLasSedes() {
        Usuario sinSede = usuario("PROFESOR", "Derecho", null, null);
        assertThatThrownBy(() -> Alcance.sede(sinSede, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("alcance");
        assertThat(Alcance.cubre(sinSede, 5, "Derecho", "Facultad de Derecho", "Neiva", 3)).isFalse();
    }

    @Test
    void unCoordinadorSinSedeNoVeNada() {
        Usuario sinSede = usuario("COORDINADOR_SEDE", null, " ", null);
        assertThatThrownBy(() -> Alcance.sede(sinSede, "Neiva")).isInstanceOf(ApiException.class);
        assertThat(Alcance.cubre(sinSede, 5, "Derecho", "F", "Neiva", 3)).isFalse();
    }

    @Test
    void unRolDesconocidoNoVeNada() {
        Usuario raro = usuario("ADMIN_FALSO", null, null, null);
        assertThatThrownBy(() -> Alcance.carrera(raro, null)).isInstanceOf(ApiException.class);
        assertThat(Alcance.cubre(raro, 5, "Derecho", "F", "Neiva", 3)).isFalse();
    }

    @Test
    void lasCuentasConAlcanceCompletoSiguenIgual() {
        Usuario coordinador = usuario("COORDINADOR_SEDE", null, "Bogotá", null);
        assertThat(Alcance.sede(coordinador, "Neiva")).isEqualTo("Bogotá");
        assertThat(Alcance.cubre(coordinador, 5, "Derecho", "F", "Bogotá", 3)).isTrue();
        assertThat(Alcance.cubre(coordinador, 5, "Derecho", "F", "Neiva", 3)).isFalse();

        Usuario rector = usuario("RECTOR", null, null, null);
        assertThat(Alcance.sede(rector, "Neiva")).isEqualTo("Neiva");
        assertThat(Alcance.cubre(rector, 5, "Derecho", "F", "Neiva", 3)).isTrue();

        Usuario decano = usuario("DECANO", null, "Pasto", "Facultad de Ingeniería");
        assertThat(Alcance.facultad(decano, null)).isEqualTo("Facultad de Ingeniería");
        assertThat(Alcance.sede(decano, null)).isNull();
    }
}
