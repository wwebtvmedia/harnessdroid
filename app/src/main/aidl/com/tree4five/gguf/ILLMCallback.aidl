package com.tree4five.gguf;
interface ILLMCallback {
    oneway void onTokenReceived(String token);
    oneway void onGenerationComplete(String fullText);
}
