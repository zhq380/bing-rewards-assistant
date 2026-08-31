# Ripple Rewards Script · 必应积分自动助手

> 「微软必应积分」Android 无障碍自动化脚本，**自适应响应式 UI**，支持手机/平板/横屏。

[![Release](https://img.shields.io/github/v/release/zhq380/bing-rewards-assistant?include_prereleases&sort=date)](https://github.com/zhq380/bing-rewards-assistant/releases)
[![Download](https://img.shields.io/github/downloads/zhq380/bing-rewards-assistant/total?color=success)](https://github.com/zhq380/bing-rewards-assistant/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-blue)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-brightgreen)](https://developer.android.com/jetpack/compose)

## 📥 快速下载

👉 **[点击下载最新 APK](https://github.com/zhq380/bing-rewards-assistant/releases/latest)**

或访问 Releases 页面：https://github.com/zhq380/bing-rewards-assistant/releases

## ✨ 功能特性

| 功能 | 说明 |
|------|------|
| 🎯 **每日自动签到** | 智能识别「金色硬币」位置，自动随日期后移点击 |
| 🔍 **智能搜索** | 内置 1000 词中英文词库，自动防重复 |
| 📰 **连续阅读** | 缺口预计算 + 连续阅读模式，减少来回跳转 |
| 📅 **每日活动** | OCR 兜底识别活动卡片，15s 计分等待 |
| 🧩 **双实例支持** | 主应用 / 分身独立配置、独立运行 |
| 🛡️ **看门狗自愈** | 超时自动恢复，弹窗不卡死 |
| 💾 **断点续跑** | 进程被杀后跳过已完成任务 |
| 🎨 **自适应 UI** | 响应式布局，600dp 限宽适配平板横屏 |
| 📊 **运行日志独立页** | 首页精简，日志统计单独分页 |

## 📋 自动化算法

1. **缺口预计算**：积分卡片 → 总积分 - 当前积分 / 单任务积分 → 自动算出剩余次数
2. **连续阅读模式**：一次进入新闻流 → 逐个点开 → 计时完成 → 读完回积分页确认
3. **签到积分闭环**：点硬币 → 等积分到账 → 前后积分对比 → 未到账重试
4. **硬币位置推算**：Day N 标签 → 推算上方硬币 → 自动适配每日后移
5. **看门狗检测**：超时无活动 → 弹窗卡住 → 自动重试拉回必应 → 耗尽才终止

## 📱 环境要求

- **Android**: API 26+ (Android 8.0)
- **权限**:
  - 无障碍服务（必填）
  - WRITE_SECURE_SETTINGS（可选，ADB 授权用于自动恢复无障碍）
  - 通知权限、电池白名单（推荐保活）

## 🔧 安装使用

1. **下载 APK**: 从 [GitHub Releases](https://github.com/zhq380/bing-rewards-assistant/releases/latest) 下载
2. **开启无障碍**: 系统设置 → 无障碍 → Ripple Rewards Script → 开启
3. **（可选）ADB 授权自动恢复**:
   `shell
   adb shell pm grant com.ripple.script android.permission.WRITE_SECURE_SETTINGS
   `
4. **配置脚本**: 打开 APP → 运行页 → 点击主应用/分身卡片 → 开启模块
5. **点击运行**: 首页 → 立即运行 → 自动完成今日任务

## 🎯 工作流程

`
拉起必应 → 签到（硬币闭环验证）
  ↓
每日活动 → OCR 识别 → 计分等待
  ↓
缺口预计算 → 搜索 × N → 阅读 × N（连续模式）
  ↓
积分验证 → 保存结果 → 通知 → 结束
`

## 🏗️ 项目结构

`
com.ripple.script/
├── data/          # 数据持久化
├── rewards/       # 核心业务逻辑
├── service/       # 无障碍服务/悬浮窗/定时任务
├── ui/            # Jetpack Compose 界面
│   ├── screens/   # 运行中心 / 日志 / 设置
│   └── theme/     # 自适应配色 / 动效 / 交互
├── util/          # 权限工具 / 启动
└── MainActivity.kt
`

## 📄 许可证

[MIT License](LICENSE)