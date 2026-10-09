package com.ucc.abandono.service;

import com.ucc.abandono.model.entity.Estudiante;
import com.ucc.abandono.model.entity.HistorialIA;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AnalisisService {

    @Autowired
    private EstudianteService estudianteService;

    @Autowired
    private HistorialIAService historialIAService;

    public Map<String, Object> analizar(Long estudianteId) {
        Optional<Estudiante> opt = estudianteService.buscarPorId(estudianteId);
        if (opt.isEmpty()) {
            return Map.of("error", "Estudiante no encontrado");
        }

        Estudiante e = opt.get();

        // Simulación de respuesta IA (mañana se reemplaza por Claude real)
        String recomendacion = "Se recomienda agendar tutoría académica y revisar situación socioeconómica.";

        // Guardar en historial
        HistorialIA h = HistorialIA.builder()
                .estudiante(e)
                .prompt("Analizar riesgo de " + e.getNombre())
                .respuestaJson("{\"recomendacion\":\"" + recomendacion + "\"}")
                .fecha(LocalDate.now())
                .build();
        historialIAService.guardar(h);

        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("estudianteId", e.getId());
        respuesta.put("nombre", e.getNombre());
        respuesta.put("riesgoActual", e.getRiesgoActual());
        respuesta.put("recomendacion", recomendacion);
        respuesta.put("fecha", LocalDate.now().toString());

        return respuesta;
    }
}
