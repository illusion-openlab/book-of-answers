# 答案之书 — 设计文档

日期：2026-08-17
项目：BookOfAnswers（`tech.illusion.bookofanswers`）
平台：PICO OS 6 / Spatial SDK BOM 6.0.0，volumetric WindowContainer

## 1. 目标

volume 里放一本书。用户用手戳一下，书翻开，页面上出现一条随机答案。再戳一下，书合上又翻开，换一条新答案。

原始需求描述的是「翻页」。实测所得模型只包含开合动画、不含翻页（第 6.2 节），经确认改为「合上再翻开」的重抽仪式 —— 与「重新抽一次」的语义更吻合，且无需额外资产。

## 2. 已确定的决策

| 决策 | 结论 | 理由 |
|---|---|---|
| 答案语言 | 中文为主，用全部 1099 条 | 语料量最大、重复感最低；中文中位数 6 字，排版最容易做好看 |
| 交互节奏 | 再戳一下就重抽，答案常驻不自动消失 | 契合「默念问题 → 触碰 → 书翻开 → 看答案」的仪式感；状态机最简单，最不易误触 |
| 翻页表现 | 用模型自带的**开合**动画做「合上再翻开」，不做真正的翻页 | 模型不含翻页动画且为单一蒙皮网格，逐页 tween 不可行（6.2）。「合上再翻开」在语义上就是「重新抽一次」，仪式感比翻单页更完整，且零额外资产成本 |
| 答案呈现 | 浮在书页上方的 SpatialUI 面板 | 替代方案（把文字烘进书页贴图）需运行时动态生成纹理，复杂度翻倍且失去 PicoTheme 排版与可读性保障 |
| 输入处理 | 统一按 tap 处理，接受全部 `InteractionKind` | 手指戳、捏合、注视捏合、射线点走的都是同一个 `detectSpatialTapGesture` 回调，不区分来源。开发期用射线验证，真机手指戳留到最后一并测 |
| 待机文案 | 主「心中默念你的问题」／副「然后触碰这本书」 | 见 4.4.1 |
| 碰撞体 | bounding box，不用 mesh | 文档明确 mesh collider 性能开销高得多；书本外形接近盒子，精度差异用户不可感知 |

## 3. 素材盘点

来源：`https://github.com/zhang-brook/answerbook`

| 文件 | 内容 | 用途 |
|---|---|---|
| `assets/all.json` | 1100 条答案，字段 `{id, chinese, english}`。1099 条有中文，295 条有英文，294 条两者都有。中文长度中位数 6、p95 12、最长 19 | **核心素材**，转换后打包进 app |
| `assets/img/background.jpg` | 网页背景图 | 不用 |
| `assets/img/title.png` | 网页标题图 | 不用 |
| `answer.txt` | 空文件（0 行） | 不用 |
| `*.sql` / `src/*.js` | 网页后端 | 不用 |

**仓库内没有任何 3D 书本模型。** 书本模型由用户另行提供，实测结果见第 6 节。

## 4. 架构

五个单元，各自单一职责、接口清晰、可独立理解。

### 4.1 `AnswerRepository` — 数据层

- **做什么**：持有答案列表，对外提供 `next(): Answer`
- **怎么用**：启动时从 `assets/answers.json` 载入一次；`next()` 返回随机答案，内部维护一个容量 32 的最近使用环形队列，重抽直到命中队列外的条目，避免短期内重复（1099 条语料下 32 的排除窗口不会显著影响随机性）
- **依赖什么**：无。纯 Kotlin + Android `AssetManager`，**不依赖 Spatial SDK**

零 SDK 依赖是刻意的：这是整个项目里唯一能自动化单元测试的部分，所以把可测逻辑尽量收拢到这里。

`answers.json` 由 `all.json` 预处理生成：滤掉 `chinese` 为空的条目（1 条），只保留 `{id, text}`。

### 4.2 `BookScene` — 3D 装配

- **做什么**：加载书模型，摆放到位，挂碰撞与交互组件
- **怎么用**：在 `SpatialView(initial = ...)` 中一次性执行；返回持有 `bookEntity` 的句柄
- **依赖什么**：Spatial SDK ECS、模型资源

