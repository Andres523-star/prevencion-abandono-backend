package co.edu.permanencia.demo.chat;

/** Mensaje del chat interno. Los adjuntos se guardan aparte y se referencian por id. */
public class Mensaje {

    private final long id;
    private final int estudianteId;
    private final String autor;
    private final String texto;
    private final String tipo;
    private final String nombreArchivo;
    private final String mime;
    private final String archivoId;
    private final String fecha;
    private final long timestamp;
    private boolean leido;

    public Mensaje(long id, int estudianteId, String autor, String texto, String tipo, String nombreArchivo,
                   String mime, String archivoId, String fecha, long timestamp, boolean leido) {
        this.id = id;
        this.estudianteId = estudianteId;
        this.autor = autor;
        this.texto = texto;
        this.tipo = tipo;
        this.nombreArchivo = nombreArchivo;
        this.mime = mime;
        this.archivoId = archivoId;
        this.fecha = fecha;
        this.timestamp = timestamp;
        this.leido = leido;
    }

    public long getId() {
        return id;
    }

    public int getEstudianteId() {
        return estudianteId;
    }

    public String getAutor() {
        return autor;
    }

    public String getTexto() {
        return texto;
    }

    public String getTipo() {
        return tipo;
    }

    public String getNombreArchivo() {
        return nombreArchivo;
    }

    public String getMime() {
        return mime;
    }

    public String getArchivoId() {
        return archivoId;
    }

    public String getFecha() {
        return fecha;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isLeido() {
        return leido;
    }

    public void setLeido(boolean leido) {
        this.leido = leido;
    }
}
