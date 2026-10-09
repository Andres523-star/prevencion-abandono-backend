package com.ucc.abandono.controller;

import com.ucc.abandono.model.entity.Estudiante;
import com.ucc.abandono.service.EstudianteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reportes")
@CrossOrigin(origins = "*")
public class ReporteController {

    @Autowired
    private EstudianteService estudianteService;

    @GetMapping
    public Map<String, Object> reporte() {
        List<Estudiante> todos = estudianteService.listarTodos();
        Map<String, Object> r = new HashMap<>();
        r.put("totalEstudiantes", todos.size());
        r.put("riesgoAlto", todos.stream().filter(e -> "ALTO".equalsIgnoreCase(e.getRiesgoActual())).count());
        r.put("riesgoMedio", todos.stream().filter(e -> "MEDIO".equalsIgnoreCase(e.getRiesgoActual())).count());
        r.put("riesgoBajo", todos.stream().filter(e -> "BAJO".equalsIgnoreCase(e.getRiesgoActual())).count());
        return r;
    }
}