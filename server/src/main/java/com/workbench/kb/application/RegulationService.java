package com.workbench.kb.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 制度正文读取：文件存放在服务端受控目录，仅登录用户可读。
 * docNo 只接受中文/字母/数字/〔〕() 等安全字符，解析后必须落在根目录内，防路径穿越。
 */
@Service
public class RegulationService {

  private static final Pattern SAFE_DOC_NO = Pattern.compile("^[\\p{IsHan}A-Za-z0-9〔〕（）()\\-]{1,80}$");

  @Value("${workbench.regulations.dir:./regulations}")
  private String dir;

  /**
   * 按 docNo（如「集团〔2024〕124号」）读取正文。
   *
   * @throws IllegalArgumentException docNo 非法或文件不存在
   */
  public String read(String docNo) throws IOException {
    if (docNo == null || !SAFE_DOC_NO.matcher(docNo).matches()) {
      throw new IllegalArgumentException("文号格式不合法");
    }
    Path base = Paths.get(dir).toAbsolutePath().normalize();
    Path file = base.resolve(docNo + ".txt").normalize();
    if (!file.startsWith(base)) {
      throw new IllegalArgumentException("文号格式不合法");
    }
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("正文未入库");
    }
    return Files.readString(file, StandardCharsets.UTF_8);
  }
}
