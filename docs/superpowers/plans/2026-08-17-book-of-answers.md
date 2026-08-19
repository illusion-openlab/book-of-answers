# 答案之书 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 volumetric 容器里放一本书，触碰它就翻开并显示一条随机答案，再触碰就合上重开、换一条。

**Architecture:** 五个单元。数据层（`Answer` / `AnswerParser` / `AnswerRepository`）与状态机（`BookState`）是纯 Kotlin、可 JVM 单测；3D 层（`BookScene` / `BookAnimator`）与展示层（`AnswerPanel`）依赖 Spatial SDK，靠构建 + 设备截图验证。触碰事件直接驱动 ECS 动画，只有「答案文本」一个值流向 Compose。

**Tech Stack:** Kotlin 2.1.20 / AGP 8.13.2 / PICO Spatial SDK BOM 6.0.0 / SpatialUI + PicoTheme / JUnit 4.13.2（`testImplementation(libs.junit)`，已在 `app/build.gradle.kts` 中）

**Spec:** `docs/superpowers/specs/2026-08-17-book-of-answers-design.md`

## Global Constraints

以下要求对每一个 Task 都生效，不再逐条重复。

- **SpatialUI 强制**：所有 2D UI 用 `com.pico.spatial.ui.*`，根节点包 `PicoTheme`。**禁止** `androidx.compose.material`、`androidx.compose.material3`、`MaterialTheme`、`Scaffold`。颜色走 `PicoTheme.colorScheme.*`，字体走 `PicoTheme.typography.*`，不得硬编码 `Color(0x...)` 或 `TextStyle(fontSize = ...)`（`.copy(fontSize = ...)` 微调允许）。
- **环境变量**：任何 Gradle / pico-cli 命令前必须先 export，否则构建失败：
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PICO_HOME="$HOME/Library/pico/sdk"
  ```
- **不得发明 API**。本计划中出现的每个 Spatial SDK 符号都已在 `~/Library/pico/sdk/6.0/agent-vault/spatial/api-reference/` 中核实。若编译报某符号不存在，去该目录 grep 确认真实签名，不要臆测替换。
- **包名**：`tech.illusion.bookofanswers`
- **待机文案**（逐字，不得改写）：主句「心中默念你的问题」，副句「然后触碰这本书」。用「触碰」不用「点击」；不用「揭晓」。
- **动画时间轴**（fps = 120，实测）：合着 frame 5、开书 frame 100→200、摊开 frame 250、合书 frame 300→400。
- **不得谎报验证**。截图没看到就说没看到；真机手指 `Poke` 在最后一个 Task 之前一律标注为 pending。

---

## 文件结构

| 文件 | 职责 | 测试 |
|---|---|---|
| `app/src/main/assets/answers.txt` | 答案语料，每行一条，1094 行 | — |
| `app/src/main/assets/book.usdz` | 书本模型（已就位，勿改） | — |
| `data/Answer.kt` | 答案数据类 | 随 Parser |
| `data/AnswerParser.kt` | 文本 → `List<Answer>`，纯函数 | `AnswerParserTest` |
| `data/AnswerRepository.kt` | 随机抽取 + 防近期重复 | `AnswerRepositoryTest` |
| `data/AnswerSource.kt` | 从 assets 读取并兜底，薄封装 | 不测（仅 IO） |
| `content/BookState.kt` | 状态机，回调注入 | `BookStateTest` |
| `content/BookAnimator.kt` | 内置动画的区间定位播放 + 超时闸 | 设备 |
| `content/BookScene.kt` | 加载模型、挂碰撞与交互组件 | 设备 |
| `content/AnswerPanel.kt` | SpatialUI 面板，两种内容 | 设备 |
| `content/HomeVolume.kt` | 组装以上全部 + 触碰接线 | 设备 |

### 对设计文档的一处偏离

设计文档 4.1 写的是 `assets/answers.json`。**本计划改为 `assets/answers.txt`，每行一条。**

原因：项目没有任何 JSON 依赖（`libs.versions.toml` 里只有 junit / androidx / spatial），而 `org.json` 在 JVM 单元测试中是抛异常的桩实现。若坚持 JSON，要么新增依赖，要么解析层无法单测 —— 后者会直接推翻设计文档「把可测逻辑收拢到数据层」的立论。纯文本让解析成为零依赖纯函数，可完整单测。

答案文本经实测确认无换行、无制表符，最长 19 字，按行存储安全。同时去重：1099 条非空中文中有 5 条完全重复，去重后 **1094 条**。

---

## Task 1: 初始化版本控制

**说明**：当前目录**不是 git 仓库**（`git rev-parse` 报 `not a git repository`）。后续 Task 每步都要提交，需要先建仓。

若用户不希望引入 git，跳过本 Task，并把后续所有「Commit」步骤一并跳过；其余步骤不受影响。

**Files:**
- Create: `.git/`（由 `git init` 生成）
- Verify: `.gitignore`（脚手架已生成，无需修改）

**Interfaces:**
- Consumes: 无
- Produces: 可用的 git 仓库，供后续所有 Task 提交

- [ ] **Step 1: 确认 .gitignore 已覆盖构建产物**

Run: `cat .gitignore`

Expected: 包含 `build/`、`.gradle`、`local.properties` 等条目。若缺失 `artifacts/`，追加一行 `artifacts/`（截图产物不入库）。

- [ ] **Step 2: 初始化仓库并建立首次提交**

```bash
git init
git add -A
git status --short | head -30
```

检查输出中**不应**出现 `app/build/`、`.gradle/`。若出现，先修 `.gitignore` 再 `git add -A`。

- [ ] **Step 3: 提交**

```bash
git commit -m "chore: init repository with scaffolded Spatial SDK project

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 4: 确认提交成功**

