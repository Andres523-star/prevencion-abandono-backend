package co.edu.retencion.imports.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de trabajos de importación. */
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
}
