package com.ai.harnessdroid;

interface IToolCallback {
    // Called when the server sends an MCP JSON-RPC message to the client
    oneway void onMcpMessage(String jsonRpcMessage);
}