Run: `git log --oneline -1 && git status --short | wc -l`
Expected: 显示一条提交；未跟踪文件数为 0（或仅剩 `artifacts/`）。

---

## Task 2: 答案语料与解析

**Files:**
- Create: `app/src/main/assets/answers.txt`
- Create: `app/src/main/java/tech/illusion/bookofanswers/data/Answer.kt`
- Create: `app/src/main/java/tech/illusion/bookofanswers/data/AnswerParser.kt`
- Test: `app/src/test/java/tech/illusion/bookofanswers/data/AnswerParserTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class Answer(val text: String)`
  - `object AnswerParser { fun parse(raw: String): List<Answer> }`

- [ ] **Step 1: 生成 answers.txt**

源数据仓库已克隆在 scratchpad。若不存在，先执行：
`git clone --depth 1 https://github.com/zhang-brook/answerbook.git /tmp/answerbook`

```bash
python3 - <<'PY'
import json, io, os
src = '/tmp/answerbook/assets/all.json'
if not os.path.exists(src):
    raise SystemExit('源数据不存在，请先 clone answerbook 仓库')
data = json.load(open(src, encoding='utf-8'))
seen, out = set(), []
for item in data:
    t = item.get('chinese', '').strip()
    if not t or t in seen:
        continue
    assert '\n' not in t and '\r' not in t, f'含换行: {t!r}'
    seen.add(t); out.append(t)
io.open('app/src/main/assets/answers.txt', 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('written lines:', len(out))
PY
```

Expected 输出：`written lines: 1094`

- [ ] **Step 2: 校验产出文件**

```bash
wc -l app/src/main/assets/answers.txt
head -3 app/src/main/assets/answers.txt
awk '{ if (length($0) > 19) print "TOO LONG:", $0 }' app/src/main/assets/answers.txt | head
```

Expected: 行数 1094；前几行是中文答案；无 `TOO LONG` 输出。

- [ ] **Step 3: 写失败的测试**

Create `app/src/test/java/tech/illusion/bookofanswers/data/AnswerParserTest.kt`:

```kotlin
package tech.illusion.bookofanswers.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerParserTest {

    @Test
    fun `parses one answer per line`() {
        val result = AnswerParser.parse("去做吧\n再等等\n不要回头")
        assertEquals(
            listOf(Answer("去做吧"), Answer("再等等"), Answer("不要回头")),
            result,
        )
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(listOf(Answer("去做吧")), AnswerParser.parse("  去做吧  "))
    }

    @Test
    fun `skips blank lines`() {
        val result = AnswerParser.parse("去做吧\n\n   \n再等等\n")
        assertEquals(listOf(Answer("去做吧"), Answer("再等等")), result)
    }

    @Test
    fun `handles windows line endings`() {
        val result = AnswerParser.parse("去做吧\r\n再等等")
        assertEquals(listOf(Answer("去做吧"), Answer("再等等")), result)
    }

    @Test
    fun `empty input yields empty list`() {
        assertEquals(emptyList<Answer>(), AnswerParser.parse(""))
        assertEquals(emptyList<Answer>(), AnswerParser.parse("   \n  \n"))
    }
}
```

- [ ] **Step 4: 运行测试，确认失败**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PICO_HOME="$HOME/Library/pico/sdk"
./gradlew testDebugUnitTest --tests "*AnswerParserTest*"
```

Expected: 编译失败，`Unresolved reference: AnswerParser`（以及 `Answer`）。

- [ ] **Step 5: 写最小实现**

Create `app/src/main/java/tech/illusion/bookofanswers/data/Answer.kt`:

```kotlin
package tech.illusion.bookofanswers.data

/**
 * 一条答案。
 *
 * 文本即身份 —— 语料在打包阶段已按文本去重，因此不需要额外的 id。
 */
data class Answer(val text: String)
```

Create `app/src/main/java/tech/illusion/bookofanswers/data/AnswerParser.kt`:

```kotlin
package tech.illusion.bookofanswers.data

/**
 * 把 `assets/answers.txt` 的原始文本解析成答案列表。
 *
 * 格式：每行一条，忽略空行与行首尾空白。刻意不用 JSON —— 项目没有 JSON 依赖，
 * 而 `org.json` 在 JVM 单元测试中是抛异常的桩实现，那样这里就无法单测了。
 */
object AnswerParser {

