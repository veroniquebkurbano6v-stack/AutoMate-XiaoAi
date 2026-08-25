#!/bin/bash
# Git LFS 一键配置脚本（首次克隆后执行一次）
# 作用：
#   1. 检查 git-lfs 是否安装（未安装则给出警告并退出）
#   2. 初始化 git-lfs（git lfs install）
#   3. 配置 core.hooksPath=.githooks，让 post-checkout/post-merge 钩子生效
#   4. 立即拉取一次 LFS 模型文件
#
# 用法：bash scripts/setup.sh

set -e

echo "🔧 正在配置 Git LFS 开发环境..."

# 1. 检查 git-lfs 是否安装
if ! command -v git-lfs >/dev/null 2>&1; then
    echo "⚠️ 警告：未安装 Git LFS，模型文件将无法拉取！"
    echo "   请先安装 Git LFS："
    echo "   - Windows:  https://git-lfs.com（下载安装包，或 winget install Git.GitLFS）"
    echo "   - macOS:    brew install git-lfs"
    echo "   - Linux:    sudo apt install git-lfs 或 sudo yum install git-lfs"
    echo "   安装完成后重新执行：bash scripts/setup.sh"
    exit 1
fi

# 2. 初始化 git-lfs（注册 smudge/clean filter）
git lfs install

# 3. 配置 hooksPath，让仓库内 .githooks/ 钩子生效
git config core.hooksPath .githooks
echo "✅ 已配置 core.hooksPath=.githooks（后续 pull/checkout 将自动拉取模型）"

# 4. 立即拉取 LFS 文件
echo "⬇️ 正在拉取 Git LFS 模型文件..."
git lfs pull

echo "✅ 环境配置完成！模型文件已就绪。"
