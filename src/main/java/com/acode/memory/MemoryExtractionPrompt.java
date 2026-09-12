package com.acode.memory;

/** 自动记忆提取的指令文本：只输出结构化操作列表，不调工具。 */
public final class MemoryExtractionPrompt {

    /** 独立常量：提取请求的 max_tokens（与对话级 8_192、摘要级 20_000 均不同） */
    public static final int MAX_TOKENS = 4_000;

    private MemoryExtractionPrompt() {}

    public static String instruction() {
        return """
                You maintain the long-term memory of a coding assistant.

                You receive the current memory index, the list of existing memories, and the most
                recent exchange of the conversation. Decide whether anything in it is worth
                remembering long-term.

                Reply with the operations to perform. You never touch the files or the index
                yourself: the caller writes the memory files and rebuilds MEMORY.md from your
                operations, so refer to each memory by type and slug only.
                - create: a memory that does not exist yet
                - update: an existing memory whose information changed
                - delete: an existing memory that is outdated

                Memory types:
                - user: the user's personal coding preferences and style requests
                - feedback: corrections the user made, or an approach the user explicitly confirmed
                - project: project-specific technical knowledge
                - reference: external links and resources for this project

                Do not create a memory that duplicates an existing one.
                If nothing is worth remembering, do nothing.

                Output ONLY a JSON array with no prose and no code fence. Each element:
                {"op":"create|update|delete","type":"user|feedback|project|reference",
                 "name":"kebab-case-slug","description":"one-line summary",
                 "body":"**Why**: ...\\n**How to apply**: ..."}

                For delete only op/type/name are required. Output [] when nothing is worth remembering.
                """;
    }
}
