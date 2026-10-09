package com.ucc.abandono.service;

import com.ucc.abandono.model.entity.HistorialIA;
import com.ucc.abandono.repository.HistorialIARepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HistorialIAService {

    @Autowired
    private HistorialIARepository repository;

    public List<HistorialIA> listar() {
        return repository.findAll();
    }

    public HistorialIA guardar(HistorialIA historial) {
        return repository.save(historial);
    }
}