package com.saasinvestigator.llm;

import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.Change;
import com.saasinvestigator.report.ChangeCategory;
import com.saasinvestigator.report.Confidence;
import com.saasinvestigator.report.RunType;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds every prompt this application sends, for both providers.
 *
 * <p>One class, not one per provider, and that is the point. The prompt <em>is</em> the comparison logic here:
 * there is no diffing code anywhere in this codebase, so what the model is asked and what it is shown is the entire
 * algorithm. Two copies of it would mean the Anthropic answer and the OpenAI answer differed for reasons nobody
 * intended, and the "run the same source through both providers" comparison the app invites would be measuring the
 * prompts rather than the models.
 *
 * <p>Four prompts are built here:
 *
 * <ul>
 *   <li>{@link #systemPrompt()} - the role and the output contract, identical for every run
 *   <li>{@link #runPrompt(RunContext)} - the data and the instructions for one run or compare
 *   <li>{@link #jsonCorrectionPrompt()} - the single retry when a response was not valid JSON
 *   <li>{@link #askSystemPrompt()} / {@link #askPrompt(AskContext)} - the free-text question path
 * </ul>
 *
 * <h2>Why the model is told what <em>not</em> to report</h2>
 *
 * <p>The instructions spend as much space on exclusions as on the task. That is deliberate: the failure mode of a
 * change detector is not missing a change, it is reporting forty non-changes - a rotated testimonial, a rebuilt nav
 * menu, a copyright year - until the reader stops reading the reports. Every "do not report" line below exists
 * because it is a thing two crawls of the same unchanged marketing page will genuinely differ on.
 */
@Component
public class PromptBuilder {

    /** Day granularity, UTC, matching the calendar-picker granularity of a custom-range compare. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy").withZone(ZoneOffset.UTC);

    /** Minute granularity for run timestamps, where "which run" matters more than "which day". */
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /**
     * Smallest block of source text worth sending. Below this a block is all truncation marker and no content, so
     * an over-subscribed prompt drops whole blocks instead of shredding all of them.
     */
    static final int MIN_BLOCK_CHARS = 2_000;

    private final LlmProperties properties;

    public PromptBuilder(LlmProperties properties) {
        this.properties = properties;
    }

    /**
     * The system prompt for a run or compare: who the model is, and the exact JSON it must return.
     *
     * <p>The JSON contract is stated here rather than appended to the user message because it is invariant - it does
     * not change with depth, run type, or which sources exist - and because both providers treat a system prompt as
     * the more durable half of the instruction. The shape matches the {@code change_reports} collection, minus the
     * fields the model has no way to know.
     *
     * @return the system prompt, identical for every run
     */
    public String systemPrompt() {
        return """
                You are a change analyst for SaaS products. You are given the current and previous state of one or \
                more information sources about a single product - crawled web pages, and sometimes live MCP tools \
                you can call yourself - and you report what has changed in terms a customer of that product would \
                care about.

                Return ONLY a single JSON object, with no prose before or after it, no explanation, and no markdown \
                code fences. The object must have exactly this shape:

                {
                  "overallSummary": "string - a few sentences to a few paragraphs, depending on the requested \
                depth, describing what changed overall and why it matters. Write for someone who has not read the \
                sources.",
                  "changes": [
                    {
                      "sourceName": "string - MUST exactly match one of the source names given below",
                      "category": "one of: feature, pricing, policy, bugfix, documentation, deprecation, other",
                      "description": "string - one or more sentences describing this specific change",
                      "confidence": "one of: high, medium, low",
                      "evidenceSnippet": "string - a short verbatim quote from the source supporting this, or null \
                if the change is the absence of something that used to be there"
                    }
                  ]
                }

                Rules for the content:

                - "changes" may be an empty array. Nothing changed is a real, useful, common answer. Never invent a \
                change to avoid returning an empty list.
                - Report only substantive changes. Do NOT report: reordered navigation or menus, rotated \
                testimonials or customer logos, changed copyright years, cache-busting URL parameters, \
                reformatting that did not alter meaning, reworded headings whose meaning is unchanged, or \
                differences that are obviously a crawl artefact rather than an edit to the product.
                - Use "high" confidence only when the evidence directly states the change. Use "medium" when you are \
                inferring it from related wording. Use "low" when it is a plausible reading you would not defend. \
                Do not mark everything "high": the value of this field is entirely in it varying.
                - Quote evidence verbatim and keep it short - a sentence or two. Do not paraphrase inside \
                "evidenceSnippet"; that is the one field a reader uses to check you.
                - Attribute each change to the single source it was found in. If the same change appears in two \
                sources, report it once against the source with the clearest evidence and say so in the description.
                - If a source is listed below as unavailable, say so in "overallSummary" rather than ignoring it. A \
                silent omission reads exactly like a source with no changes.
                """;
    }

    /**
     * The per-run prompt: the product, the depth instruction, the source data, and what to do with it.
     *
     * @param context the run's provider-agnostic inputs
     * @return the user-message text for this run
     */
    public String runPrompt(RunContext context) {
        StringBuilder prompt = new StringBuilder(1_024);

        prompt.append("PRODUCT: ").append(context.productName()).append('\n');
        if (context.productDescription() != null && !context.productDescription().isBlank()) {
            prompt.append("DESCRIPTION: ").append(context.productDescription().trim()).append('\n');
        }
        prompt.append('\n').append(scopeParagraph(context)).append("\n\n");
        prompt.append("HOW THOROUGH TO BE: ").append(context.analysisDepth().promptInstruction()).append("\n\n");

        appendMcpInstructions(prompt, context);
        appendMcpHistory(prompt, context);
        appendFailures(prompt, context);
        appendSourceTexts(prompt, context);

        prompt.append("\nNow return the JSON object described in your instructions, and nothing else.");
        return prompt.toString();
    }

    /**
     * The corrective retry, sent once when a response could not be parsed as the required JSON.
     *
     * <p>One retry, not a loop. A model that has produced unparseable output once usually produces valid output when
     * told so plainly; a model that fails twice is failing for a reason a third attempt will not fix, and a retry
     * loop on an expensive call is a way to turn one bad run into a large bill.
     *
     * @return the correction message
     */
    public String jsonCorrectionPrompt() {
        return """
                Your previous response could not be parsed as JSON. Return the same analysis again as a single \
                valid JSON object matching the shape you were given: an "overallSummary" string and a "changes" \
                array. No prose, no explanation, no markdown code fences, nothing before or after the object. If \
                you found no changes, return an empty "changes" array.
                """;
    }

    /**
     * The system prompt for an ad-hoc question.
     *
     * @return the ask system prompt
     */
    public String askSystemPrompt() {
        return """
                You are answering questions about one SaaS product, using only the stored material provided below: \
                the most recently captured text from each of its sources, and the summary of its most recent \
                change report.

                Answer in prose, directly and briefly. Do not return JSON.

                Two rules matter more than the rest:

                - Answer only from the material provided. If it does not contain the answer, say so plainly and say \
                what would - for example that the product has not been run since the date in question. Do not fill \
                a gap with what is generally true of products like this one; a confident wrong answer about \
                someone's pricing page is worse than no answer.
                - The material is a stored capture with a date on it, not a live look at the product. When the \
                answer depends on how current it is, say when it was captured.
                """;
    }

    /**
     * The per-question prompt: the stored context, then the question.
     *
     * @param context the question and the stored data to answer from
     * @return the user-message text for this ask
     */
    public String askPrompt(AskContext context) {
        StringBuilder prompt = new StringBuilder(1_024);
        prompt.append("PRODUCT: ").append(context.productName()).append("\n\n");

        if (context.hasNoContext()) {
            // Said explicitly rather than left as an absence, so the answer is "this has never been run" rather
            // than an answer improvised from the product's name.
            prompt.append("There is no stored material for this product yet - it has never been run, so no "
                    + "source text and no change report exist.\n\n");
        } else {
            if (context.latestReportSummary() != null) {
                prompt.append("MOST RECENT CHANGE REPORT");
                if (context.latestReportAt() != null) {
                    prompt.append(" (produced ").append(MINUTE.format(context.latestReportAt())).append(')');
                }
                prompt.append(":\n").append(context.latestReportSummary().trim()).append("\n\n");
            }
            appendAskExcerpts(prompt, context);
        }

        prompt.append("QUESTION: ").append(context.question().trim()).append('\n');
        return prompt.toString();
    }

    /**
     * Resolves a source name the model returned back to its type.
     *
     * <p>The model is asked for {@code sourceName} but never for {@code sourceType}, because the type is something
     * this application already knows and the model would only be guessing at. Having it guess would let a report
     * claim a finding came from an Atlassian MCP server when it came from a crawled page - a wrong attribution in
     * the one field a reader uses to judge how much to trust a finding.
     *
     * @param context the run whose sources are the valid set
     * @param sourceName the name the model attributed a change to
     * @return the matching source's type, or {@code OTHER_UNKNOWN} semantics via {@code null} when the model named
     *     something that does not exist
     */
    static SourceType resolveSourceType(RunContext context, String sourceName) {
        if (sourceName == null) {
            return null;
        }
        String wanted = sourceName.trim();
        for (SourceComparison comparison : context.comparisons()) {
            if (comparison.sourceName().equalsIgnoreCase(wanted)) {
                return comparison.sourceType();
            }
        }
        for (McpSourceRef mcp : context.mcpSources()) {
            if (mcp.name().equalsIgnoreCase(wanted)) {
                return mcp.type();
            }
        }
        for (McpChangeHistory history : context.mcpHistory()) {
            if (history.sourceName().equalsIgnoreCase(wanted)) {
                return history.sourceType();
            }
        }
        for (SourceFailure failure : context.failures()) {
            if (failure.sourceName().equalsIgnoreCase(wanted)) {
                return failure.sourceType();
            }
        }
        return null;
    }

    /** Describes what window this run covers, which is the one thing that differs most between the two run types. */
    private String scopeParagraph(RunContext context) {
        if (context.runType() == RunType.CUSTOM_RANGE) {
            return "TASK: Compare this product's state between " + DAY.format(context.rangeFrom())
                    + " and " + DAY.format(context.rangeTo())
                    + ". Every source text below is a stored historical capture, labelled with the date it was "
                    + "captured. Nothing here was fetched just now, so report on the window between those two "
                    + "dates and do not speculate about anything after the later one.";
        }
        if (context.lastRunAt() == null) {
            return "TASK: This is the first time this product has been analysed, so there is no previous state for "
                    + "most or all of its sources. Describe the current state of the product as a baseline - what "
                    + "it offers, how it is priced, what its documentation covers - rather than reporting changes. "
                    + "Use \"changes\" only for anything that is genuinely presented as new or recently changed on "
                    + "the sources themselves, such as a dated changelog entry.";
        }
        return "TASK: Report what has changed since this product was last analysed on "
                + MINUTE.format(context.lastRunAt())
                + ". Each crawled source below gives you its previous state and its current state.";
    }

    /** MCP sources are tools the model calls; this is the only place that says how much to hint per type. */
    private void appendMcpInstructions(StringBuilder prompt, RunContext context) {
        if (context.mcpSources().isEmpty()) {
            return;
        }
        prompt.append("LIVE TOOLS AVAILABLE TO YOU:\n")
                .append("The MCP servers below are connected as tools. Call them yourself to find out what has "
                        + "changed; the text blocks further down do not cover them.\n");
        String since = context.lastRunAt() == null ? null : MINUTE.format(context.lastRunAt());

        for (McpSourceRef mcp : context.mcpSources()) {
            prompt.append("- \"").append(mcp.name()).append("\" (").append(describe(mcp.type())).append("): ");
            switch (mcp.type()) {
                case DOCS_MCP -> prompt.append("search and read this documentation set for material that is new or "
                        + "revised").append(sinceClause(since))
                        .append(". Pay particular attention to anything describing new capabilities, changed "
                                + "limits, or deprecations.");
                case ATLASSIAN_MCP -> prompt.append("this server exposes Atlassian tools, which may cover Jira "
                        + "issues and their changelogs, Confluence pages, or both. Use whichever of its tools are "
                        + "available to find work completed, released, or documented")
                        .append(sinceClause(since))
                        .append(". Treat everything it exposes as fair game rather than assuming it is Jira only.");
                // No domain hints for a generic server on purpose: its tool shape is unknown ahead of time, and
                // guessing at it ("look for new issues") would send the model looking for tools that do not exist.
                case GENERIC_MCP -> prompt.append("list this server's tools and use your judgment about which of "
                        + "them could reveal recent changes to this product")
                        .append(sinceClause(since))
                        .append(". Nothing is assumed about what this server does.");
                default -> prompt.append("use this server's tools to find recent changes")
                        .append(sinceClause(since)).append('.');
            }
            prompt.append('\n');
        }
        prompt.append('\n');
    }

    /** The compare path's substitute for live MCP access, with the caveat stated to the model as well as the user. */
    private void appendMcpHistory(StringBuilder prompt, RunContext context) {
        if (context.mcpHistory().isEmpty()) {
            return;
        }
        prompt.append("PREVIOUSLY RECORDED CHANGES FOR MCP SOURCES:\n")
                .append("These MCP servers cannot be rewound to a past date, so instead of calling them, here are "
                        + "the changes this application itself recorded for them during the requested window. "
                        + "Fold them into your overall summary rather than re-deriving them, and carry them "
                        + "through as changes where they are still relevant. Treat them as second-hand: they are "
                        + "as complete as the runs that happened in this window, no more.\n");

        for (McpChangeHistory history : context.mcpHistory()) {
            prompt.append("- \"").append(history.sourceName()).append("\" (")
                    .append(describe(history.sourceType())).append("): ");
            if (history.isEmpty()) {
                prompt.append("no run in this window recorded anything for this source. That is not the same as "
                        + "nothing having changed - say so rather than reporting it as unchanged.\n");
                continue;
            }
            prompt.append(history.changes().size()).append(" recorded change(s):\n");
            for (Change change : history.changes()) {
                prompt.append("    * [").append(change.category().wireName()).append(", ")
                        .append(change.confidence().wireName()).append("] ")
                        .append(oneLine(change.description())).append('\n');
            }
        }
        prompt.append('\n');
    }

    /** Failures are in the prompt so the summary can mention them; see {@link SourceFailure}. */
    private void appendFailures(StringBuilder prompt, RunContext context) {
        if (context.failures().isEmpty()) {
            return;
        }
        prompt.append("SOURCES UNAVAILABLE FOR THIS RUN:\n");
        for (SourceFailure failure : context.failures()) {
            prompt.append("- \"").append(failure.sourceName()).append("\" (")
                    .append(describe(failure.sourceType())).append("): ")
                    .append(oneLine(failure.reason())).append('\n');
        }
        prompt.append("Mention these in your overall summary. Do not report changes for them.\n\n");
    }

    /**
     * The bulk of the prompt: each crawled source's two states, budgeted so many sources cannot overflow the call.
     *
     * <p>The budget is split per <em>block</em> rather than per source, since a source with no prior state
     * contributes one block and a source with one contributes two. Blocks whose fair share falls below
     * {@link #MIN_BLOCK_CHARS} are dropped whole rather than sliced into uselessness, and the drop is stated in the
     * prompt so the model can qualify its answer instead of quietly analysing a fragment.
     */
    private void appendSourceTexts(StringBuilder prompt, RunContext context) {
        if (context.comparisons().isEmpty()) {
            return;
        }

        int blocks = 0;
        for (SourceComparison comparison : context.comparisons()) {
            blocks += comparison.hasPrior() ? 2 : 1;
        }
        int perBlock = properties.maxPromptChars() / Math.max(1, blocks);
        List<String> dropped = new ArrayList<>();

        prompt.append("SOURCE TEXT:\n");
        for (SourceComparison comparison : context.comparisons()) {
            if (perBlock < MIN_BLOCK_CHARS) {
                dropped.add(comparison.sourceName());
                continue;
            }
            prompt.append("\n=== SOURCE: ").append(comparison.sourceName())
                    .append(" (").append(describe(comparison.sourceType())).append(") ===\n");

            if (comparison.hasPrior()) {
                prompt.append("--- PREVIOUS STATE, captured ")
                        .append(MINUTE.format(comparison.priorFetchedAt())).append(" ---\n")
                        .append(truncate(comparison.priorText(), perBlock)).append('\n');
                prompt.append("--- CURRENT STATE, captured ")
                        .append(MINUTE.format(comparison.currentFetchedAt())).append(" ---\n")
                        .append(truncate(comparison.currentText(), perBlock)).append('\n');
            } else {
                prompt.append("--- CURRENT STATE, captured ")
                        .append(MINUTE.format(comparison.currentFetchedAt()))
                        .append(" (no previous capture exists for this source) ---\n")
                        .append(truncate(comparison.currentText(), perBlock)).append('\n');
            }
        }

        if (!dropped.isEmpty()) {
            prompt.append("\nNOTE: this product has more source text than fits in one analysis, so the following "
                            + "sources were omitted entirely rather than truncated to fragments: ")
                    .append(String.join(", ", dropped))
                    .append(". Say so in your overall summary.\n");
        }
    }

    /** The latest stored text per source, for the ask path, under the same overall budget. */
    private void appendAskExcerpts(StringBuilder prompt, AskContext context) {
        if (context.snapshots().isEmpty()) {
            return;
        }
        int perBlock = properties.maxPromptChars() / Math.max(1, context.snapshots().size());
        prompt.append("STORED SOURCE TEXT:\n");
        for (AskContext.SourceExcerpt excerpt : context.snapshots()) {
            prompt.append("\n=== SOURCE: ").append(excerpt.sourceName())
                    .append(" (").append(describe(excerpt.sourceType()))
                    .append("), captured ").append(MINUTE.format(excerpt.fetchedAt())).append(" ===\n")
                    .append(truncate(excerpt.text(), Math.max(MIN_BLOCK_CHARS, perBlock))).append('\n');
        }
        prompt.append('\n');
    }

    /**
     * Cuts a block to a budget, keeping the start and labelling the cut.
     *
     * <p>Keeps the beginning because crawled text arrives in crawl order, which is breadth-first from the source's
     * starting URL - so the earliest text is the entry page and the pages it links to directly, which is where a
     * changelog or pricing table actually lives. The tail of a long crawl is the deep, incidental pages.
     *
     * <p>The marker is not decoration. Without it the model sees a page that appears to end mid-sentence and can
     * reasonably report the missing remainder as removed content.
     */
    private static String truncate(String text, int budget) {
        if (text == null) {
            return "";
        }
        if (text.length() <= budget) {
            return text;
        }
        int removed = text.length() - budget;
        return text.substring(0, budget)
                + String.format("%n[... truncated here: %,d further characters of this capture were omitted to fit "
                + "the analysis budget. Do not treat this cut-off as removed content. ...]", removed);
    }

    /** Human-readable source type, so the prompt reads as English rather than as enum constants. */
    private static String describe(SourceType type) {
        return switch (type) {
            case DOCS_MCP -> "documentation MCP server";
            case ATLASSIAN_MCP -> "Atlassian MCP server";
            case GENERIC_MCP -> "MCP server";
            case WEBSITE -> "crawled website";
            case SAAS_URL -> "crawled SaaS app page";
        };
    }

    private static String sinceClause(String since) {
        return since == null ? "" : " since " + since;
    }

    /** Collapses whitespace so one recorded change stays on one line of the prompt. */
    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /**
     * The set of category names the model is allowed to use, for tests and for any future schema generation.
     *
     * @return the wire names of every {@link ChangeCategory}
     */
    static List<String> categoryWireNames() {
        return java.util.Arrays.stream(ChangeCategory.values()).map(ChangeCategory::wireName).toList();
    }

    /**
     * @return the wire names of every {@link Confidence} level
     */
    static List<String> confidenceWireNames() {
        return java.util.Arrays.stream(Confidence.values()).map(Confidence::wireName).toList();
    }

    /**
     * @param at an instant to render at day granularity, matching a compare's calendar granularity
     * @return e.g. {@code "1 June 2026"}
     */
    static String formatDay(Instant at) {
        return DAY.format(at);
    }
}
