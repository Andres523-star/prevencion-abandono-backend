package com.ucc.abandono.service;

import com.ucc.abandono.model.entity.CausaAbandono;
import com.ucc.abandono.repository.CausaAbandonoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CausaAbandonoService {

    @Autowired
    private CausaAbandonoRepository repository;

    public List<CausaAbandono> listar() {
        return repository.findAll();
    }

    public CausaAbandono guardar(CausaAbandono causa) {
        return repository.save(causa);
    }
}