装配流程：

```kotlin
Entity.loadSuspend(uriString = "asset://book.usdz").apply {
    // 原生尺寸实测为 0.03 × 0.21 × 0.29 m，已是真实书本大小，不缩放。
    // 仅设置在 volume 内的落位与朝向（具体值见第 9 节未决项 2）。
    components[TransformComponent::class.java]?.apply {
        setPosition(BOOK_POSITION)
        setEulerAngles(BOOK_ORIENTATION)
    }

    val bounds = getVisualBounds(this, recursive = true, enabledOnly = true)
    components.set(
        CollisionComponent(
            collisionShape = listOf(ShapeResource.createBox(bounds.size)),
            physicsMaterial = PhysicsMaterialResource(),
        )
    )
    components.set(InteractableComponent())
}
```

碰撞体按**合着**状态的包围盒计算即可 —— 书摊开后占地更大，但触碰目标始终是书本体，合着时的盒子已覆盖用户会去戳的区域。若实机发现摊开后边缘戳不到，改用摊开帧的包围盒重算。

本模型是单一蒙皮网格，**无独立书页节点**，因此不需要 `findEntity` 查找页节点。

### 4.3 `BookAnimator` — 开合动画区间控制

模型自带一段名为 `Demo` 的 USD 骨骼动画，内容是**开书与合书**，不含翻页（实测详见第 6 节）。因此本单元不生成 tween，而是对内置动画做**区间定位播放**。

- **做什么**：把内置动画切成语义化的四个状态，对外提供 `open(onComplete)` 与 `closeThenOpen(onSwap, onComplete)`
- **怎么用**：由 `BookState` 调用
- **依赖什么**：`AnimationPlaybackController`

时间轴分段（原始单位为帧，fps = 120）：

| 语义 | 帧 | 秒 |
|---|---|---|
| 合着驻留 | 5 – 100 | 0.04 – 0.83 |
| 开书 | 100 → 200 | 0.83 → 1.67 |
| 摊开驻留 | 200 – 300 | 1.67 – 2.50 |
| 合书 | 300 → 400 | 2.50 → 3.33 |

控制手段（`AnimationPlaybackController`，API 已核实）：

```kotlin
controller.setTime(seconds)   // 定位
controller.resume()           // 播放
controller.pause()            // 停在当前帧
controller.getTime()          // 轮询进度，用于判断是否到达区间终点
controller.close()            // 释放
```

`closeThenOpen` 的时序：`setTime(2.50)` → `resume()` → 轮询至 `getTime() >= 3.33` → `pause()` → **此刻换答案** → `setTime(0.83)` → `resume()` → 轮询至 `>= 1.67` → `pause()`。

答案在书完全合上的瞬间替换，用户看不到切换过程。这是"合上再翻开 = 重新抽一次"读起来成立的关键。

区间终点的判定放在 ECS 侧的轮询中，不依赖 Compose 帧回调。

**资源释放是硬性要求**：dispose 路径须 `entity.stopAllAnimations()` 并 `controller.close()`。

### 4.4 `AnswerPanel` — SpatialUI 展示

- **做什么**：渲染当前答案文本
- **怎么用**：`AttachmentPanel(id = "answer_panel")`，由 `BookScene` 定位到书页上方
- **依赖什么**：SpatialUI + PicoTheme

沿用现有 `HomeVolume` 已验证的 attachment 模式。走 `PicoTheme.colorScheme.labelPrimary` 与 `PicoTheme.typography`，背景用 `backgroundMaterial(true, Material.Regular)`。面板宽度按中文最长 19 字定尺寸。

**禁止 Material/Material3**（项目硬约束，见 `CLAUDE.md`）。

#### 4.4.1 面板的两种内容

面板本身是同一个，内容随 `BookState` 切换：

| 状态 | 内容 |
|---|---|
| 首次进入（`Closed`） | 主：**心中默念你的问题**<br>副：**然后触碰这本书** |
| `Revealed` | 当前抽到的答案（单行，最长 19 字） |

用词说明，供后续迭代时保持一致：

