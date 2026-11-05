package co.edu.retencion.retention.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.retention.RetentionFollowUpDue;
import co.edu.retencion.retention.domain.FollowUp;
import co.edu.retencion.retention.domain.FollowUpRepository;
import co.edu.retencion.retention.domain.ReentryCampaign;
import co.edu.retencion.retention.domain.ReentryCampaignRepository;
import co.edu.retencion.retention.domain.RetentionCase;
import co.edu.retencion.retention.domain.RetentionCaseRepository;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Campaña de reingreso para retirados y aplazados. Una tarea programada (mensual) arma un BORRADOR
 * con los casos elegibles; nada se envía hasta que ADMIN o la coordinación lo aprueba. Aprobar crea
 * contactos de reingreso para el consejero de cada caso (recordatorio en la campanita); la plataforma
 * nunca escribe al estudiante de forma masiva por su cuenta.
 */
@Service
public class ReentryCampaignService {

    private final RetentionCaseRepository cases;
    private final FollowUpRepository followUps;
    private final ReentryCampaignRepository campaigns;
    private final SettingsProvider settings;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ReentryCampaignService(RetentionCaseRepository cases, FollowUpRepository followUps,
                                  ReentryCampaignRepository campaigns, SettingsProvider settings,
                                  ApplicationEventPublisher events, ObjectMapper mapper, Clock clock) {
        this.cases = cases;
        this.followUps = followUps;
        this.campaigns = campaigns;
        this.settings = settings;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record CampaignView(UUID id, ReentryCampaign.Status status, int targetCount, List<UUID> caseIds,
                               Instant createdAt, UUID reviewedBy, Instant reviewedAt) {
    }

    @Scheduled(cron = "${app.retention.reentry-cron:0 0 6 1 * *}", zone = "America/Bogota")
    @Transactional
    public void monthlyDraft() {
        draft(null);
    }

    /** Arma el borrador con los casos cerrados como RETIRADO o APLAZADO hace al menos N días. */
    @Transactional
    public CampaignView draft(UUID actor) {
        Instant before = clock.instant().minus(Duration.ofDays(Math.max(0, settings.getInt("retention.reentry-after-days", 30))));
        LocalDate recent = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).minusDays(180);
        List<UUID> targets = cases.findClosedBefore(EnumSet.of(RetentionCase.Outcome.RETIRADO, RetentionCase.Outcome.APLAZADO),
                before).stream().filter(c -> !followUps.existsByCaseIdAndKindAndDueOnAfter(c.getId(), FollowUp.Kind.REINGRESO,
                recent)).map(RetentionCase::getId).toList();
        return view(campaigns.save(ReentryCampaign.draft(json(targets), targets.size(), actor, clock.instant())));
    }

    @Transactional
    public CampaignView review(UUID campaignId, boolean approved) {
        ReentryCampaign campaign = campaigns.findById(campaignId)
                .orElseThrow(() -> DomainException.notFound("Campaña no encontrada"));
        campaign.review(approved, CurrentUser.require().userId(), clock.instant());
        if (approved) {
            LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
            for (UUID caseId : ids(campaign.getTargets())) {
                cases.findById(caseId).ifPresent(c -> {
                    FollowUp f = FollowUp.schedule(caseId, FollowUp.Kind.REINGRESO, 0, today);
                    if (c.getAssignedCounselorId() != null) {
                        f.reminded(clock.instant());
                        events.publishEvent(new RetentionFollowUpDue(caseId, c.getStudentId(), c.getAssignedCounselorId(),
                                FollowUp.Kind.REINGRESO.name(), 0));
                    }
                    followUps.save(f);
                });
            }
        }
        return view(campaigns.save(campaign));
    }

    @Transactional(readOnly = true)
    public List<CampaignView> list() {
        return campaigns.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    private CampaignView view(ReentryCampaign c) {
        return new CampaignView(c.getId(), c.getStatus(), c.getTargetCount(), ids(c.getTargets()), c.getCreatedAt(),
                c.getReviewedBy(), c.getReviewedAt());
    }

    private List<UUID> ids(String json) {
        try {
            return new ArrayList<>(mapper.readValue(json, new TypeReference<List<UUID>>() { }));
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
