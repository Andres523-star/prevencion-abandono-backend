package co.edu.retencion.students.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import co.edu.retencion.students.StudentStatus;

/** Repositorio de estudiantes. */
public interface StudentRepository extends JpaRepository<Student, UUID> {

    boolean existsByAnonCode(String anonCode);

    /** Keyset de estudiantes activos de un programa (lote nocturno de riesgo). */
    @Query("""
            select s.id from Student s
            where s.programId = :programId and s.status = co.edu.retencion.students.StudentStatus.ACTIVO
              and (:fromStart = true or s.id > :after)
            order by s.id
            """)
    List<UUID> activeIds(UUID programId, boolean fromStart, UUID after, Limit limit);

    Optional<Student> findByUserId(UUID userId);

    /**
     * Listado keyset con filtros. Las banderas evitan parámetros nulos (PostgreSQL no infiere su
     * tipo) y el filtro de programas aplica el alcance del coordinador o consejero.
     */
    @Query("""
            select s from Student s
            where (:anyProgram = true or s.programId in :programIds)
              and (:anyStatus = true or s.status = :status)
              and (:fromStart = true or s.id > :after)
            order by s.id
            """)
    List<Student> search(boolean anyProgram, Set<UUID> programIds, boolean anyStatus, StudentStatus status,
                         boolean fromStart, UUID after, Limit limit);
}
