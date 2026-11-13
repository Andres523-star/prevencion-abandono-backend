package co.edu.permanencia.demo.reports;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.Alcance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reportes agregados con filtros por programa, sede, semestre, causa y riesgo. */
@RestController
@RequestMapping("/api/reportes")
public class ReporteController {

    private final ReporteService reportes;

    public ReporteController(ReporteService reportes) {
        this.reportes = reportes;
    }

    @GetMapping
    public Reporte reporte(@RequestParam(required = false) String carrera,
                           @RequestParam(required = false) String sede,
                           @RequestParam(required = false) String facultad,
                           @RequestParam(required = false) Integer semestre,
                           @RequestParam(required = false) String categoria,
                           @RequestParam(required = false) String riesgo,
                           @RequestParam(required = false) String grupo,
                           @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        String carreraPermitida = Alcance.carrera(usuario, carrera);
        String sedePermitida = Alcance.sede(usuario, sede);
        String facultadPermitida = Alcance.facultad(usuario, facultad);
        return reportes.generar(carreraPermitida, sedePermitida, facultadPermitida, semestre,
                Alcance.semestres(usuario, semestre), categoria, riesgo, grupo);
    }
}
