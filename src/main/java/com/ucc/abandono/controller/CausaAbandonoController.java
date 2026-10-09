package com.ucc.abandono.controller;

import com.ucc.abandono.model.entity.CausaAbandono;
import com.ucc.abandono.service.CausaAbandonoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/causas")
@CrossOrigin(origins = "*")
public class CausaAbandonoController {

    @Autowired
    private CausaAbandonoService service;

    @GetMapping
    public List<CausaAbandono> listar() {
        return service.listar();
    }

    @PostMapping
    public CausaAbandono crear(@RequestBody CausaAbandono causa) {
        return service.guardar(causa);
    }
}