package com.ucc.abandono.service;

import com.ucc.abandono.model.entity.Intervencion;
import com.ucc.abandono.repository.IntervencionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IntervencionService {

    @Autowired
    private IntervencionRepository repository;

    public List<Intervencion> listar() {
        return repository.findAll();
    }

    public Intervencion guardar(Intervencion intervencion) {
        return repository.save(intervencion);
    }
}