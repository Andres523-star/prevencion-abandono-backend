package co.edu.retencion.risk.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Exportaciones del dataset de entrenamiento. */
public interface DatasetExportRepository extends JpaRepository<DatasetExport, UUID> {
}