    fun parse(raw: String): List<Answer> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Answer(it) }
            .toList()
}
```

- [ ] **Step 6: 运行测试，确认通过**

```bash
./gradlew testDebugUnitTest --tests "*AnswerParserTest*"
```

Expected: `BUILD SUCCESSFUL`，5 个测试全部通过。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/assets/answers.txt \
        app/src/main/java/tech/illusion/bookofanswers/data/Answer.kt \
        app/src/main/java/tech/illusion/bookofanswers/data/AnswerParser.kt \
        app/src/test/java/tech/illusion/bookofanswers/data/AnswerParserTest.kt
git commit -m "feat: add answer corpus and parser

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: AnswerRepository — 随机抽取与防重复

**Files:**
- Create: `app/src/main/java/tech/illusion/bookofanswers/data/AnswerRepository.kt`
- Test: `app/src/test/java/tech/illusion/bookofanswers/data/AnswerRepositoryTest.kt`

**Interfaces:**
- Consumes: `Answer`（Task 2）
- Produces:
  - `class AnswerRepository(answers: List<Answer>, random: Random = Random.Default, recentCapacity: Int = 32)`
  - `fun next(): Answer`

**关键约束**：排除窗口绝不能大到把所有候选都排除掉 —— 那会让 `next()` 陷入死循环。窗口取 `min(recentCapacity, answers.size / 2)`，保证任何时候至少一半语料可选。这不是理论风险：兜底路径只有 3 条答案，若窗口按 32 取就会当场挂死。

- [ ] **Step 1: 写失败的测试**

Create `app/src/test/java/tech/illusion/bookofanswers/data/AnswerRepositoryTest.kt`:

```kotlin
package tech.illusion.bookofanswers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AnswerRepositoryTest {

    private fun answers(n: Int) = (1..n).map { Answer("a$it") }

    @Test
    fun `always returns an answer from the source list`() {
        val source = answers(50)
        val repo = AnswerRepository(source, Random(1))
        repeat(200) { assertTrue(repo.next() in source) }
    }

    @Test
    fun `does not repeat within the recent window`() {
        // windowSize = min(10, 100 / 2) = 10，故任意 11 连抽应互不相同
        val repo = AnswerRepository(answers(100), Random(42), recentCapacity = 10)
        val drawn = (1..500).map { repo.next() }
        drawn.windowed(11).forEach { window ->
            assertEquals("窗口内出现重复: $window", 11, window.distinct().size)
        }
    }

    @Test
    fun `single answer list does not hang`() {
        val repo = AnswerRepository(listOf(Answer("only")), Random(1))
        repeat(20) { assertEquals(Answer("only"), repo.next()) }
    }

    @Test
    fun `tiny list does not hang even with large capacity`() {
        // 兜底路径就是 3 条答案配默认 capacity=32，必须不能死循环
        val repo = AnswerRepository(answers(3), Random(7), recentCapacity = 32)
        val drawn = (1..100).map { repo.next() }
        assertEquals(3, drawn.distinct().size)
    }

    @Test
    fun `empty list is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnswerRepository(emptyList(), Random(1))
        }
    }

    @Test
    fun `same seed yields the same sequence`() {
        val source = answers(60)
        val a = (1..30).map { AnswerRepository(source, Random(99)).next() }
        val b = (1..30).map { AnswerRepository(source, Random(99)).next() }
        assertEquals(a, b)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
./gradlew testDebugUnitTest --tests "*AnswerRepositoryTest*"
```

Expected: 编译失败，`Unresolved reference: AnswerRepository`。

- [ ] **Step 3: 写最小实现**

Create `app/src/main/java/tech/illusion/bookofanswers/data/AnswerRepository.kt`:

```kotlin
package tech.illusion.bookofanswers.data

import kotlin.random.Random

/**
 * 随机抽取答案，并避免短期内重复。
 *
 * 不依赖 Spatial SDK，也不依赖 Android framework —— 这是全项目唯一能跑
 * JVM 单元测试的部分，所以尽量把可测逻辑放在这里。
 */
class AnswerRepository(
    private val answers: List<Answer>,
    private val random: Random = Random.Default,
    recentCapacity: Int = RECENT_CAPACITY,
) {
    init {
        require(answers.isNotEmpty()) { "answers must not be empty" }
    }

    /**
     * 排除窗口不能大到把所有候选都排除掉，否则 [next] 无解、当场死循环。
     * 取语料量的一半封顶，保证任何时候至少还有一半可选。
     */
    private val windowSize = minOf(recentCapacity, answers.size / 2)

    private val recent = ArrayDeque<Answer>()

    fun next(): Answer {
        var picked: Answer
        do {
            picked = answers[random.nextInt(answers.size)]
        } while (windowSize > 0 && picked in recent)

        if (windowSize > 0) {
            recent.addLast(picked)
            while (recent.size > windowSize) recent.removeFirst()
        }
        return picked
    }

    companion object {
        const val RECENT_CAPACITY = 32
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
./gradlew testDebugUnitTest --tests "*AnswerRepositoryTest*"
```

Expected: `BUILD SUCCESSFUL`，6 个测试通过。若某个测试**挂住不返回**，说明窗口封顶逻辑被改错了，回到 Step 3 对照。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/bookofanswers/data/AnswerRepository.kt \
        app/src/test/java/tech/illusion/bookofanswers/data/AnswerRepositoryTest.kt
git commit -m "feat: add answer repository with recent-window dedup

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: AnswerSource — 从 assets 装载与兜底

**Files:**
- Create: `app/src/main/java/tech/illusion/bookofanswers/data/AnswerSource.kt`

**Interfaces:**
- Consumes: `Answer`、`AnswerParser`（Task 2）、`AnswerRepository`（Task 3）
- Produces: `object AnswerSource { fun load(context: Context): AnswerRepository }`

本 Task 不写单测：它只有文件 IO，可测逻辑都已在 Task 2/3 覆盖。

- [ ] **Step 1: 写实现**

Create `app/src/main/java/tech/illusion/bookofanswers/data/AnswerSource.kt`:

```kotlin
package tech.illusion.bookofanswers.data

import android.content.Context
import android.util.Log

/**
 * 从 assets 装载答案语料。读取或解析失败时回退到内置兜底，绝不让 app 因为
 * 语料问题而不可用。
 */
object AnswerSource {

    private const val TAG = "AnswerSource"
    private const val ASSET_NAME = "answers.txt"

    /** 语料读不出来时的兜底。条数很少，[AnswerRepository] 的窗口封顶会自动适配。 */
    private val FALLBACK = listOf(
        Answer("再等等"),
        Answer("去做吧"),
        Answer("答案不在书里"),
    )

    fun load(context: Context): AnswerRepository {
        val answers = try {
            val raw = context.assets.open(ASSET_NAME).use { it.readBytes().decodeToString() }
            AnswerParser.parse(raw).ifEmpty {
                Log.w(TAG, "$ASSET_NAME parsed to empty list, using fallback")
                FALLBACK
            }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to read $ASSET_NAME, using fallback", t)
            FALLBACK
        }
        Log.i(TAG, "loaded ${answers.size} answers")
        return AnswerRepository(answers)
    }
}
```

- [ ] **Step 2: 编译确认**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/tech/illusion/bookofanswers/data/AnswerSource.kt
git commit -m "feat: load answers from assets with fallback

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: BookState — 状态机

**Files:**
- Create: `app/src/main/java/tech/illusion/bookofanswers/content/BookState.kt`
- Test: `app/src/test/java/tech/illusion/bookofanswers/content/BookStateTest.kt`

**Interfaces:**
- Consumes: 无（动画与抽答案全部以回调注入，因此可脱离 SDK 单测）
- Produces:
  - `enum class BookPhase { Closed, Opening, Revealed, Reshuffling }`
  - `class BookState(openBook: ((() -> Unit)) -> Unit, closeThenOpen: (() -> Unit, () -> Unit) -> Unit, drawAnswer: () -> Unit)`
  - `val phase: BookPhase`
  - `fun onTap()`

- [ ] **Step 1: 写失败的测试**

Create `app/src/test/java/tech/illusion/bookofanswers/content/BookStateTest.kt`:

```kotlin
package tech.illusion.bookofanswers.content

import org.junit.Assert.assertEquals
import org.junit.Test

class BookStateTest {

    /** 手写的假动画：记录调用次数，并把完成回调留给测试手动触发。 */
    private class FakeAnimator {
        var openCalls = 0
        var closeThenOpenCalls = 0
        private var openDone: (() -> Unit)? = null
        private var swap: (() -> Unit)? = null
        private var reshuffleDone: (() -> Unit)? = null

        fun open(onDone: () -> Unit) {
            openCalls++
            openDone = onDone
        }

        fun closeThenOpen(onSwap: () -> Unit, onDone: () -> Unit) {
            closeThenOpenCalls++
            swap = onSwap
            reshuffleDone = onDone
        }

        fun finishOpen() = openDone!!.invoke()
        fun triggerSwap() = swap!!.invoke()
        fun finishReshuffle() = reshuffleDone!!.invoke()
    }

    private class Harness {
        val animator = FakeAnimator()
        var draws = 0
        val state = BookState(
            openBook = animator::open,
            closeThenOpen = animator::closeThenOpen,
            drawAnswer = { draws++ },
        )
    }

    @Test
    fun `starts closed`() {
        assertEquals(BookPhase.Closed, Harness().state.phase)
    }

    @Test
    fun `first tap opens the book`() {
        val h = Harness()
        h.state.onTap()
        assertEquals(BookPhase.Opening, h.state.phase)
        assertEquals(1, h.animator.openCalls)
        assertEquals("答案应在动画结束后才抽", 0, h.draws)
    }

    @Test
    fun `finishing open draws an answer and reveals`() {
        val h = Harness()
        h.state.onTap()
        h.animator.finishOpen()
        assertEquals(BookPhase.Revealed, h.state.phase)
        assertEquals(1, h.draws)
    }

    @Test
    fun `taps during opening are ignored`() {
        val h = Harness()
        h.state.onTap()
        h.state.onTap()
        h.state.onTap()
        assertEquals(1, h.animator.openCalls)
        assertEquals(BookPhase.Opening, h.state.phase)
    }

    @Test
    fun `tap while revealed starts a reshuffle`() {
        val h = Harness()
        h.state.onTap(); h.animator.finishOpen()
        h.state.onTap()
        assertEquals(BookPhase.Reshuffling, h.state.phase)
        assertEquals(1, h.animator.closeThenOpenCalls)
    }

    @Test
    fun `reshuffle swaps the answer while the book is shut`() {
        val h = Harness()
        h.state.onTap(); h.animator.finishOpen()
        assertEquals(1, h.draws)
        h.state.onTap()
        h.animator.triggerSwap()
        assertEquals("合上瞬间应换答案", 2, h.draws)
        h.animator.finishReshuffle()
        assertEquals(BookPhase.Revealed, h.state.phase)
        assertEquals("重开阶段不应再抽一次", 2, h.draws)
    }

    @Test
    fun `taps during reshuffling are ignored`() {
        val h = Harness()
        h.state.onTap(); h.animator.finishOpen()
        h.state.onTap()
        h.state.onTap()
        h.state.onTap()
        assertEquals(1, h.animator.closeThenOpenCalls)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
./gradlew testDebugUnitTest --tests "*BookStateTest*"
```

Expected: 编译失败，`Unresolved reference: BookState`。

- [ ] **Step 3: 写最小实现**

Create `app/src/main/java/tech/illusion/bookofanswers/content/BookState.kt`:

```kotlin
package tech.illusion.bookofanswers.content

enum class BookPhase { Closed, Opening, Revealed, Reshuffling }

/**
 * 触碰 → 开书 / 重抽 的状态机。
 *
 * ```
 * Closed ──触碰──> Opening ──动画完成──> Revealed ──触碰──> Reshuffling ──┐
 *                                          ▲                            │
 *                                          └────────────────────────────┘
 * ```
 *
 * 动画与抽答案都以回调注入，因此本类不依赖 Spatial SDK，可独立单测。
 * 状态由本类持有，**不通过 Compose recomposition 驱动 3D**。
 */
class BookState(
    private val openBook: (onDone: () -> Unit) -> Unit,
    private val closeThenOpen: (onSwap: () -> Unit, onDone: () -> Unit) -> Unit,
    private val drawAnswer: () -> Unit,
) {
    var phase: BookPhase = BookPhase.Closed
        private set

    fun onTap() {
        when (phase) {
            BookPhase.Closed -> {
                phase = BookPhase.Opening
                openBook {
                    drawAnswer()
                    phase = BookPhase.Revealed
                }
            }

            BookPhase.Revealed -> {
                phase = BookPhase.Reshuffling
                closeThenOpen(
                    { drawAnswer() },
                    { phase = BookPhase.Revealed },
                )
            }

            // 动画进行中忽略输入。不加这道闸，连续触碰会把动画打断成一团乱。
            BookPhase.Opening, BookPhase.Reshuffling -> Unit
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
./gradlew testDebugUnitTest --tests "*BookStateTest*"
```

Expected: `BUILD SUCCESSFUL`，7 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/bookofanswers/content/BookState.kt \
        app/src/test/java/tech/illusion/bookofanswers/content/BookStateTest.kt
git commit -m "feat: add book interaction state machine

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: BookAnimator — 内置动画的区间播放

**Files:**
- Create: `app/src/main/java/tech/illusion/bookofanswers/content/BookAnimator.kt`

**Interfaces:**
- Consumes: `AnimationPlaybackController`（由 Task 7 的 `BookScene` 提供）
- Produces:
  - `class BookAnimator(controller: AnimationPlaybackController, scope: CoroutineScope) : Closeable`
  - `fun showClosed()`
  - `fun open(onDone: () -> Unit)`
  - `fun closeThenOpen(onSwap: () -> Unit, onDone: () -> Unit)`
  - `override fun close()`

**背景**：模型自带的 `Demo` 动画是「开书 → 驻留 → 合书」，实测总时长 3.29166s。本类把它切成语义化区间来用。超时闸不可省 —— 区间终点靠轮询 `getTime()` 判定，一旦播放停滞而没有超时保护，状态机会永久停在 `Opening`，表现为「书戳不动了」，比动画不播更糟。

本 Task 无单测：`AnimationPlaybackController` 是 SDK 的 `@MainThread` 类，无法在 JVM 测试中构造。验证放在 Task 9。

- [ ] **Step 1: 写实现**

Create `app/src/main/java/tech/illusion/bookofanswers/content/BookAnimator.kt`:

```kotlin
package tech.illusion.bookofanswers.content

import android.util.Log
import com.pico.spatial.core.ecs.animation.AnimationPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

/**
 * 对模型内置的 `Demo` 骨骼动画做区间定位播放。
 *
 * 该动画的实际内容是「开书 → 驻留 → 合书」，不含翻页。时间轴分段（fps = 120，实测）：
 *
 * | 语义     | 帧        | 秒          |
 * |----------|-----------|-------------|
 * | 合着驻留 | 5 – 100   | 0.04 – 0.83 |
 * | 开书     | 100 → 200 | 0.83 → 1.67 |
 * | 摊开驻留 | 200 – 300 | 1.67 – 2.50 |
 * | 合书     | 300 → 400 | 2.50 → 3.33 |
 *
 * `scope` 必须是主线程 scope：`AnimationPlaybackController` 标注了 `@MainThread`。
 */
class BookAnimator(
    private val controller: AnimationPlaybackController,
    private val scope: CoroutineScope,
) : Closeable {

    private var job: Job? = null

    /** 立即定位到合着的姿态，不播放。 */
    fun showClosed() {
        job?.cancel()
        controller.setTime(CLOSED_POSE)
        controller.pause()
    }

    fun open(onDone: () -> Unit) = playSegment(OPEN_START, OPEN_END, onDone)

    /** 合上 → 在完全合上的瞬间执行 [onSwap] → 重新翻开 → [onDone]。 */
    fun closeThenOpen(onSwap: () -> Unit, onDone: () -> Unit) {
        playSegment(CLOSE_START, CLOSE_END) {
            onSwap()
            playSegment(OPEN_START, OPEN_END, onDone)
        }
    }

    private fun playSegment(from: Float, to: Float, onDone: () -> Unit) {
        job?.cancel()
        controller.setTime(from)
        controller.resume()

        // 宽限上限取区间时长的 2 倍，最少 500ms。超时即强制收尾，
        // 避免状态机永久卡在动画中而彻底失去响应。
        val budgetMs = (((to - from) * 2f) * 1000f).toLong().coerceAtLeast(MIN_BUDGET_MS)

        job = scope.launch {
            val reached = withTimeoutOrNull(budgetMs) {
                while (controller.getTime() < to) delay(POLL_INTERVAL_MS)
                true
            }
            if (reached == null) {
                Log.w(TAG, "segment $from -> $to timed out at ${controller.getTime()}, forcing completion")
            }
            controller.pause()
            onDone()
        }
    }

    override fun close() {
        job?.cancel()
        job = null
        controller.close()
    }

    private companion object {
        const val TAG = "BookAnimator"
        const val FPS = 120f

        const val CLOSED_POSE = 5f / FPS      // 0.042s
        const val OPEN_START = 100f / FPS     // 0.833s
        const val OPEN_END = 200f / FPS       // 1.667s
        const val CLOSE_START = 300f / FPS    // 2.500s
        const val CLOSE_END = 400f / FPS      // 3.333s

        const val POLL_INTERVAL_MS = 16L
        const val MIN_BUDGET_MS = 500L
    }
}
```

- [ ] **Step 2: 确认协程依赖可用**

Run: `grep -rn "kotlinx.coroutines" app/build.gradle.kts gradle/libs.versions.toml`

若无显式声明，说明协程是经 Spatial SDK 传递依赖引入的（脚手架的 `HomeVolume` 用了 suspend 的 `Entity.loadSuspend`，Compose 也依赖协程）。下一步的编译会给出确切答案。

- [ ] **Step 3: 编译确认**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`。

若报 `Unresolved reference: kotlinx`，在 `gradle/libs.versions.toml` 的 `[versions]` 加 `coroutines = "1.8.1"`，`[libraries]` 加
`kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }`，
并在 `app/build.gradle.kts` 的 `dependencies` 中加 `implementation(libs.kotlinx.coroutines.android)`，然后重新编译。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/tech/illusion/bookofanswers/content/BookAnimator.kt
git add gradle/libs.versions.toml app/build.gradle.kts 2>/dev/null || true
git commit -m "feat: add segment-based book animation controller

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: BookScene — 3D 装配

**Files:**
- Create: `app/src/main/java/tech/illusion/bookofanswers/content/BookScene.kt`

**Interfaces:**
- Consumes: `BookAnimator`（Task 6）
- Produces:
  - `class BookScene(val entity: Entity, val animator: BookAnimator?) : Closeable`
  - `suspend fun loadBookScene(scope: CoroutineScope): BookScene?`（加载失败返回 `null`）

**说明**：`animator` 可为 `null` —— 对应设计文档第 5 节的「无动画模式」降级。模型的原生尺寸实测为 0.03 × 0.21 × 0.29 m，已是真实书本大小，**不缩放**。

- [ ] **Step 1: 写实现**

Create `app/src/main/java/tech/illusion/bookofanswers/content/BookScene.kt`:

```kotlin
package tech.illusion.bookofanswers.content

