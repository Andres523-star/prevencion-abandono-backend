package co.edu.permanencia.demo.students;

public class EstudianteNoEncontradoException extends RuntimeException {
    public EstudianteNoEncontradoException(String id) {
        super("No se encontro estudiante con el identificador: " + id);
    }
}
