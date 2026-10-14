package com.workbench.ai.dto;

/** Result returned by an OpenAI-compatible chat-completions provider. */
public record WritingResponse(String content, String model) {
}