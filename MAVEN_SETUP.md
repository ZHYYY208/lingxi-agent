# Maven / IDE 配置

## 为什么会出现 `程序包 org.springframework.stereotype 不存在`

Spring Boot 依赖由 Maven 管理。如果 IDE 没有把项目识别为 Maven 项目，而是用普通 Java 项目方式执行 `javac`，就会报：

```text
程序包 org.springframework.stereotype 不存在
程序包 org.springframework.boot 不存在
程序包 lombok 不存在
```

这不是依赖缺失，而是 Maven 依赖没有加入 IDE 的编译 classpath。

## 正确的项目入口

现在项目根目录已经有聚合 `pom.xml`：

```text
D:\项目\灵犀\pom.xml
D:\项目\灵犀\backend\pom.xml
```

请用 IDE 打开 **`D:\项目\灵犀` 根目录**，不要只把 `backend\src` 当作普通源码目录打开。

## IntelliJ IDEA

1. `File → Open`，选择 `D:\项目\灵犀`
2. 等待右下角提示，点击 **Load Maven Project**
3. 打开右侧 **Maven** 工具窗口，点击 **Reload All Maven Projects**
4. `File → Project Structure → Project`：
   - SDK：**JDK 21**
   - Language level：**21**
5. `Settings → Build, Execution, Deployment → Build Tools → Maven`：
   - Maven home path：`D:\apache-maven-3.10.0`
   - JDK for importer：`21`
   - 勾选自动导入 Maven 项目
6. `Build → Rebuild Project`

如果仍然报错，执行一次：

```powershell
cd D:\项目\灵犀
mvn clean test
```

然后对 `pom.xml` 右键选择 **Maven → Reload Project**。

## VS Code

项目已经提供 `.vscode/settings.json` 和扩展推荐。

1. 安装扩展包 **Extension Pack for Java**（会包含 Maven for Java）
2. 打开 `D:\项目\灵犀`
3. `Ctrl+Shift+P` → **Java: Clean Java Language Server Workspace**
4. 重新加载窗口
5. 右下角 Java 项目状态应识别到 `backend` 模块

如果 Maven 仍未被识别，在 VS Code 设置中确认：

```json
"java.jdt.ls.java.home": "C:\\Program Files\\Java\\jdk-21.0.12.1",
"maven.executable.path": "D:\\apache-maven-3.10.0\\bin\\mvn.cmd"
```

## Eclipse

`File → Import → Maven → Existing Maven Projects`，选择 `D:\项目\灵犀`，勾选根 `pom.xml` 和 `backend/pom.xml`，完成后执行 `Maven → Update Project`。

## 命令行验证

```powershell
cd D:\项目\灵犀
mvn -version
mvn clean test
```

看到 `BUILD SUCCESS` 即表示依赖和编译配置正常。

运行：

```powershell
cd D:\项目\灵犀
mvn -DskipTests package
java -jar backend\target\lingxi-backend.jar
```

注意：如果 8000 端口已经有服务在运行，打包前先停止旧进程，否则 `lingxi-backend.jar` 会被占用。
