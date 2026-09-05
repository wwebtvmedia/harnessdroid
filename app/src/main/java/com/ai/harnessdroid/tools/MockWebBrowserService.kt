package com.ai.harnessdroid.tools

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.ai.harnessdroid.IToolCallback
import com.ai.harnessdroid.IToolProviderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MockWebBrowserService : Service() {
    private var clientCallback: IToolCallback? = null
    
    private val binder = object : IToolProviderService.Stub() {
        override fun registerCallback(callback: IToolCallback) {
            clientCallback = callback
        }
        
        override fun unregisterCallback(callback: IToolCallback) {
            if (clientCallback?.asBinder() == callback.asBinder()) {
                clientCallback = null
            }
        }
        
        override fun sendMcpMessage(jsonRpcMessage: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val request = JSONObject(jsonRpcMessage)
                    val method = request.optString("method")
                    val id = request.optInt("id", -1)
                    
                    if (method == "tools/list") {
                        val tool = JSONObject().apply {
                            put("name", "web_search")
                            put("description", "Search the web for information.")
                            put("inputSchema", JSONObject().apply {
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
                        val result = JSONObject().apply {
                            put("tools", JSONArray().put(tool))
                        }
                        
                        val response = JSONObject().apply {
                            put("jsonrpc", "2.0")
                            if (id != -1) put("id", id)
                            put("result", result)
                        }
                        
                        clientCallback?.onMcpMessage(response.toString())
                        
                    } else if (method == "tools/call") {
                        val params = request.getJSONObject("params")
                        val toolName = params.getString("name")
                        if (toolName != "web_search") {
                            sendErrorResponse(id, -32601, "Unknown tool: $toolName")
                            return@launch
                        }
                        val arguments = params.getJSONObject("arguments")
                        val query = arguments.getString("query")
                        Log.i("WebBrowserTool", "Searching for: $query")
                        
                        delay(1500) // mock delay
                        
                        val resultObj = JSONObject().apply {
                            put("query", query)
                            put("summary", "This is a mock search result for '$query'. The DeepSeek harness works flawlessly on Android.")
                            put("url", "https://mock-search.com/result")
                        }
                        
                        val content = JSONArray().put(JSONObject().apply {
                            put("type", "text")
                            put("text", resultObj.toString())
                        })
                        val result = JSONObject().apply {
                            put("content", content)
                            put("isError", false)
                        }
                        
                        val response = JSONObject().apply {
                            put("jsonrpc", "2.0")
                            if (id != -1) put("id", id)
                            put("result", result)
                        }
                        
                        clientCallback?.onMcpMessage(response.toString())
                    }
                } catch (e: Exception) {
                    Log.e("MockWebBrowser", "Error handling MCP", e)
                }
            }
        }
    }
    
    private fun sendErrorResponse(id: Int, code: Int, message: String) {
        try {
            val response = JSONObject().apply {
                put("jsonrpc", "2.0")
                if (id != -1) put("id", id)
                put("error", JSONObject().apply {
                    put("code", code)
                    put("message", message)
                })
            }
            clientCallback?.onMcpMessage(response.toString())
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