- 用「触碰」不用「点击」。「点击」是 2D 界面词汇；用户在空间中用手指戳一本实体书，「触碰」才对得上身体动作。
- 不用「揭晓」。综艺感过重，冲淡神秘。答案之书的气质是「答案本来就在那儿，你只是翻到它」，而非「揭晓谜底」。

提示文案**只在书第一次翻开前出现**。进入 `Revealed` 后答案常驻，再次触碰直接重抽，提示不再回归 —— 用户此时已经知道怎么用了，重复提示只会削弱仪式感。

两行的排版层级：主句用 `PicoTheme.typography` 的标题级角色，副句降一级并降低不透明度，形成引导而非并列的关系。具体 token 在实现时经 `spatial-ui-design-style` 确认。

### 4.5 `BookState` — 状态机

```
Closed ──触碰──> Opening ──到达帧200──> Revealed ──触碰──> Reshuffling ──┐
  │                                        │                            │
  │                                        │        （合书→换答案→开书）  │
  └─ 书合着                                 └─ 书摊开                     │
     面板显示提示文案                          面板显示答案（常驻）          │
                                             ▲                          │
                                             └──────────────────────────┘
```

| 状态 | 书 | 面板 | 触碰响应 |
|---|---|---|---|
| `Closed` | 停在帧 5 | 提示文案 | → `Opening` |
| `Opening` | 播 100→200 | 提示文案淡出 | **忽略** |
| `Revealed` | 停在帧 250 | 当前答案 | → `Reshuffling` |
| `Reshuffling` | 播 300→400，换答案，再播 100→200 | 合上时切换 | **忽略** |

- `Opening` 与 `Reshuffling` 期间**忽略一切输入**。不加这道闸，连续触碰会把动画打断成一团乱。
- `Closed` 只在首次进入时存在，之后不再回归。
- 状态由 Kotlin 侧持有，**不通过 Compose recomposition 驱动 3D**。

### 4.6 数据流与 ECS-first 约束

首次触碰：

```
手指戳 / 射线点
  → detectSpatialTapGesture 回调，校验 targetEntity == bookEntity
  → BookState.onPoke()                     [仅 Closed / Revealed 响应]
  → BookAnimator.open()                    → 内置动画播 0.83s → 1.67s
  → 到达终点 → AnswerRepository.next() → 更新 AnswerPanel 文本
  → BookState = Revealed
```

再次触碰（重抽）：

```
  → BookAnimator.closeThenOpen()
      合书 2.50s → 3.33s
      └─ 书完全合上的瞬间 → AnswerRepository.next() → 更新面板文本
      开书 0.83s → 1.67s
  → BookState = Revealed
```

**关键约束**：触碰事件直接驱动 ECS 动画，不经由 2D UI 状态再回流到 3D。整条链路上只有「答案文本」这一个值流向 Compose。

插件规范将 2D→3D 反馈回路列为明确禁止项（引入可避免的延迟与抖动）。开合动画完全由 ECS 动画系统承担，Compose 不参与逐帧驱动。

交互接线：

```kotlin
SpatialView(
    Modifier.pointerInput(Unit) {
        detectSpatialTapGesture(context, bookEntity?.let { TargetEntity.hit(it) }) { tap ->
            // tap.interactionKind: Poke / DirectPinch / GazePinch / RayBasedPinch / Pointer
            // 全部接受 —— 真机上是手指戳，模拟器上是射线
        }
    }
) { content, attachments -> /* BookScene 装配 */ }
```

注意：文档明确**不可在同一个 `pointerInput` DSL 内调用多个 `detectSpatial*` API**（互斥，会导致识别失败）。本设计只用一个 tap，暂无此问题；后续若加拖拽，必须另起一个 `pointerInput`。

## 5. 降级与容错

| 情况 | 处理 |
|---|---|
| 模型加载抛 `ResourceLoadingException` | 显示兜底面板说明模型缺失，app 不崩 |
| `getAnimationResources()` 返回空 | 降级为「无动画模式」：书静置，触碰直接淡入淡出换答案。核心功能不残，只是失去仪式感 |
| 动画播放超时未到达目标时间 | 设一个宽限上限（区间时长的 2 倍），超时则 `pause()` 并强制进入 `Revealed`，避免状态机永久卡在 `Opening` / `Reshuffling` 而失去响应 |
| `answers.json` 解析失败或为空 | 回退到内置的 3 条硬编码答案 |

