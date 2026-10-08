#!/bin/bash
# ==============================================================================
# Exegese AI - Ollama container entrypoint
# Starts the Ollama server, waits until its API answers and pulls the chat model
# configured in OLLAMA_CHAT_MODEL (default: llama3.2, the OLLAMA_LOCAL default
# seeded by the application). Embeddings use Google Gemini, so no embedding
# model is pulled here.
# ==============================================================================

set -u

CHAT_MODEL="${OLLAMA_CHAT_MODEL:-llama3.2}"
READY_TIMEOUT_SECONDS="${OLLAMA_READY_TIMEOUT:-120}"

# Start Ollama service in background
/bin/ollama serve &
pid=$!

# Wait until the API answers ('ollama list' talks to the local server)
echo "Waiting for the Ollama server to accept requests..."
elapsed=0
until ollama list > /dev/null 2>&1; do
    if ! kill -0 "$pid" 2> /dev/null; then
        echo "Ollama server exited unexpectedly during startup." >&2
        wait "$pid"
        exit 1
    fi
    if [ "$elapsed" -ge "$READY_TIMEOUT_SECONDS" ]; then
        echo "Ollama server not ready after ${READY_TIMEOUT_SECONDS}s; skipping model pull." >&2
        break
    fi
    sleep 1
    elapsed=$((elapsed + 1))
done

if [ "$elapsed" -lt "$READY_TIMEOUT_SECONDS" ]; then
    echo "Pulling local chat model: ${CHAT_MODEL}..."
    if ollama pull "$CHAT_MODEL"; then
        echo "Model ${CHAT_MODEL} successfully initialized!"
    else
        echo "Failed to pull model ${CHAT_MODEL}; the server keeps running." >&2
    fi
fi

# Keep alive with Ollama daemon process
wait "$pid"
