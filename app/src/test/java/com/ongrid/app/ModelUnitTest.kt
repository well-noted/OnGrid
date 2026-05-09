package com.ongrid.app

import com.ongrid.app.data.model.ChatMessage
import com.ongrid.app.data.model.McpInputSchema
import com.ongrid.app.data.model.McpServer
import com.ongrid.app.data.model.McpTool
import com.ongrid.app.data.model.MessageRole
import com.ongrid.app.data.model.OllamaModel
import com.ongrid.app.data.model.OllamaServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUnitTest {

    @Test
    fun ollamaServer_baseUrl_formattedCorrectly() {
        val server = OllamaServer(host = "192.168.1.10", port = 11434)
        assertEquals("http://192.168.1.10:11434", server.baseUrl)
        assertEquals("192.168.1.10:11434", server.displayName)
    }

    @Test
    fun ollamaModel_displayName_stripsTag() {
        val model = OllamaModel(name = "llama3:8b", size = 4_700_000_000L)
        assertEquals("llama3", model.displayName)
        assertEquals("8b", model.tag)
        assertTrue(model.sizeFormatted.contains("GB"))
    }

    @Test
    fun ollamaModel_noTag_returnsLatest() {
        val model = OllamaModel(name = "mistral")
        assertEquals("mistral", model.displayName)
        assertEquals("latest", model.tag)
    }

    @Test
    fun chatMessage_defaultRoleIsUser() {
        val msg = ChatMessage(role = MessageRole.USER, content = "Hello")
        assertEquals(MessageRole.USER, msg.role)
        assertFalse(msg.isStreaming)
        assertNotNull(msg.id)
    }

    @Test
    fun mcpTool_convertsToOllamaTool() {
        val tool = McpTool(
            name = "get_weather",
            description = "Get current weather",
            inputSchema = McpInputSchema(
                type = "object",
                properties = mapOf("location" to mapOf("type" to "string")),
                required = listOf("location")
            )
        )
        val ollamaTool = tool.toOllamaTool()
        assertEquals("get_weather", ollamaTool.function.name)
        assertEquals("Get current weather", ollamaTool.function.description)
        assertEquals("function", ollamaTool.type)
    }

    @Test
    fun mcpServer_displayUrl_stripsHttpPrefix() {
        val server = McpServer(
            name = "Test",
            baseUrl = "http://192.168.1.50:3000",
            enabled = true
        )
        assertEquals("192.168.1.50:3000", server.displayUrl)
        assertTrue(server.enabled)
    }

    @Test
    fun ollamaModel_sizeFormatted_mb() {
        val model = OllamaModel(name = "tiny", size = 512_000_000L)
        assertTrue(model.sizeFormatted.contains("MB"))
    }

    @Test
    fun ollamaModel_sizeFormatted_kb() {
        val model = OllamaModel(name = "tiny", size = 512_000L)
        assertTrue(model.sizeFormatted.contains("KB"))
    }

    @Test
    fun ollamaModel_sizeFormatted_bytes() {
        val model = OllamaModel(name = "tiny", size = 512L)
        assertTrue(model.sizeFormatted.contains("B"))
    }
}
