package co.edu.retencion.records.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.records.AbsenceInfo;
import co.edu.retencion.records.AttendanceLedger;
import co.edu.retencion.records.CounterInfo;
import co.edu.retencion.records.domain.AbsenceCounter;
import co.edu.retencion.records.domain.AbsenceCounterRepository;
import co.edu.retencion.records.domain.AttendanceRecord;
import co.edu.retencion.records.domain.AttendanceRecordRepository;

/** Implementación de {@link AttendanceLedger} (justificaciones y correo). */
@Service
public class AttendanceLedgerService implements AttendanceLedger {

    private final AttendanceRecordRepository records;
    private final AbsenceCounterRepository counters;
    private final CounterCalculator calculator;
    private final AcademicDirectory academic;
    private final Clock clock;

    public AttendanceLedgerService(AttendanceRecordRepository records, AbsenceCounterRepository counters,
                                   CounterCalculator calculator, AcademicDirectory academic, Clock clock) {
        this.records = records;
        this.counters = counters;
        this.calculator = calculator;
        this.academic = academic;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AbsenceInfo> findRecord(UUID recordId) {
        return records.findById(recordId).map(r -> new AbsenceInfo(r.getId(), r.getTermId(), r.getSessionId(),
                r.getEnrollmentId(), r.getStudentId(), r.getGroupId(),
                academic.findSession(r.getSessionId()).map(s -> s.date()).orElse(null), r.getStatus(),
                r.getJustification(), r.getJustificationDeadline()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CounterInfo> counter(UUID enrollmentId) {
        return counters.findById(enrollmentId).map(AbsenceCounter::info);
    }

    @Override
    @Transactional
    public void markJustificationPending(UUID recordId) {
        AttendanceRecord record = load(recordId);
        record.startJustification(today(), clock.instant());
        recalculate(record);
    }

    @Override
    @Transactional
    public CounterInfo resolveJustification(UUID recordId, boolean approved) {
        AttendanceRecord record = load(recordId);
        record.resolveJustification(approved, clock.instant());
        return recalculate(record);
    }

    @Override
    @Transactional
    public int expireOverdue(LocalDate today) {
        int expired = 0;
        for (AttendanceRecord record : records.findOverdue(today)) {
            if (record.expireIfOverdue(today, clock.instant())) {
                recalculate(record);
                expired++;
            }
        }
        return expired;
    }

    private CounterInfo recalculate(AttendanceRecord record) {
        records.saveAndFlush(record);
        return calculator.recalculate(record.getEnrollmentId(), record.getStudentId(), record.getGroupId(),
                record.getTermId(), academic.effectivePolicy(record.getGroupId()));
    }

    private AttendanceRecord load(UUID id) {
        return records.findById(id).orElseThrow(() -> DomainException.notFound("Registro de asistencia no encontrado"));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
