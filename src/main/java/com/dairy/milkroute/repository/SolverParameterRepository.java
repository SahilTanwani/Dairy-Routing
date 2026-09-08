package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.SolverParameter;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every tuning number in the system. Loaded as a whole and cached by the parameter
 * service; individual lookups here would put a query inside the solver's inner loop.
 */
public interface SolverParameterRepository extends JpaRepository<SolverParameter, String> {
}
