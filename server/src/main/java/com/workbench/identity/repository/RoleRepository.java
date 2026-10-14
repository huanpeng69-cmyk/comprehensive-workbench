package com.workbench.identity.repository;

import com.workbench.identity.domain.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

  List<Role> findByCodeIn(Collection<String> codes);

  Optional<Role> findByCode(String code);
}
