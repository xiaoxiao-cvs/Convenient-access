#!/bin/bash

# ConvenientAccess Plugin Build Script
# 用于本地构建和测试的自动化脚本

set -e  # 遇到错误时退出

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 打印带颜色的消息
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查Java环境
check_java() {
    print_info "检查Java环境..."
    if ! command -v java &> /dev/null; then
        print_error "Java未安装或不在PATH中"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 17 ]; then
        print_error "需要Java 17或更高版本，当前版本: $JAVA_VERSION"
        exit 1
    fi
    
    print_success "Java环境检查通过 (版本: $JAVA_VERSION)"
}

# 检查Maven环境
check_maven() {
    print_info "检查Maven环境..."
    if ! command -v mvn &> /dev/null; then
        print_error "Maven未安装或不在PATH中"
        exit 1
    fi
    
    MVN_VERSION=$(mvn -version | head -n1 | cut -d' ' -f3)
    print_success "Maven环境检查通过 (版本: $MVN_VERSION)"
}

# 清理构建目录
clean_build() {
    print_info "清理构建目录..."
    mvn clean
    print_success "构建目录清理完成"
}

# 编译项目
compile_project() {
    print_info "编译项目..."
    mvn compile
    print_success "项目编译完成"
}

# 运行测试
run_tests() {
    print_info "运行测试..."
    if mvn test; then
        print_success "所有测试通过"
    else
        print_warning "测试失败，但继续构建过程"
    fi
}

# 打包项目
package_project() {
    print_info "打包项目..."
    mvn package -DskipTests
    
    # 获取项目版本
    PROJECT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
    JAR_FILE="target/convenient-access-${PROJECT_VERSION}.jar"
    
    if [ -f "$JAR_FILE" ]; then
        print_success "项目打包完成: $JAR_FILE"
        
        # 显示JAR文件信息
        JAR_SIZE=$(du -h "$JAR_FILE" | cut -f1)
        print_info "JAR文件大小: $JAR_SIZE"
        
        # 创建构建信息文件
        BUILD_INFO="target/build-info.txt"
        echo "ConvenientAccess Plugin Build Information" > "$BUILD_INFO"
        echo "=========================================" >> "$BUILD_INFO"
        echo "Version: $PROJECT_VERSION" >> "$BUILD_INFO"
        echo "Build Date: $(date)" >> "$BUILD_INFO"
        echo "Java Version: $(java -version 2>&1 | head -n1)" >> "$BUILD_INFO"
        echo "Maven Version: $(mvn -version | head -n1)" >> "$BUILD_INFO"
        echo "JAR File: $JAR_FILE" >> "$BUILD_INFO"
        echo "JAR Size: $JAR_SIZE" >> "$BUILD_INFO"
        
        print_info "构建信息已保存到: $BUILD_INFO"
    else
        print_error "JAR文件未找到: $JAR_FILE"
        exit 1
    fi
}

# 验证构建结果
verify_build() {
    print_info "验证构建结果..."
    
    PROJECT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
    JAR_FILE="target/convenient-access-${PROJECT_VERSION}.jar"
    
    if [ -f "$JAR_FILE" ]; then
        # 检查JAR文件内容
        if jar -tf "$JAR_FILE" | grep -q "plugin.yml"; then
            print_success "JAR文件包含plugin.yml"
        else
            print_warning "JAR文件中未找到plugin.yml"
        fi
        
        if jar -tf "$JAR_FILE" | grep -q "com/xaoxiao/convenientaccess"; then
            print_success "JAR文件包含主要类文件"
        else
            print_error "JAR文件中未找到主要类文件"
            exit 1
        fi
        
        print_success "构建验证通过"
    else
        print_error "JAR文件不存在"
        exit 1
    fi
}

# 显示帮助信息
show_help() {
    echo "ConvenientAccess Plugin Build Script"
    echo ""
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  -h, --help     显示此帮助信息"
    echo "  -c, --clean    仅清理构建目录"
    echo "  -t, --test     运行测试"
    echo "  -p, --package  仅打包（跳过测试）"
    echo "  -f, --full     完整构建（默认）"
    echo "  -v, --verify   验证构建结果"
    echo ""
    echo "示例:"
    echo "  $0              # 完整构建"
    echo "  $0 --clean     # 清理构建目录"
    echo "  $0 --test      # 运行测试"
    echo "  $0 --package   # 仅打包"
}

# 主函数
main() {
    echo "========================================"
    echo "ConvenientAccess Plugin Build Script"
    echo "========================================"
    
    case "${1:-full}" in
        -h|--help)
            show_help
            exit 0
            ;;
        -c|--clean)
            check_maven
            clean_build
            ;;
        -t|--test)
            check_java
            check_maven
            run_tests
            ;;
        -p|--package)
            check_java
            check_maven
            package_project
            verify_build
            ;;
        -v|--verify)
            verify_build
            ;;
        -f|--full|*)
            check_java
            check_maven
            clean_build
            compile_project
            run_tests
            package_project
            verify_build
            ;;
    esac
    
    print_success "构建脚本执行完成！"
}

# 执行主函数
main "$@"