package co.edu.retencion.records.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.records.application.RecordsViews.ObservationView;
import co.edu.retencion.records.domain.Observation;
import co.edu.retencion.records.domain.ObservationRepository;

/**
 * Observaciones de docentes y tutores. El personal de seguimiento (coordinación, consejería,
 * bienestar) ve todas las del estudiante; un docente solo ve las que él mismo escribió.
 */
@Service
public class ObservationService {

    private final ObservationRepository observations;
    private final Clock clock;

    public ObservationService(ObservationRepository observations, Clock clock) {
        this.observations = observations;
        this.clock = clock;
    }

    @Transactional
    public ObservationView write(UUID studentId, UUID groupId, Observation.Type type, String text) {
        return ObservationView.of(observations.save(Observation.write(studentId, groupId,
                CurrentUser.require().userId(), type, text, clock.instant())));
    }

    @Transactional(readOnly = true)
    public List<ObservationView> ofStudent(UUID studentId) {
        AuthenticatedUser user = CurrentUser.require();
        boolean followUpStaff = user.hasRole(Role.ADMIN) || user.hasRole(Role.COORDINADOR)
                || user.hasRole(Role.CONSEJERO) || user.hasRole(Role.PSICOLOGO);
        return observations.findByStudentIdOrderByCreatedAtDesc(studentId).stream()
                .filter(o -> followUpStaff || o.getAuthorUserId().equals(user.userId()))
                .map(ObservationView::of).toList();
    }

    @Transactional
    public void delete(UUID observationId) {
        Observation observation = observations.findById(observationId)
                .orElseThrow(() -> DomainException.notFound("Observación no encontrada"));
        observation.delete(CurrentUser.require().userId(), clock.instant());
        observations.saveAndFlush(observation);
    }
}