import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.CoroutineScope
import java.io.Closeable

/**
 * 加载好的书本场景：实体本身，以及（可选的）内置动画控制器。
 *
 * [animator] 为 null 表示模型没有可用的内置动画，此时上层走「无动画模式」降级：
 * 书静置，触碰直接换答案。
 */
class BookScene(
    val entity: Entity,
    val animator: BookAnimator?,
) : Closeable {

    override fun close() {
        animator?.close()
        entity.stopAllAnimations()
    }
}

private const val TAG = "BookScene"
private const val BOOK_ASSET = "asset://book.usdz"

/**
 * 书本在 volume 内的落位与朝向。
 *
 * 这两个值是**设备定标出来的**，不是算出来的 —— 验证切片显示模型默认朝向是书脊
 * 侧对观察者。若在设备上看着不对，调这里，见 Task 9。
 */
private val BOOK_POSITION = Vector3(0f, -0.1f, 0f)
private val BOOK_ORIENTATION = EulerAngles(-60f, 180f, 0f)

suspend fun loadBookScene(scope: CoroutineScope): BookScene? {
    val entity = try {
        Entity.loadSuspend(uriString = BOOK_ASSET)
    } catch (t: Throwable) {
        Log.e(TAG, "failed to load $BOOK_ASSET", t)
        return null
    }

    entity.components[TransformComponent::class.java]?.apply {
        setPosition(BOOK_POSITION)
        setEulerAngles(BOOK_ORIENTATION)
    }

    // 碰撞体用包围盒而非 mesh：文档明确 mesh collider 开销高得多，
    // 而书本外形本就接近盒子，精度差异用户不可感知。
    val bounds = entity.getVisualBounds(entity, recursive = true, enabledOnly = false)
    entity.components.set(
        CollisionComponent(
            collisionShape = listOf(ShapeResource.createBox(bounds.size)),
            physicsMaterial = PhysicsMaterialResource(),
        )
    )
    entity.components.set(InteractableComponent())

    val meshEntity = entity.findSkinnedMeshEntity().firstOrNull()
    val resource = meshEntity?.getAnimationResources()?.firstOrNull()
    val animator = if (meshEntity != null && resource != null) {
        BookAnimator(meshEntity.playAnimation(resource), scope)
    } else {
        null
    }

    if (animator == null) {
        Log.w(TAG, "no animation resource on book model — falling back to still mode")
    } else {
        animator.showClosed()
    }

    Log.i(TAG, "book loaded, bounds=${bounds.size}, animated=${animator != null}")
    return BookScene(entity, animator)
}
```

- [ ] **Step 2: 编译确认**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/tech/illusion/bookofanswers/content/BookScene.kt
git commit -m "feat: add book scene assembly with collision and interaction

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: AnswerPanel — SpatialUI 面板

**Files:**
- Create: `app/src/main/java/tech/illusion/bookofanswers/content/AnswerPanel.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `sealed interface PanelContent { data object Prompt; data class AnswerText(val text: String) }`
  - `@Composable fun AnswerPanel(content: PanelContent)`

