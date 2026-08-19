package tech.illusion.bookofanswers.data

/**
 * 一条答案。
 *
 * 文本即身份 —— 语料在打包阶段已按文本去重，因此不需要额外的 id。
 */
data class Answer(val text: String)
