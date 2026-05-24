# 后端优化准则

## Java 源码与测试目录结构

- 后端 Maven 模块必须遵循标准 Maven 目录结构：`src` 下 `main` 和 `test` 为同级目录。
- 生产代码必须放在 `java-backend/src/main/java`，该目录只包含运行时会被打包和发布的源码。
- 测试代码必须放在 `java-backend/src/test/java`，不得放入 `src/main/java` 或 `src/main` 下任何目录。
- 禁止将 `*Test.java`、`*Tests.java`、`*IT.java` 等测试类放入 `src/main/java`。
- 测试包名应与被测代码包名保持对应，便于定位和维护。
- 新增测试时优先运行与改动相关的测试，再运行 `mvn -pl java-backend test -B -ntp` 做后端验证。
