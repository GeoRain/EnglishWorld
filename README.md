# 英语天地 (EnglishWorld)

一款专为中小学生打造的英语学习Android应用，支持单词查询、分级学习、每日推荐等功能。

## 功能特点

### 核心功能
- **智能查词** - 快速查找英文单词，支持模糊搜索
- **TAB导航** - 首页、查词、学习、我的 四大模块
- **开场动画** - 精美的启动画面，提升用户体验
- **分级学习** - 按小学/初中分类展示词汇
- **离线使用** - 内置完整词库，无需联网

### 单词库内容
- **小学词汇** (50+ 示例词) - 基础词汇：动物、水果、颜色等
- **初中词汇** (50+ 示例词) - 核心词汇：中考必备
- **高中词汇** (扩展中) - 高考相关词汇

## 项目结构

```
EnglishWorld/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml          # 应用清单文件
│       ├── build.gradle                 # 构建配置
│       ├── java/com/englishworld/
│       │   ├── MainActivity.kt          # 主界面（TAB栏）
│       │   ├── SplashActivity.kt        # 开场动画
│       │   ├── ViewPagerAdapter.kt      # ViewPager适配器
│       │   ├── Word.kt                  # 单词数据模型
│       │   ├── WordDatabase.kt          # 单词数据库
│       │   ├── WordListAdapter.kt       # 单词列表适配器
│       │   ├── HomeFragment.kt          # 首页Fragment
│       │   ├── SearchFragment.kt        # 查词Fragment
│       │   ├── LearnFragment.kt         # 学习Fragment
│       │   ├── MineFragment.kt          # 我的Fragment（含隐藏的版本安装入口）
│       │   ├── ApkInstaller.kt          # 安装通道编排（设备官方接口 → 系统安装器）
│       │   ├── ZybosBridge.kt           # zybos_manager 绑定探测与静默安装
│       │   └── UpdateManager.kt         # 新版本 APK 下载与校验
│       └── res/
│           ├── layout/                  # 布局文件
│           ├── drawable/                # 图标资源
│           ├── values/                  # 资源值
│           └── anim/                    # 动画资源
├── website/
│   └── index.html                      # 官网页面
└── README.md                           # 项目说明
```

## 快速开始

### 环境要求
- Android Studio Hedgehog 或更高版本
- JDK 17+
- Android SDK 34+
- Gradle 8.0+

### 安装步骤

1. **克隆或下载项目**
```bash
cd EnglishWorld
```

2. **使用Android Studio打开项目**
   - 打开 Android Studio
   - 选择 `File` -> `Open`
   - 选择 `EnglishWorld` 目录
   - 等待Gradle同步完成

3. **运行应用**
   - 连接Android设备或启动模拟器
   - 点击运行按钮
   - 等待应用安装并启动

## 应用界面预览

### 开场动画
- 显示应用Logo和名称
- 2.5秒后自动跳转到主界面
- 平滑的淡入淡出效果

### 主界面（TAB栏）
1. **首页** - 每日一句、快速入口、学习进度
2. **查词** - 搜索框、单词详情展示
3. **学习** - 年级选择、单词列表浏览
4. **我的** - 个人中心、收藏、历史、设置

## 官网

访问 `website/index.html` 查看官网页面：
- 响应式设计，适配各种设备
- 功能介绍和特点展示
- 下载引导区域
- 数据统计展示

## 版本更新

应用内「我的 → 连点版本号 9 次 → 安装本地 APK / 检查更新」走这条链路：

1. **下载**：`UpdateManager` 按顺序尝试下载源，默认第一源是仓库 Release 的稳定附件
   `https://github.com/GeoRain/EnglishWorld/releases/latest/download/JCCodeV3.apk`，
   第二源是官网分发地址兜底；开发者页可临时覆盖。
   以后发新版本，只需在新 Release 里挂**同名**附件 `JCCodeV3.apk`，客户端不用改代码。
2. **校验**：ZIP 魔数 → 包可解析 → 降级拦截 → 同名应用签名比对。
3. **安装**：`ApkInstaller` 先探测设备官方接口 `zybos_manager`（`ZybosBridge`），
   可用则 `setInstallPackageDisallow(false)` → `silentInstallApk(path, true, pkg)` 静默安装并轮询验证；
   被系统签名校验封禁或设备无此接口时，明确提示原因并改用系统安装器（用户确认）。

当前分发的是 **JC Code V3 3.2.6**（包名 `JC.CODE.V3`，versionCode 97），
与官网旧的 3.1.0 证书不同，覆盖安装前必须先卸载旧版。

## 技术栈

- **语言**: Kotlin
- **最低版本**: Android 7.0 (API 24)
- **目标版本**: Android 14 (API 34)
- **UI组件**: Material Design Components
- **架构**: Fragment + ViewPager2 + TabLayout
- **官网**: HTML5 + CSS3 (响应式设计)
- **图标**: Google Material Icons

## 主要代码说明

### SplashActivity.kt
开场动画Activity，使用协程延迟2.5秒后跳转到主界面。

### MainActivity.kt
主界面，包含ViewPager2和TabLayout实现底部导航栏。

### WordDatabase.kt
单词数据管理类，提供搜索、按年级筛选等功能。

### SearchFragment.kt
查词页面，实现实时搜索和单词详情展示。

## 自定义说明

### 添加更多单词
编辑 `WordDatabase.kt` 文件，在对应的List中添加Word对象：

```kotlin
Word("word", "/wɜːd/", "单词", "This is a word.", "小学")
```

### 修改主题颜色
编辑 `res/values/colors.xml` 文件中的颜色值。

### 更新官网内容
编辑 `website/index.html` 文件即可自定义官网内容。

## 许可证

本项目仅供学习和参考使用。

## 开发者

英语天地开发团队

---

Copyright 2026 英语天地 EnglishWorld. All rights reserved.
