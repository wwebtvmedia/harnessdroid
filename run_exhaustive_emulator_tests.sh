#!/bin/bash
set -e

SERIAL="R52T408F3KV"
URL="http://192.168.1.194:11434"
MODELS=("qwen-opti:latest" "qwen2.5-coder:7b" "gemma-opti:latest" "gemma4:12b" "vision:latest" "codeur:latest" "qwen2.5-coder:14b" "fastmodel:latest" "bestmodel:latest")

echo "========================================="
echo "1. Running LOCAL LLM E2E Tests (Tree4Five via ../LLMProvider)"
echo "========================================="
./run_e2e_local_llm.sh $SERIAL || echo "Local tests failed, continuing..."

echo "========================================="
echo "2. Running REMOTE LLM E2E Tests (Ollama)"
echo "========================================="
for model in "${MODELS[@]}"; do
    echo "--- Testing with Remote Model: $model ---"
    REMOTE_URL=$URL REMOTE_MODEL=$model ./run_e2e_remote_llm.sh $SERIAL || echo "Tests for $model failed, continuing..."
done

echo "Exhaustive test suite complete."
