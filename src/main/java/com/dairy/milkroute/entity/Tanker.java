package com.dairy.milkroute.entity;

import com.dairy.milkroute.enums.TankerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A milk tanker.
 *
 * <p>Note what is absent: there is no "how long can this tanker hold milk" field. That is
 * computed at planning time from ambient temperature and {@link #insulated}, because a
 * stored value would freeze a number that changes twice a day.
 */
@Entity
@Table(name = "tanker")
public class Tanker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reg_no", nullable = false, unique = true, length = 20)
    private String regNo;

    @Column(name = "capacity_litres", nullable = false)
    private int capacityLitres;

    /** Worth roughly six degrees of ambient temperature in the spoilage calculation. */
    @Column(name = "insulated", nullable = false)
    private boolean insulated = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TankerStatus status = TankerStatus.AVAILABLE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_plant_id", nullable = false)
    private Plant homePlant;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRegNo() {
        return regNo;
    }

    public void setRegNo(String regNo) {
        this.regNo = regNo;
    }

    public int getCapacityLitres() {
        return capacityLitres;
    }

    public void setCapacityLitres(int capacityLitres) {
        this.capacityLitres = capacityLitres;
    }

    public boolean isInsulated() {
        return insulated;
    }

    public void setInsulated(boolean insulated) {
        this.insulated = insulated;
    }

    public TankerStatus getStatus() {
        return status;
    }

    public void setStatus(TankerStatus status) {
        this.status = status;
    }

    public Plant getHomePlant() {
        return homePlant;
    }

    public void setHomePlant(Plant homePlant) {
        this.homePlant = homePlant;
    }
}
