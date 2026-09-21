#!/bin/bash
SERIAL="R52T408F3KV"
URL="http://192.169.1.194:11434"
MODELS=("qwen-opti:latest" "qwen2.5-coder:7b" "gemma-opti:latest" "fastmodel:latest" "bestmodel:latest")

echo "Starting model evaluation suite..." > test_results.log
for model in "${MODELS[@]}"; do
    echo "Running tests for $model..."
    echo "--- $model ---" >> test_results.log
    REMOTE_URL=$URL REMOTE_MODEL=$model ./run_e2e_remote_llm.sh $SERIAL >> test_results.log 2>&1
    echo "Finished $model"
done
echo "All tests finished."
