package co.edu.retencion.academic.application;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.application.AcademicViews.SubjectView;
import co.edu.retencion.academic.domain.ProgramRepository;
import co.edu.retencion.academic.domain.Subject;
import co.edu.retencion.academic.domain.SubjectRepository;
import co.edu.retencion.common.error.DomainException;

/**
 * Materias y prerrequisitos. Los prerrequisitos deben ser del mismo programa y no pueden formar
 * ciclos (A requiere B y B requiere A).
 */
@Service
public class SubjectService {

    private final SubjectRepository subjects;
    private final ProgramRepository programs;
    private final Clock clock;

    public SubjectService(SubjectRepository subjects, ProgramRepository programs, Clock clock) {
        this.subjects = subjects;
        this.programs = programs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SubjectView> listByProgram(UUID programId) {
        return subjects.findByProgramIdOrderBySuggestedSemesterAscCodeAsc(programId).stream().map(SubjectView::of).toList();
    }

    @Transactional(readOnly = true)
    public SubjectView get(UUID id) {
        return SubjectView.of(load(id));
    }

    @Transactional
    public SubjectView create(UUID programId, String code, String name, int credits, int semester,
                              Set<UUID> prerequisites) {
        if (!programs.existsById(programId)) {
            throw DomainException.notFound("Programa inexistente");
        }
        if (subjects.findByProgramIdAndCodeIgnoreCase(programId, code).isPresent()) {
            throw DomainException.conflict("Ya existe la materia " + code + " en el programa");
        }
        Subject subject = Subject.create(programId, code, name, credits, semester, clock.instant());
        setPrerequisites(subject, prerequisites);
        return SubjectView.of(subjects.save(subject));
    }

    @Transactional
    public SubjectView update(UUID id, String name, int credits, int semester, boolean active, Set<UUID> prerequisites) {
        Subject subject = load(id);
        subject.update(name, credits, semester, active, clock.instant());
        setPrerequisites(subject, prerequisites);
        return SubjectView.of(subjects.saveAndFlush(subject));
    }

    /** @return {@code true} si los prerrequisitos cambiaron */
    boolean setPrerequisites(Subject subject, Set<UUID> prerequisites) {
        for (UUID prerequisiteId : prerequisites) {
            Subject prerequisite = load(prerequisiteId);
            if (!prerequisite.getProgramId().equals(subject.getProgramId())) {
                throw DomainException.badRequest("Los prerrequisitos deben ser del mismo programa");
            }
        }
        if (createsCycle(subject.getId(), prerequisites)) {
            throw DomainException.badRequest("Los prerrequisitos forman un ciclo");
        }
        return subject.replacePrerequisites(Set.copyOf(prerequisites), clock.instant());
    }

    /** Búsqueda en anchura: ¿alguno de los nuevos prerrequisitos depende (transitivamente) de la materia? */
    private boolean createsCycle(UUID subjectId, Set<UUID> prerequisites) {
        Deque<UUID> pending = new ArrayDeque<>(prerequisites);
        Set<UUID> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            UUID current = pending.pop();
            if (current.equals(subjectId)) {
                return true;
            }
            if (visited.add(current)) {
                subjects.findById(current).ifPresent(s -> pending.addAll(s.getPrerequisiteIds()));
            }
        }
        return false;
    }

    Subject load(UUID id) {
        return subjects.findById(id).orElseThrow(() -> DomainException.notFound("Materia no encontrada"));
    }
}
