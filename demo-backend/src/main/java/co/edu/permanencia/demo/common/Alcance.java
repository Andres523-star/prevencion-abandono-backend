package co.edu.permanencia.demo.common;

import co.edu.permanencia.demo.auth.Usuario;

import java.util.ArrayList;
import java.util.List;

/**
 * Reglas de acceso a los datos según la jerarquía institucional.
 *
 * <p>El profesor solo ve su programa, su sede y los semestres que dicta; el jefe de programa ve
 * su programa y su sede completos; el coordinador de sede ve toda una sede; el decano ve una
 * facultad completa en todas las sedes; el rector no tiene restricción. El estudiante solo se ve a
 * sí mismo.</p>
 *
 * <p><b>Falla cerrado</b> (como el ABAC del backend principal): si a una cuenta con alcance
 * restringido le falta el programa, la sede o la facultad que la delimitan, se responde 403 en
 * lugar de mostrarle todo, y un rol desconocido no ve nada.</p>
 */
public final class Alcance {

    private static final String SIN_ALCANCE = "Tu cuenta no tiene un alcance asignado. Contacta al administrador.";

    private Alcance() {
    }

    /** Programa que el usuario puede consultar (null = todos). */
    public static String carrera(Usuario usuario, String solicitada) {
        if (usuario == null) {
            return solicitada;
        }
        return switch (usuario.rol()) {
            case "PROFESOR", "JEFE_PROGRAMA", "ESTUDIANTE" -> requerido(usuario.programa());
            case "COORDINADOR_SEDE", "DECANO", "RECTOR" -> solicitada;
            default -> throw ApiException.forbidden(SIN_ALCANCE);
        };
    }

    /** Sede que el usuario puede consultar (null = todas). */
    public static String sede(Usuario usuario, String solicitada) {
        if (usuario == null) {
            return solicitada;
        }
        return switch (usuario.rol()) {
            case "PROFESOR", "JEFE_PROGRAMA", "COORDINADOR_SEDE", "ESTUDIANTE" -> requerido(usuario.sede());
            // El decano ya no se limita a una sede: su alcance es la facultad completa.
            case "DECANO", "RECTOR" -> solicitada;
            default -> throw ApiException.forbidden(SIN_ALCANCE);
        };
    }

    /** Facultad que el usuario puede consultar (null = todas). */
    public static String facultad(Usuario usuario, String solicitada) {
        if (usuario == null) {
            return solicitada;
        }
        return switch (usuario.rol()) {
            case "DECANO" -> requerido(usuario.facultad());
            case "PROFESOR", "JEFE_PROGRAMA", "COORDINADOR_SEDE", "ESTUDIANTE", "RECTOR" -> solicitada;
            default -> throw ApiException.forbidden(SIN_ALCANCE);
        };
    }

    private static String requerido(String valor) {
        if (texto(valor)) {
            throw ApiException.forbidden(SIN_ALCANCE);
        }
        return valor;
    }

    /** Semestres que el profesor dicta (null = todos). */
    public static List<Integer> semestres(Usuario usuario) {
        if (usuario == null || !"PROFESOR".equals(usuario.rol()) || texto(usuario.semestres())) {
            return null;
        }
        List<Integer> valores = new ArrayList<>();
        for (String parte : usuario.semestres().split(",")) {
            try {
                valores.add(Integer.parseInt(parte.trim()));
            } catch (NumberFormatException ignorado) {
                // Un valor inválido se ignora.
            }
        }
        return valores.isEmpty() ? null : List.copyOf(valores);
    }

    /** Semestres a filtrar: los del profesor o el que se solicitó explícitamente. */
    public static List<Integer> semestres(Usuario usuario, Integer solicitado) {
        List<Integer> permitidos = semestres(usuario);
        if (permitidos == null) {
            return solicitado == null ? null : List.of(solicitado);
        }
        if (solicitado == null) {
            return permitidos;
        }
        return permitidos.contains(solicitado) ? List.of(solicitado) : List.of(-1);
    }

    /** Id del estudiante asociado a la cuenta (solo aplica al rol ESTUDIANTE). */
    public static Integer estudianteId(Usuario usuario) {
        return usuario == null ? null : usuario.estudianteId();
    }

    /** Indica si el usuario puede consultar la ficha de un estudiante concreto. */
    public static boolean cubre(Usuario usuario, int estudianteId, String programa, String facultad,
                                String sede, int semestre) {
        if (usuario == null) {
            return true;
        }
        if ("ESTUDIANTE".equals(usuario.rol())) {
            return esPropio(usuario, estudianteId);
        }
        return switch (usuario.rol()) {
            case "RECTOR" -> true;
            case "PROFESOR" -> igual(usuario.programa(), programa) && igual(usuario.sede(), sede)
                    && (semestres(usuario) == null || semestres(usuario).contains(semestre));
            case "JEFE_PROGRAMA" -> igual(usuario.programa(), programa) && igual(usuario.sede(), sede);
            case "COORDINADOR_SEDE" -> igual(usuario.sede(), sede);
            case "DECANO" -> igual(usuario.facultad(), facultad);
            default -> false;
        };
    }

    /**
     * Variante por id para el rol ESTUDIANTE. Los demás roles se validan con
     * {@link #cubre(Usuario, int, String, String, String, int)} usando los datos del estudiante.
     */
    public static boolean esPropio(Usuario usuario, int estudianteId) {
        return usuario != null && "ESTUDIANTE".equals(usuario.rol())
                && usuario.estudianteId() != null && usuario.estudianteId() == estudianteId;
    }

    /** Un alcance vacío no cubre nada (falla cerrado). */
    private static boolean igual(String alcance, String valor) {
        return !texto(alcance) && valor != null && alcance.equalsIgnoreCase(valor);
    }

    private static boolean texto(String valor) {
        return valor == null || valor.isBlank();
    }
}
