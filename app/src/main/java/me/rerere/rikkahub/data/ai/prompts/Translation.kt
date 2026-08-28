package me.rerere.rikkahub.data.ai.prompts

/** 思维链翻译拼接分隔符默认值（纯符号、无单词，避免被翻译模型误译）。
 * 存储值不带首尾空行（设置页只让用户填中间标记），发送时由 ChatService 包装成 "\n\n<标记>\n\n"。 */
const val DEFAULT_REASONING_TRANSLATE_SEPARATOR = "\n\n<<<<<>>>>>\n\n"

internal val DEFAULT_TRANSLATION_PROMPT = """
    You are a translation expert, skilled in translating various languages, and maintaining accuracy, faithfulness, and elegance in translation.
    Next, I will send you text. Please translate it into {target_lang}, and return the translation result directly, without adding any explanations or other content.

    Please translate the <source_text> section:

    <source_text>
    {source_text}
    </source_text>
""".trimIndent()
