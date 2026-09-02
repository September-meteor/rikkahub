package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ManagedTextField
import me.rerere.rikkahub.ui.components.ui.rememberSyncedTextFieldState
import me.rerere.rikkahub.ui.theme.JetbrainsMono

private val jsonLenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

@Composable
fun CustomHeaders(headers: List<CustomHeader>, onUpdate: (List<CustomHeader>) -> Unit) {
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_headers))
        Spacer(Modifier.height(8.dp))

        headers.forEachIndexed { index, header ->
            // TextFieldState：输入以 state 为准（IME/光标稳定）；仅结构变化（增删行）时按模型对齐槽位
            val headerNameState = rememberTextFieldState(initialText = header.name)
            val headerValueState = rememberTextFieldState(initialText = header.value)
            LaunchedEffect(headers.size, index) {
                if (headerNameState.text.toString() != header.name) {
                    headerNameState.setTextAndPlaceCursorAtEnd(header.name)
                }
                if (headerValueState.text.toString() != header.value) {
                    headerValueState.setTextAndPlaceCursorAtEnd(header.value)
                }
            }

            CardGroup {
                item(
                    supportingContent = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ManagedTextField(
                                state = headerNameState,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.assistant_page_header_name)) },
                                persistDebounceMs = 300,
                                onPersist = { name ->
                                    val updatedHeaders = headers.toMutableList()
                                    updatedHeaders[index] = updatedHeaders[index].copy(name = name)
                                    onUpdate(updatedHeaders)
                                },
                                normalizeOnBlur = { it.trim() },
                            )
                            ManagedTextField(
                                state = headerValueState,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.assistant_page_header_value)) },
                                persistDebounceMs = 300,
                                onPersist = { value ->
                                    val updatedHeaders = headers.toMutableList()
                                    updatedHeaders[index] = updatedHeaders[index].copy(value = value)
                                    onUpdate(updatedHeaders)
                                },
                                normalizeOnBlur = { it.trim() },
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val updatedHeaders = headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            onUpdate(updatedHeaders)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_header)
                            )
                        }
                    },
                    headlineContent = {},
                )
            }
        }

        Button(
            onClick = {
                val updatedHeaders = headers.toMutableList()
                updatedHeaders.add(CustomHeader("", ""))
                onUpdate(updatedHeaders)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_header))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_header))
        }
    }
}

@Composable
fun CustomBodies(customBodies: List<CustomBody>, onUpdate: (List<CustomBody>) -> Unit) {
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_bodies))
        Spacer(Modifier.height(8.dp))

        customBodies.forEachIndexed { index, body ->
            // TextFieldState：输入以 state 为准；JSON 解析/错误提示移到失焦（validateOnBlur），
            // 输入过程不再被 pretty-print 重排打断（可正常输入配对符号）；仅结构变化时按模型对齐。
            val bodyKeyState = rememberTextFieldState(initialText = body.key)

            LaunchedEffect(customBodies.size, index) {
                val modelKey = body.key
                if (bodyKeyState.text.toString() != modelKey) {
                    bodyKeyState.setTextAndPlaceCursorAtEnd(modelKey)
                }
            }

            CardGroup {
                item(
                    supportingContent = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ManagedTextField(
                                state = bodyKeyState,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.assistant_page_body_key)) },
                                persistDebounceMs = 300,
                                onPersist = { key ->
                                    val updatedBodies = customBodies.toMutableList()
                                    updatedBodies[index] = updatedBodies[index].copy(key = key)
                                    onUpdate(updatedBodies)
                                },
                                normalizeOnBlur = { it.trim() },
                            )
                            val bodyValueState = rememberSyncedTextFieldState(
                                jsonLenient.encodeToString(JsonElement.serializer(), body.value)
                            )
                            // 结构变化（增删行）时按模型对齐槽位；输入中不回流、不重排
                            LaunchedEffect(customBodies.size, index) {
                                val modelText = jsonLenient.encodeToString(JsonElement.serializer(), body.value)
                                if (bodyValueState.text.toString() != modelText) {
                                    bodyValueState.setTextAndPlaceCursorAtEnd(modelText)
                                }
                            }
                            ManagedTextField(
                                state = bodyValueState,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.assistant_page_body_value)) },
                                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 5),
                                textStyle = LocalTextStyle.current.merge(fontFamily = JetbrainsMono),
                                persistDebounceMs = 300,
                                onPersist = { newString ->
                                    // 输入过程中静默解析：合法才写模型（不重排、不打断输入）；非法不写入
                                    runCatching { jsonLenient.parseToJsonElement(newString) }
                                    .onSuccess { newJsonValue ->
                                        val updatedBodies = customBodies.toMutableList()
                                        updatedBodies[index] =
                                        updatedBodies[index].copy(value = newJsonValue)
                                        onUpdate(updatedBodies)
                                    }
                                },
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val updatedBodies = customBodies.toMutableList()
                            updatedBodies.removeAt(index)
                            onUpdate(updatedBodies)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_body)
                            )
                        }
                    },
                    headlineContent = {},
                )
            }
        }

        Button(
            onClick = {
                val updatedBodies = customBodies.toMutableList()
                updatedBodies.add(CustomBody("", JsonPrimitive("")))
                onUpdate(updatedBodies)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_body))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_body))
        }
    }
}