- [ ] **Step 1: 写实现**

Create `app/src/main/java/tech/illusion/bookofanswers/content/AnswerPanel.kt`:

```kotlin
package tech.illusion.bookofanswers.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

/** 面板要显示什么。面板本身只有一个，内容随 [BookPhase] 切换。 */
sealed interface PanelContent {
    /** 书还没翻开过时的引导。 */
    data object Prompt : PanelContent

    /** 当前抽到的答案。 */
    data class AnswerText(val text: String) : PanelContent
}

/**
 * 浮在书页上方的答案面板。
 *
 * 文案用词是定过的，改动前先看设计文档 4.4.1：用「触碰」不用「点击」，不用「揭晓」。
 */
@Composable
fun AnswerPanel(content: PanelContent) {
    Column(
        modifier = Modifier
            .size(PANEL_WIDTH, PANEL_HEIGHT)
            .clip(RoundedCornerShape(CORNER_RADIUS))
            .backgroundMaterial(true, Material.Regular)
            .padding(PANEL_PADDING),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (content) {
            PanelContent.Prompt -> {
                Text(
                    text = "心中默念你的问题",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "然后触碰这本书",
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = SUBTITLE_GAP)
                        .alpha(SUBTITLE_ALPHA),
                )
            }

            is PanelContent.AnswerText -> {
                Text(
                    text = content.text,
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// 面板宽度按语料实测的最长 19 字定，避免答案换行。
private val PANEL_WIDTH = 760.dp
private val PANEL_HEIGHT = 220.dp
private val PANEL_PADDING = 32.dp
private val CORNER_RADIUS = 48.dp
private val SUBTITLE_GAP = 12.dp
private const val SUBTITLE_ALPHA = 0.75f
```

