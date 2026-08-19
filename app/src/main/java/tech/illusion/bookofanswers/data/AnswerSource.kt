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
