package com.ucc.abandono.controller;

import com.ucc.abandono.model.entity.Intervencion;
import com.ucc.abandono.service.IntervencionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/intervenciones")
@CrossOrigin(origins = "*")
public class IntervencionController {

    @Autowired
    private IntervencionService service;

    @GetMapping
    public List<Intervencion> listar() {
        return service.listar();
    }

    @PostMapping
    public Intervencion crear(@RequestBody Intervencion intervencion) {
        return service.guardar(intervencion);
    }
}