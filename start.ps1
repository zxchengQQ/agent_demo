<#
.SYNOPSIS
    AI Agent 示例项目启动脚本

.DESCRIPTION
    业务含义：自动化设置环境变量、Maven 打包、启动 Spring Boot 应用。
    支持 dev / prod 多环境切换，支持跳过打包直接启动。
    支持通过 -Provider 参数切换 LLM 提供商（ark 火山引擎方舟 / bailian 阿里百炼）。

.EXAMPLE
    .\start.ps1
    # 使用 $env:ARK_API_KEY 启动（dev 环境，默认 ark 提供商）

.EXAMPLE
    .\start.ps1 -ApiKey "ark-xxxx"
    # 指定火山引擎 API Key 启动

.EXAMPLE
    .\start.ps1 -Provider bailian -BailianApiKey "sk-xxxx"
    # 切换为阿里百炼启动，指定百炼 API Key

.EXAMPLE
    .\start.ps1 -Profile prod -SkipBuild
    # 使用 prod 环境启动已有 jar（跳过打包）

.EXAMPLE
    .\start.ps1 -Help
    # 查看帮助
#>
param(
    [ValidateSet('ark','bailian')]
    [string]$Provider = "ark",
    [string]$ApiKey = $env:ARK_API_KEY,
    [string]$BailianApiKey = $env:BAILIAN_API_KEY,
    [string]$Profile = "dev",
    [string]$JavaHome = "D:\java\jdk-17.0.7",
    [string]$TestMessage = "你好，请简单介绍一下自己",
    [switch]$SkipBuild,
    [switch]$Test,
    [switch]$Help
)

# 帮助信息
if ($Help) {
    Write-Host ""
    Write-Host "AI Agent 示例项目启动脚本" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "用法:" -ForegroundColor Yellow
    Write-Host "  .\start.ps1                                      # 默认启动（ark 提供商，dev 环境）"
    Write-Host "  .\start.ps1 -ApiKey 'ark-xxxx'                   # 指定火山引擎 API Key"
    Write-Host "  .\start.ps1 -Provider bailian -BailianApiKey 'sk-xxxx'  # 切换阿里百炼启动"
    Write-Host "  .\start.ps1 -Profile prod                        # 指定 profile（dev / prod）"
    Write-Host "  .\start.ps1 -SkipBuild                           # 跳过打包，直接启动已有 jar"
    Write-Host "  .\start.ps1 -Test                                # 测试对话接口（需应用已启动）"
    Write-Host "  .\start.ps1 -Test -TestMessage '2+2='            # 自定义测试消息"
    Write-Host "  .\start.ps1 -JavaHome 'D:\java\jdk-17'            # 指定 JDK 路径"
    Write-Host "  .\start.ps1 -Help                                 # 显示本帮助"
    Write-Host ""
    Write-Host "LLM 提供商切换:" -ForegroundColor Yellow
    Write-Host "  -Provider ark      # 火山引擎方舟（默认），需设置 ARK_API_KEY"
    Write-Host "  -Provider bailian  # 阿里百炼，需设置 BAILIAN_API_KEY"
    Write-Host ""
    Write-Host "启动后访问地址:" -ForegroundColor Yellow
    Write-Host "  Swagger UI:  http://localhost:8080/swagger-ui.html"
    Write-Host "  对话接口:    POST http://localhost:8080/api/agent/chat"
    Write-Host ""
    exit 0
}

# 错误时立即停止
$ErrorActionPreference = "Stop"

# 设置控制台为 UTF-8 编码（解决中文乱码）
# 业务含义：Windows PowerShell 默认使用 GBK（代码页 936）解码输出，
# 而应用日志（logback）输出为 UTF-8 字节流，不切换会导致中文乱码。
# chcp 65001 将控制台代码页切换为 UTF-8，再设置 .NET 编码对象保证一致性。
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# ========================================
# 测试模式：调用对话接口并正确显示中文响应
# 业务含义：PowerShell 5 的 Invoke-RestMethod 用系统编码解析响应体，
# 会导致 UTF-8 中文乱码。这里用 Invoke-WebRequest + RawContentStream
# 获取原始字节流，再手动用 UTF-8 解码，确保中文正确显示。
# ========================================
if ($Test) {
    Write-Host ""
    Write-Host ">>> 测试对话接口..." -ForegroundColor Cyan
    Write-Host "消息: $TestMessage"
    Write-Host ""
    try {
        $body = @{ message = $TestMessage } | ConvertTo-Json
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        $response = Invoke-WebRequest -Uri "http://localhost:8080/api/agent/chat" `
            -Method POST `
            -ContentType "application/json; charset=utf-8" `
            -Body $bytes `
            -UseBasicParsing
        $json = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
        $resp = $json | ConvertFrom-Json

        Write-Host "success:  $($resp.success)" -ForegroundColor $(if ($resp.success) { 'Green' } else { 'Red' })
        Write-Host "code:     $($resp.code)"
        Write-Host "traceId:  $($resp.traceId)"
        Write-Host "sessionId: $($resp.data.sessionId)"
        Write-Host "duration: $($resp.data.duration)ms"
        Write-Host "content:  $($resp.data.content)"
    } catch {
        Write-Host "[错误] 接口调用失败: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "请确认应用已启动（端口 8080）" -ForegroundColor Yellow
        exit 1
    }
    exit 0
}

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$JarPath = Join-Path $ProjectRoot "agent-demo-bootstrap\target\agent-demo-bootstrap-1.0.0.jar"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  AI Agent 示例项目启动脚本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "项目目录: $ProjectRoot"
Write-Host "Profile:  $Profile"
Write-Host "Provider: $Provider"
Write-Host ""

