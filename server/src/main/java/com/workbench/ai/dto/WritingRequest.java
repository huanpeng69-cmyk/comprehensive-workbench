package com.workbench.ai.dto;

/** Request for a single AI writing operation. */
public record WritingRequest(String skillName, String skillPrompt, String content, String additionalRequest) {
}