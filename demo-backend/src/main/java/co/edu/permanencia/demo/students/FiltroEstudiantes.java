package co.edu.permanencia.demo.students;

public class FiltroEstudiantes {
    private String sede;
    private String programa;
    private Integer semestre;
    private String nivelRiesgo;

    public FiltroEstudiantes() {}

    public String getSede() { return sede; }
    public void setSede(String sede) { this.sede = sede; }

    public String getPrograma() { return programa; }
    public void setPrograma(String programa) { this.programa = programa; }

    public Integer getSemestre() { return semestre; }
    public void setSemestre(Integer semestre) { this.semestre = semestre; }

    public String getNivelRiesgo() { return nivelRiesgo; }
    public void setNivelRiesgo(String nivelRiesgo) { this.nivelRiesgo = nivelRiesgo; }
}
