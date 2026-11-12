package co.edu.permanencia.demo.students;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.Alcance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Catálogos para los filtros del panel, ya recortados según el rol de la sesión. */
@RestController
@RequestMapping("/api/catalogos")
public class CatalogoController {

    private final EstudianteService estudiantes;

    public CatalogoController(EstudianteService estudiantes) {
        this.estudiantes = estudiantes;
    }

    @GetMapping
    public Catalogo catalogo(@RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        String programaFijo = Alcance.carrera(usuario, null);
        String sedeFija = Alcance.sede(usuario, null);
        String facultadFija = Alcance.facultad(usuario, null);

        List<String> sedes;
        if (sedeFija != null) {
            sedes = List.of(sedeFija);
        } else if (facultadFija != null) {
            sedes = estudiantes.sedesDeFacultad(facultadFija);
        } else {
            sedes = estudiantes.sedes();
        }

        List<String> programas;
        if (programaFijo != null) {
            programas = List.of(programaFijo);
        } else if (facultadFija != null) {
            programas = estudiantes.programasDeFacultad(facultadFija);
        } else if (sedeFija != null) {
            programas = estudiantes.programasDe(sedeFija);
        } else {
            programas = estudiantes.programas();
        }

        List<String> facultades;
        if (programaFijo != null) {
            String facultad = estudiantes.facultadDe(programaFijo);
            facultades = facultad == null ? estudiantes.facultades() : List.of(facultad);
        } else if (facultadFija != null) {
            facultades = List.of(facultadFija);
        } else if (sedeFija != null) {
            facultades = estudiantes.facultadesDe(sedeFija);
        } else {
            facultades = estudiantes.facultades();
        }

        // El profesor solo ve los semestres que dicta; los demás ven la carrera completa.
        List<Integer> semestres = Alcance.semestres(usuario);
        if (semestres == null) {
            semestres = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }

        return new Catalogo(sedes, facultades, programas,
                EstudianteService.CATEGORIAS, semestres,
                List.of("ALTO", "MEDIO", "BAJO"), sedeFija, programaFijo,
                EstudianteService.GRUPOS);
    }

    public record Catalogo(
            List<String> sedes,
            List<String> facultades,
            List<String> programas,
            List<String> categorias,
            List<Integer> semestres,
            List<String> riesgos,
            String sedeFija,
            String programaFijo,
            List<String> grupos
    ) {
    }
}
