package com.ucc.abandono.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.ucc.abandono.model.entity.CausaAbandono;

@Repository
public interface CausaAbandonoRepository extends JpaRepository<CausaAbandono, Long> {
}