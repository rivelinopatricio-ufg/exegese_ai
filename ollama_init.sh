#!/bin/bash

# Start Ollama service in background
/bin/ollama serve &
pid=$!

# Wait for service initialization
sleep 5

echo "Pulling local embedding model: nomic-embed-text..."
ollama pull nomic-embed-text

echo "Pulling local chat model: llama3.2..."
ollama pull llama3.2

echo "Models successfully initialized!"

# Keep alive with Ollama daemon process
wait $pid