# ========================================
# 1. 校验 API Key（根据提供商校验对应的 Key）
# 业务含义：切换提供商后只校验当前提供商的 API Key（BR-LLM-010）
# ========================================
if ($Provider -eq "bailian") {
    if ([string]::IsNullOrWhiteSpace($BailianApiKey)) {
        Write-Host "[错误] 未检测到 BAILIAN_API_KEY" -ForegroundColor Red
        Write-Host "请通过以下方式之一提供:" -ForegroundColor Yellow
        Write-Host "  1. 设置环境变量: `$env:BAILIAN_API_KEY = 'sk-xxxx'"
        Write-Host "  2. 脚本参数指定: .\start.ps1 -Provider bailian -BailianApiKey 'sk-xxxx'"
        Write-Host ""
        exit 1
    }
    $env:BAILIAN_API_KEY = $BailianApiKey
    Write-Host "[OK] BAILIAN_API_KEY 已设置（隐藏显示）" -ForegroundColor Green
} else {
    if ([string]::IsNullOrWhiteSpace($ApiKey)) {
        Write-Host "[错误] 未检测到 ARK_API_KEY" -ForegroundColor Red
        Write-Host "请通过以下方式之一提供:" -ForegroundColor Yellow
        Write-Host "  1. 设置环境变量: `$env:ARK_API_KEY = 'ark-xxxx'"
        Write-Host "  2. 脚本参数指定: .\start.ps1 -ApiKey 'ark-xxxx'"
        Write-Host "  3. 切换百炼:     .\start.ps1 -Provider bailian -BailianApiKey 'sk-xxxx'"
        Write-Host ""
        exit 1
    }
    $env:ARK_API_KEY = $ApiKey
    Write-Host "[OK] ARK_API_KEY 已设置（隐藏显示）" -ForegroundColor Green
}

# ========================================
# 2. 设置 JAVA_HOME 并校验
# ========================================
if (-not (Test-Path $JavaHome)) {
    Write-Host "[错误] JAVA_HOME 路径不存在: $JavaHome" -ForegroundColor Red
    Write-Host "请通过 -JavaHome 参数指定有效的 JDK 17 路径" -ForegroundColor Yellow
    Write-Host "示例: .\start.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'" -ForegroundColor Yellow
    Write-Host ""
    exit 1
}
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

# 验证 Java 版本（要求 17+）
# 注意：java -version 输出到 stderr，PowerShell 5 在 ErrorActionPreference=Stop 时
# 会将 stderr 内容当作异常，因此使用 cmd /c 包装避免此问题
$javaExe = Join-Path $JavaHome "bin\java.exe"
if (-not (Test-Path $javaExe)) {
    Write-Host "[错误] 未找到 java 可执行文件: $javaExe" -ForegroundColor Red
    exit 1
}
$javaOutput = & cmd /c "`"$javaExe`" -version 2>&1" | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($javaOutput)) {
    Write-Host "[错误] 无法执行 java 命令，请检查 JDK 安装" -ForegroundColor Red
    exit 1
}
Write-Host "[OK] Java: $javaOutput" -ForegroundColor Green
if ($javaOutput -notmatch 'version "17') {
    Write-Host "[警告] 建议使用 JDK 17，当前版本可能不兼容" -ForegroundColor Yellow
}

# ========================================
# 3. Maven 打包（可选跳过）
# ========================================
if (-not $SkipBuild) {
    Write-Host ""
    Write-Host ">>> 开始 Maven 打包..." -ForegroundColor Cyan
    Push-Location $ProjectRoot
    try {
        & mvn clean package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Write-Host ""
            Write-Host "[错误] Maven 打包失败（退出码: $LASTEXITCODE）" -ForegroundColor Red
            exit 1
        }
    }
    finally {
        Pop-Location
    }
    Write-Host "[OK] Maven 打包成功" -ForegroundColor Green
} else {
    Write-Host "[跳过] 已指定 -SkipBuild，跳过 Maven 打包" -ForegroundColor Yellow
}

# ========================================
# 4. 校验 jar 包存在
# ========================================
if (-not (Test-Path $JarPath)) {
    Write-Host "[错误] 未找到 jar 包: $JarPath" -ForegroundColor Red
    Write-Host "请先执行打包（去掉 -SkipBuild 参数）" -ForegroundColor Yellow
    exit 1
}

# ========================================
# 5. 启动应用
# ========================================
Write-Host ""
Write-Host ">>> 启动 Spring Boot 应用..." -ForegroundColor Cyan
Write-Host "Jar:     $JarPath"
Write-Host "Profile: $Profile"
Write-Host ""
Write-Host "启动后可访问:" -ForegroundColor Yellow
Write-Host "  Swagger UI:  http://localhost:8080/swagger-ui.html"
Write-Host "  对话接口:    POST http://localhost:8080/api/agent/chat"
Write-Host "  健康检查:    GET  http://localhost:8080/api/agent/session/list"
Write-Host ""
Write-Host "停止应用: Ctrl+C"
Write-Host "----------------------------------------" -ForegroundColor Cyan
Write-Host ""

# 使用数组传递 JVM 参数，避免 PowerShell 5 将 -Dfile.encoding=UTF-8 拆分为多个参数
# 通过 --llm.provider 覆盖 application.yml 中的配置，实现启动时切换提供商
$javaArgs = @(
    "-Dfile.encoding=UTF-8",
    "-jar",
    $JarPath,
    "--spring.profiles.active=$Profile",
    "--llm.provider=$Provider"
)
& java @javaArgs
