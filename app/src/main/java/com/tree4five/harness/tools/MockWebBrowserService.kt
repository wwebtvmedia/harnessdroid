package com.tree4five.harness.tools

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.tree4five.harness.IToolCallback
import com.tree4five.harness.IToolProviderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * A mock tool provider for testing. 
 * Responds to the Harness Tool discovery intent.
 */
class MockWebBrowserService : Service() {

    private val binder = object : IToolProviderService.Stub() {
        override fun getAvailableTools(): String {
            val tool = JSONObject().apply {
                put("name", "web_search")
                put("description", "Search the web for information.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "The search query")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            }
            return JSONArray().put(tool).toString(2)
        }

        override fun executeTool(toolName: String, jsonArguments: String, callback: IToolCallback) {
            if (toolName != "web_search") {
                callback.onToolError("Unknown tool: $toolName")
                return
            }

            // Execute asynchronously
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val query = JSONObject(jsonArguments).getString("query")
                    Log.i("WebBrowserTool", "Searching for: $query")
                    
                    // Simulate network delay
                    delay(1500)
                    
                    val result = JSONObject().apply {
                        put("query", query)
                        put("summary", "This is a mock search result for '$query'. The DeepSeek harness works flawlessly on Android.")
                        put("url", "https://mock-search.com/result")
                    }
                    callback.onToolSuccess(result.toString())
                } catch (e: Exception) {
                    callback.onToolError("Error executing web_search: ${e.message}")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
