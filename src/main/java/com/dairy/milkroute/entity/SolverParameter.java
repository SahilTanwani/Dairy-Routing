package com.dairy.milkroute.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One tuning number, keyed by name. Safety buffer, circuity factor, road speeds, spoilage
 * constants, equity exponent, thresholds: all of them live here rather than in code, so
 * they can be retuned without a redeploy and a change can be demonstrated live.
 *
 * <p>The key is the natural primary key. There is no surrogate id because there is nothing
 * a numeric id would buy: the name is what every caller looks it up by.
 */
@Entity
@Table(name = "solver_parameter")
public class SolverParameter {

    @Id
    @Column(name = "key", nullable = false, length = 48)
    private String key;

    @Column(name = "value", nullable = false, precision = 10, scale = 4)
    private BigDecimal value;

    @Column(name = "description")
    private String description;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
