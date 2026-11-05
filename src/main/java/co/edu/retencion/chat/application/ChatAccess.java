package co.edu.retencion.chat.application;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.chat.domain.ParticipantRepository;
import co.edu.retencion.students.EnrollmentInfo;
import co.edu.retencion.students.StudentRegistry;

/**
 * Reglas de acceso del chat: un docente solo conversa con estudiantes inscritos (inscripción activa)
 * en grupos suyos de periodos no cerrados; fuera de eso la conversación queda de solo lectura.
 */
@Component
public class ChatAccess {

    private final AcademicDirectory academic;
    private final StudentRegistry students;
    private final ParticipantRepository participants;

    public ChatAccess(AcademicDirectory academic, StudentRegistry students, ParticipantRepository participants) {
        this.academic = academic;
        this.students = students;
        this.participants = participants;
    }

    /** Grupos del periodo activo que comparten el docente y el estudiante. */
    public Set<UUID> sharedGroups(UUID teacherUserId, UUID studentId) {
        Set<UUID> taught = new HashSet<>(academic.groupsTaughtBy(teacherUserId));
        return students.enrollmentsOf(studentId).stream().filter(EnrollmentInfo::active)
                .map(EnrollmentInfo::groupId).filter(taught::contains).collect(Collectors.toSet());
    }

    public boolean isParticipant(UUID userId, UUID conversationId) {
        return participants.existsByConversationIdAndUserId(conversationId, userId);
    }
}
