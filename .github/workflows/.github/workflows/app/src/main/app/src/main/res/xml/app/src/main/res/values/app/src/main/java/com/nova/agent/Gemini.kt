package com.nova.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    /** Calls Gemini generateContent and returns the model's JSON object reply. */
    fun plan(key: String, model: String, system: String, prompt: String): JSONObject {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 45000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = (if (code in 200..299) c.inputStream else c.errorStream)
                ?: throw RuntimeException("HTTP $code (no body)")
            val txt = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw RuntimeException("HTTP $code: " + txt.take(200))
            val out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            val clean = out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            return JSONObject(clean)
        } finally {
            c.disconnect()
        }
    }
}