- [ ] **Step 2: 编译确认**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

若报 `labelSecondary` 或 `bodyLarge` 不存在，去
`~/Library/pico/sdk/6.0/agent-vault/spatial/api-reference/` grep `colorScheme` / `typography` 的实际角色名，
换成真实存在的角色 —— **不要改用硬编码颜色或字号**。

- [ ] **Step 3: 确认没有 Material 泄漏**

```bash
grep -rn "androidx.compose.material\|MaterialTheme" app/src/main/java/ || echo "clean"
```

Expected: `clean`

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/tech/illusion/bookofanswers/content/AnswerPanel.kt
git commit -m "feat: add answer panel with prompt and answer states

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: HomeVolume 组装、接线与设备验证

**Files:**
- Modify: `app/src/main/java/tech/illusion/bookofanswers/content/HomeVolume.kt`（当前是标了 `⚠️ THROWAWAY SPIKE` 的验证代码，**整个替换**）

**Interfaces:**
- Consumes: `AnswerSource`（Task 4）、`BookState` / `BookPhase`（Task 5）、`loadBookScene` / `BookScene`（Task 7）、`AnswerPanel` / `PanelContent`（Task 8）
- Produces: 可运行的完整交互

- [ ] **Step 1: 整体替换 HomeVolume.kt**

