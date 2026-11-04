package co.edu.retencion.risk.application;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.risk.RiskTrigger;
import co.edu.retencion.risk.domain.RiskStudentState;
import co.edu.retencion.risk.domain.RiskStudentStateRepository;
import co.edu.retencion.students.StudentRegistry;

/**
 * Lote nocturno (Spring Batch con particionado): una partición por programa; cada una recorre por
 * keyset los estudiantes ACTIVOS, calcula variables, puntaje y hash del payload, y solo analiza
 * (fila nueva en el historial, IA según la política del orquestador) a quien cambió desde el
 * último análisis o está en vigilancia.
 *
 * <p>El paso usa un {@link ResourcelessTransactionManager}: cada análisis maneja su propia
 * transacción corta y la IA nunca se llama con una conexión de base de datos tomada. El
 * JobRepository (tablas {@code BATCH_*} de la migración V4) sí es transaccional.
 */
@Configuration(proxyBeanMethods = false)
class NightlyRiskBatchConfig {

    static final String JOB_NAME = "nightlyRiskAnalysis";
    static final String PROGRAM_KEY = "programId";

    @Bean
    Job nightlyRiskJob(JobRepository repository, Step riskManagerStep) {
        return new JobBuilder(JOB_NAME, repository).start(riskManagerStep).build();
    }

    @Bean
    Step riskManagerStep(JobRepository repository, Step riskWorkerStep, Partitioner programPartitioner,
                         @Value("${app.risk.batch.concurrency:3}") int concurrency) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("risk-batch-");
        executor.setConcurrencyLimit(Math.max(1, concurrency));
        return new StepBuilder("riskManager", repository)
                .partitioner("riskWorker", programPartitioner)
                .step(riskWorkerStep)
                .taskExecutor(executor)
                .build();
    }

    /**
     * Una partición por programa académico. El parámetro opcional {@code programs} (códigos separados
     * por coma) limita el lote a algunos programas (reproceso manual o pruebas).
     */
    @Bean
    @JobScope
    Partitioner programPartitioner(AcademicDirectory academic,
                                   @Value("#{jobParameters['programs'] ?: ''}") String programs) {
        java.util.Set<String> only = java.util.Arrays.stream(programs.split(",")).map(String::trim)
                .filter(c -> !c.isEmpty()).collect(java.util.stream.Collectors.toSet());
        return gridSize -> {
            Map<String, ExecutionContext> partitions = new HashMap<>();
            for (ProgramInfo program : academic.listPrograms()) {
                if (!only.isEmpty() && !only.contains(program.code())) {
                    continue;
                }
                ExecutionContext context = new ExecutionContext();
                context.putString(PROGRAM_KEY, program.id().toString());
                partitions.put("programa-" + program.code(), context);
            }
            return partitions;
        };
    }

    @Bean
    Step riskWorkerStep(JobRepository repository, ItemStreamReader<UUID> activeStudentReader,
                        ItemProcessor<UUID, RiskOrchestrator.Prepared> riskChangeFilter,
                        ItemWriter<RiskOrchestrator.Prepared> riskAnalysisWriter,
                        @Value("${app.risk.batch.chunk-size:20}") int chunk) {
        return new StepBuilder("riskWorker", repository)
                .<UUID, RiskOrchestrator.Prepared>chunk(chunk, new ResourcelessTransactionManager())
                .reader(activeStudentReader)
                .processor(riskChangeFilter)
                .writer(riskAnalysisWriter)
                .faultTolerant()
                // Un estudiante con datos problemáticos no detiene el lote del programa.
                .skip(RuntimeException.class)
                .skipLimit(10_000)
                .build();
    }

    @Bean
    @StepScope
    ItemStreamReader<UUID> activeStudentReader(@Value("#{stepExecutionContext['programId']}") String programId,
                                               StudentRegistry students,
                                               @Value("${app.risk.batch.page-size:200}") int pageSize) {
        return new ActiveStudentReader(students, UUID.fromString(programId), pageSize);
    }

    /** Filtra a quien no cambió (mismo hash) y no está en vigilancia. */
    @Bean
    @StepScope
    ItemProcessor<UUID, RiskOrchestrator.Prepared> riskChangeFilter(RiskOrchestrator orchestrator,
                                                                    RiskStudentStateRepository states, Clock clock) {
        return studentId -> {
            Optional<RiskOrchestrator.Prepared> prepared = orchestrator.prepare(studentId);
            if (prepared.isEmpty()) {
                return null;
            }
            Optional<RiskStudentState> state = states.findById(studentId);
            boolean unchanged = state.map(s -> prepared.get().payloadHash().equals(s.getLastPayloadHash()))
                    .orElse(false);
            boolean watched = state.map(s -> s.watched(clock.instant())).orElse(false);
            return unchanged && !watched ? null : prepared.get();
        };
    }

    @Bean
    @StepScope
    ItemWriter<RiskOrchestrator.Prepared> riskAnalysisWriter(RiskOrchestrator orchestrator) {
        return chunk -> chunk.forEach(p -> orchestrator.analyzePrepared(p, RiskTrigger.BATCH, null, null));
    }

    /** Lector por keyset con reinicio: guarda el último id leído en el contexto del paso. */
    static final class ActiveStudentReader implements ItemStreamReader<UUID> {

        static final String LAST_KEY = "lastStudentId";

        private final StudentRegistry students;
        private final UUID programId;
        private final int pageSize;
        private final Deque<UUID> buffer = new ArrayDeque<>();
        private UUID last;
        private boolean exhausted;

        ActiveStudentReader(StudentRegistry students, UUID programId, int pageSize) {
            this.students = students;
            this.programId = programId;
            this.pageSize = pageSize;
        }

        @Override
        public void open(ExecutionContext context) {
            if (context.containsKey(LAST_KEY)) {
                last = UUID.fromString(context.getString(LAST_KEY));
            }
        }

        @Override
        public void update(ExecutionContext context) {
            if (last != null) {
                context.putString(LAST_KEY, last.toString());
            }
        }

        @Override
        public UUID read() {
            if (buffer.isEmpty() && !exhausted) {
                List<UUID> page = students.activeStudentIds(programId, last, pageSize);
                buffer.addAll(page);
                exhausted = page.size() < pageSize;
            }
            UUID next = buffer.poll();
            if (next != null) {
                last = next;
            }
            return next;
        }
    }
}
