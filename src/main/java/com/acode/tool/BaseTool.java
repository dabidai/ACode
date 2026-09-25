package com.acode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具抽象基类：参数校验（缺失 / 类型错 → 失败结果并带参数名）、
 * 执行超时包装、运行时异常 → 失败结果。
 *
 * <p>执行放入独立虚拟线程并受超时上限约束；超时或取消后等待该线程真正退出，
 * 防止后续对话与旧工具副作用重叠。doExecute 抛出的异常转为失败结果。
 */
public abstract class BaseTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MILLIS = 10_000;

    private final String name;
    private final String description;
    private final Permission permission;

    protected BaseTool(String name, String description, Permission permission) {
        this.name = name;
        this.description = description;
        this.permission = permission;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final String description() {
        return description;
    }

    @Override
    public final Permission permission() {
        return permission;
    }

    @Override
    public final ToolResult execute(JsonNode input, ToolContext context) {
        String validationError = validate(input);
        if (validationError != null) {
            return ToolResult.failure(validationError);
        }
        long timeout = timeoutMillis(input);
        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("acode-tool-" + name()).start(() -> {
            try {
                future.complete(doExecute(input, context));
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            worker.interrupt();
            awaitStopped(worker);
            return ToolResult.failure("执行超时（上限 " + timeout + " ms）：" + name());
        } catch (InterruptedException e) {
            worker.interrupt();
            awaitStopped(worker);
            Thread.currentThread().interrupt();
            return ToolResult.failure("执行被中断：" + name());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String detail = cause != null && cause.getMessage() != null
                    ? cause.getMessage() : String.valueOf(cause);
            return ToolResult.failure("工具执行异常：" + detail);
        }
    }

    /** 中断仅是请求；必须等工作线程实际退出，下一轮才不会与旧工具副作用重叠。 */
    private static void awaitStopped(Thread worker) {
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                interrupted = true;
                worker.interrupt();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 参数定义：校验与默认 inputSchema 的依据 */
    protected abstract List<ParamSpec> paramSpecs();

    /** 实际执行逻辑。不应抛出异常；需要失败时返回失败结果或抛 ToolExecutionException。 */
    protected abstract ToolResult doExecute(JsonNode input, ToolContext context);

    /** 超时上限（毫秒），子类可按需覆盖 */
    protected long defaultTimeoutMillis() {
        return DEFAULT_TIMEOUT_MILLIS;
    }

    /** 外壳超时上限（毫秒）：默认取 defaultTimeoutMillis()，子类可据请求参数扩展（如 Bash 读 timeout_ms） */
    protected long timeoutMillis(JsonNode input) {
        return defaultTimeoutMillis();
    }

    /** 由 paramSpecs 生成的默认参数 Schema；结构复杂的工具可覆盖此方法 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (ParamSpec spec : paramSpecs()) {
            ObjectNode prop = properties.putObject(spec.name());
            prop.put("type", spec.type().name().toLowerCase());
            if (spec.type() == ParamSpec.Type.ARRAY) {
                prop.putObject("items").put("type", "object");
            }
            if (spec.description() != null && !spec.description().isBlank()) {
                prop.put("description", spec.description());
            }
            if (spec.required()) {
                required.add(spec.name());
            }
        }
        return schema;
    }

    private String validate(JsonNode input) {
        JsonNode params = (input == null || input.isNull()) ? JSON.createObjectNode() : input;
        if (!params.isObject()) {
            return "参数必须是 JSON 对象";
        }
        for (ParamSpec spec : paramSpecs()) {
            JsonNode value = params.get(spec.name());
            if (value == null || value.isNull()) {
                if (spec.required()) {
                    return "缺少参数：" + spec.name();
                }
                continue;
            }
            if (!matches(spec.type(), value)) {
                return "参数 " + spec.name() + " 类型应为 " + spec.type().name().toLowerCase()
                        + "，实际为 " + nodeTypeName(value);
            }
        }
        return null;
    }

    private static boolean matches(ParamSpec.Type type, JsonNode value) {
        return switch (type) {
            case STRING -> value.isTextual();
            case INTEGER -> value.isIntegralNumber();
            case NUMBER -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case ARRAY -> value.isArray();
            case OBJECT -> value.isObject();
        };
    }

    private static String nodeTypeName(JsonNode value) {
        if (value.isTextual()) {
            return "string";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isObject()) {
            return "object";
        }
        if (value.isNull()) {
            return "null";
        }
        return "unknown";
    }
}