```kotlin
package tech.illusion.bookofanswers.content

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import tech.illusion.bookofanswers.data.AnswerSource
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture

private const val TAG = "HomeVolume"

/** 答案面板相对书本的落位，设备定标值，见 Task 9 Step 6。 */
private val PANEL_OFFSET = Vector3(0f, 0.24f, 0.12f)

@Composable
fun HomeVolume() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { AnswerSource.load(context) }

    var panelContent by remember { mutableStateOf<PanelContent>(PanelContent.Prompt) }
    var scene by remember { mutableStateOf<BookScene?>(null) }
    var bookState by remember { mutableStateOf<BookState?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            scene?.close()
            scene = null
        }
    }

    SpatialView(
        // key 必须是 scene 而非 Unit：书本是异步加载的，若用 Unit，
        // TargetEntity 会永久停留在首次组合时的 null，命中范围就错了。
        Modifier.pointerInput(scene) {
            detectSpatialTapGesture(
                context,
                scene?.entity?.let { TargetEntity.hit(it) },
            ) { tap ->
                Log.i(TAG, "tap kind=${tap.interactionKind}")
                bookState?.onTap()
            }
        },
        initial = { content, attachments ->
            val loaded = loadBookScene(scope)
            if (loaded == null) {
                panelContent = PanelContent.AnswerText("书没能翻开")
                Log.e(TAG, "book scene unavailable")
            } else {
                content.addEntity(loaded.entity)
                scene = loaded

                val animator = loaded.animator
                bookState = BookState(
                    openBook = { onDone ->
                        if (animator != null) animator.open(onDone) else onDone()
                    },
                    closeThenOpen = { onSwap, onDone ->
                        if (animator != null) {
                            animator.closeThenOpen(onSwap, onDone)
                        } else {
                            onSwap(); onDone()
                        }
                    },
                    drawAnswer = {
                        panelContent = PanelContent.AnswerText(repository.next().text)
                    },
                )
            }

            attachments.entity(id = ANSWER_PANEL_ID)?.apply {
                components[TransformComponent::class.java]?.setPosition(PANEL_OFFSET)
                content.addEntity(this)
            }
        },
        attachments = {
            AttachmentPanel(id = ANSWER_PANEL_ID) {
                AnswerPanel(panelContent)
            }
        },
    )
}

private const val ANSWER_PANEL_ID = "answer_panel"
```

