# Ripple Rewards Script · 必应积分自动助手

> 「微软必应积分」Android 无障碍自动化脚本，基于 **ColorOS 16 光场设计** 构建。

[![](https://img.shields.io/badge/Android-36%2B-blue)](https://developer.android.com/)
[![](https://img.shields.io/badge/Kotlin-2.0-blue)](https://kotlinlang.org/)
[![](https://img.shields.io/badge/Jetpack%20Compose-brightgreen)](https://developer.android.com/jetpack/compose)
[![](https://img.shields.io/badge/ColorOS-16-orange)](https://www.coloros.com/)

## ✨ 功能特性

| 功能                     | 说明                              |
| ---------------------- | ------------------------------- |
| 🎯 **每日自动签到**          | 智能识别「金色硬币」位置，自动随日期后移点击，无需手动修改位置 |
| 🔍 **智能搜索**            | 从内置词库随机选词，支持中英文混合，自动完成          |
| 📰 **连续阅读**            | 自动化缺口预计算 + 一次进入新闻流连续读完，减少来回跳转   |
| 📅 **每日活动**            | 自动点击连续栏目活动卡片，完成每日任务             |
| 🧩 **双实例支持**           | 主应用 / 应用分身 分别独立配置，支持同时运行        |
| 🛡️ **看门狗自愈**          | 因系统弹窗/分身选择器导致离开必应，超时自动重试拉回，不卡死  |
| 💾 **断点续跑**            | 进程被杀/清后台后，重启自动跳过今日已完成任务         |
| 🎨 **ColorOS 16 光场设计** | 原生光场质感、极光引擎动效、温润通透配色            |
| 📊 **运行日志独立页**         | 首页只保留脚本卡片，日志统计单独分页              |

## 📋 自动化算法

1. **缺口预计算**：解析积分卡片获取「总积分 - 当前积分 / 每个任务积分」→ 自动算出本次还需搜索/阅读多少次，不用手动设置
2. **连续阅读模式**：一次进入新闻列表 → 逐个点开阅读 → 计时完成后再点开下一个 → 读完目标次数回积分页确认
3. **签入积分闭环**：点硬币 → 等待积分到账 → 对比前后积分 → 未到账重试 → 确认到账才标记完成
4. **每日硬币定位**：通过无障碍找到 `Day N` 标签 → 根据 N 推算上方硬币位置 → 点击对应位置，自动适配每日后移
5. **看门狗检测**：记录最后活动时间戳 → 超时无活动说明被弹窗卡住 → 自动重试拉回必应 → 耗尽重试次数才终止

## 📱 环境要求

* **Android 版本**: API 26+ (Android 8.0)

* **ColorOS 版本**: 建议 ColorOS 16 匹配设计语言，旧版本也能运行

* **需要权限**:

  * 无障碍服务（必填）

  * `WRITE_SECURE_SETTINGS`（可选，用于自动恢复无障碍服务，需要通过 ADB 授权）

  * 通知权限、电池白名单（推荐，保活用）

## 🔧 安装使用

1. **下载 APK**: 从 [GitHub Releases](https://github.com/zhq380/RippleScript/releases) 下载最新 APK，安装到你的 Android 设备
2. **开启无障碍**: 进入「系统设置 → 无障碍」找到 `Ripple Rewards Script` 开启服务
3. **（可选）授权** **`WRITE_SECURE_SETTINGS`**: 如果需要自动恢复无障碍（被杀后重启），通过 ADB 执行：

   ```shell
   adb shell pm grant com.ripple.script android.permission.WRITE_SECURE_SETTINGS
   ```
4. **配置脚本**: 打开 APP，在「运行」页点击主应用/分身卡片进入配置，开启你需要的模块
5. **点击运行**: 返回首页点击「立即运行」，脚本会自动在后台完成今日任务

## 🎯 工作流程

```
准备（拉起必应） → 会话检测（确认已登录）
  ↓
每日签到（点金币） → 积分闭环验证
  ↓
每日活动 → 完成后回积分页
  ↓
缺口预计算 → 搜索 × N 次 → 阅读 × N 次
  ↓
检测全部完成 → 保存结果 → 推送通知 → 结束
```

## 🏗️ 项目结构

```
com.ripple.script/
├── data/              # 数据持久化（参数存储/历史记录/断点进度）
├── rewards/           # 核心业务逻辑（状态机/智能识别/算法）
├── service/          # 无障碍服务/悬浮窗控制/定时任务/保活广播
├── ui/               # Jetpack Compose 配置界面（ColorOS 16 光场设计）
│   ├── screens/      # 运行中心 / 运行日志 / 全局设置 / 脚本设置
│   └── theme/        # Color 配色 / Motion 动效 / Interactions 交互 / Theme 主题
├── util/             # 权限工具 / App 启动
└── MainActivity.kt    # 入口 Activity
```

## 🔄 更新日志

* **v1.10.x**: 升级 ColorOS 16 光场设计，偏好设置默认折叠，界面精简

* 实现签到硬币位置智能推算

* 新增自动化缺口预计算，连续阅读模式

* 解决分身选择器弹窗卡死问题，看门狗自愈机制

* 签入积分闭环验证，确保点对了才有积分

* 采用 Jetpack Compose，ColorOS 设计语言重新设计 UI

## 📄 许可证

[MIT License](LICENSE)

## 🙏 致谢

* [OPPO ColorOS 16](https://www.coloros.com/version/coloros16) 设计理念参考

* 微软必应积分活动玩法参考了社区众多方案，在此致谢

