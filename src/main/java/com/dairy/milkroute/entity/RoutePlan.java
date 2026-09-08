package com.dairy.milkroute.entity;

import com.dairy.milkroute.enums.PlanMode;
import com.dairy.milkroute.enums.PlanSource;
import com.dairy.milkroute.enums.PlanStatus;
import com.dairy.milkroute.enums.Session;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One generated plan for one session: the head of the "what we intend" side of the model.
 *
 * <p>At most one plan per session may be PUBLISHED at a time, enforced by the partial
 * unique index uq_one_published_per_session. Publishing a second one fails in the
 * database, so no bug in service code can put two live morning plans in front of the
 * fleet.
 */
@Entity
@Table(name = "route_plan")
public class RoutePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The plan's own version number within its session, unique with {@code session}. This
     * is a domain value the planner increments, not optimistic locking: it deliberately
     * carries no {@code @Version}. Trip is the only entity with optimistic locking.
     */
    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "session", nullable = false, length = 10)
    private Session session;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private PlanSource source;

    /** Whether this plan could serve every active point or had to choose between them. */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 24)
    private PlanMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PlanStatus status = PlanStatus.DRAFT;

    /** The ambient temperature this plan was built against, frozen at generation. */
    @Column(name = "planned_temp_c", nullable = false, precision = 4, scale = 1)
    private BigDecimal plannedTempC;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "generation_ms")
    private Integer generationMs;

    /**
     * The feasibility report, held as JSON. Its shape will keep changing and it is read as
     * a whole blob rather than queried field by field, so a column beats seven tables.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feasibility")
    private String feasibility;

    @Column(name = "notes")
    private String notes;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public PlanSource getSource() {
        return source;
    }

    public void setSource(PlanSource source) {
        this.source = source;
    }

    public PlanMode getMode() {
        return mode;
    }

    public void setMode(PlanMode mode) {
        this.mode = mode;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public void setStatus(PlanStatus status) {
        this.status = status;
    }

    public BigDecimal getPlannedTempC() {
        return plannedTempC;
    }

    public void setPlannedTempC(BigDecimal plannedTempC) {
        this.plannedTempC = plannedTempC;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public Integer getGenerationMs() {
        return generationMs;
    }

    public void setGenerationMs(Integer generationMs) {
        this.generationMs = generationMs;
    }

    public String getFeasibility() {
        return feasibility;
    }

    public void setFeasibility(String feasibility) {
        this.feasibility = feasibility;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
