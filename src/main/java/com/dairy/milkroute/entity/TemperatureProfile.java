package com.dairy.milkroute.entity;

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

/**
 * Ambient temperature by month and session: twenty-four rows standing in for a weather
 * API, which would be an external dependency the clean-machine run cannot rely on.
 *
 * <p>This is the input to the hold budget, so it is the single most load-bearing lookup
 * in the system: get it wrong and every route is planned against the wrong deadline.
 */
@Entity
@Table(name = "temperature_profile")
public class TemperatureProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "month_no", nullable = false)
    private short monthNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "session", nullable = false, length = 10)
    private Session session;

    @Column(name = "ambient_c", nullable = false, precision = 4, scale = 1)
    private BigDecimal ambientC;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public short getMonthNo() {
        return monthNo;
    }

    public void setMonthNo(short monthNo) {
        this.monthNo = monthNo;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public BigDecimal getAmbientC() {
        return ambientC;
    }

    public void setAmbientC(BigDecimal ambientC) {
        this.ambientC = ambientC;
    }
}