第二条（超时闸）不可省。动画区间终点靠轮询 `getTime()` 判定，一旦播放因任何原因停滞，没有超时闸的状态机会永久停在 `Opening` / `Reshuffling`，表现为「书戳不动了」—— 这是比动画不播更糟的失败模式。

无动画降级路径的必要性已经下降（6.3 实测动画正常），但仍保留：它成本极低，且能兜住换模型、换 SDK 版本等未来变化。

## 6. 3D 模型（已获取并验证）

来源文件：`Simple_animated_book.usdz`（Sketchfab 导出，3.8 MB）。以下全部为实测结果。

### 6.1 实测规格

| 项 | 实测值 | 对照设计要求 |
|---|---|---|
| 格式 | 有效 USDZ，内含 `scene.usdc` + 5 张贴图 | ✅ |
| upAxis | Y | ✅ |
| metersPerUnit | 0.01 |  |
| 包围盒 | **0.03 × 0.21 × 0.29 m**（SDK `getVisualBounds` 实测，合着状态） | ✅ 已是真实书本尺寸，无需缩放 |
| 三角面 | 216（顶点 238） | ✅ 远低于 5 万上限 |
| 贴图 | baseColor / normal / roughness / occlusion 均 2048²，metallic 为 1×1 常量 | ✅ 在 1–2K 区间内 |
| 材质绑定 | `material:binding → /scene/Materials/Base` | ✅ |
| 灯光 / 相机 | 无 | ✅ |
| 摊开后页面 | 干净的米色空白，中缝有书脊折痕 | ✅ 正合答案面板需要的底 |

层级结构：

```
scene (Xform)
├── Materials (Scope) └── Base (Material) + 6 Shader
└── SkinnedMeshes (Xform, scale 11.18)
    └── Sketchfab_model (Xform, Z-up→Y-up 转换矩阵)
        └── Root └── Armature (scale 0.846, 1.0, 0.810 —— 非均匀)
            └── skin0 (SkelRoot)
                ├── skeleton (Skeleton, 16 骨骼，命名 n5…n20 无语义)
                ├── Demo (SkelAnimation)
                └── Book (Mesh, 单一蒙皮网格)
```

### 6.2 动画实测：是开合书，不是翻页

尽管文件名为 `animated_book`，`Demo` 这段动画不包含任何翻页动作。按 43 个旋转采样逐帧解算的结果：

- `n5/n6`、`n5/n11`（两侧封面）：0° → 72.3° → 0°
- `n5/n16/n17/n18`、`n5/n16/n19/n20`：0° → 91.8° → 0°

先前从累计角位移得到的「183.7°」是 91.8° 去程加 91.8° 回程之和，**不是一次 180° 翻页**。

分段见 4.3。由于是单一蒙皮网格、无独立页 prim，「按名称取出每一页、用 tween 逐页旋转」的方案在此模型上不可行。

### 6.3 `skel:animationSource` 绑定位置：已验证无需修复

Sketchfab 的导出器把 `skel:animationSource` 写在了 **Mesh** prim 上，而非更常见的 Skeleton prim：

```
/scene/.../skin0/Book (Mesh)
    skel:animationSource -> /scene/.../skin0/Demo
    skel:skeleton        -> /scene/.../skin0/skeleton
```

**桌面端实测**：Blender 的 USD 导入器与 Hydra（`usdrecord`）两个相互独立的实现都**不应用**这段动画 —— 渲染多个时间码得到逐字节相同的图像。把同一关系改挂到 Skeleton prim 上后，两者立即正常。

**设备端实测（2026-08-17，PICO 模拟器 6.0.0，spatial runtime 6.0.0.0-alpha.11）**：

| 文件 | `findSkinnedMeshEntity()` | `getAnimationResources()` | 视觉确认 |
|---|---|---|---|
| 原始 `Simple_animated_book.usdz` | 1 | 1 | ✅ 开合动画正常播放 |
| 重绑 `animationSource` 的修正版 | 1 | 1 | ✅ 开合动画正常播放 |

