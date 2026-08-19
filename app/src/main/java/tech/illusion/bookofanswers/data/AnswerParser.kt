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
