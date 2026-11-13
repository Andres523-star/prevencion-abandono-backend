package co.edu.permanencia.demo.notifications;

/**
 * Alerta de la campanita. El nombre se resuelve en el backend para que el frontend no tenga que
 * cruzar datos; en un sistema real la identidad solo se mostraría a roles autorizados.
 *
 * <p>Cada notificación pertenece a un usuario concreto (por eso el servicio genera una lista
 * distinta para cada cuenta) y lleva el grupo A/B para que la campanita se pueda filtrar.</p>
 */
public class Notificacion {

    private final int id;
    private final int estudianteId;
    private final String nombre;
    private final String carrera;
    private final int semestre;
    private final String grupo;
    private final String categoria;
    private final String riesgo;
    private final String tipo;
    private final String titulo;
    private final String motivo;
    private final String linkCita;
    private final String fecha;
    private boolean leida;

    public Notificacion(int id, int estudianteId, String nombre, String carrera, int semestre, String grupo,
                        String categoria, String riesgo, String tipo, String titulo, String motivo,
                        String linkCita, String fecha, boolean leida) {
        this.id = id;
        this.estudianteId = estudianteId;
        this.nombre = nombre;
        this.carrera = carrera;
        this.semestre = semestre;
        this.grupo = grupo;
        this.categoria = categoria;
        this.riesgo = riesgo;
        this.tipo = tipo;
        this.titulo = titulo;
        this.motivo = motivo;
        this.linkCita = linkCita;
        this.fecha = fecha;
        this.leida = leida;
    }

    public int getId() {
        return id;
    }

    public int getEstudianteId() {
        return estudianteId;
    }

    public String getNombre() {
        return nombre;
    }

    public String getCarrera() {
        return carrera;
    }

    public int getSemestre() {
        return semestre;
    }

    public String getGrupo() {
        return grupo;
    }

    public String getCategoria() {
        return categoria;
    }

    public String getRiesgo() {
        return riesgo;
    }

    public String getTipo() {
        return tipo;
    }

    public String getTitulo() {
        return titulo;
    }

    public String getMotivo() {
        return motivo;
    }

    public String getLinkCita() {
        return linkCita;
    }

    public String getFecha() {
        return fecha;
    }

    public boolean isLeida() {
        return leida;
    }

    public void setLeida(boolean leida) {
        this.leida = leida;
    }
}
