package com.hassaku.plamotranslate.data.inference

/** 対訳履歴の 1 件分。 */
data class HistoryPair(val source: String, val translation: String)

/** 用語集の 1 件分 (原語 → 訳語)。 */
data class GlossaryEntry(val source: String, val target: String)

/**
 * PLaMo 3 Translate の PFML3 プロンプト組み立て
 * (公式モデルカード pfnet/plamo-3-translate-0.8b-GGUF の PFML3 仕様準拠)。
 *
 * 基本形:
 * ```
 * <|plamo:tag|>dataset<|plamo:msg|>translation
 * [<|plamo:tag|>context<|plamo:msg|>{参考文脈}]
 * [履歴ペアごとに:
 *   <|plamo:tag|>input[<|plamo:key|>lang<|plamo:val|>{src}]<|plamo:msg|>{原文}
 *   <|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>{tgt}<|plamo:msg|>{訳文}]
 * [<|plamo:tag|>glossary (<|plamo:index|>{原語}<|plamo:item|>{訳語})*]
 * <|plamo:tag|>input[<|plamo:key|>lang<|plamo:val|>{src}]<|plamo:msg|>{原文}
 * <|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>{tgt}
 *   [<|plamo:key|>form<|plamo:val|>plain|polite]<|plamo:msg|>
 * ```
 *
 * - BOS (<|plamo:bos|>) は付けない。llama.cpp 側の llama_tokenize が
 *   add_special=true で自動付与する前提。
 * - [sourceLang] が null (自動検出) の場合、input 要素に lang 属性を付けない
 *   (モデルカードの「source_lang を省略するとモデルが判断する」に相当)。
 * - [form] は [targetLang] が "ja" の場合のみ有効。それ以外では無視する。
 * - ユーザー入力中の "<|plamo:" という文字列は PFML3 の構造を壊すため、
 *   公式チャットテンプレートと同様に無害化する ("<|plamo:" → "<\u200B|plamo:")。
 *   本来の特殊トークン列を原文に含めることはできない (モデル側の仕様)。
 */
object Pfml3 {

    const val FORM_PLAIN = "plain"
    const val FORM_POLITE = "polite"

    /** 生成時の停止トークン文字列 (モデルカード準拠)。ネイティブ側の停止判定でも使う。 */
    val STOP_TOKENS = listOf("<|plamo:tag|>", "<|plamo:bos|>")

    private fun sanitize(text: String): String =
        text.replace("<|plamo:", "<\u200B|plamo:")

    private fun langAttr(code: String): String =
        "<|plamo:key|>lang<|plamo:val|>$code"

    /**
     * @param sourceText 翻訳する原文
     * @param sourceLang 原文の言語コード (例: "en", "ja")。null なら自動検出 (lang 属性なし)
     * @param targetLang 翻訳先の言語コード (必須)
     * @param form "plain" (常体) / "polite" (敬体) / null (指定なし)。ja 宛のみ
     * @param context 参考文脈 (任意)
     * @param glossary 用語集 (任意)
     * @param history 対訳履歴。古い順に渡すこと (任意)
     */
    fun buildPrompt(
        sourceText: String,
        sourceLang: String?,
        targetLang: String,
        form: String? = null,
        context: String? = null,
        glossary: List<GlossaryEntry> = emptyList(),
        history: List<HistoryPair> = emptyList(),
    ): String {
        val srcAttr = sourceLang?.let { langAttr(it) } ?: ""
        val sb = StringBuilder()

        sb.append("<|plamo:tag|>dataset<|plamo:msg|>translation")

        if (!context.isNullOrBlank()) {
            sb.append("<|plamo:tag|>context<|plamo:msg|>")
            sb.append(sanitize(context.trim()))
        }

        for (pair in history) {
            sb.append("<|plamo:tag|>input")
            sb.append(srcAttr)
            sb.append("<|plamo:msg|>")
            sb.append(sanitize(pair.source))
            sb.append("<|plamo:tag|>output")
            sb.append(langAttr(targetLang))
            sb.append("<|plamo:msg|>")
            sb.append(sanitize(pair.translation))
        }

        if (glossary.isNotEmpty()) {
            sb.append("<|plamo:tag|>glossary")
            for (entry in glossary) {
                sb.append("<|plamo:index|>")
                sb.append(sanitize(entry.source))
                sb.append("<|plamo:item|>")
                sb.append(sanitize(entry.target))
            }
        }

        sb.append("<|plamo:tag|>input")
        sb.append(srcAttr)
        sb.append("<|plamo:msg|>")
        sb.append(sanitize(sourceText))

        sb.append("<|plamo:tag|>output")
        sb.append(langAttr(targetLang))
        if (targetLang == "ja" && (form == FORM_PLAIN || form == FORM_POLITE)) {
            sb.append("<|plamo:key|>form<|plamo:val|>")
            sb.append(form)
        }
        sb.append("<|plamo:msg|>")

        return sb.toString()
    }

    /**
     * 用語集テキストをパースする。1 行 1 件、「原語=訳語」形式。
     * 空行と "#" で始まる行は無視し、"=" を含まない行は読み飛ばす。
     */
    fun parseGlossary(text: String): List<GlossaryEntry> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0 || idx >= line.length - 1) {
                    null
                } else {
                    val src = line.substring(0, idx).trim()
                    val tgt = line.substring(idx + 1).trim()
                    if (src.isEmpty() || tgt.isEmpty()) null else GlossaryEntry(src, tgt)
                }
            }
            .toList()
    }
}
