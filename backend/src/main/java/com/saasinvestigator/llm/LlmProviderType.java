package com.saasinvestigator.llm;

/**
 * The LLM providers this application can call.
 *
 * <p>Two are implemented. Both are server-side only: a key never reaches the frontend, and the
 * frontend never talks to Anthropic or OpenAI directly.
 *
 * <p>Adding a third means adding a constant here and an {@link LlmProvider} implementation - see the
 * {@code add-llm-provider} skill for the full checklist, including the Account Settings UI and the
 * provider-resolution path that both need updating alongside it.
 */
public enum LlmProviderType {

    /** Anthropic's Messages API. Also the system-wide default when no personal preference is set. */
    ANTHROPIC,

    /** OpenAI's Responses API. */
    OPENAI
}
