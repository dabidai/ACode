# 参考：FlashMemory 长上下文 KV Cache 压缩 — 设计笔记

> 最后更新：2026-09-02
> 状态：**仅作参考**。本文是一篇公众号论文解读的摘记，不是任何已定功能范围。真正的阶段内容待补充后再另行规划。

## 来源

- 微信公众号文章《面向 DeepSeek-V4 的 FlashMemory：长上下文 KV Cache 如何压到约 1/10》，URL：https://mp.weixin.qq.com/s/cckaQyOGSuZGXzB0rrs8Ag
- 解读对象：论文 FlashMemory-DeepSeek-V4 / Lookahead Sparse Attention (LSA)
- 全文与概念卡已存第二大脑（来源摘要卡 + KV Cache / LSA / Neural Memory Indexer / FlashMemory-DeepSeek-V4 / Attention Denoiser / MRCR 等概念卡）

## 论文要点（备忘）

- 问题：长上下文推理时 KV Cache 常驻 GPU，128K~512K 上下文显存不可承受。
- 方案：历史 KV 下沉 CPU Cold Pool，GPU 只留最近窗口 + 按需召回的关键 chunk；Neural Memory Indexer（独立 dual-encoder，只训 query 侧）根据当前 hidden state 预判未来会用到哪些历史 chunk；Sigmoid 阈值召回（非固定 top-k）；训练标签用 Cross-Layer Majority Voting 去噪。
- 效果：平均物理 KV Cache 压到 baseline 约 1/7（512K 时约 1/10）；平均准确率反升约 0.6pp（attention denoiser 效应）。
- 对照组落差：Recency Only 33.3、Random 10% 38.7 vs 检索感知 77.5 → "知道该召回哪些历史"才是关键。
- 局限：密集全局记忆任务（MRCR 76→48）失败；超出 512K 训练长度后选择质量退化接近随机；历史侧 key 冻结、与 backbone 解耦训练、无端到端联合优化。

## 对 ACode 的可能借鉴（仅假设，未定案）

ACode 是 API 客户端，服务端显存机制（LSA/Cold Pool/Memory Indexer 训练）不可迁移。若要借鉴，落在客户端概念层：

1. 现状超限裁最旧（Recency Only 语义）在长会话上会静默丢远距决策，且模型不知情；
2. "被裁历史可被按需取回"的冷池思想，与"裁剪前留一小段摘要保远距信号"两类方向；
3. "少塞噪声反升质量"提醒克制注入，避免自动记忆过度灌入上下文。

> 以上仅是读后摘记，不等同于 ACode 任何阶段的功能承诺。真正要做什么，等补充内容后再定。