**结论：PICO Spatial SDK 的 USD 实现比 Blender / Hydra 宽容，能够解析挂在 Mesh 上的绑定。原始文件可直接使用，不需要任何预处理步骤。**

这条记录保留下来，是因为它有两点持续价值：

1. 若日后动画突然不播，这是第一个该查的地方，且修法已知。
2. 它标定了一个认知边界 —— **桌面 USD 工具链的行为不能外推到 SDK**。当时基于 Blender 与 Hydra 的一致失败，我判断「SDK 很可能也不解析、必须预处理」，这个判断被设备实测推翻了。今后遇到同类问题，应当直接在设备上验证，而不是从桌面工具的表现推论。

### 6.4 已知取舍

216 三角面意味着几何本体只是若干平板，皮革质感与书页厚度**完全依赖 2.4 MB 的法线贴图**。远观无碍，VR 中近距离观察会暴露：边缘为硬直线，缺乏真实的页层。

对「置于身前观看」的使用场景可以接受。若后续更换更精细的模型，只要仍是「单一实体 + 内置开合动画」的形态，`BookScene` 与 `BookAnimator` 的接口无需改动。

## 7. 测试与验证边界

### 能自动化验证

- `AnswerRepository` 单元测试：随机性、短期不重复、空输入与损坏输入的兜底
- Gradle 构建
- 安装、启动、进程存活
- 截图（`pico-cli capture screenshot`）

### 不能自动化验证 —— 必须如实标注

**手指戳（`InteractionKind.Poke`）无法在模拟器中自动化触发。** `pico-cli shell input tap` 只注入 2D 屏幕坐标，不能驱动 volumetric 窗口与空间命中测试（来源：`spatial-emulator-usage` skill 的 spatial interaction limitation 条款）。

按用户决策，验证策略如下：

1. **开发期**：模拟器内用射线交互手动验证。`RayBasedPinch` 与 `Poke` 进入同一个 `detectSpatialTapGesture` 回调、走同一条代码路径，因此可完整覆盖状态机、翻页动画、答案刷新的全链路。代码层面不区分输入来源，验证等价性成立。
2. **收尾阶段**：真机手指 `Poke` 作为最后一项验收测试。在此之前，任何报告都必须将其标注为 pending，不得谎报已验证。

### 待验证风险

- ~~PICO SDK 是否解析本模型的 `skel:animationSource` 绑定~~ —— **已于 2026-08-17 在模拟器实测排除，动画正常播放**（6.3）
- **PicoTheme 默认字体的中文覆盖**。ROM 为中文环境，大概率正常，但需在首次截图时确认，避免最终出现豆腐块
- 开合动画的播放速率需真机手感调整。内置时长为开 0.83s / 合 0.83s，可用 `setSpeed()` 整体调节，模拟器帧率不代表设备表现
- 书本在 volume 中的落位、朝向与答案面板的相对高度，需在设备上按实际观感调整

## 8. 环境现状（供后续参考）

- 模拟器：`Pico_Emulator_6_0`，bundle 6.0.0，spatial runtime `6.0.0.0-alpha.11`，与 SDK BOM 6.0.0 匹配
- 旧的 0.13 模拟器与 AVD 已删除。`sdk/0.13/editor` 保留未动
- `PICO_HOME` 须为小写路径 `~/Library/pico/sdk`；`JAVA_HOME` 指向 Android Studio JBR。均已写入 `~/.zshrc`

详见 `CLAUDE.md` 的 onboarding 段。

## 9. 未决项

1. **书本在 volume 内的落位与朝向**（`BOOK_POSITION` / `BOOK_ORIENTATION`），以及答案面板相对书页的高度。验证切片中书本默认朝向是**书脊侧对观察者**，施加 -60° pitch 后看到的是封面背面 —— 正确朝向需在设备上实测定标，预计需要一个 yaw 分量
2. 动画播放速率是否需要 `setSpeed()` 调整，待真机手感确认
3. 中文在 PicoTheme 默认字体下的渲染效果 —— 验证切片只显示了 ASCII，尚未覆盖

三项均为「拿到设备才能定」的调参事项，不阻塞实现开工。
