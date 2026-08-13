package com.acode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具抽象基类：参数校验（缺失 / 类型错 → 失败结果并带参数名）、
 * 执行超时包装、运行时异常 → 失败结果。
 *
 * <p>执行放入虚拟线程池并受超时上限约束；doExecute 抛出的任何异常
 * 都会被捕获并转为失败结果，不向上抛。具体工具只需声明参数、实现执行逻辑。
 */
public abstract class BaseTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
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
        Future<ToolResult> future = EXECUTOR.submit(() -> doExecute(input, context));
        try {
            return future.get(defaultTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.failure("执行超时（上限 " + defaultTimeoutMillis() + " ms）：" + name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.failure("执行被中断：" + name());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String detail = cause != null && cause.getMessage() != null
                    ? cause.getMessage() : String.valueOf(cause);
            return ToolResult.failure("工具执行异常：" + detail);
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
