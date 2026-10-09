package com.ucc.abandono.repository;

import com.ucc.abandono.model.entity.Intervencion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IntervencionRepository extends JpaRepository<Intervencion, Long> {
}