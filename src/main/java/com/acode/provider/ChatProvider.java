package com.acode.provider;

/**
 * 统一的大模型对话接口。Anthropic / OpenAI 各自实现，未来新后端按此扩展。
 * 调用方决定线程模型：本接口同步执行，需要异步时由调用方提交线程池。
 */
public interface ChatProvider {

    /**
     * 发起一次流式对话。返回前所有回调均已发生（同步阻塞）。
     *
     * @param request  请求内容
     * @param listener 流式回调，onComplete 或 onError 二选一结束
     */
    void streamChat(ChatRequest request, ChatListener listener);
}
