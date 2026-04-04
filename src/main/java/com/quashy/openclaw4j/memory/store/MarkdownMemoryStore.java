package com.quashy.openclaw4j.memory.store;

import com.quashy.openclaw4j.memory.model.MemoryTargetBucket;
import com.quashy.openclaw4j.memory.model.MemoryWriteRequest;
import com.quashy.openclaw4j.memory.model.MemoryWriteResult;
import com.quashy.openclaw4j.memory.model.UserProfileCategory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责把 memory V1 请求写入 workspace 下的 Markdown 事实源，并维护 `USER.md` 的节内追加与去重规则。
 */
public class MarkdownMemoryStore {

    /**
     * 指向当前 memory 操作所作用的 workspace 根目录，用于统一解析事实源文件位置。
     */
    private final Path workspaceRoot;

    /**
     * 提供统一时间源，使 session 日志命名与 provenance 时间在测试和生产中都可稳定控制。
     */
    private final Clock clock;

    /**
     * 通过显式注入 workspace 根目录与时间源，避免存储组件依赖全局静态状态。
     */
    public MarkdownMemoryStore(Path workspaceRoot, Clock clock) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /**
     * 按目标桶写入一条 Markdown 记忆，并在需要时自动创建缺失文件或目录。
     */
    public MemoryWriteResult write(MemoryWriteRequest request) throws IOException {
        return switch (request.targetBucket()) {
            case USER_PROFILE -> writeUserProfile(request);
            case LONG_TERM -> writeGenericEntry(request, Path.of("MEMORY.md"));
            case SESSION_LOG -> writeGenericEntry(request, Path.of(resolveSessionRelativePath()));
        };
    }

    /**
     * 把用户画像写入匹配节，并在同节已存在相同正文时返回去重结果而不是重复追加。
     */
    private MemoryWriteResult writeUserProfile(MemoryWriteRequest request) throws IOException {
        UserProfileCategory category = request.category();
        Path userProfileFile = workspaceRoot.resolve("USER.md");
        Files.createDirectories(workspaceRoot);
        List<String> lines = Files.exists(userProfileFile)
                ? new ArrayList<>(Files.readAllLines(userProfileFile))
                : new ArrayList<>(defaultUserProfileTemplateLines());
        int sectionHeaderIndex = ensureSection(lines, category.sectionTitle());
        if (sectionContainsContent(lines, sectionHeaderIndex, request.content())) {
            return new MemoryWriteResult(
                    MemoryTargetBucket.USER_PROFILE.value(),
                    "USER.md",
                    category.value(),
                    true
            );
        }
        int insertionIndex = findSectionInsertionIndex(lines, sectionHeaderIndex);
        lines.addAll(insertionIndex, buildEntryLines(request));
        Files.write(userProfileFile, lines);
        return new MemoryWriteResult(
                MemoryTargetBucket.USER_PROFILE.value(),
                "USER.md",
                category.value(),
                false
        );
    }

    /**
     * 把长期记忆或 session 日志按统一块格式追加到目标文件末尾，并在缺失目录场景下自动创建路径。
     */
    private MemoryWriteResult writeGenericEntry(MemoryWriteRequest request, Path relativePath) throws IOException {
        Path targetFile = workspaceRoot.resolve(relativePath);
        if (targetFile.getParent() != null) {
            Files.createDirectories(targetFile.getParent());
        }
        String existingContent = Files.exists(targetFile) ? Files.readString(targetFile) : defaultDocumentHeader(relativePath);
        StringBuilder builder = new StringBuilder(existingContent);
        if (!existingContent.isBlank() && !existingContent.endsWith(System.lineSeparator() + System.lineSeparator())) {
            builder.append(System.lineSeparator());
            builder.append(System.lineSeparator());
        }
        builder.append(String.join(System.lineSeparator(), buildEntryLines(request)));
        builder.append(System.lineSeparator());
        Files.writeString(targetFile, builder.toString());
        return new MemoryWriteResult(
                request.targetBucket().value(),
                normalizeRelativePath(relativePath),
                request.category() != null ? request.category().value() : null,
                false
        );
    }

