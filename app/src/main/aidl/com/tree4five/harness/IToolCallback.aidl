package com.tree4five.harness;

interface IToolCallback {
    // Called when the tool successfully completes its task
    oneway void onToolSuccess(String jsonResult);
    
    // Called when the tool fails or hits an error
    oneway void onToolError(String errorMessage);
}
