package com.workbench.identity.application;

import com.workbench.identity.domain.OrgUnit;
import com.workbench.identity.domain.Role;
import com.workbench.identity.domain.User;
import com.workbench.identity.repository.OrgUnitRepository;
import com.workbench.identity.repository.RoleRepository;
import com.workbench.identity.repository.UserRepository;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示数据初始化：表为空时才写入，重复启动幂等。密码统一为演示口令（见仓库 README 的「演示账号」一节，仅演示环境）。
 */
@Component
public class DataInitializer implements CommandLineRunner {

  private final OrgUnitRepository orgUnitRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final RoleRepository roleRepository;

  public DataInitializer(OrgUnitRepository orgUnitRepository, UserRepository userRepository,
      RoleRepository roleRepository, PasswordEncoder passwordEncoder) {
    this.orgUnitRepository = orgUnitRepository;
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (userRepository.count() > 0) {
      return;
    }
    OrgUnit company = orgUnitRepository.save(OrgUnit.builder()
        .name("示范建工集团南方公司").type("COMPANY").parentId(null)
        .fullPath("南方公司").sortNo(1).enabled(true).build());

    List<String> projectNames = List.of("云岭项目部", "江湾项目部", "榕荫项目部", "南岸项目部", "荔园项目部");
    for (int i = 0; i < projectNames.size(); i++) {
      orgUnitRepository.save(OrgUnit.builder()
          .name(projectNames.get(i)).type("PROJECT").parentId(company.getId())
          .fullPath("南方公司/" + projectNames.get(i)).sortNo(i + 1).enabled(true).build());
    }
    List<OrgUnit> projects = orgUnitRepository.findByTypeOrderBySortNo("PROJECT");
    OrgUnit tianhe = projects.get(0);
    List<String> deptNames = List.of("综合管理部", "工程管理部", "合同管理部", "安全环保部", "质检部", "设备物资部", "财务管理部");
    for (int i = 0; i < deptNames.size(); i++) {
      orgUnitRepository.save(OrgUnit.builder()
          .name(deptNames.get(i)).type("DEPARTMENT").parentId(tianhe.getId())
          .fullPath("南方公司/云岭项目部/" + deptNames.get(i)).sortNo(i + 1).enabled(true).build());
    }

    Role employee = roleRepository.save(role("EMPLOYEE", "普通员工", "PROJECT"));
    Role pm = roleRepository.save(role("PROJECT_MANAGER", "项目经理", "PROJECT"));
    Role hr = roleRepository.save(role("HR_ADMIN", "人力管理员", "COMPANY"));
    Role finance = roleRepository.save(role("FINANCE", "财务", "COMPANY"));
    roleRepository.save(role("PARTY_ADMIN", "党建管理员", "PROJECT"));
    roleRepository.save(role("UNION_ADMIN", "工会管理员", "PROJECT"));
    roleRepository.save(role("SYSTEM_ADMIN", "系统管理员", "COMPANY"));

    String hash = passwordEncoder.encode("Workbench@2026");
    saveUser("chenguodong", "陈国栋", hash, tianhe, tianhe.getId(), "正式职工", "13800000001", pm);
    saveUser("limingyuan", "李明远", hash, tianhe, tianhe.getId(), "正式职工", "13800000002", pm);
    saveUser("fangjing", "方静", hash, tianhe, tianhe.getId(), "正式职工", "13800000015", hr, finance);
    saveUser("wanghaitao", "王海涛", hash, tianhe, tianhe.getId(), "正式职工", "13800000003", employee);
    saveUser("zhaoqiming", "赵启明", hash, tianhe, tianhe.getId(), "正式职工", "13800000004", employee);
    saveUser("wujunjie", "吴俊杰", hash, projects.get(1), projects.get(1).getId(), "正式职工", "13800000009", employee);
  }

  private Role role(String code, String name, String dataScope) {
    return Role.builder().code(code).name(name).dataScope(dataScope).enabled(true).build();
  }

  private void saveUser(String username, String name, String hash, OrgUnit org, Long projectId,
      String employmentType, String phone, Role... roles) {
    User user = User.builder()
        .username(username).name(name).passwordHash(hash)
        .orgId(org.getId()).projectId(projectId)
        .employmentType(employmentType).phone(phone).enabled(true)
        .roles(new java.util.HashSet<>(List.of(roles)))
        .build();
    userRepository.save(user);
  }
}
