package com.quashy.openclaw4j.tool.safety.validator;

import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.safety.model.ToolArgumentValidatorType;
import com.quashy.openclaw4j.tool.safety.policy.ToolPolicyDecision;
import com.quashy.openclaw4j.tool.safety.policy.ToolPolicyVerdict;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 负责在 filesystem 写类请求进入真实 transport 之前做参数级安全校验，阻断路径逃逸与敏感文件修改。
 */
public class FilesystemWriteArgumentValidator {

    /**
     * 保存 workspace 根目录下禁止被高风险写操作触达的敏感相对路径列表。
     */
    private final List<String> sensitiveRelativePaths;

    /**
     * 通过显式注入 denylist 固定策略边界，避免校验器内部散落魔法字符串。
     */
    public FilesystemWriteArgumentValidator(List<String> sensitiveRelativePaths) {
        this.sensitiveRelativePaths = sensitiveRelativePaths == null
                ? List.of()
                : sensitiveRelativePaths.stream()
                .map(path -> path.replace('\\', '/'))
                .toList();
    }

    /**
     * 对 filesystem 写请求执行 workspace 白名单、敏感文件、递归和批量限制校验，失败时返回拒绝结论。
     */
    public ToolPolicyDecision validate(ToolCallRequest request) {
        Assert.notNull(request, "request must not be null");
        if (request.executionContext() == null || request.executionContext().workspaceRoot() == null) {
            return ToolPolicyDecision.denied(
                    "missing_execution_context",
                    "Filesystem write validation requires execution context and workspace root.",
                    Map.of()
            );
        }
        Object recursive = request.arguments().get("recursive");
        if (recursive instanceof Boolean recursiveFlag && recursiveFlag) {
            return ToolPolicyDecision.denied(
                    "filesystem_recursive_operation_blocked",
                    "Recursive filesystem write operations are blocked by the current safety policy.",
                    Map.of("toolName", request.toolName())
            );
        }
        Object paths = request.arguments().get("paths");
        if (paths instanceof List<?> pathList && pathList.size() > 1) {
            return ToolPolicyDecision.denied(
                    "filesystem_batch_operation_blocked",
                    "Batch filesystem write operations are blocked by the current safety policy.",
                    Map.of("pathCount", pathList.size())
            );
        }
        for (String pathKey : List.of("path", "source", "destination")) {
            Object candidateValue = request.arguments().get(pathKey);
            if (candidateValue instanceof String rawPath) {
                ToolPolicyDecision pathDecision = validateSinglePath(request.executionContext().workspaceRoot(), rawPath);
                if (pathDecision.verdict() == ToolPolicyVerdict.DENIED) {
                    return pathDecision;
                }
            }
        }
        return ToolPolicyDecision.allowed(Map.of("validatorType", ToolArgumentValidatorType.FILESYSTEM_WRITE.name()));
    }

    /**
     * 对单个路径执行规范化、workspace 约束、符号链接逃逸和敏感文件 denylist 校验。
     */
    private ToolPolicyDecision validateSinglePath(Path workspaceRoot, String rawPath) {
        Path normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize();
        Path resolvedPath = Path.of(rawPath);
        if (!resolvedPath.isAbsolute()) {
            resolvedPath = normalizedWorkspace.resolve(resolvedPath);
        }
        resolvedPath = resolvedPath.toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(normalizedWorkspace)) {
            return ToolPolicyDecision.denied(
                    "filesystem_path_outside_workspace",
                    "Filesystem write target resolves outside the configured workspace root.",
                    Map.of(
                            "workspaceRoot", normalizedWorkspace.toString(),
                            "targetPath", resolvedPath.toString()
                    )
            );
        }
        if (escapesViaSymlink(normalizedWorkspace, resolvedPath)) {
            return ToolPolicyDecision.denied(
                    "filesystem_symlink_escape_detected",
                    "Filesystem write target resolves through a symbolic link outside the workspace root.",
                    Map.of("targetPath", resolvedPath.toString())
            );
        }
        String relativePath = normalizedWorkspace.relativize(resolvedPath).toString().replace('\\', '/');
        String relativePathLowerCase = relativePath.toLowerCase(Locale.ROOT);
        boolean sensitivePathMatched = sensitiveRelativePaths.stream()
                .map(path -> path.toLowerCase(Locale.ROOT))
                .anyMatch(relativePathLowerCase::equals);
        if (sensitivePathMatched) {
            return ToolPolicyDecision.denied(
                    "filesystem_sensitive_path_blocked",
                    "Filesystem write target hits a denylisted workspace file.",
                    Map.of("targetPath", relativePath)
            );
        }
        return ToolPolicyDecision.allowed(Map.of("targetPath", relativePath));
    }

    /**
     * 通过最近的现存祖先节点检测符号链接逃逸，避免在 workspace 内部借由链接间接改写外部文件。
     */
    private boolean escapesViaSymlink(Path workspaceRoot, Path resolvedPath) {
        try {
            Path workspaceRealPath = workspaceRoot.toRealPath();
            Path existingCandidate = resolvedPath;
            while (existingCandidate != null && !Files.exists(existingCandidate)) {
                existingCandidate = existingCandidate.getParent();
            }
            if (existingCandidate == null) {
                return false;
            }
            Path existingRealPath = existingCandidate.toRealPath();
            return !existingRealPath.startsWith(workspaceRealPath);
        } catch (IOException exception) {
            return false;
        }
    }
}

