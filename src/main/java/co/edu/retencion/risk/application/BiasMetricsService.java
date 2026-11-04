package co.edu.retencion.risk.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.risk.domain.BiasMetrics;
import co.edu.retencion.risk.domain.RiskStudentStateRepository;

/**
 * Panel básico de sesgo: distribución del último nivel por programa, banda de estrato y semestre,
 * con supresión de celdas pequeñas. Sirve para vigilar que el motor no concentre el riesgo ALTO en
 * un grupo socioeconómico (el estrato no suma puntaje, pero puede correlacionar con otras señales).
 */
@Service
public class BiasMetricsService {

    /** Celdas con menos estudiantes que esto se suprimen (reidentificación). */
    public static final int MIN_CELL = 10;

    private final RiskStudentStateRepository states;
    private final AcademicDirectory academic;

    public BiasMetricsService(RiskStudentStateRepository states, AcademicDirectory academic) {
        this.states = states;
        this.academic = academic;
    }

    @Transactional(readOnly = true)
    public BiasMetrics.Report report() {
        AuthenticatedUser user = CurrentUser.require();
        boolean global = user.hasRole(Role.ADMIN) || user.programIds().isEmpty();
        Set<UUID> programs = user.programIds();
        Map<UUID, String> codes = states.findAssessed().stream().map(s -> s.getProgramId()).filter(java.util.Objects::nonNull)
                .distinct().collect(Collectors.toMap(id -> id,
                        id -> academic.findProgram(id).map(ProgramInfo::code).orElse("NA")));
        return BiasMetrics.compute(states.findAssessed().stream()
                .filter(s -> s.getProgramId() != null && (global || programs.contains(s.getProgramId())))
                .map(s -> new BiasMetrics.Row(codes.get(s.getProgramId()), s.getStratum(),
                        s.getSemester() == null ? 0 : s.getSemester(), s.getLastLevel()))
                .toList(), MIN_CELL);
    }
}
