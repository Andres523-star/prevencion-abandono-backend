package com.ucc.abandono.controller;

import com.ucc.abandono.service.AnalisisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/analizar")
@CrossOrigin(origins = "*")
public class AnalisisController {

    @Autowired
    private AnalisisService analisisService;

    @PostMapping("/{id}")
    public Map<String, Object> analizar(@PathVariable Long id) {
        return analisisService.analizar(id);
    }
}