    /**
     * 为 `USER.md` 生成默认模板，确保白名单节在首次写入时就具备稳定顺序和可读标题。
     */
    private List<String> defaultUserProfileTemplateLines() {
        List<String> lines = new ArrayList<>();
        lines.add("# 用户画像");
        lines.add("");
        for (UserProfileCategory category : UserProfileCategory.values()) {
            lines.add("## " + category.sectionTitle());
            lines.add("");
        }
        return lines;
    }

    /**
     * 为新建的长期记忆或 session 日志文件生成最小标题，避免文件首次创建时只剩一段裸条目。
     */
    private String defaultDocumentHeader(Path relativePath) {
        if (relativePath.startsWith("memory")) {
            return "# 会话记忆 " + relativePath.getFileName().toString().replace(".md", "") + System.lineSeparator();
        }
        return "# 长期记忆" + System.lineSeparator();
    }

    /**
     * 保证指定节标题存在，并返回该节标题所在行号，供后续追加或去重逻辑复用。
     */
    private int ensureSection(List<String> lines, String sectionTitle) {
        String expectedHeader = "## " + sectionTitle;
        for (int index = 0; index < lines.size(); index++) {
            if (expectedHeader.equals(lines.get(index))) {
                return index;
            }
        }
        if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
            lines.add("");
        }
        lines.add(expectedHeader);
        lines.add("");
        return lines.size() - 2;
    }

    /**
     * 检查目标节内是否已包含同一条正文，避免 `USER.md` 因重复自动写入快速膨胀。
     */
    private boolean sectionContainsContent(List<String> lines, int sectionHeaderIndex, String content) {
        int sectionEndIndex = findNextSectionIndex(lines, sectionHeaderIndex);
        String expectedLine = "- " + content;
        for (int index = sectionHeaderIndex + 1; index < sectionEndIndex; index++) {
            if (expectedLine.equals(lines.get(index).trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算新条目在目标节中的插入位置，优先追加到下一个节之前，保持节内条目聚合。
     */
    private int findSectionInsertionIndex(List<String> lines, int sectionHeaderIndex) {
        int nextSectionIndex = findNextSectionIndex(lines, sectionHeaderIndex);
        int insertionIndex = nextSectionIndex;
        while (insertionIndex > sectionHeaderIndex + 1 && lines.get(insertionIndex - 1).isBlank()) {
            insertionIndex--;
        }
        if (insertionIndex > sectionHeaderIndex + 1) {
            lines.add(insertionIndex, "");
            insertionIndex++;
        }
        return insertionIndex;
    }

    /**
     * 找到当前节后面的下一个二级标题位置，若不存在则回退到文件末尾。
     */
    private int findNextSectionIndex(List<String> lines, int sectionHeaderIndex) {
        for (int index = sectionHeaderIndex + 1; index < lines.size(); index++) {
            if (lines.get(index).startsWith("## ")) {
                return index;
            }
        }
        return lines.size();
    }

    /**
     * 把一条记忆统一格式化为 Markdown 列表块，保证正文和 provenance 都可读且易于后续索引。
     */
    private List<String> buildEntryLines(MemoryWriteRequest request) {
        OffsetDateTime writtenAt = OffsetDateTime.now(clock);
        List<String> entryLines = new ArrayList<>();
        entryLines.add("- " + request.content());
        entryLines.add("  - written_at: " + writtenAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        entryLines.add("  - channel: " + request.executionContext().sourceMessage().channel());
        entryLines.add("  - trigger_reason: " + request.triggerReason());
        if (request.confidence() != null) {
            entryLines.add("  - confidence: " + request.confidence());
        }
        if (request.executionContext().conversationId() != null) {
            entryLines.add("  - conversation_id: " + request.executionContext().conversationId().value());
        }
        if (request.executionContext().userId() != null) {
            entryLines.add("  - user_id: " + request.executionContext().userId().value());
        }
        if (request.executionContext().traceContext() != null) {
            entryLines.add("  - run_id: " + request.executionContext().traceContext().runId());
        }
        return entryLines;
    }

    /**
     * 按服务器本地日期解析 session 日志相对路径，满足 `memory/YYYY-MM-DD.md` 的文件布局要求。
     */
    private String resolveSessionRelativePath() {
        return "memory/" + OffsetDateTime.now(clock).toLocalDate() + ".md";
    }

    /**
     * 统一把相对路径序列化为正斜杠形式，避免 Windows 环境下返回反斜杠破坏工具协议稳定性。
     */
    private String normalizeRelativePath(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }
}
