package co.edu.retencion.students.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de perfiles socioeconómicos. */
public interface SocioeconomicProfileRepository extends JpaRepository<SocioeconomicProfile, UUID> {
}