- [ ] **Step 2: 编译**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

若 `SpatialView` 的 `Modifier` 参数位置报错，对照脚手架原始写法与
`~/Library/pico/sdk/6.0/agent-vault/spatial/documentation/spatial-sdk_interaction_implement-basic-interactions-for-3d-objects.md`
中 `SpatialView(Modifier.pointerInput(Unit) { ... }) { content, attachments -> }` 的形式调整。

- [ ] **Step 3: 跑全部单测**

```bash
./gradlew testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，Task 2/3/5 的全部测试通过。

- [ ] **Step 4: 确认模拟器在线，安装启动**

```bash
adb devices
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
pico-cli shell "logcat -c"
pico-cli app launch tech.illusion.bookofanswers --activity .platform.LaunchActivity
```

若 `adb devices` 无设备：`pico-cli emulator start --watch --watch-interval 5 --wait-timeout 300 -y`

- [ ] **Step 5: 读日志确认装载正常**

```bash
pico-cli shell "logcat -d -v brief -s AnswerSource:V BookScene:V BookAnimator:V HomeVolume:V"
```

Expected 至少包含：
- `AnswerSource: loaded 1094 answers`（若是 3，说明 assets 没打进去，回 Task 2）
- `BookScene: book loaded, bounds=..., animated=true`（若 `animated=false`，动画降级被触发，需排查）

- [ ] **Step 6: 截图，定标朝向与落位**

```bash
pico-cli capture screenshot --device emulator-5554 --out ./artifacts/step6-initial.png
```

看图确认三件事，任何一条不满足就调参数重来：

1. **书是否正面朝向观察者**（能看到封面，而不是书脊或封底）。不对就调 `BookScene.kt` 的 `BOOK_ORIENTATION`，主要调 yaw（第二个分量）。
2. **书是否合着**。应当是合着的，因为 `showClosed()` 定位在 frame 5。
3. **面板文字是否正常显示中文**，不是方块或空白。若是方块，说明 PicoTheme 默认字体不覆盖中文 —— 记录下来并向用户报告，不要自行更换字体方案。

每次调参后重复：`./gradlew assembleDebug && pico-cli app install ... && pico-cli app launch ...` 再截图。

- [ ] **Step 7: 验证触碰全链路**

模拟器无法自动化触发空间点击。在模拟器窗口中用射线手动点一下书本，然后：

```bash
pico-cli shell "logcat -d -v brief -s HomeVolume:V"
pico-cli capture screenshot --device emulator-5554 --out ./artifacts/step7-revealed.png
```

Expected:
- 日志出现 `tap kind=RayBasedPinch`（或 `Pointer`）
- 截图中书**已翻开**，面板显示的是一条中文答案，不再是提示文案

再点一次，确认书合上又翻开、答案换成了另一条。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/tech/illusion/bookofanswers/content/HomeVolume.kt \
        app/src/main/java/tech/illusion/bookofanswers/content/BookScene.kt
git commit -m "feat: wire up book interaction end to end

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 9: 更新 CLAUDE.md**

把 `CLAUDE.md` 中 onboarding 段的「Key files」表格与「Natural next steps」更新为当前实现（新增的 `data/` 与 `content/` 各文件及其职责），并把 `box.usdz` 相关描述换成 `book.usdz`。

```bash
git add CLAUDE.md
git commit -m "docs: update project notes for book of answers implementation

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: 真机验收

**前置**：需要一台连接好的 PICO 真机。若暂无设备，本 Task 挂起，并在向用户汇报时明确写明「手指 Poke 未验证」。

- [ ] **Step 1: 确认真机在线**

```bash
pico-cli device list --format json
```

- [ ] **Step 2: 安装并启动**

```bash
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device <真机ID>
pico-cli app launch tech.illusion.bookofanswers --activity .platform.LaunchActivity --device <真机ID>
```

- [ ] **Step 3: 用手指戳书，确认 Poke 生效**

```bash
pico-cli shell "logcat -d -v brief -s HomeVolume:V" --device <真机ID>
```

Expected: 出现 `tap kind=Poke`。这是全流程中唯一无法在模拟器验证的一环。

- [ ] **Step 4: 手感确认并记录**

请用户实际体验，重点确认三项，把结论记入 `CLAUDE.md`：

1. 开合动画速率是否合适（偏慢/偏快可用 `controller.setSpeed()` 调，见 `BookAnimator`）
2. 书本大小与距离是否舒适
3. 答案面板高度是否挡住书页

---

## 完成标准

- `./gradlew testDebugUnitTest` 全绿（Task 2/3/5 共 18 个测试）
- `./gradlew assembleDebug` 成功
- 模拟器上：书正面朝向、初始合着、中文正常显示
- 模拟器上：射线点击 → 书翻开 → 出现中文答案；再点 → 合上重开 → 换一条答案
- 全项目无 Material/Material3
- 真机上手指 Poke 生效（Task 10；未做则如实标注 pending）
