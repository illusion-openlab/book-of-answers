# 答案之书

一本书躺在你面前。心中默念一个问题，碰它一下，书翻开，给你一句话。

基于 **PICO Spatial SDK 6.0** 的 Volume（Volumetric WindowContainer）空间应用，运行于 PICO OS 6。
1,094 条答案随安装包分发，**运行时不发起任何网络请求**。

## 特性

- **平放开合** —— 合上时平躺，翻开时摊成双页，两端都是平的，不会立起来
- **触碰即答** —— 碰一下翻开并浮现答案，再碰一下合上；合上后回到「心中默念你的问题」
- **文字浮在书上空** —— 答案显示在书正上方的毛玻璃面板里，随开合淡入淡出
- **多种触碰方式** —— 捏合、手部射线、注视捏合、控制器，以及**食指指尖直接戳**（双手均可）
- **不重复抽取** —— 连续抽取不会立刻重复，窗口为语料量的一半
- **完全离线** —— 无网络权限，无数据采集，无内购，无账号

## 素材来源与授权

本仓库源代码以 MIT 发布，但随包分发的素材各有来源，需要分别说明。

| 部分 | 来源 | 授权 |
| --- | --- | --- |
| 答案文案 `app/src/main/assets/answers.txt` | [zhang-brook/answerbook](https://github.com/zhang-brook/answerbook) | **上游未声明授权**，见下 |
| 书本模型 `app/src/main/assets/book.usdz` | Sketchfab [Simple animated book](https://sketchfab.com/3d-models/simple-animated-book-ed83e749b47c4703be85d56539ee9f2e) by [Zayfert1999](https://sketchfab.com/Zayfert1999) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)，**已修改** |
| 图标素材 `art/icon-source-*.png` | 本项目 AI 生成 | 随本仓库 MIT |
| 源代码 | 本项目 | [MIT](LICENSE) |

### 答案文案

文案整理自 [zhang-brook/answerbook](https://github.com/zhang-brook/answerbook)，
经去重后得到 1,094 条。**该上游仓库未包含任何 LICENSE 文件，也未声明授权方式**
（GitHub API 的 `license` 字段为 `null`）。这些短句本身是中文互联网上流传多年的
「答案之书」民间汇编，原始出处已难追溯。

此处如实标注来源，不代表本项目对这部分文本主张任何权利。**若你要复用
`app/src/main/assets/answers.txt`，授权状态需要你自行判断。**

### 书本模型

模型采用 CC BY 4.0，署名见上表。授权信息就嵌在文件里，可自行核验：

```bash
python3 -c "from pxr import Usd; print(Usd.Stage.Open('app/src/main/assets/book.usdz').GetRootLayer().customLayerData)"
```

CC BY 4.0 要求声明修改，**本仓库内的副本相对 Sketchfab 原始下载有一处改动**：
原文件把 `skel:animationSource` 绑定在三个 Mesh prim 上，而它们是 `Skeleton` 的兄弟节点。
按 UsdSkel 规范，骨架的动画源只从自身或祖先继承解析，因此骨架解析不到动画，网格永不变形
（PICO 运行时会报 `Failed to get skeleton animation source`）。修复方式是给 `Skeleton`
补上 `SkelBindingAPI` 并将 `skel:animationSource` 指向动画 prim `Demo`，几何、贴图、
动画曲线均未改动。

## 构建与运行

需要 JDK 17、Android SDK（compileSdk 35）与 PICO Spatial SDK 依赖仓库。

```bash
./gradlew :app:assembleDebug          # 构建
./gradlew :app:testDebugUnitTest      # 单元测试
```

安装到模拟器或设备（[pico-cli](https://developer-cn.picoxr.com/)）：

```bash
pico-cli emulator start
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
pico-cli app launch tech.illusion.bookofanswers --activity .platform.LaunchActivity
```

启动正常时只有两行日志：

```
AnswerSource: loaded 1094 answers
BookScene: book loaded, animated=true, skinnedMeshes=1, controllers=1, ...
```

`skinnedMeshes` 与 `controllers` 必须相等 —— 不相等说明有蒙皮网格没被驱动，
书会只翻开一部分。

### 打签名包

在 `local.properties`（**不进版本库**）中补上四行，指向你自己的 keystore：

```properties
RELEASE_STORE_FILE=/绝对路径/your-keystore.jks
RELEASE_STORE_PASSWORD=你的密码
RELEASE_KEY_ALIAS=你的别名
RELEASE_KEY_PASSWORD=你的密码
```

然后：

```bash
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

四个键缺任意一个时，构建不会失败，而是产出 `app-release-unsigned.apk`——
这样没有密钥的人也能完整构建本项目。

## 工程结构

```
app/src/main/java/tech/illusion/bookofanswers/
├── content/HomeVolume.kt      Volume 内容装配；两条独立的触碰输入链
├── content/BookScene.kt       模型加载、姿态补偿、碰撞体、动画器构建
├── content/BookAnimator.kt    按帧区间定位播放开合动画
├── content/BookState.kt       开合状态机（纯 Kotlin，无 SDK 依赖）
├── content/AnswerPanel.kt     SpatialUI 答案面板
├── data/                      语料读取、解析与不重复抽取
└── platform/                  Application 与 LaunchActivity
```

几处刻意的设计：

- **姿态补偿而非硬编码位置** —— 模型原点在书底，且视觉中心随开合移动 0.088 m，
  实体位置按开合进度反向补偿，书看起来才是「原地摊开」。开合过程中实体 roll 也要从
  +90° 插值到 0°，否则合上与摊开无法同时平放。所有数值都是离线用 `UsdSkel`
  逐帧解算蒙皮顶点量出来的，不是眼估。
- **两条触碰链分开挂** —— `detectSpatialTapGesture` 与 `detectSpatialPointerEvent`
  必须位于各自独立的 `pointerInput` 块，同一块内的多个 `detectSpatial*` 会争抢事件流。
  指尖触碰走后者，筛 `InteractionKind.Poke` 并在 `isDownEvent()` 上触发；左右手是不同的
  `pointerId`，双手支持不需要额外代码。
- **状态机与 SDK 解耦** —— `BookState` 不引用任何 SDK 类型，因此可以在纯 JVM 测试里跑。
- **全部 UI 使用 SpatialUI + `PicoTheme`** —— 工程内不含 Material / Material3。

## 图标

分层图标由脚本从两张母版重新生成，可复现：

```bash
python3 art/build-icons.py
```

分层规范见 PICO 文档 `spatial-design_foundation_icon_app-icon-and-layered-design`。
其中 `icon.sdf.list` 那两张 SDF 的规则文档未覆盖，是从脚手架素材实测逆推的
（内部欧氏距离场，31px 处线性截断），推导过程与实测数据见 [`art/README.md`](art/README.md)。

## 测试

21 项 JVM 单元测试，覆盖语料解析、不重复抽取与开合状态机：

```bash
./gradlew :app:testDebugUnitTest
```

## 隐私

本应用**不收集、不存储、不上传任何信息**，且没有网络权限，技术上无法联网。
完整说明见 [隐私政策](https://illusion-openlab.github.io/book-of-answers/legal/privacy-policy.html)
（[源文件](docs/legal/privacy-policy.md)），其中每一条承诺都给出了自行核验的方法。

## 许可

- **代码**：[MIT](LICENSE)
- **素材**：见上文「素材来源与授权」，逐项列明
