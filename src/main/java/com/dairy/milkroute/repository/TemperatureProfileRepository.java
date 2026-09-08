package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.TemperatureProfile;
import com.dairy.milkroute.enums.Session;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemperatureProfileRepository extends JpaRepository<TemperatureProfile, Long> {

    /** The ambient temperature that sets the hold budget for a planning run. */
    Optional<TemperatureProfile> findByMonthNoAndSession(short monthNo, Session session);
}
