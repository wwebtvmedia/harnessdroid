package com.tree4five.gguf;
import com.tree4five.gguf.ILLMCallback;
interface ILLMService {
    oneway void generateTextStream(String prompt, ILLMCallback callback);
    oneway void stopGeneration();
    String getVersion();
}
