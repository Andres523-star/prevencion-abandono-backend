package com.ucc.abandono.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.ucc.abandono.model.entity.HistorialIA;

@Repository
public interface HistorialIARepository extends JpaRepository<HistorialIA, Long> {
}