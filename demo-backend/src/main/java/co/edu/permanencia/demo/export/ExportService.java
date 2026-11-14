package co.edu.permanencia.demo.export;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.acompanamiento.AccionAcompanamiento;
import co.edu.permanencia.demo.acompanamiento.AcompanamientoService;
import co.edu.permanencia.demo.common.Alcance;
import co.edu.permanencia.demo.justificaciones.Justificacion;
import co.edu.permanencia.demo.justificaciones.JustificacionService;
import co.edu.permanencia.demo.reports.Reporte;
import co.edu.permanencia.demo.reports.ReporteService;
import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import co.edu.permanencia.demo.students.NotaMateria;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exportaciones de la demo: CSV para análisis, ficha HTML imprimible y un ZIP con todo el alcance
 * del usuario. Todas las consultas pasan por {@link Alcance} para que nadie descargue datos que no
 * puede ver.
 */
@Service
public class ExportService {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter MARCA = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter LEGIBLE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final EstudianteService estudiantes;
    private final ReporteService reportes;
    private final JustificacionService justificaciones;
    private final AcompanamientoService acompanamiento;

    public ExportService(EstudianteService estudiantes, ReporteService reportes,
                         JustificacionService justificaciones, AcompanamientoService acompanamiento) {
        this.estudiantes = estudiantes;
        this.reportes = reportes;
        this.justificaciones = justificaciones;
        this.acompanamiento = acompanamiento;
    }

