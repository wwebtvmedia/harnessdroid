package com.ai.harnessdroid;
import com.ai.harnessdroid.IToolCallback;

interface IToolProviderService {
    // Registers a callback for receiving MCP messages from the server
    oneway void registerCallback(IToolCallback callback);
    
    // Unregisters the callback
    oneway void unregisterCallback(IToolCallback callback);
    
    // Sends an MCP JSON-RPC message to the server
    oneway void sendMcpMessage(String jsonRpcMessage);
}
