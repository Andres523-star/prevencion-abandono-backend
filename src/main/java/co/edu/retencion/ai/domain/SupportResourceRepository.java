package co.edu.retencion.ai.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio del catálogo de recursos de apoyo. */
public interface SupportResourceRepository extends JpaRepository<SupportResource, String> {

    List<SupportResource> findByActiveTrueOrderByIdAsc();

    List<SupportResource> findAllByOrderByIdAsc();
}
