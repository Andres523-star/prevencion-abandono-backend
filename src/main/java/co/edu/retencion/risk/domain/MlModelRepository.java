package co.edu.retencion.risk.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Registro de modelos ML. */
public interface MlModelRepository extends JpaRepository<MlModel, UUID> {

    Optional<MlModel> findByModelVersion(String modelVersion);

    boolean existsByModelVersion(String modelVersion);

    List<MlModel> findAllByOrderByCreatedAtDesc();
}
