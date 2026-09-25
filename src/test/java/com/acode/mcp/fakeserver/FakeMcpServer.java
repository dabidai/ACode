package com.acode.mcp.fakeserver;

import com.acode.mcp.JsonRpcCodec;
import com.acode.mcp.JsonRpcMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 可独立运行的假 MCP server：stdin 逐行读 JSON-RPC、stdout 逐行回。
 * 支持 initialize / notifications/initialized / tools/list / tools/call，
 * 暴露 echo（回显 text 参数）与 echo_env（返回指定环境变量值）两个工具，
 * 另有 kill 工具（收到即退出，模拟进程死亡）与未知工具（回 isError）。
 * 故障注入参数：--protocol-version &lt;v&gt;（改握手版本）、--fail-first-call（首次 tools/call 回 isError）。
 */
public class FakeMcpServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static volatile boolean exitAfterReply;

    public static void main(String[] args) throws IOException {
        String protocolVersion = pickArg(args, "--protocol-version", PROTOCOL_VERSION);
        boolean failFirstCall = hasArg(args, "--fail-first-call");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Writer out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        int toolsCallCount = 0;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonRpcMessage message;
            try {
                message = JsonRpcCodec.parse(line);
            } catch (Exception e) {
                continue; // 单帧解析失败跳过，不终止
            }
            boolean wasToolCall = message instanceof JsonRpcMessage.Request req
                    && "tools/call".equals(req.method());
            if (wasToolCall) {
                toolsCallCount++;
            }
            String reply = handle(message, protocolVersion, failFirstCall, toolsCallCount);
            if (reply != null) {
                out.write(reply);
                out.write('\n');
                out.flush();
            }
            if (exitAfterReply) {
                break; // 模拟子进程死亡：先回包再退出
            }
        }
    }

    private static String handle(JsonRpcMessage message, String protocolVersion,
                                 boolean failFirstCall, int toolsCallCount) {
        if (!(message instanceof JsonRpcMessage.Request request)) {
            return null; // 通知与响应忽略
        }
        switch (request.method()) {
            case "initialize" -> {
                ObjectNode result = JSON.createObjectNode();
                result.put("protocolVersion", protocolVersion);
                result.putObject("capabilities").putObject("tools");
                return JsonRpcCodec.serializeResponse(request.id(), result);
            }
            case "tools/list" -> {
                ObjectNode result = JSON.createObjectNode();
                var tools = result.putArray("tools");
                tools.add(tool("echo", "回显 text 参数", Map.of("text", "string")));
                tools.add(tool("echo_env", "返回指定环境变量的值", Map.of("name", "string")));
                tools.add(tool("kill", "先回包再退出进程（模拟子进程死亡）", Map.of()));
                tools.add(tool("drop", "收到调用后不回包就断开（模拟结果未知）", Map.of()));
                tools.add(tool("sleep", "睡眠指定毫秒后返回（模拟慢请求）", Map.of("ms", "integer")));
                return JsonRpcCodec.serializeResponse(request.id(), result);
            }
            case "tools/call" -> {
                if (failFirstCall && toolsCallCount == 1) {
                    return errorResult(request.id(), "首轮调用强制失败（故障注入）");
                }
                String name = request.params().path("name").asText("");
                JsonNode arguments = request.params().path("arguments");
                switch (name) {
                    case "echo" -> {
                        String text = arguments.path("text").asText("");
                        return JsonRpcCodec.serializeResponse(request.id(), textResult(text));
                    }
                    case "echo_env" -> {
                        String varName = arguments.path("name").asText("");
                        String value = System.getenv(varName);
                        return JsonRpcCodec.serializeResponse(request.id(), textResult(value == null ? "(null)" : value));
                    }
                    case "kill" -> {
                        exitAfterReply = true;
                        return JsonRpcCodec.serializeResponse(request.id(), textResult("bye"));
                    }
                    case "drop" -> {
                        System.exit(0);
                        return null;
                    }
                    case "sleep" -> {
                        long ms = arguments.path("ms").asLong(0);
                        try {
                            Thread.sleep(ms);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return JsonRpcCodec.serializeResponse(request.id(), textResult("slept " + ms));
                    }
                    default -> {
                        return errorResult(request.id(), "未知工具：" + name);
                    }
                }
            }
            default -> {
                return JsonRpcCodec.serializeError(request.id(), -32601, "不支持的方法：" + request.method(), null);
            }
        }
    }

    private static ObjectNode tool(String name, String description, Map<String, String> stringProps) {
        ObjectNode tool = JSON.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        stringProps.forEach((key, type) -> properties.putObject(key).put("type", type));
        return tool;
    }

    /** isError=true 的结果（远端执行失败语义） */
    private static String errorResult(String id, String text) {
        ObjectNode result = JSON.createObjectNode();
        result.put("isError", true);
        result.put("content", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("type", "text").put("text", text)));
        return JsonRpcCodec.serializeResponse(id, result);
    }

    /** 成功结果：单个 text 内容块 */
    private static ObjectNode textResult(String text) {
        ObjectNode result = JSON.createObjectNode();
        result.put("content", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("type", "text").put("text", text)));
        return result;
    }

    private static String pickArg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static boolean hasArg(String[] args, String name) {
        for (String arg : args) {
            if (arg.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
