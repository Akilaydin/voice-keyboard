package com.tyraen.voicekeyboard.feature.transcription

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatCompletionsSpeechClientTest {

    @Test fun `records use the right input audio format`() {
        assertEquals("ogg", ChatCompletionsSpeechClient.audioFormat(File("speech.ogg")))
        assertEquals("mp4", ChatCompletionsSpeechClient.audioFormat(File("speech.m4a")))
        assertEquals("wav", ChatCompletionsSpeechClient.audioFormat(File("speech.wav")))
        assertEquals("mp3", ChatCompletionsSpeechClient.audioFormat(File("speech.mp3")))
    }

    @Test fun `chat response returns text and rejects a missing transcript`() {
        assertEquals("Hello", ChatCompletionsSpeechClient.responseText("""{"choices":[{"message":{"content":" Hello "}}]}"""))
        assertEquals("Hi there", ChatCompletionsSpeechClient.responseText("""{"choices":[{"message":{"content":[{"type":"text","text":"Hi "},{"type":"text","text":"there"}]}}]}"""))
        assertTrue(runCatching { ChatCompletionsSpeechClient.responseText("""{"choices":[{"message":{"content":null}}]}""") }.isFailure)
    }

    @Test fun `sends chat audio and reads transcript without a network request`() {
        val audio = File.createTempFile("dictation", ".ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var sent: JSONObject? = null
        var auth = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            auth = request.header("Authorization").orEmpty()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            sent = JSONObject(buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"Привет"}}]}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        try {
            val config = TranscriptionConfig("test-key", "https://example.com/v1/chat/completions", "audio-model", "ru", "Preserve names")
            val result = runBlocking { ChatCompletionsSpeechClient(http).transcribe(audio, config) }
            assertEquals("Привет", result.getOrThrow())
            assertEquals("Bearer test-key", auth)
            assertEquals("audio-model", sent!!.getString("model"))
            assertFalse(sent!!.getBoolean("stream"))
            val messages = sent!!.getJSONArray("messages")
            val instruction = messages.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
            assertTrue(instruction.contains("Language: ru"))
            assertTrue(instruction.contains("Preserve names"))
            val input = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getJSONObject("input_audio")
            assertEquals("ogg", input.getString("format"))
            assertEquals("AQID", input.getString("data"))
        } finally {
            audio.delete()
        }
    }
}
