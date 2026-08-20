package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsStreamDecoderTest {

    private fun sse(data: String) = SseEvent(id = null, event = null, data = data)

    /** 构造一个流式 chunk 的 SSE data（可选带 usage 字段）。 */
    private fun chunk(
        content: String = "",
        finishReason: String? = null,
        usage: String? = null,
    ): String = json.encodeToString(
        buildJsonObject {
            put("id", "chatcmpl-test")
            put("model", "test-model")
            put("choices", buildJsonArray {
                add(
                    buildJsonObject {
                        put("delta", buildJsonObject {
                            put("content", content)
                        })
                        finishReason?.let { put("finish_reason", it) }
                    }
                )
            })
            usage?.let { put("usage", json.parseToJsonElement(it).jsonObject) }
        }
    )

    @Test
    fun `partial usage mid stream should not be emitted until finish`() {
        val decoder = ChatCompletionsStreamDecoder()

        // 中途 chunk 只带 completion_tokens（部分中转会这样发送）：不应立即产生 Usage，避免输入显示为 0
        val mid = decoder.accept(sse(chunk(content = "hi", usage = """{"completion_tokens":5}""")))
        assertTrue(mid.chunks.none { it is StreamChunk.Usage })

        // 最后 chunk 带完整 usage：仍缓冲，不立即发出
        val last = decoder.accept(sse(chunk(
            content = "",
            finishReason = "stop",
            usage = """{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}""",
        )))
        assertTrue(last.chunks.none { it is StreamChunk.Usage })

        // [DONE] 触发 finish()：统一提交最后一条完整 usage
        val done = decoder.accept(sse("[DONE]"))
        val usage = done.chunks.filterIsInstance<StreamChunk.Usage>().lastOrNull()?.usage
        assertEquals(
            TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150),
            usage,
        )
        assertTrue(done.completed)
    }

    @Test
    fun `usage should fall back to last partial usage when no complete usage received`() {
        val decoder = ChatCompletionsStreamDecoder()

        decoder.accept(sse(chunk(content = "hi")))
        // 全程只有不完整的 usage（无输入 Token）：不应丢弃，退回提交最后一条，至少保留输出
        decoder.accept(sse(chunk(content = "", finishReason = "stop", usage = """{"completion_tokens":5}""")))

        val done = decoder.accept(sse("[DONE]"))
        val usage = done.chunks.filterIsInstance<StreamChunk.Usage>().lastOrNull()?.usage
        assertEquals(
            TokenUsage(promptTokens = 0, completionTokens = 5),
            usage,
        )
    }

    @Test
    fun `later complete usage with missing fields should keep earlier non zero fields`() {
        val decoder = ChatCompletionsStreamDecoder()

        // 第一条完整 usage：含缓存命中
        decoder.accept(sse(chunk(content = "hi", usage = """{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,"prompt_tokens_details":{"cached_tokens":30}}""")))
        // 第二条修正 usage：缺失 cached_tokens（解析为 0），merge 应保留第一条的 30
        decoder.accept(sse(chunk(content = "", finishReason = "stop", usage = """{"prompt_tokens":100,"completion_tokens":45,"total_tokens":145}""")))

        val done = decoder.accept(sse("[DONE]"))
        val usage = done.chunks.filterIsInstance<StreamChunk.Usage>().lastOrNull()?.usage
        assertEquals(
            TokenUsage(promptTokens = 100, completionTokens = 45, cachedTokens = 30, totalTokens = 145),
            usage,
        )
    }

    @Test
    fun `complete usage should be buffered and submitted on close`() {
        val decoder = ChatCompletionsStreamDecoder()

        decoder.accept(sse(chunk(content = "hi", usage = """{"prompt_tokens":50,"completion_tokens":10,"total_tokens":60}""")))
        // 流异常结束（未收到 [DONE]）时 onClosed 也会提交缓冲的 usage
        val closed = decoder.onClosed()
        val usage = closed.filterIsInstance<StreamChunk.Usage>().lastOrNull()?.usage
        assertEquals(
            TokenUsage(promptTokens = 50, completionTokens = 10, totalTokens = 60),
            usage,
        )
    }
}
