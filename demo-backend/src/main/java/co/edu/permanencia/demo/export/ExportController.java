package co.edu.permanencia.demo.export;

import co.edu.permanencia.demo.auth.Usuario;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Descargas de la plataforma: CSV, ficha imprimible y ZIP con todo el alcance. */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService export;

    public ExportController(ExportService export) {
        this.export = export;
    }

    @GetMapping("/estudiantes.csv")
    public ResponseEntity<byte[]> estudiantes(
            @RequestParam(required = false) String carrera,
            @RequestParam(required = false) String sede,
            @RequestParam(required = false) String facultad,
            @RequestParam(required = false) Integer semestre,
            @RequestParam(required = false) String grupo,
            @RequestParam(required = false) String riesgo,
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return archivo(export.estudiantesCsv(usuario, carrera, sede, facultad, semestre, grupo, riesgo),
                export.nombreArchivo("estudiantes", "csv"), "text/csv; charset=UTF-8");
    }

    @GetMapping("/reporte.csv")
    public ResponseEntity<byte[]> reporte(
            @RequestParam(required = false) String carrera,
            @RequestParam(required = false) String sede,
            @RequestParam(required = false) String facultad,
            @RequestParam(required = false) Integer semestre,
            @RequestParam(required = false) String grupo,
            @RequestParam(required = false) String riesgo,
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return archivo(export.reporteCsv(usuario, carrera, sede, facultad, semestre, grupo, riesgo),
                export.nombreArchivo("reporte", "csv"), "text/csv; charset=UTF-8");
    }

    @GetMapping("/estudiantes/{id}/ficha.csv")
    public ResponseEntity<byte[]> fichaCsv(@PathVariable int id,
                                           @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return archivo(export.estudianteCsv(usuario, id),
                export.nombreArchivo("ficha-" + id, "csv"), "text/csv; charset=UTF-8");
    }

    @GetMapping("/estudiantes/{id}/ficha.html")
    public ResponseEntity<byte[]> fichaHtml(@PathVariable int id,
                                            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return archivo(export.estudianteHtml(usuario, id),
                export.nombreArchivo("ficha-" + id, "html"), "text/html; charset=UTF-8");
    }

    @GetMapping("/todo.zip")
    public ResponseEntity<byte[]> todo(
            @RequestParam(required = false) String carrera,
            @RequestParam(required = false) String sede,
            @RequestParam(required = false) String facultad,
            @RequestParam(required = false) Integer semestre,
            @RequestParam(required = false) String grupo,
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return archivo(export.todoZip(usuario, carrera, sede, facultad, semestre, grupo),
                export.nombreArchivo("completo", "zip"), "application/zip");
    }

    private ResponseEntity<byte[]> archivo(byte[] contenido, String nombre, String tipo) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nombre + "\"")
                .contentType(MediaType.parseMediaType(tipo))
                .body(contenido);
    }
}
