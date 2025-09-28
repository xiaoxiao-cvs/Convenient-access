@echo off
REM ConvenientAccess Plugin Build Script for Windows
REM 用于本地构建和测试的自动化脚本

setlocal enabledelayedexpansion

REM 设置颜色代码（Windows 10+）
set "INFO=[94m[INFO][0m"
set "SUCCESS=[92m[SUCCESS][0m"
set "WARNING=[93m[WARNING][0m"
set "ERROR=[91m[ERROR][0m"

REM 打印消息函数
:print_info
echo %INFO% %~1
goto :eof

:print_success
echo %SUCCESS% %~1
goto :eof

:print_warning
echo %WARNING% %~1
goto :eof

:print_error
echo %ERROR% %~1
goto :eof

REM 检查Java环境
:check_java
call :print_info "检查Java环境..."
java -version >nul 2>&1
if errorlevel 1 (
    call :print_error "Java未安装或不在PATH中"
    exit /b 1
)

for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION_STRING=%%g
)
set JAVA_VERSION_STRING=%JAVA_VERSION_STRING:"=%
for /f "delims=." %%a in ("%JAVA_VERSION_STRING%") do set JAVA_MAJOR=%%a

if %JAVA_MAJOR% LSS 17 (
    call :print_error "需要Java 17或更高版本，当前版本: %JAVA_MAJOR%"
    exit /b 1
)

call :print_success "Java环境检查通过 (版本: %JAVA_MAJOR%)"
goto :eof

REM 检查Maven环境
:check_maven
call :print_info "检查Maven环境..."
mvn -version >nul 2>&1
if errorlevel 1 (
    call :print_error "Maven未安装或不在PATH中"
    exit /b 1
)

for /f "tokens=3" %%g in ('mvn -version 2^>^&1 ^| findstr "Apache Maven"') do (
    set MVN_VERSION=%%g
)
call :print_success "Maven环境检查通过 (版本: %MVN_VERSION%)"
goto :eof

REM 清理构建目录
:clean_build
call :print_info "清理构建目录..."
mvn clean
if errorlevel 1 (
    call :print_error "清理构建目录失败"
    exit /b 1
)
call :print_success "构建目录清理完成"
goto :eof

REM 编译项目
:compile_project
call :print_info "编译项目..."
mvn compile
if errorlevel 1 (
    call :print_error "项目编译失败"
    exit /b 1
)
call :print_success "项目编译完成"
goto :eof

REM 运行测试
:run_tests
call :print_info "运行测试..."
mvn test
if errorlevel 1 (
    call :print_warning "测试失败，但继续构建过程"
) else (
    call :print_success "所有测试通过"
)
goto :eof

REM 打包项目
:package_project
call :print_info "打包项目..."
mvn package -DskipTests
if errorlevel 1 (
    call :print_error "项目打包失败"
    exit /b 1
)

REM 获取项目版本
for /f "delims=" %%i in ('mvn help:evaluate -Dexpression=project.version -q -DforceStdout') do set PROJECT_VERSION=%%i
set JAR_FILE=target\convenient-access-%PROJECT_VERSION%.jar

if exist "%JAR_FILE%" (
    call :print_success "项目打包完成: %JAR_FILE%"
    
    REM 显示JAR文件信息
    for %%A in ("%JAR_FILE%") do set JAR_SIZE=%%~zA
    set /a JAR_SIZE_KB=%JAR_SIZE%/1024
    call :print_info "JAR文件大小: %JAR_SIZE_KB% KB"
    
    REM 创建构建信息文件
    set BUILD_INFO=target\build-info.txt
    echo ConvenientAccess Plugin Build Information > "%BUILD_INFO%"
    echo ========================================= >> "%BUILD_INFO%"
    echo Version: %PROJECT_VERSION% >> "%BUILD_INFO%"
    echo Build Date: %date% %time% >> "%BUILD_INFO%"
    echo JAR File: %JAR_FILE% >> "%BUILD_INFO%"
    echo JAR Size: %JAR_SIZE_KB% KB >> "%BUILD_INFO%"
    
    call :print_info "构建信息已保存到: %BUILD_INFO%"
) else (
    call :print_error "JAR文件未找到: %JAR_FILE%"
    exit /b 1
)
goto :eof

REM 验证构建结果
:verify_build
call :print_info "验证构建结果..."

for /f "delims=" %%i in ('mvn help:evaluate -Dexpression=project.version -q -DforceStdout') do set PROJECT_VERSION=%%i
set JAR_FILE=target\convenient-access-%PROJECT_VERSION%.jar

if exist "%JAR_FILE%" (
    REM 检查JAR文件内容
    jar -tf "%JAR_FILE%" | findstr "plugin.yml" >nul
    if errorlevel 1 (
        call :print_warning "JAR文件中未找到plugin.yml"
    ) else (
        call :print_success "JAR文件包含plugin.yml"
    )
    
    jar -tf "%JAR_FILE%" | findstr "com/xaoxiao/convenientaccess" >nul
    if errorlevel 1 (
        call :print_error "JAR文件中未找到主要类文件"
        exit /b 1
    ) else (
        call :print_success "JAR文件包含主要类文件"
    )
    
    call :print_success "构建验证通过"
) else (
    call :print_error "JAR文件不存在"
    exit /b 1
)
goto :eof

REM 显示帮助信息
:show_help
echo ConvenientAccess Plugin Build Script for Windows
echo.
echo 用法: %~nx0 [选项]
echo.
echo 选项:
echo   -h, --help     显示此帮助信息
echo   -c, --clean    仅清理构建目录
echo   -t, --test     运行测试
echo   -p, --package  仅打包（跳过测试）
echo   -f, --full     完整构建（默认）
echo   -v, --verify   验证构建结果
echo.
echo 示例:
echo   %~nx0              # 完整构建
echo   %~nx0 --clean     # 清理构建目录
echo   %~nx0 --test      # 运行测试
echo   %~nx0 --package   # 仅打包
goto :eof

REM 主函数
:main
echo ========================================
echo ConvenientAccess Plugin Build Script
echo ========================================

set "ARG=%~1"
if "%ARG%"=="" set "ARG=full"

if "%ARG%"=="-h" goto help
if "%ARG%"=="--help" goto help
if "%ARG%"=="-c" goto clean_only
if "%ARG%"=="--clean" goto clean_only
if "%ARG%"=="-t" goto test_only
if "%ARG%"=="--test" goto test_only
if "%ARG%"=="-p" goto package_only
if "%ARG%"=="--package" goto package_only
if "%ARG%"=="-v" goto verify_only
if "%ARG%"=="--verify" goto verify_only
if "%ARG%"=="-f" goto full_build
if "%ARG%"=="--full" goto full_build
if "%ARG%"=="full" goto full_build

:help
call :show_help
exit /b 0

:clean_only
call :check_maven
call :clean_build
goto end

:test_only
call :check_java
call :check_maven
call :run_tests
goto end

:package_only
call :check_java
call :check_maven
call :package_project
call :verify_build
goto end

:verify_only
call :verify_build
goto end

:full_build
call :check_java
call :check_maven
call :clean_build
call :compile_project
call :run_tests
call :package_project
call :verify_build

:end
call :print_success "构建脚本执行完成！"
exit /b 0

REM 调用主函数
call :main %*