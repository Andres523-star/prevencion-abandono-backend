package co.edu.retencion.academic.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Repositorio de grupos. */
public interface CourseGroupRepository extends JpaRepository<CourseGroup, UUID> {

    Optional<CourseGroup> findBySubjectIdAndTermIdAndCodeIgnoreCase(UUID subjectId, UUID termId, String code);

    List<CourseGroup> findByTermId(UUID termId);

    /** Grupos del docente en periodos no cerrados (base del ABAC del docente). */
    @Query("""
            select g.id from CourseGroup g, Term t
            where t.id = g.termId and g.teacherUserId = :teacher and t.status <> co.edu.retencion.academic.domain.Term.Status.CLOSED
            """)
    Set<UUID> findOpenGroupIdsByTeacher(UUID teacher);

    /**
     * Listado con filtros opcionales y paginación keyset. Las banderas evitan comparar con
     * parámetros nulos (PostgreSQL no infiere su tipo).
     */
    @Query("""
            select g from CourseGroup g, Subject s
            where s.id = g.subjectId
              and (:anyTerm = true or g.termId = :termId)
              and (:anySubject = true or g.subjectId = :subjectId)
              and (:anyTeacher = true or g.teacherUserId = :teacherId)
              and (:anyProgram = true or s.programId in :programIds)
              and (:fromStart = true or g.id > :after)
            order by g.id
            """)
    List<CourseGroup> search(boolean anyTerm, UUID termId, boolean anySubject, UUID subjectId,
                             boolean anyTeacher, UUID teacherId, boolean anyProgram, Set<UUID> programIds,
                             boolean fromStart, UUID after, Limit limit);
}
