package com.workbench.salary.repository;

import com.workbench.salary.domain.SalaryConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalaryConfigRepository extends JpaRepository<SalaryConfig, Integer> {
}
