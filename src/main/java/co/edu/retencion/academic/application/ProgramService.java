package co.edu.retencion.academic.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.application.AcademicViews.ProgramView;
import co.edu.retencion.academic.domain.Program;
import co.edu.retencion.academic.domain.ProgramRepository;
import co.edu.retencion.common.error.DomainException;

/** Consulta y edición de programas académicos. */
@Service
public class ProgramService {

    private final ProgramRepository programs;
    private final Clock clock;

    public ProgramService(ProgramRepository programs, Clock clock) {
        this.programs = programs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProgramView> list() {
        return programs.findAllByOrderByNameAsc().stream().map(ProgramView::of).toList();
    }

    @Transactional(readOnly = true)
    public ProgramView get(UUID id) {
        return ProgramView.of(load(id));
    }

    @Transactional
    public ProgramView update(UUID id, String name, boolean clinical, boolean active) {
        Program program = load(id);
        program.update(name, clinical, active, clock.instant());
        return ProgramView.of(programs.saveAndFlush(program));
    }

    Program load(UUID id) {
        return programs.findById(id).orElseThrow(() -> DomainException.notFound("Programa no encontrado"));
    }
}
