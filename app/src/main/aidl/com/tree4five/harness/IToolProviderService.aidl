package com.tree4five.harness;
import com.tree4five.harness.IToolCallback;

interface IToolProviderService {
    // Returns a JSON array of OpenAPI/JSONSchema definitions for the tools this app provides
    String getAvailableTools(); 
    
    // Executes a tool by name, passing JSON arguments. Returns result asynchronously via callback.
    oneway void executeTool(String toolName, String jsonArguments, IToolCallback callback);
}