    public byte[] estudiantesCsv(Usuario usuario, String carrera, String sede, String facultad,
                                 Integer semestre, String grupo, String riesgo) {
        List<Estudiante> lista = filtrar(usuario, carrera, sede, facultad, semestre, grupo, riesgo);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, lista.size() * 340));
        try {
            bos.write(BOM);
            escribirEstudiantesCsv(bos, lista);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo generar el CSV de estudiantes", ex);
        }
        return bos.toByteArray();
    }

    /**
     * Escribe el CSV de estudiantes por bloques para no duplicar en memoria un millón de filas
     * (el ZIP de descarga completa también usa esta ruta).
     */
    private void escribirEstudiantesCsv(OutputStream salida, List<Estudiante> lista)
            throws IOException {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("ID,Nombre,Apellido,Cédula,Programa,Facultad,Sede,Semestre,Grupo,Correo,Teléfono,")
                .append("Promedio,Faltas,Riesgo,Score,Estado,Fecha ingreso,Categoría,Materias,Notas,Faltas materias\n");
        for (Estudiante e : lista) {
            sb.append(e.id()).append(',')
                    .append(csv(e.nombre())).append(',')
                    .append(csv(e.apellido())).append(',')
                    .append(csv(e.cedula())).append(',')
                    .append(csv(e.carrera())).append(',')
                    .append(csv(e.facultad())).append(',')
                    .append(csv(e.sede())).append(',')
                    .append(e.semestre()).append(',')
                    .append(csv(e.grupo())).append(',')
                    .append(csv(e.email())).append(',')
                    .append(csv(e.telefono())).append(',')
                    .append(num(e.promedio())).append(',')
                    .append(e.faltas()).append(',')
                    .append(csv(e.riesgoActual())).append(',')
                    .append(e.riesgoScore()).append(',')
                    .append(csv(e.estado())).append(',')
                    .append(csv(e.fechaIngreso())).append(',')
                    .append(csv(e.categoria())).append(',')
                    .append(csv(materias(e))).append(',')
                    .append(csv(notas(e))).append(',')
                    .append(csv(faltasMaterias(e))).append('\n');
            if (sb.length() >= 64 * 1024) {
                salida.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            salida.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public byte[] estudianteCsv(Usuario usuario, int id) {
        Estudiante e = autorizado(usuario, id);
        StringBuilder sb = new StringBuilder();
        sb.append("Campo,Valor\n");
        fila(sb, "ID", String.valueOf(e.id()));
        fila(sb, "Nombre", e.nombre() + " " + e.apellido());
        fila(sb, "Cédula", e.cedula());
        fila(sb, "Programa", e.carrera());
        fila(sb, "Facultad", e.facultad());
        fila(sb, "Sede", e.sede());
        fila(sb, "Semestre", e.semestre() + ".º");
        fila(sb, "Grupo", e.grupo());
        fila(sb, "Correo", e.email());
        fila(sb, "Teléfono", e.telefono());
        fila(sb, "Promedio", num(e.promedio()));
        fila(sb, "Faltas", String.valueOf(e.faltas()));
        fila(sb, "Riesgo", e.riesgoActual() + " (" + e.riesgoScore() + ")");
        fila(sb, "Estado", e.estado());
        fila(sb, "Categoría", e.categoria());
        fila(sb, "Motivo", e.razon());
        sb.append("\nMateria,Nota,Fecha del corte,Faltas,Justificadas,Pendientes,Semestre,Grupo,Homologada\n");
        for (NotaMateria m : e.materias()) {
            sb.append(csv(m.nombre())).append(',').append(num(m.nota())).append(',')
                    .append(csv(m.fecha())).append(',')
                    .append(m.faltas()).append(',').append(m.faltasJustificadas()).append(',')
                    .append(m.faltasPendientes()).append(',').append(m.semestre()).append(',')
                    .append(csv(m.grupo())).append(',').append(m.homologada() ? "Sí" : "No")
                    .append('\n');
        }
        sb.append("\nHistorial de acompañamiento\n")
                .append("Fecha,Acción,Descripción,Responsable,Estado\n");
        for (AccionAcompanamiento a : acompanamiento.de(e.id())) {
            sb.append(csv(a.fecha())).append(',').append(csv(a.titulo())).append(',')
                    .append(csv(a.descripcion())).append(',').append(csv(a.responsable())).append(',')
                    .append(csv(a.estado())).append('\n');
        }
        return conBom(sb.toString());
    }

    public byte[] estudianteHtml(Usuario usuario, int id) {
        Estudiante e = autorizado(usuario, id);
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">")
                .append("<title>Ficha de ").append(html(e.nombre() + " " + e.apellido()))
                .append("</title><style>")
                .append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:36px;color:#12251f}")
                .append("h1{font-size:26px;margin:0 0 4px}h2{font-size:16px;margin:26px 0 8px;color:#0f4c40}")
                .append(".meta{color:#5b6b64;font-size:13px;margin-bottom:18px}")
                .append(".grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin:16px 0}")
                .append(".dato{border:1px solid #dce7e2;border-radius:10px;padding:10px 12px}")
                .append(".dato span{display:block;font-size:11px;text-transform:uppercase;letter-spacing:.06em;color:#7b8a83}")
                .append(".dato strong{font-size:15px}")
                .append("table{width:100%;border-collapse:collapse;font-size:12.5px;margin-top:8px}")
                .append("th,td{border-bottom:1px solid #e6eeea;padding:7px 8px;text-align:left}")
                .append("th{background:#f2f8f5;color:#284a3f}")
                .append(".alto{color:#b3261e;font-weight:600}.medio{color:#a36a00;font-weight:600}.bajo{color:#1c7c4a;font-weight:600}")
                .append("footer{margin-top:28px;font-size:11px;color:#7b8a83}")
                .append("@media print{body{margin:14mm}.no-print{display:none}}")
                .append("</style></head><body>");
        sb.append("<h1>").append(html(e.nombre() + " " + e.apellido())).append("</h1>");
        sb.append("<div class=\"meta\">").append(html(e.carrera())).append(" · ")
                .append(html(e.facultad())).append(" · Sede ").append(html(e.sede()))
                .append(" · ").append(e.semestre()).append(".º semestre · Grupo ")
                .append(html(e.grupo())).append("</div>");
        sb.append("<div class=\"grid\">");
        dato(sb, "Promedio", num(e.promedio()));
        dato(sb, "Faltas", String.valueOf(e.faltas()));
        dato(sb, "Riesgo", e.riesgoActual() + " (" + e.riesgoScore() + ")");
        dato(sb, "Estado", e.estado());
        dato(sb, "Cédula", e.cedula());
        dato(sb, "Correo", e.email());
        dato(sb, "Teléfono", e.telefono());
        dato(sb, "Ingreso", e.fechaIngreso());
        dato(sb, "Categoría", e.categoria());
        sb.append("</div>");

        sb.append("<h2>Materias, notas y faltas</h2><table><thead><tr>")
                .append("<th>Materia</th><th>Nota</th><th>Fecha del corte</th><th>Faltas</th>")
                .append("<th>Justificadas</th>")
                .append("<th>Pendientes</th><th>Semestre</th><th>Grupo</th><th>Homologada</th>")
                .append("</tr></thead><tbody>");
        for (NotaMateria m : e.materias()) {
            sb.append("<tr><td>").append(html(m.nombre())).append("</td><td>")
                    .append(num(m.nota())).append("</td><td>")
                    .append(m.fecha() == null || m.fecha().isBlank() ? "—" : html(m.fecha()))
                    .append("</td><td>").append(m.faltas())
                    .append("</td><td>").append(m.faltasJustificadas()).append("</td><td>")
                    .append(m.faltasPendientes()).append("</td><td>").append(m.semestre())
                    .append(".º</td><td>").append(html(m.grupo())).append("</td><td>")
                    .append(m.homologada() ? "Sí" : "No").append("</td></tr>");
        }
        sb.append("</tbody></table>");

        sb.append("<h2>Evolución del riesgo</h2><table><thead><tr><th>Corte</th><th>Score</th>")
                .append("<th>Nivel</th></tr></thead><tbody>");
        List<Integer> historial = e.historialRiesgo();
        for (int i = 0; i < historial.size(); i++) {
            int score = historial.get(i);
            String nivel = score >= 70 ? "ALTO" : score >= 45 ? "MEDIO" : "BAJO";
            sb.append("<tr><td>").append(i + 1).append("</td><td>").append(score)
                    .append("</td><td class=\"").append(nivel.toLowerCase(Locale.ROOT)).append("\">")
                    .append(nivel).append("</td></tr>");
        }
        sb.append("</tbody></table>");

        sb.append("<h2>Historial de acompañamiento</h2><table><thead><tr><th>Fecha</th>")
                .append("<th>Acción</th><th>Descripción</th><th>Responsable</th><th>Estado</th>")
                .append("</tr></thead><tbody>");
        for (AccionAcompanamiento a : acompanamiento.de(e.id())) {
            sb.append("<tr><td>").append(html(a.fecha())).append("</td><td>")
                    .append(html(a.titulo())).append("</td><td>").append(html(a.descripcion()))
                    .append("</td><td>").append(html(a.responsable())).append("</td><td>")
                    .append(html(a.estado())).append("</td></tr>");
        }
        sb.append("</tbody></table>");

        sb.append("<h2>Motivo de acompañamiento</h2><p>").append(html(e.razon())).append("</p>");
        sb.append("<footer>Generado por Permanencia el ")
                .append(LocalDateTime.now().format(LEGIBLE))
                .append(". Documento de demostración con datos ficticios.</footer>");
        sb.append("</body></html>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] reporteCsv(Usuario usuario, String carrera, String sede, String facultad,
                             Integer semestre, String grupo, String riesgo) {
        Reporte r = reportes.generar(Alcance.carrera(usuario, carrera), Alcance.sede(usuario, sede),
                Alcance.facultad(usuario, facultad), semestre, Alcance.semestres(usuario, semestre),
                null, riesgo, grupo);
        StringBuilder sb = new StringBuilder("Indicador,Valor\n");
        fila(sb, "Total estudiantes", String.valueOf(r.totalEstudiantes()));
        fila(sb, "Riesgo alto", String.valueOf(r.riesgoAlto()));
        fila(sb, "Riesgo medio", String.valueOf(r.riesgoMedio()));
        fila(sb, "Riesgo bajo", String.valueOf(r.riesgoBajo()));
        fila(sb, "Retirados", String.valueOf(r.retirados()));
        fila(sb, "Aplazados", String.valueOf(r.aplazados()));
        fila(sb, "Activos", String.valueOf(r.activos()));
        fila(sb, "Deserción %", num(r.porcentajeDesercion()));
        fila(sb, "Promedio general", num(r.promedioGeneral()));
        fila(sb, "Faltas", String.valueOf(r.totalFaltas()));
        fila(sb, "Causa principal", r.categoriaPrincipal());
        sb.append('\n');
        sb.append("Programa,Total,Alto,Medio,Bajo,Retirados,Aplazados\n");
        for (Reporte.ConteoCarrera c : r.porCarrera()) {
            sb.append(csv(c.carrera())).append(',').append(c.total()).append(',').append(c.alto())
                    .append(',').append(c.medio()).append(',').append(c.bajo()).append(',')
                    .append(c.retirados()).append(',').append(c.aplazados()).append('\n');
        }
        sb.append("\nGrupo,Total,Alto,Medio,Bajo,Retirados\n");
        for (Reporte.ConteoGrupo g : r.porGrupo()) {
            sb.append(csv(g.grupo())).append(',').append(g.total()).append(',').append(g.alto())
                    .append(',').append(g.medio()).append(',').append(g.bajo()).append(',')
                    .append(g.retirados()).append('\n');
        }
        sb.append("\nSede,Total,Alto,Medio,Bajo,Retirados\n");
        for (Reporte.ConteoSede s : r.porSede()) {
            sb.append(csv(s.sede())).append(',').append(s.total()).append(',').append(s.alto())
                    .append(',').append(s.medio()).append(',').append(s.bajo()).append(',')
                    .append(s.retirados()).append('\n');
        }
        return conBom(sb.toString());
    }

    /** ZIP con el detalle completo del alcance del usuario. */
    public byte[] todoZip(Usuario usuario, String carrera, String sede, String facultad,
                          Integer semestre, String grupo) {
        List<Estudiante> lista = filtrar(usuario, carrera, sede, facultad, semestre, grupo, null);
        Reporte r = reportes.generar(Alcance.carrera(usuario, carrera), Alcance.sede(usuario, sede),
                Alcance.facultad(usuario, facultad), semestre, Alcance.semestres(usuario, semestre),
                null, null, grupo);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        String marca = LocalDateTime.now().format(MARCA);
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            agregar(zip, "LEEME.txt", ("Exportación completa de Permanencia\n"
                    + "Generada: " + LocalDateTime.now().format(LEGIBLE) + "\n"
                    + "Alcance: " + (usuario == null ? "demo" : usuario.rol()) + "\n"
                    + "Estudiantes incluidos: " + lista.size() + "\n\n"
                    + "Archivos:\n"
                    + "- estudiantes.csv: una fila por estudiante con notas y faltas.\n"
                    + "- materias.csv: una fila por materia de cada estudiante.\n"
                    + "- resumen.csv: indicadores generales del alcance.\n"
                    + "- por_carrera.csv, por_sede.csv, por_semestre.csv, por_grupo.csv, "
                    + "por_categoria.csv.\n"
                    + "- justificaciones.csv: radicados de faltas.\n"
                    + "- fichas/: ficha imprimible de los primeros 300 estudiantes.\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("estudiantes.csv"));
            zip.write(BOM);
            escribirEstudiantesCsv(zip, lista);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("materias.csv"));
            zip.write(BOM);
            escribirMateriasCsv(zip, lista);
            zip.closeEntry();
            agregar(zip, "resumen.csv", reporteCsv(usuario, carrera, sede, facultad, semestre, grupo,
                    null));
            agregar(zip, "por_carrera.csv", tabla(r.porCarrera().stream()
                    .map(c -> c.carrera() + "," + c.total() + "," + c.alto() + "," + c.medio()
                            + "," + c.bajo() + "," + c.retirados())
                    .toList(), "Programa,Total,Alto,Medio,Bajo,Retirados"));
            agregar(zip, "por_sede.csv", tabla(r.porSede().stream()
                    .map(s -> s.sede() + "," + s.total() + "," + s.alto() + "," + s.medio()
                            + "," + s.bajo() + "," + s.retirados())
                    .toList(), "Sede,Total,Alto,Medio,Bajo,Retirados"));
            agregar(zip, "por_semestre.csv", tabla(r.porSemestre().stream()
                    .map(s -> s.semestre() + "," + s.total() + "," + s.alto() + "," + s.medio()
                            + "," + s.bajo() + "," + s.retirados())
                    .toList(), "Semestre,Total,Alto,Medio,Bajo,Retirados"));
            agregar(zip, "por_grupo.csv", tabla(r.porGrupo().stream()
                    .map(g -> g.grupo() + "," + g.total() + "," + g.alto() + "," + g.medio()
                            + "," + g.bajo() + "," + g.retirados())
                    .toList(), "Grupo,Total,Alto,Medio,Bajo,Retirados"));
            agregar(zip, "por_categoria.csv", tabla(r.porCategoria().stream()
                    .map(c -> csv(c.categoria()) + "," + c.total() + "," + num(c.porcentaje()) + ","
                            + c.alto())
                    .toList(), "Categoría,Total,Porcentaje,Alto"));
            agregar(zip, "justificaciones.csv", justificacionesCsv(usuario));
            zip.putNextEntry(new ZipEntry("fichas/"));
            zip.closeEntry();
            for (int i = 0; i < Math.min(300, lista.size()); i++) {
                Estudiante e = lista.get(i);
                String nombre = ("fichas/" + String.format("%05d", e.id()) + "-"
                        + slug(e.nombre() + "-" + e.apellido()) + ".html");
                agregar(zip, nombre, estudianteHtml(usuario, e.id()));
            }
            zip.finish();
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo generar el ZIP", ex);
        }
        return bos.toByteArray();
    }

    public String nombreArchivo(String base, String extension) {
        return "permanencia-" + base + "-" + LocalDateTime.now().format(MARCA) + "." + extension;
    }

    private List<Estudiante> filtrar(Usuario usuario, String carrera, String sede, String facultad,
                                     Integer semestre, String grupo, String riesgo) {
        return estudiantes.listar(null, Alcance.carrera(usuario, carrera),
                Alcance.sede(usuario, sede), Alcance.facultad(usuario, facultad), semestre,
                Alcance.semestres(usuario, semestre), null, riesgo, null, grupo, null);
    }

    private Estudiante autorizado(Usuario usuario, int id) {
        Estudiante e = estudiantes.porId(id);
        if (!Alcance.cubre(usuario, e.id(), e.carrera(), e.facultad(), e.sede(), e.semestre())) {
            throw co.edu.permanencia.demo.common.ApiException.forbidden(
                    "No tienes permiso para descargar esta ficha");
        }
        return e;
    }

    /** Una fila por materia, también por bloques para soportar el millón de estudiantes. */
    private void escribirMateriasCsv(OutputStream salida, List<Estudiante> lista)
            throws IOException {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("ID estudiante,Estudiante,Cédula,Programa,Sede,Semestre estudiante,Grupo estudiante,")
                .append("Materia,Nota,Fecha del corte,Faltas,Justificadas,Pendientes,Semestre materia,Grupo materia,Homologada\n");
        for (Estudiante e : lista) {
            for (NotaMateria m : e.materias()) {
                sb.append(e.id()).append(',').append(csv(e.nombre() + " " + e.apellido())).append(',')
                        .append(csv(e.cedula())).append(',').append(csv(e.carrera())).append(',')
                        .append(csv(e.sede())).append(',').append(e.semestre()).append(',')
                        .append(csv(e.grupo())).append(',').append(csv(m.nombre())).append(',')
                        .append(num(m.nota())).append(',').append(csv(m.fecha())).append(',')
                        .append(m.faltas()).append(',')
                        .append(m.faltasJustificadas()).append(',').append(m.faltasPendientes())
                        .append(',').append(m.semestre()).append(',').append(csv(m.grupo()))
                        .append(',').append(m.homologada() ? "Sí" : "No").append('\n');
                if (sb.length() >= 64 * 1024) {
                    salida.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    sb.setLength(0);
                }
            }
        }
        if (sb.length() > 0) {
            salida.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private byte[] justificacionesCsv(Usuario usuario) {
        StringBuilder sb = new StringBuilder();
        sb.append("Radicado,Estudiante,Materia,Semestre,Grupo,Fecha falta,Motivo,Estado,Correo,Evidencia,Creada\n");
        for (Justificacion j : justificaciones.listar(usuario)) {
            sb.append(csv(j.radicado())).append(',').append(csv(j.estudiante())).append(',')
                    .append(csv(j.materia())).append(',').append(j.semestre()).append(',')
                    .append(csv(j.grupo())).append(',').append(csv(j.fechaFalta())).append(',')
                    .append(csv(j.motivo())).append(',').append(csv(j.estado())).append(',')
                    .append(csv(j.correoDestino())).append(',')
                    .append(csv(j.evidencia() != null && j.evidencia().length() > 120
                            ? j.evidencia().substring(0, 120) + "…" : j.evidencia()))
                    .append(',').append(csv(j.creadaEn())).append('\n');
        }
        return conBom(sb.toString());
    }

    private static byte[] tabla(List<String> filas, String encabezado) {
        StringBuilder sb = new StringBuilder(encabezado).append('\n');
        filas.forEach(f -> sb.append(f).append('\n'));
        return conBom(sb.toString());
    }

    private static void agregar(ZipOutputStream zip, String nombre, byte[] contenido)
            throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(contenido);
        zip.closeEntry();
    }

    private static void fila(StringBuilder sb, String campo, String valor) {
        sb.append(csv(campo)).append(',').append(csv(valor)).append('\n');
    }

    private static void dato(StringBuilder sb, String etiqueta, String valor) {
        sb.append("<div class=\"dato\"><span>").append(html(etiqueta)).append("</span><strong>")
                .append(html(valor)).append("</strong></div>");
    }

    private static String materias(Estudiante e) {
        return String.join(" | ", e.materias().stream().map(NotaMateria::nombre).toList());
    }

    private static String notas(Estudiante e) {
        return String.join(" | ", e.materias().stream()
                .map(m -> String.format(Locale.US, "%.1f", m.nota())).toList());
    }

    private static String faltasMaterias(Estudiante e) {
        return String.join(" | ", e.materias().stream()
                .map(m -> String.valueOf(m.faltas())).toList());
    }

    private static byte[] conBom(String contenido) {
        byte[] texto = contenido.getBytes(StandardCharsets.UTF_8);
        byte[] salida = new byte[BOM.length + texto.length];
        System.arraycopy(BOM, 0, salida, 0, BOM.length);
        System.arraycopy(texto, 0, salida, BOM.length, texto.length);
        return salida;
    }

    private static String csv(String valor) {
        if (valor == null) {
            return "";
        }
        String limpio = valor.replace("\r", " ").replace("\n", " ");
        if (limpio.contains(",") || limpio.contains("\"") || limpio.contains(";")) {
            return "\"" + limpio.replace("\"", "\"\"") + "\"";
        }
        return limpio;
    }

    private static String num(double valor) {
        return String.format(Locale.US, "%.1f", valor);
    }

    private static String html(String valor) {
        if (valor == null) {
            return "";
        }
        return valor.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String slug(String valor) {
        String normal = java.text.Normalizer.normalize(valor, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normal.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
