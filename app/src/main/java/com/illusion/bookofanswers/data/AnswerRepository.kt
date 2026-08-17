package com.illusion.bookofanswers.data

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
