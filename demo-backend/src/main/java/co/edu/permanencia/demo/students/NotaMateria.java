package co.edu.permanencia.demo.students;

/**
 * Nota de una materia con el detalle que necesita la ficha y el portal del estudiante.
 *
 * <p>Una materia puede estar homologada desde otro semestre o grupo; por eso guarda su propio
 * semestre y grupo además de las faltas y cuántas de ellas ya fueron justificadas. La fecha
 * corresponde al corte en el que se registró la nota.</p>
 */
public record NotaMateria(
        String nombre,
        double nota,
        int faltas,
        int faltasJustificadas,
        int semestre,
        String grupo,
        boolean homologada,
        String fecha
) {

    /** Constructor corto para datos históricos que no necesitan el detalle completo. */
    public NotaMateria(String nombre, double nota) {
        this(nombre, nota, 0, 0, 0, "", false, "");
    }

    /** Constructor sin fecha; el servicio de perfil la completa con el corte del período. */
    public NotaMateria(String nombre, double nota, int faltas, int faltasJustificadas,
                       int semestre, String grupo, boolean homologada) {
        this(nombre, nota, faltas, faltasJustificadas, semestre, grupo, homologada, "");
    }

    /** Copia con el semestre y el grupo de la materia. */
    public NotaMateria conUbicacion(int semestre, String grupo, boolean homologada) {
        return new NotaMateria(nombre, nota, faltas, faltasJustificadas, semestre, grupo, homologada, fecha);
    }

    /** Copia con la fecha del corte en la que se registró la nota. */
    public NotaMateria conFecha(String fecha) {
        return new NotaMateria(nombre, nota, faltas, faltasJustificadas, semestre, grupo, homologada, fecha);
    }

    public int faltasPendientes() {
        return Math.max(0, faltas - faltasJustificadas);
    }
}
