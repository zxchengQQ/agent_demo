# 项目习惯

> 上次压缩：2026-07-22 | 当前条目数：24

### 构建
- WSL2 环境：`export JAVA_HOME=/home/zhaoxc/tools/jdk17; export PATH="$JAVA_HOME/bin:/home/zhaoxc/tools/apache-maven-3.9.6/bin:$PATH"`（Windows 侧为 `D:\Java\jdk-17.0.7`，设 `$env:JAVA_HOME`）
- 本地 Maven 仓库 `/mnt/d/maven/repo`，华为云镜像配在 `~/.m2/settings.xml`，离线模式会失败需在线
- 编译单模块用 `mvn compile -pl {模块} -am`，不全量编译
- bash 中 Maven 参数加引号：`"-Dtest=XXX" "-Dsurefire.failIfNoSpecifiedTests=false"`
- agent-demo-tools 测试前需先 `mvn install -pl agent-demo-common -DskipTests` 安装依赖到本地仓库

### 编码
- 构造器注入优于 @Autowired 字段注入
- Lombok `@Data` 自动生成 getter/setter，配置类只需声明字段
- `@Tool` 注解的 `value()` 返回 `String[]`，需用 `String.join(" ", ...)` 合并
- Spring Boot 3.2.5 默认启用 `-parameters`，反射 `param.getName()` 可获取真实参数名
- 核心业务逻辑必须在代码块上方写明业务含义注释（说明"为什么"）

### 测试
- 测试框架：JUnit 5 + Mockito（mock 用 `mock()`，spy 用 `spy()`）
- 运行单模块测试：`mvn test -pl {模块} -am "-Dtest=XXX" "-Dsurefire.failIfNoSpecifiedTests=false"`
- protected 方法可用 spy 覆盖返回值或抛异常来模拟 HTTP 调用
- 改动 Spring bean 构造器/装配后必须实际启动验证（mock 单测全绿≠能启动）

### 部署
- 后端端口 8080，前端开发端口 5173（Vite 代理 `/api` -> `localhost:8080`）
- ARK_API_KEY 通过环境变量注入，禁止硬编码
- 前端需装 Linux 平台原生包 `@rollup/rollup-linux-x64-gnu`（--no-save）
- 启动验证：`mvn -pl agent-demo-bootstrap spring-boot:run`，需先 `mvn install -pl 依赖模块 -DskipTests`

### AI
- 修改代码前必须先读取目标文件，理解上下文再动手
- 接口签名变更必须全链路搜索所有调用方并逐一适配
- 删除或修改已有代码前必须向用户确认
- 接口扩展时，现有实现类需同步适配（空实现保证编译通过）
- Spring bean 多构造器时主构造器必加 @Autowired；漏标会启动失败或静默失效（选无参构造，如压缩不生效）
- 读取 classpath 资源须同时处理 file/jar 两种协议（打包/run 场景 jar 下无 Path，需按内容读取）
