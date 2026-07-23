<#
.SYNOPSIS
    AI Agent 前端项目启动脚本

.DESCRIPTION
    业务含义：自动化检查 Node.js 环境、安装依赖、启动 Vite 开发服务器。
    开发服务器运行在 5173 端口，/api 请求代理到后端 8080 端口（规避 CORS）。

.EXAMPLE
    .\start.ps1
    # 检查环境并启动开发服务器

.EXAMPLE
    .\start.ps1 -SkipInstall
    # 跳过依赖检查，直接启动

.EXAMPLE
    .\start.ps1 -Help
    # 查看帮助
#>
param(
    [switch]$SkipInstall,
    [switch]$Help
)

# 帮助信息
if ($Help) {
    Write-Host ""
    Write-Host "AI Agent 前端项目启动脚本" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "用法:" -ForegroundColor Yellow
    Write-Host "  .\start.ps1                # 检查环境并启动开发服务器"
    Write-Host "  .\start.ps1 -SkipInstall   # 跳过依赖检查，直接启动"
    Write-Host "  .\start.ps1 -Help          # 显示本帮助"
    Write-Host ""
    Write-Host "启动后访问地址:" -ForegroundColor Yellow
    Write-Host "  前端页面:  http://localhost:5173/"
    Write-Host "  API 代理:  /api -> http://localhost:8080"
    Write-Host ""
    Write-Host "前提条件:" -ForegroundColor Yellow
    Write-Host "  1. 已安装 Node.js 18+"
    Write-Host "  2. 后端服务已启动（端口 8080），否则 API 请求会失败"
    Write-Host ""
    exit 0
}

# 错误时立即停止
$ErrorActionPreference = "Stop"

# 设置控制台为 UTF-8 编码（解决中文乱码）
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  AI Agent 前端项目启动脚本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "项目目录: $ScriptDir"
Write-Host ""

# ========================================
# 1. 校验 Node.js 环境
# ========================================
try {
    $nodeVersion = & cmd /c "node -v 2>&1" | Select-Object -First 1
} catch {
    $nodeVersion = $null
}
if ([string]::IsNullOrWhiteSpace($nodeVersion)) {
    Write-Host "[错误] 未检测到 Node.js，请先安装 Node.js 18+" -ForegroundColor Red
    Write-Host "下载地址: https://nodejs.org/" -ForegroundColor Yellow
    exit 1
}
Write-Host "[OK] Node.js: $nodeVersion" -ForegroundColor Green

# 校验 Node.js 版本（要求 18+）
$versionNum = [int]($nodeVersion -replace 'v(\d+).*', '$1')
if ($versionNum -lt 18) {
    Write-Host "[警告] 建议使用 Node.js 18+，当前版本可能不兼容" -ForegroundColor Yellow
}

# ========================================
# 2. 检查依赖并安装（可选跳过）
# ========================================
$NodeModulesPath = Join-Path $ScriptDir "node_modules"
if (-not $SkipInstall) {
    if (-not (Test-Path $NodeModulesPath)) {
        Write-Host ""
        Write-Host ">>> 首次运行，安装依赖..." -ForegroundColor Cyan
        Push-Location $ScriptDir
        try {
            & cmd /c "npm install 2>&1"
            if ($LASTEXITCODE -ne 0) {
                Write-Host ""
                Write-Host "[错误] npm install 失败（退出码: $LASTEXITCODE）" -ForegroundColor Red
                exit 1
            }
        }
        finally {
            Pop-Location
        }
        Write-Host "[OK] 依赖安装完成" -ForegroundColor Green
    } else {
        Write-Host "[OK] node_modules 已存在，跳过安装" -ForegroundColor Green
    }
} else {
    Write-Host "[跳过] 已指定 -SkipInstall，跳过依赖检查" -ForegroundColor Yellow
}

# ========================================
# 3. 启动 Vite 开发服务器
# ========================================
Write-Host ""
Write-Host ">>> 启动 Vite 开发服务器..." -ForegroundColor Cyan
Write-Host ""
Write-Host "启动后可访问:" -ForegroundColor Yellow
Write-Host "  前端页面:  http://localhost:5173/"
Write-Host "  API 代理:  /api -> http://localhost:8080"
Write-Host ""
Write-Host "停止服务: Ctrl+C"
Write-Host "----------------------------------------" -ForegroundColor Cyan
Write-Host ""

Push-Location $ScriptDir
try {
    & cmd /c "npm run dev"
}
finally {
    Pop-Location
}
