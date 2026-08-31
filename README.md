# 必应积分自动助手 (Adaptive) · Bing Rewards Assistant

> 支持 Microsoft Rewards 每日签到、搜索、阅读和活动的 Android 无障碍自动化脚本 · **通用自适应版**

![GitHub release](https://img.shields.io/github/v/release/zhq380/bing-rewards-assistant)
![License](https://img.shields.io/github/license/zhq380/bing-rewards-assistant)
![Android minSdk](https://img.shields.io/badge/Android-8.0%2B-brightgreen)

## ⚡ 两个版本

| 版本 | 仓库 | 适用设备 | UI 风格 |
|------|------|---------|---------|
| **自适应版 (本仓库)** | [zhq380/bing-rewards-assistant](https://github.com/zhq380/bing-rewards-assistant) | **所有 Android 8.0+** | Material You 动态限宽 600dp |
| **ColorOS 版 (OPPO 专用)** | [zhq380/RippleScript](https://github.com/zhq380/RippleScript) | **OPPO Find X9 Pro / Find X 系列** | ColorOS 16 光场设计 · 全宽无限制 |

**不确定选哪个？用自适应版。**

---

## ✨ 功能亮点

- 🔍 **智能搜索**：模拟真实搜索行为，自动滚动加载建议
- 📖 **自然阅读**：随机时长、随机滚动，模拟真实阅读
- ✅ **一键完成**：自动完成每日签到和活动任务
- 🎯 **积分追踪**：实时进度条显示，今日/本月积分统计
- 📱 **后台运行**：前台服务 + 无障碍服务双保险
- 🎨 **自适应 UI**：默认 600dp 限宽，支持自定义屏幕像素精准适配

## 🖥️ 屏幕自定义像素 (v1.11.0+)

设置页 → 屏幕自适应 · 自定义像素：

| 字段 | 说明 | 默认 |
|------|------|------|
| **启用** | 打开自定义像素模式 | 关 |
| **最大限宽像素** | 横屏大屏时的 UI 宽度上限 (px) | 600dp 自动 |
| **屏幕宽 / 高像素** | 参考值，用于调试和换算 | 0 |

填 0 或关闭即回到默认 Material You 自适应限宽。不填则所有 Android 设备自动适配。

## 🚀 快速开始

1. 从 [Releases](https://github.com/zhq380/bing-rewards-assistant/releases) 下载最新 APK
2. 安装并授予无障碍服务权限
3. 授予前台服务、通知、悬浮窗权限
4. 启动必应，在脚本设置页配置参数
5. 一键运行！

## 🛠️ 系统要求

- Android 8.0+ (API 26+)
- Microsoft Bing 应用
- 无障碍服务权限
- 前台服务权限（Android 12+）

## 📝 权限说明

| 权限 | 用途 |
|------|------|
| 无障碍服务 | 模拟点击、滑动、输入 |
| 前台服务 | 保持后台运行 |
| 通知 | 显示运行状态和进度 |
| 悬浮窗 | 悬浮控制面板 |
| 开机自启 | 定时任务（可选） |

## ⚠️ 免责声明

本项目仅供学习研究使用。使用本脚本获取 Microsoft Rewards 积分可能违反微软服务条款，请自行承担风险。

## 📄 License

MIT