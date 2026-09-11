# DoVideoAI 面试问答

> 本文档所有数字与实现口径均以当前代码为准。
>
> **「代码细节」小节约定**：给出可以直接引用的类名、方法名、常量、字段名和 Key 格式，用于回答
> 「具体怎么实现的」。引用格式为 `类名:行号`，类名相对 `server/src/main/java/com/example/server/`。
> 与本文其他段落口径冲突时，以「代码细节」小节为准（代码是唯一真源）。

---

## 一、项目整体认知

### Q1：请用一两分钟介绍 DoVideoAI

DoVideoAI 是我用 AI Coding 辅助开发的长视频内容理解平台。用户上传课程、会议或操作录屏后，可以给出「生成复习笔记」「提取操作步骤」「核验视频观点」这样的分析目标。系统先把视频整理成带时间轴的语音和关键帧文字证据，再按目标检索相关片段，由 Planner、Executor 和 Critic 完成任务拆解、产物生成和逐结论校验，最后返回可以回到原视频时间点核验的结构化结果。

这个项目有两条主线。工程主线解决长视频文件大、处理慢、外部服务不稳定的问题，所以有分片续传、RocketMQ、Redisson、限流、重试和 Checkpoint。Agent 主线解决单次总结无法适配不同目标、长上下文干扰和结果缺少依据的问题，所以有 VideoContext、分层摘要、混合检索和受控 AgentLoop。

我的核心思路是先把视频加工成可复用、可追溯的证据资产，再让 Agent 在明确的证据、状态和预算边界内完成目标驱动分析，而不是把完整视频直接丢给模型碰运气。

**代码细节：技术栈和代码规模**

后端 **Java 21**（`pom.xml` 的 `<java.version>21</java.version>`）+ Spring Boot，
`server/src/main/java/com/example/server/` 下 **86 个 Java 文件、约 8.5 千行**。
模块划分（顶层包文件数）：

| 包 | 文件数 | 内容 |
| :--- | :--- | :--- |
| `service`（含 `service/mode`） | 27 + 4 | 业务主逻辑 |
| `dto` | 21 | record 为主的入参出参 |
| `controller` | 7 | HTTP / SSE 入口 |
| `utils` | 7 | ASR / OCR / Embedding / DeepSeek / MinIO / Key 工具 |
| `config` | 5 | 线程池、MinIO、鉴权拦截、Web |
| `mapper` / `repository` | 4 + 1 | MyBatis Mapper 与 Checkpoint 仓储 |
| `common` / `exception` | 3 + 2 | 统一返回、错误码、业务异常 |
| `entity` | 3 | `User` / `MediaFile` / `FailedAnalysisTask` |
| `consumer` | 1 | `VideoAnalysisConsumer` |
| 根 | 1 | `ServerApplication` |

`pom.xml` 里的关键依赖：`langchain4j-open-ai`（用 `OpenAiChatModel` 接 OpenAI 兼容网关）、
`redisson-spring-boot-starter`（分布式锁 + `RRateLimiter`）、
`rocketmq-spring-boot-starter`、`mybatis-plus-spring-boot3-starter`、
`flyway-core` + `flyway-mysql`、`minio`、`okhttp`、`fastjson2`、`lombok`。

**没有 Qdrant SDK 依赖**——Qdrant 是直接用 OkHttp 打 REST 接口
（`QdrantVectorStore` 里手写 `/collections/{c}/points/query` 等路径）。
ASR 和 Embedding 同样走 OkHttp 直连，只有 LLM 走了 LangChain4j。
这个取舍值得说：Qdrant 只用到三个接口（建集合、写点、查点），引 SDK 的收益不如自己控制超时和错误语义。

外部依赖的具体型号：LLM 默认 `deepseek-ai/DeepSeek-V3.2`（`ai.deepseek.model`）、
Embedding `BAAI/bge-m3`（`ai.embedding.model`，1024 维）、
ASR `TeleAI/TeleSpeechASR`（`ai.asr.model`）、
本地 OCR 是 tesseract 命令行加 `chi_sim+eng` 语言包。
LLM / Embedding / ASR 三家都走同一个 SiliconFlow 兼容网关（`ai.deepseek.base-url`）。

**代码细节：模型调用的重试和超时，与文档其他处口径对齐**

`OpenAiChatModel` 构造时显式设了 `.maxRetries(0)`（`DeepSeekUtils.java:74-82`），
把重试收回到自己的 `chat()` 里，避免 SDK 内部重试和应用重试叠乘。
超时是 `ai.deepseek.timeout-seconds`，默认 **300 秒**，
且每次调用的实际超时是 `min(300 秒, AgentExecutionBudget 剩余时间)`——
这是 Q18 里「时长预算能在模型调用内部生效」的实现。
构造器还有两条启动期校验（`:66-72`）：超时必须 ≥1、价格必须 ≥0，
且**如果配了 `max-estimated-cost > 0` 但价格是 0，直接抛异常拒绝启动**，
防止费用预算看起来配了实际永远不生效。

### Q2：解析结果具体产出什么？

这题是 Q1 的必然追问，**要把散落的产物收成一张表背下来**，而不是临场组织。
从下到上是三层，每层都是上一层的输入：

| 层次 | 产物 | 具体字段 |
| :--- | :--- | :--- |
| 证据层 | `VideoContext` → 若干 `VideoSegment` | `startMs` / `endMs`、`transcript`（ASR 转写）、`ocrTexts`（关键帧文字）、`evidenceFrames`（MinIO 关键帧地址） |
| 索引层 | `VideoChunk`，每 5 分钟一个 | `segmentSummary`（≤200 字）、`keywords`、`embedding`（1024 维） |
| 结果层 | `AnalysisResult` | `title`、`conclusions`、`evidence[{timestampMs, source, content, claim}]`、`suggestions`，按模式附带 `sections` |

三个层次的 Segment 形态要一起说：**ASR-only / OCR-only / 双模态**。
只有语音的片段、只有画面文字的片段、两者都有的片段都会保留，
所以「老师讲了一段话但屏幕是空的」和「代码演示但一句话没说」都不会丢。

**「可核验」不是形容词，是有具体实现的**：每条 `evidence` 都带 `timestampMs`，
程序会校验它落在某个 `VideoSegment` 的 `[startMs, endMs)` 内、来源包含 ASR 或 OCR、
且 `content` 是原文的规范化子串（见 Q15）。前端拿到时间戳可以跳回原视频那一秒。

**产物按模式不同**（细节见 Q19）：

| 模式 | 额外产物 |
| :--- | :--- |
| `GENERAL` | 无额外 sections，只有通用四项 |
| `LEARNING` | `outline` 知识点大纲 / `keypoints` 重点难点 / `quiz` 自测题 / `pitfalls` 易错点 |
| `REVIEW` | `fallacies` 逻辑漏洞 / `exaggerations` 夸大表述 / `omissions` 遗漏点 / `doubtful` 存疑结论 |
| `CREATION` | `highlights` 爆点片段（含起止时间戳）/ `titles` 备选标题 / `intro` 简介 / `script` 口播脚本 |

**最终落库位置**：`AnalysisResult.toMarkdown()` 的结果写进 `media_files.ai_summary`，
同时完整结构化对象存进 `agent_checkpoints` 表的 `goal:{goalDigest}:result` 行。
所以「用户看到的」和「系统存的」是同一份数据的两种形态，不是两次生成。

### Q3：从用户上传视频到拿到结果，完整流程是什么？

```
1. 前端初始化上传任务 → 后端生成 uploadId，Redis Hash 存 {文件名, 总分片数, userId}，TTL 1 天
2. 前端按 ≤5MB 分片上传 → 后端「先写 MinIO 分片，再记 Redis Set 成功序号」
3. 全部分片完成 → 调 complete → 持 Redisson 合并锁，按序合并分片、边写边算 MD5
   → 存完整文件 + media_files 记录
4. 用户提交分析目标 → 校验视频归属 + 计算 contentHash 与 goalDigest → SETNX activeKey
   → 用户级 + 全局限流 → 投递 RocketMQ → 返回 202
5. 消费者收到消息 → 拿 Redisson 锁 → 查 Checkpoint（有可复用结果直接返回）
6. 没有 VideoContext 才构建：FFmpeg 60s 切片 + ASR ‖ 场景检测抽帧 + OCR（两路并行）
   → 按 60s 窗口融合成 VideoSegment
7. 长视频聚合成 5 分钟 Chunk → 摘要 + 关键词 + Embedding → Qdrant 索引
8. 按 userGoal 混合检索 TopK 证据 → Planner 拆任务 → Executor 生成结论 + 证据 → Critic 校验
9. Critic 不通过 → 定向补证据或改计划，最多两轮
10. 结果写 MySQL（Checkpoint 真源）+ Redis（7 天热缓存）→ SSE 推阶段与终态 → 前端展示，可继续追问
```

**代码细节：这条链路对应的接口和落点**

| 阶段 | 接口 / 方法 | 关键落点 |
| :--- | :--- | :--- |
| 初始化上传 | `POST /media/init-upload` | `upload:chunked:{uploadId}` Hash，TTL 1 天 |
| 上传分片 | `POST /media/upload-chunk` | MinIO `chunk-uploads/{uploadId}/part-{i}` + Redis Set `:parts` |
| 合并 | `POST /media/complete-upload` | 锁 `lock:upload:merge:{uploadId}` → `media_files` 记录 |
| 模式路由 | `POST /analysis/route` | 返回 `RouteDecision{mode, reason}`，AUTO 不落到后端 |
| 提交分析 | `POST /analysis/ai?id=&goal=&mode=` | 202 → 投递 `video-analysis-topic` |
| 进度推送 | `GET /analysis/analysis-events`（SSE） | 事件名 `task-status`，载荷 `TaskEvent{state,result,message,stage}` |
| 修订重跑 | `POST /analysis/agent-revise` | 带 `revision=true` 的消息，切 Checkpoint |
| 证据检索 | `GET /analysis/evidence-search?id=&query=` | `List<VideoEvidenceHit>` |
| 追问 | `POST /analysis/follow-up` | 复用视频级 Checkpoint，只重算目标级 |
| Trace | `GET /analysis/agent-trace` | `agent:trace:{traceId}`，TTL 7 天 |

入口在 `controller/AnalysisController.java:100`（提交）、`:199`（SSE）、`:157`（修订）；
`controller/MediaController.java:41-65`（分片上传三件套）。

### Q4：这个项目真正解决的用户问题 / 难点是什么？

项目起点是学校课程回放。视频往往一两个小时，用户真正想要的并不是一段泛泛摘要，而是快速得到某种可使用的产物，例如复习笔记、会议待办、操作手册，之后还能点击证据时间戳回看原片段，或者围绕同一视频继续追问。

因此我把用户价值分成三层：稳定上传让长视频能进来；VideoContext 和检索让相关证据能被找到；AgentLoop 让不同目标能生成不同结构化产物。前端展示执行计划和轨迹只是辅助信息，真正的产物价值是结论能核验、任务能恢复、同一视频可以持续复用。

工程上最难的也是这条链路。上传、FFmpeg、ASR、OCR、Embedding 和 LLM 任何一步失败，都不能让用户从头重来，也不能因为 MQ 重投产生重复调用。Agent 上最难的是产出质量：模型需要看到完整且相关的证据，生成的每条结论还要能回到原始视频核验。

### Q5：什么是 OCR？它和 ASR 有什么区别？

**概念题，文档其他地方默认你知道，所以要单独准备。** 先给定义再给对比：

| | OCR | ASR |
| :--- | :--- | :--- |
| 全称 | Optical Character Recognition，光学字符识别 | Automatic Speech Recognition，自动语音识别 |
| 输入 | **图像**（我这里是一帧视频画面） | **音频**波形 |
| 输出 | 画面里出现的**文字** | 语音对应的**文本** |
| 本项目实现 | 本地 Tesseract 命令行，`chi_sim+eng` 语言包 | 托管接口 TeleSpeechASR，按 60 秒切片调用 |
| 能覆盖 | PPT 标题、代码、命令行、报错、图表里的文字标签 | 讲解、结论、负责人、时间点、口头承诺 |
| 典型盲区 | 复杂版式、手写体、艺术字、竖排、低对比度小字 | 同音字、专有名词、英文缩写 |

**要主动把「区别」讲成「互补」**，因为这才是设计动机：

> 代码演示场景里，人可能一句话都不说，但屏幕上出现了 `npm run build` 和
> `Build completed: dist/`；反过来，会议里「下周一之前完成灰度发布」这种结论常常
> 只有语音，屏幕上是空的。所以我把两路做成**独立证据**，任何一路失败另一路还能用，
> 检索时视觉关键词还是独立的一路打分通道（权重 0.15）。

**本项目的 OCR 实现细节**（被追问时能答上来）：

```bash
tesseract <图片绝对路径> stdout -l chi_sim+eng
```

- 输出重定向到临时文件再读回，超时 `waitFor(2 分钟)`，超时后 `destroyForcibly`
- **没有传 `--psm` 版面分析参数，没有做任何图像预处理**（灰度化、二值化、去噪都没有）
- **不解析置信度**，OCR 文本原样入库
- dHash 去重发生在 OCR **之前**（`VideoContextService`），`OcrUtils` 本身不感知重复

所以低对比度 PPT 或小字号代码的识别质量完全取决于 Tesseract 的默认行为，
这是当前的能力边界，不是调参能解决的。

⚠️ **不要说 OCR 是「图像识别」或「理解画面」**。OCR 是**字符识别**，
它把像素映射成字符，不理解字符的含义，更不理解画面里发生了什么。
把 OCR 说成视觉理解，面试官立刻会追问「那你能描述视频里的人做了什么动作吗」，
然后就会发现你其实没有这个能力——**这正好是 Q22 那道陷阱题的入口**。

### Q6：ASR、OCR、Embedding 和 LLM 分别怎么选？

我的选型原则是中文视频效果、成本、接入复杂度和结果可追溯性。ASR 通过托管接口调用 TeleSpeechASR，并按 60 秒切片获得稳定的分钟级时间范围；OCR 使用本地 Tesseract，适合字幕、PPT 和代码文字，复杂图表不是它的能力边界。Embedding 使用 BGE-M3，负责用户目标和 Chunk 摘要的语义相似度。Planner、Executor、Critic 通过 LangChain4j 接入 OpenAI 兼容网关上的 DeepSeek 系列模型。

**代码细节：型号和调用方式**

| 能力 | 型号 / 实现 | 配置项 | 调用方式 |
| :--- | :--- | :--- | :--- |
| LLM | `deepseek-ai/DeepSeek-V3.2` | `ai.deepseek.model` | LangChain4j `OpenAiChatModel` |
| Embedding | `BAAI/bge-m3`（1024 维） | `ai.embedding.model` | OkHttp `POST {base-url}/embeddings` |
| ASR | `TeleAI/TeleSpeechASR` | `ai.asr.model` | OkHttp multipart，字段 `file` + `model` |
| OCR | 本地 tesseract 命令行 | `tool.ocr.command` | `tesseract <img> stdout -l chi_sim+eng` |

LLM、Embedding、ASR **共用同一个 OpenAI 兼容网关**（`ai.deepseek.base-url`
默认 `https://api.siliconflow.cn/v1`，即 SiliconFlow），
所以三者共享一份 API Key（`ai.deepseek.api-key`）。

OCR 的具体调用（`utils/OcrUtils.java:31-35`）是
`tesseract <图片绝对路径> stdout -l chi_sim+eng`，输出重定向到临时文件再读回。
**没有传 `--psm`，也没有做图像预处理，不解析置信度**——OCR 文本原样入库。
超时是 `process.waitFor(2, TimeUnit.MINUTES)`（`:36-39`），超时后 `destroyForcibly`。

这里有个要主动说的边界：Q24 提到 dHash 去重，但**去重发生在 OCR 之前**，
`OcrUtils` 自己不感知重复；也没有任何图像增强（灰度化、二值化、去噪），
所以低对比度 PPT 或小字号代码的识别质量完全取决于 tesseract 的默认行为。

这四类能力都被封装在各自边界里。ASR 或 OCR 单路失败可以保留另一条证据；Embedding 或 Qdrant 不可用可以回退本地排序；LLM 输出必须经过结构和证据校验。这样更换模型时主要影响适配层和版本缓存，不需要重写 VideoContext、Agent 状态和业务流程。

### Q7：主要数据分别存在哪里？为什么这样分？

| 存储 | 存什么 | 角色 |
| :--- | :--- | :--- |
| MySQL | 用户、视频资产、最终结果、失败任务、Checkpoint | 业务事实与恢复真源 |
| MinIO | 完整视频、上传分片、关键帧 | 大二进制对象 |
| Redis | 上传进度、activeKey、限流状态、Checkpoint 热缓存、Trace、短期反馈 | 热数据 / 临时状态 |
| Qdrant | 按视频隔离的 Chunk 向量 | 语义召回 |

**代码细节：每一类数据的具体落点**

| 存储 | 表 / 对象 / Key |
| :--- | :--- |
| MySQL | `media_files`（含 `content_hash VARCHAR(64)`，`idx_media_content_hash` 普通索引）、`agent_checkpoints`（主键 `media_id + checkpoint_key`）、失败分析任务表 |
| MinIO | 完整视频（`media_files.file_path`）、上传分片 `chunk-uploads/{uploadId}/part-{i}`、关键帧前缀 `evidence-frames` |
| Redis | `upload:chunked:{uploadId}` / `:parts` / `:completed`；`analysis:active\|completed\|attempts\|context-owner:{...}`；`lock:analysis:{...}`；`limit:ai:user:{userId}` / `limit:ai:global`；`media:md5:{mediaId}`；`agent:checkpoint:{mediaId}`；`agent:feedback:{mediaId}`；`agent:trace:{traceId}` |
| Qdrant | 单集合 `video_chunks`（`vector.qdrant.collection`），**不是每个视频一个集合**；按 payload 的 `mediaId` 字段过滤做隔离 |

隔离方式值得单独说：Qdrant 里只有一个集合，检索时用
`filter.must[{key:"mediaId", match:{value:mediaId}}]` 限定范围（`QdrantVectorStore.java:87-99`）。
这样避免为每个视频建集合导致集合数量爆炸，代价是过滤依赖 payload 索引。

Qdrant 的向量维度**没有写死**，由第一次写入的 embedding 长度动态建集合
（`QdrantVectorStore.java:58`），距离度量固定 `Cosine`（`:163`）。BGE-M3 实际是 1024 维。

最重要的不是用了几个存储，而是冷热和事实边界：Redis 丢失只应该影响性能，不能让用户任务永久丢失；Qdrant 不可用只影响语义召回，不能阻断分析链路；大型二进制对象不进入 MySQL；最终状态不能只放缓存。

### Q8：大量代码由 AI 辅助生成，你自己的价值在哪里？

AI 帮我缩短了编码和方案搜索时间，但它不会替我确定业务边界。比如它可以列出线程池、MQ、分布式锁和向量库，但是否值得引入，要结合任务时长、失败成本、现有 Redis、视频复用粒度和用户目标来判断。

我的工作方式是先让 AI 展开候选方案，再用代码、官方接口和实际链路验证。AI 生成实现后，我重点检查状态推进顺序、异常是否被吞、重复请求会不会产生副作用、缓存是否被当成事实来源。几个能体现判断的例子：

- **上传为什么用 uploadId 而不是前端 MD5**：避免浏览器先完整读取 GB 级文件，也避免把用户提供的哈希当作授权和唯一身份。
- **分析幂等为什么是 contentHash + goalDigest**：同一视频不同目标不能互相复用 Plan 和结果。
- **Critic 失败后必须改变上下文**：否则第二轮只是把同一份 Prompt 再问一次。
- **先写 MinIO 再记 Redis**：否则存储失败会形成假上传。

这个项目能体现的不是我手写了多少行代码，而是我能不能识别 AI 代码里看起来合理但业务上不闭环的部分。

### Q9：当前项目还有哪些不足？

1. 这是**个人开源项目**，大量编码由 AI Coding 辅助完成，没有真实生产流量和线上 SLA，不能把本地 Demo 的设计能力包装成大规模生产验证。
2. 当前是**受控 Video Agent Workflow**，不是自主 Agent：模型不能任意创建工具、无限循环或访问视频之外的信息。
3. OCR 只提取文字，**不能理解动作、图表、表情和复杂公式**；简历口径是「语音、字幕及关键帧文本」，不是完整视觉理解。
4. Claim 校验包含确定性匹配和 LLM Critic，**不能证明每条结论语义上绝对正确**。
5. **没有真实实验数据**：4 个离线 Golden Case 是回归框架起点，尚未跑出可信结果，只能讲评测设计，不能编通过率或提升百分比。
6. Token 用量是字符规则估算；费用预算需要配置真实模型单价才有意义；时长预算只能在模型阶段之间检查。
7. 检索权重和 Top3 是工程折中，**没有低分动态扩大 TopK，也没有相邻 Chunk 自动扩展**。
8. 关键词检索是轻量 contains 匹配，不是成熟搜索引擎；跨视频规模增长后才考虑 BM25 和 Reranker。
9. 上传**没有秒传、没有前端分片 MD5 双端校验、没有 MinIO 生命周期自动清理**；合并跨 MinIO / MySQL / Redis 没有事务。
10. 断点续传依赖 Redis TTL（1 天），不是无限期；Redis 数据丢失后不能自动扫描 MinIO 重建上传状态。
11. 失败处理是**自建失败主题 + 失败任务表**，不是完整的 RocketMQ 原生 DLQ 运营体系。
12. Checkpoint 键没有编码 Prompt / Schema / Embedding 版本，版本升级可能局部失效。
13. ASR 单片和 OCR 单帧没有逐片持久化 Checkpoint，只在一次构建内部隔离失败。
14. 用户反馈目前主要保存在 Redis（30 天 TTL）参与指标统计，尚未形成长期评测数据集和自动优化闭环。
15. **ASR 的 60 秒分片是串行调用的**（`SegmentedTranscriptionService` 里的普通 for 循环），
    切片只降低了失败粒度、让时间戳可由序号推算，**没有提高吞吐**。两小时视频的 ASR 耗时是
    约 120 次调用的累加，这是当前最大的单点耗时。
16. **四组线程池参数硬编码在 `ThreadPoolConfig`**，不是配置项，调整并发度要改代码重新编译；
    也没有按用户等级或视频时长的差异化调度。
17. **消费组名在配置和注解兜底值上不一致**（`video-analysis-consumer` vs `video-analysis-group`），
    配置缺失时两边会对不上。
18. **单测只有 3 个测试类、4 个测试方法**（Checkpoint 修订状态机、执行时长预算、证据校验），
    其余链路没有自动化测试覆盖，回归主要靠人工。评测框架默认关闭且未在 CI 里运行。

这些边界不会推翻项目价值。当前目标是把长视频分析做成可检索、可校验、可恢复的后端工作流。等视觉理解需求、样本规模或多工具任务真正增长后，再分别引入视觉模型、扩充评测集和更通用的工具编排。

---

## 二、Planner-Executor-Critic 受控 Agent 工作流

### Q10：为什么一次大模型总结不够？

一次 Prompt 可以生成一段文字，但用户目标并不固定。同一个视频可能要课程笔记、会议纪要、操作步骤或观点核验。把所有要求直接塞进一次 Prompt 后，模型漏掉哪项要求、使用了哪段证据、为什么得到某个结论，程序都无法判断；结果不合格时也只能整篇重写。

我需要解决的是目标执行的可控性，所以把过程拆成计划、执行和校验。Planner 把目标变成可检查的任务，Executor 逐项完成并绑定证据，Critic 把缺失要求和无证据结论变成下一轮可执行反馈。这样后端能够知道任务进行到哪里、为什么失败、应该补哪部分。

### Q11：为什么它算 Agent，而不是连续调用三次 LLM？

判断标准不在调用次数，而在系统是否围绕目标维护状态并根据反馈改变后续动作。当前 Agent 保存 userGoal、Plan、检索上下文、AnalysisResult、CriticResult、轮次和预算；Critic 不通过后，程序会判断是补计划、补证据还是只重写结构，再决定是否进入下一轮。

准确措辞是**受控 Video Agent Workflow**：模型负责开放式理解和判断，程序负责工具边界、状态、预算、重试与终止。它没有完全自治，但在视频分析这个确定业务里已经具备目标、计划、工具、反馈和循环。

**代码细节：Agent 状态的字段**

状态本体就是 `dto/AgentState.java` 这个 record，只有五个字段：

```java
record AgentState(String goal, AgentPlan plan, AnalysisResult result,
                  CriticResult critique, int round)

record AgentPlan(String understoodGoal, List<String> tasks)

record CriticResult(boolean passed, List<String> feedback,
                    List<String> missingRequirements,
                    List<String> unsupportedClaims,
                    List<Long> requiredTimestamps)
```

产物字段来自 `dto/AnalysisResult.java`：

```java
record AnalysisResult(String title, List<String> conclusions, List<Evidence> evidence,
                      List<String> suggestions, List<Section> sections)

record Evidence(long timestampMs, String source, String content, String claim)
record Section(String key, String title, List<String> items)
```

`round` 是当前轮次，`result != null && critique == null` 正好表示「Executor 草稿已生成但还没校验」，
这就是下面 Q41 里「从 Critic 继续」的判据（`AgentLoopService.java:127`）。

### Q12：Planner、Executor、Critic 和确定性程序分别负责什么？

| 角色 | 职责 | 约束 |
| :--- | :--- | :--- |
| Planner | 理解目标 → 生成 1-5 个可验证任务 | 任务必须能靠当前视频证据完成；不能编「搜索互联网」这类无工具支持的任务 |
| Executor | 按 Plan + 相关 VideoContext 生成结构化结果（标题 / 结论 / 证据 / 建议） | 不能引入 VideoContext 之外的事实 |
| Critic | 检查目标覆盖、任务完成度、证据支持和结构完整性 | 输出 missingRequirements / unsupportedClaims / requiredTimestamps / feedback |
| 确定性程序 | 限制任务数、校验结构字段、检查证据时间戳与来源、决定是否刷新上下文、记 Checkpoint、统计 Token、预算终止 | 模型输出不稳定也不能随意改变系统状态 |

即使模型输出不稳定，系统状态也只能由确定性程序推进。

### Q13：AgentLoop 的完整执行顺序是什么？

```
1. 从完整 VideoContext 按 userGoal 粗召回相关 Chunk（避免 Planner 读整条长视频）
2. 加载目标级 Checkpoint：有合法 Plan 就复用，没有才调 Planner；Plan 结构不完整先修复
3. Executor 按 Plan 生成草稿，每条结论必须绑定证据；草稿先写 Checkpoint
4. Critic 校验 → 之后还有程序规则兜底
5. 通过 → 保存结果；未通过 → 按反馈类型分流：
   - missingRequirements（漏了用户目标）→ Replan
   - unsupportedClaims / requiredTimestamps（缺证据）→ 组成新查询，从完整上下文补相关片段
   - 纯 feedback（结构 / 表达问题）→ 带反馈重写
6. 进入下一轮，直到通过或达到终止条件（轮数 / 时长 / Token / 费用）
```

**代码细节：编排方法**

主入口 `AgentLoopService.run(mediaId, context, profile)`（`AgentLoopService.java:75`），
内部 `runWithinBudget`（`:89`）按顺序做五件事：

| 步骤 | 方法 | 行号 |
| :--- | :--- | :--- |
| 读终态 Checkpoint 并校验合法性 | `isPlanValid` / `isResultValid` | `:96-107` |
| 目标级粗召回 | `LongVideoContextService.selectRelevant` | `:109` |
| 解析或复用 Plan | `resolvePlan` | `:154` |
| 执行一轮（Executor + Critic） | `executeRound` → `critiqueRound` | `:179` / `:199` |
| 按 Critic 反馈改上下文和计划 | `contextForRetry` / `revisePlanForRetry` | `:367` / `:391` |

上下文变化的分流点在 `contextForRetry`（`:367-382`）：`requiresEvidenceRefresh` 为真才调用
`refineForCritique` 重新检索，否则直接沿用当前上下文（`criticRewriteOnlyRetries`）。
这正是 Q16 说的「上下文没有变化的循环不算有效修正」在代码里的位置。

### Q14：Planner 怎样保证任务可以执行？

Planner 有三条边界。任务必须能由当前视频证据完成，不能生成「搜索互联网评价」这种系统没有工具支持的任务；任务必须足够具体，后续能判断完成与否；任务数量被程序限制在 1 到 5 条，避免把一次视频分析拆成不可控的长计划。

如果 Planner 返回空任务、过长任务或缺少目标理解，系统会调用一次计划修复；Critic 后续发现目标遗漏时，会把 missingRequirements 交回 Planner 修订。修订失败时保留旧计划继续处理，避免一次重规划错误破坏已有结果。

**代码细节：Planner 的三条边界落在哪**

- 任务数上限：`AgentLoopService.MAX_PLAN_TASKS = 5`（`:24`）。
- 任务可执行性：`isPlanValid`（`:251-257`）要求 `understoodGoal` 非空、`tasks` 非空且 ≤5、
  每个任务非空且长度 ≤500。注意这里**只能校验结构，不能校验任务是否真的可由当前视频证据完成**——
  那条边界靠 Prompt 约束 + Critic 事后纠偏，不是程序强校验，这点要讲准确。
- 一次修复：`resolvePlan`（`:163-171`）在 Plan 非法时调用 `deepSeekUtils.repairPlan`，
  并计 `planStructureRepairs`。

当前 Plan 只有目标理解和任务列表，没有「语音依赖 / 视觉依赖」这样的字段，不能把这类设计设想说成已实现。另外要讲准确：Planner 确实拆出了多个任务，但第一版没有为每个任务启动独立 Executor 或子 Agent，而是一个受约束的 Executor 在一次调用中完成整份计划，减少调用和状态编排成本。

### Q15：Claim 级证据校验到底校验什么？

我把校验拆成三层，前两层是确定性程序，第三层是 LLM：

1. **结构绑定**：每条 conclusion 都必须有对应的 evidence，且 `normalize(claim) == normalize(evidence.claim)`，防止给了一堆引用却不知道支持哪条结论。
2. **证据存在性**：
   - 时间戳是否落在某个 VideoSegment 的 `[startMs, endMs)` 内；
   - 来源必须包含 ASR 或 OCR，且只到对应文本里查证据；
   - 证据原文经过规范化（去标点、符号、空白并统一小写）后，是原文的**包含关系**。
3. **语义支持**：由 LLM Critic 判断证据是否足以推出结论、是否遗漏目标、是否存在逻辑冲突。这是唯一不能确定性保证的一层。

**代码细节：前两层就是 `EvidenceVerificationService` 的三个方法**

```java
boolean timestampCovered(VideoContext, Evidence)   // 时间戳落在 [startMs, endMs) 内，左闭右开
boolean supported(VideoContext, Evidence)          // 来源 + 原文包含关系
boolean supportsClaim(VideoContext, claim, Evidence)// 先做 claim 等值，再委托 supported
```

逐个说清：

- **结构绑定**在 `supportsClaim`（`EvidenceVerificationService.java:28-35`）：
  `normalize(claim).equals(normalize(evidence.claim()))`，两边都归一化后必须完全相等。
- **来源校验**在 `supported`（`:17-26`）：`source` 转大写后必须含 `ASR` 或 `OCR`，
  否则直接 false。然后按来源拼出候选原文——只含 ASR 就只给 `transcript`，只含 OCR 就只给
  `String.join(" ", ocrTexts)`，两者都有才合并（`sourceText`，`:41-47`）。
- **原文包含**在 `textMatches`（`:49-55`）：**方向是「原文包含证据」，不是「证据包含原文」**，
  即 `normalize(candidate).contains(normalize(evidence))`。
- **归一化**（`:57-61`）：`toLowerCase(ROOT)` 后 `replaceAll("[\\p{P}\\p{S}\\s]+", "")`，
  也就是去掉所有标点、符号和空白。

这个方向性有个直接后果值得主动讲：证据文本越短越容易通过，所以 Critic 的程序兜底只能拦住
「伪造原文」和「张冠李戴」，**拦不住「拿一句真实但不相关的原文去支持一个过强的结论」**——
后者只能靠 LLM Critic 的语义层。

前两层可以稳定拦截伪造时间戳、错误来源和不存在的原文；第三层仍然存在模型误判。因此我**不会说 Claim 校验消除了幻觉**，而会说它把无依据结论变成了可检测、可追踪、可重试的问题。

Critic 与 Executor 使用同一模型确实可能共享盲区，所以质量不能完全交给模型自检。当前方案用程序规则兜住结构和证据存在性，再由 Critic 处理语义；质量要求更高时可以换不同 Critic 模型或扩大人工评测集，但第一版不为了 Multi-Agent 形式增加成本。

**代码细节：每个节点向模型要的确切 JSON 字段**

这是最有说服力的「我确实控制了模型输出」的证据。所有 Prompt 都在 `utils/DeepSeekUtils.java`，
每个节点都有自己的 stage 名，并要求返回严格 JSON：

| 节点 | stage | 要求的 JSON 字段 |
| :--- | :--- | :--- |
| Planner / Replan / 计划修复 | `PLANNER` / `REPLANNER` / `PLANNER_REPAIR` | `{"understoodGoal": "...", "tasks": ["任务1", ...]}` |
| 检索意图 | `RETRIEVAL_PLANNER` | `{"semanticQuery": "...", "keywords": [...], "visualKeywords": [...]}` |
| 模式路由 | `MODE_ROUTER` | `{"mode": "GENERAL", "reason": "..."}` |
| Chunk 摘要 | `CHUNK_SUMMARY` | `{"segmentSummary": "...", "keywords": [...]}` |
| Executor | `EXECUTOR` | `{"title", "conclusions", "evidence":[{"timestampMs","source","content","claim"}], "suggestions"}`，有模式指令时**额外**要求顶层 `"sections":[{"key","title","items"}]` |
| Critic | `CRITIC` | `{"passed", "feedback", "missingRequirements", "unsupportedClaims", "requiredTimestamps"}` |

几个值得主动讲的点：

- `evidence.source` 被 Prompt 限定为 **`ASR` / `OCR` / `ASR+OCR`** 三选一，
  正好对应 `EvidenceVerificationService.supported` 里 `source.contains("ASR")` 的判断。
- Executor 的 `sections` 是**条件要求**的：`executeSuffix`（`:357-363`）只在传了模式指令时
  追加这段要求。所以 GENERAL 模式不产生 sections，`requiredSectionKeys` 也是空的
  （见 Q19 表格），两边是配套的。
- Critic 的 Prompt 要求 `requiredTimestamps` 在**不需要补证据时返回空数组**——
  这个约束很关键，因为 `requiresEvidenceRefresh`（`AgentLoopService:384-389`）
  把「非空」直接当作「需要刷新上下文」的触发条件，模型随手填充时间戳会导致每轮都重新检索。
- 所有调用都带同一个 `SYSTEM_POLICY` 系统消息（`:37-45`），把用户内容标记为**不可信证据**。

### Q16：Critic 不通过后，系统怎样真正改变下一轮？

Critic 必须返回结构化失败原因，程序按类型分流：

| 反馈类型 | 含义 | 程序动作 |
| :--- | :--- | :--- |
| missingRequirements | 用户要求或计划任务被遗漏 | **Replan**，把缺失交给 Planner 修订 |
| unsupportedClaims | 某条结论没有足够证据 | 把它作为**新的检索查询** |
| requiredTimestamps | 需要特定时间范围的原文 | 绕过摘要，**直接加载该时间附近的原始 Segment** |
| feedback | 证据已足够，只是写法或结构要改 | 沿用当前上下文重写 |

第二轮上下文由三部分合并：**上一轮证据 + Critic 指定时间附近的 Segment + 新查询召回的 Chunk**，并且仍受上下文长度预算限制。只有出现证据缺口时系统才调用检索层；任务遗漏先 Replan；纯结构问题沿用当前上下文重写。

所以反馈重执行的关键不在「多调用一次模型」，而在 Critic 的输出会改变下一轮的计划、查询或证据。**上下文没有变化的循环不算有效修正。**

### Q17：结构化输出失败、证据不足或两轮后仍未通过怎么办？

- **JSON 非法**：使用相同任务要求再做一次严格格式重试；仍无法解析就抛出节点异常，由 Checkpoint 和任务级重试接管，**不会拿空对象伪装成功**。
- **证据不足**：保留可以确认的部分，对无法确认的 Claim 给出警告，**不允许根据常识补齐视频里没有的信息**。
- **达到最大轮次**：保存带警告的最佳结果，同时保留 Critic 反馈供用户查看和修正。

**代码细节：JSON 重试和「不伪装成功」分别在哪**

- **JSON 非法**：`DeepSeekUtils.structuredChat`（`:380-388`）捕获解析失败后，
  追加一句「请严格返回合法 JSON，不要添加解释或代码块。」**再问一次**，并计
  `structuredOutputRetries`。所以是一次格式修复，不是无限循环。
  两次都失败则由 `parseJson` 抛 `IllegalStateException("模型未返回 JSON 对象")`（`:375`）。
- **模型调用本身的重试**是另一层：`chat()`（`:392`）最多 `MAX_MODEL_ATTEMPTS = 3` 次（`:35`），
  退避 `Thread.sleep(1_000L << attempt)`（`:467`）即 1 秒、2 秒。
  可重试判定在 `isRetriableModelFailure`（`:450-463`）：HTTP 状态为 408 / 429 / ≥500 可重试，
  其余 4xx 视为永久错误，抛 `IllegalArgumentException("模型请求不可重试")`。
- **不伪装成功**：`AgentLoopService.validateResult`（`:259-263`）在结果结构不完整时直接抛
  `IllegalStateException("Executor 未生成完整结构化结果")`，不做兜底填充。
- **达到轮次**：`critiqueRound`（`:219-231`）在 `round >= maxRounds` 且未通过时，
  阶段标记为 `TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS`，
  对应 `saveResult` 落库的 warning 语义（`AgentCheckpointService.java:127-128`）。

调用异常进入 Checkpoint 与 MQ 恢复；预算耗尽属于主动终止，进入独立终态 `BUDGET_EXHAUSTED`，不再交给 MQ 重试，因为重试不会改变预算条件，只会继续消耗资源。

### Q18：为什么不能只设置「最多两轮」？

轮次只能限制循环次数，不能覆盖单次模型调用过慢、上下文过大或模型价格变化。所以系统同时设置四个预算：**最大轮数、最大执行时长、预估 Token、预估费用**。每个关键模型阶段结束后读取当前消耗，任一条件超过上限就终止。

**代码细节：四个预算的配置项和默认值**

| 预算 | 配置项 | 默认值 | 在代码里的位置 |
| :--- | :--- | :--- | :--- |
| 最大轮数 | `agent.budget.max-rounds` | 2 | `AgentLoopService.java:43` |
| 最大执行时长 | `agent.budget.max-duration-ms` | 120000（120 秒） | `:44` |
| 预估 Token | `agent.budget.max-estimated-tokens` | 50000 | `:45` |
| 预估费用 | `agent.budget.max-estimated-cost` | 0（关闭） | `:46` |

检查点是 `checkBudget(startedNanos, completedStage)`（`:445-460`），只做
`>` 比较，任一超限就抛 `BudgetExceededException`。费用预算为 0 时该分支被跳过
（`:454`），所以默认配置下**费用预算实际不生效**。

时长预算其实有**两层**，不要只讲一层：

1. `AgentExecutionBudget`（`AgentExecutionBudget.java:10-45`）用 `ThreadLocal<Long>` 保存
   deadline，`open(maxDurationMs)` 取「当前剩余」和「新请求」的较小值（`:21`），
   支持嵌套收窄。`DeepSeekUtils` 每次模型调用用它算自己的超时：
   `min(modelTimeoutMs, AgentExecutionBudget.remainingMillis())`（`DeepSeekUtils.java:420-421`）。
   **这是唯一能在阻塞式 HTTP 调用内部生效的预算**。
2. `AgentLoopService.checkBudget` 只在模型阶段之间做检查。

当前默认最多两轮，是因为第一轮生成、第二轮定向修正已经能形成最小闭环。

诚实边界（要主动说）：时长检查只能发生在模型阶段之间，不能强行中止已经发出的阻塞式 HTTP 请求；Token 是字符规则估算，不是精确计费；费用预算只有配置真实模型单价后才有约束意义。

### Q19：四种分析模式和自动路由怎么工作？

四种模式各自是一份 ModeProfile（计划 / 执行 / Critic 三段指令 + 必须的 section key），注册在 ModeRegistry 中：

- `GENERAL`：结论 + 证据 + 建议
- `LEARNING`：大纲 + 自测题 + 易错点
- `REVIEW`：逻辑漏洞 + 存疑结论
- `CREATION`：爆点 + 标题 + 口播脚本

`AUTO` 是**纯前端概念**：提交前先调路由接口，由 ModeRouter 让 AI 判定模式，再按具体模式提交。AUTO 本身永远不进入后端带 key 的接口，从根上避免读写端 key 不对称。路由不可用时回退 GENERAL，不阻断分析任务。

**代码细节：四个模式的 requiredSectionKeys**

`dto/AnalysisMode.java:13-22` 只有四个枚举值，顺序是 `GENERAL, LEARNING, REVIEW, CREATION`。
每个模式在 `ModeRegistry`（`service/mode/ModeRegistry.java:24-65`）注册一份
`ModeProfile(mode, displayName, planInstruction, executeInstruction, criticInstruction, requiredSectionKeys)`：

| 模式 | displayName | requiredSectionKeys |
| :--- | :--- | :--- |
| `GENERAL` | 通用分析 | `[]`（空，只要求通用字段） |
| `LEARNING` | 学习复习 | `["outline","keypoints","quiz","pitfalls"]` |
| `REVIEW` | 内容审查 | `["fallacies","exaggerations","omissions","doubtful"]` |
| `CREATION` | 内容创作 | `["highlights","titles","intro","script"]` |

`requiredSectionKeys` 会被程序强校验：`isResultValid`（`AgentLoopService.java:265-280`）要求
`result.sections()` 里确实存在这些 key 且 `items` 非空，
缺失时由 `enforceStructureBounds`（`:340-365`）写进 Critic 的 feedback 触发重写。
所以模式不只是换 Prompt，**结构约束是程序在兜**，模型漏段落会被判不通过。

`ModeRegistry` 构造时做启动自检（`:60-64`）：任何 `AnalysisMode` 没注册 Profile 直接抛
`IllegalStateException`，避免新增模式后静默回退。

**路由的两套限流**（`service/mode/ModeRouter.java:34-35`）：
`limit:ai:route:user:{userId}` 每分钟 10 次、`limit:ai:route:global` 每分钟 60 次，
与分析任务的 5 / 30 是两组独立的配额。路由**从不抛异常**：配额耗尽、Redis 异常、
目标为空、LLM 异常四种情况都返回 GENERAL 并附带可展示的 reason（`:70-76`），
让路由故障永远不阻断分析。

### Q20：如何证明 AgentLoop 优于单次 Prompt？

我会固定同一批 VideoContext 和用户目标，比较单次 Prompt、Planner + Executor、完整 P-E-C 三组。结果指标看结构完整率、目标覆盖、时间戳覆盖、证据支持和 Claim 证据支持；过程指标看模型调用次数、平均轮次、耗时、Token 和预算终止次数。

仓库提供 4 个最小 Golden Case，覆盖多模态互补、纯语音、纯画面和音画冲突，评测入口会走真实 AgentLoop。它现在只是回归框架，还没有实际运行数据，所以面试中只能讲评测设计，不能声称已经达到某个通过率。后续应增加人工标准答案、困难负样本和跨模型回归，避免用同一模型既生成又自证。

**代码细节：4 个 Golden Case 和评测指标**

用例定义在 `src/main/resources/evaluation/golden-video-tasks.json`，四个用例的 `userGoal`
和 `expectedKeywords` 均可直接引用：

| # | name | userGoal | expectedKeywords |
| :--- | :--- | :--- | :--- |
| 1 | 课程视频-ASR与OCR互补 | 生成二叉树前序遍历学习笔记，给出步骤和证据 | 前序遍历 / 根节点 / 左子树 / 右子树 |
| 2 | 会议视频-纯语音待办 | 整理会议结论、负责人和截止时间 | 七月二十五日 / 灰度发布 / 张明 |
| 3 | 操作录屏-纯画面步骤 | 提取画面中的构建命令和输出目录 | npm run build / dist |
| 4 | 音画冲突-保留不确定性 | 核验视频中的最终上线日期，存在冲突时明确指出 | 8月1日 / 8月3日 / 冲突 |

指标由 `AgentEvaluationService.evaluate`（`:30-95`）在 `LinkedHashMap` 里产出：
`structuredValid`、`timestampCoverageRate`、`evidenceSupportRate`、`claimEvidenceSupportRate`、
`criticPassed`、`userAcceptanceRate`、`feedbackSamples`。

**跑法**：`agent.evaluation.enabled=true` 时 `OfflineAgentEvaluationRunner` 作为
`ApplicationRunner` 在启动时加载 JSON 并逐条 `agentLoopService.run(context)`（`:52-56`）。
单条判定成功的条件是三项**同时**满足（`:59-61`）：`structuredValid == true`
且 `claimEvidenceSupportRate >= 0.8` 且 `keywordCoverage >= 0.8`
（`keywordCoverage` = `expectedKeywords` 在 `result.toMarkdown()` 里的小写子串命中率）。

要主动说清两个边界：**默认关闭**（`agent.evaluation.enabled` 默认 `false`，`application.properties`），
所以仓库里那 4 个用例从未在 CI 里跑过；**没有人工标准答案**，判定完全靠关键词包含率和
自证的证据支持率，这也是 Q20 说「不能编通过率」的原因。

---

## 三、ASR 与关键帧 OCR 构建多模态 VideoContext

### Q21：为什么纯 ASR 不够？VideoContext 具体是什么？

课程、会议和操作录屏的信息不只存在于语音。老师说「看这个公式」时 ASR 只能得到一句指代；代码演示可能不讲解，但屏幕里出现了命令和报错；PPT 还可能展示日期、负责人和关键结论。**输入阶段已经丢失的信息，后面换更强模型也补不回来。**

所以我把语音和关键帧文字当作两类独立证据。VideoContext 是项目内部定义的统一时序模型，不是某个框架提供的行业标准。它由多个 VideoSegment 组成，每个 Segment 保存起止时间、ASR 转写、OCR 文本和关键帧引用。后续检索、Planner、Executor 和 Critic 只理解 VideoContext，不需要分别适配 ASR、OCR 和 FFmpeg 的原始返回格式。

### Q22：这个项目是不是只理解口播内容？你做过基于画面内容的理解吗？

⚠️ **这是全篇最危险的引导性问题，两种顺口答法都会翻车：**

- 顺着答「对，主要是口播内容」→ *低估了自己的项目*，OCR 那条线和独立视觉评分通道白做了
- 硬答「画面也理解」→ 面试官一问「那你能描述视频里的人做了什么动作吗」就当场崩

**正确答法是先把提问的二分法拆掉：把「画面里的文字」和「画面的语义」切开。**

> 要分两层说，因为「理解画面」这个词把两件难度完全不同的事混在一起了。
>
> **画面里的文字，我是做了的。** 关键帧抽出来跑 Tesseract OCR，PPT 标题、代码、
> 命令行、报错信息、字幕都会被提取成文本证据。它和 ASR 转写是**两条独立通道**，
> 检索时视觉关键词还是独立的一路打分（权重 0.15），回答的是「画面里写了什么」。
>
> **画面的语义我没做。** 人物的动作、表情、手势、复杂图表、公式的视觉含义，
> 这些需要视觉模型，我的方案里没有。**OCR 是「读字」，不是「看图」。**
>
> 所以准确的说法是：**这个项目做的是「语音 + 画面文字」的多模态理解，
> 不是完整的视频视觉理解。**

**然后主动讲为什么这么选**（这一步决定了你是「做不到」还是「权衡过」）：

> 这是有意的取舍。课程、会议、操作录屏这三类视频，信息绝大部分承载在**语音和屏幕文字**上。
> 老师说「看这个公式」时 ASR 只能拿到一个指代，但屏幕上往往就写着那个公式；
> 代码演示可能全程不讲解，但命令和报错都在画面里。
> **用 OCR 覆盖这类场景的性价比，远高于为每个关键帧引入视觉模型推理。**
>
> 反过来说，如果需求变成「理解实验操作的动作是否规范」或者「读懂 K 线图的走势」，
> OCR 就完全无能为力，那时候必须用视觉模型。

**最后给升级路径**（这句是加分项，把「没做」变成「知道什么时候该做」）：

> 合理的升级方式不是全量关键帧都调视觉模型——那等于把成本乘上关键帧数量。
> 而是**定向调用**：比如 REVIEW 模式下发现某条结论缺少语音支撑，
> 只对那几帧调视觉模型；或者对 OCR 置信度低、文字极少的帧做二次理解。
> 这样成本和收益都在可控范围。

**口径要对齐简历**（和 Q9 第 3 条一致）：

> 简历写的是「语音、字幕及关键帧**文本**」，用的是「文本」不是「视觉理解」，
> 这个措辞是刻意的，就是为了不被理解成完整的视频视觉理解。

⚠️ 如果面试官追问「那你在别的项目里做过视觉吗」——**只答真实做过的**，
不要为了这个问题临时把视觉经验往这个项目上靠。这个项目的边界就是清楚地到此为止。

### Q23：VideoContext 从视频开始怎样完整构建？

```
1. 查视频级 Checkpoint，有则复用（跨目标共享，避免重复烧钱）
2. 建临时工作目录，ASR 与 关键帧 OCR 提交到两个独立有界线程池，并行执行
3. 语音分支：FFmpeg 抽音频 → 编码 MP3 → 按 60s 切片 → 逐片调用 ASR
4. 视觉分支：抽帧（三层机制）→ dHash 去重 → Tesseract OCR（中英文语言包）→ 关键帧传 MinIO
5. 按 60s 窗口融合两路：左闭右开，ASR 按开始时间进窗，OCR 帧按时间点进窗
6. 生成 ASR-only / OCR-only / 双模态 三种 Segment
```

**代码细节：两路分支的提交和汇合**

入口是 `VideoContextService.build(videoPath, userGoal, traceId)`（`:70`），两路各提交一个
`Future`：

```java
Future<BranchResult<TranscriptSegment>> = submitBranch(asrExecutor, ...)   // :79
Future<BranchResult<FramePart>>        = submitBranch(ocrExecutor, ...)   // :84
```

关键设计是**分支不向外抛异常，而是返回 `BranchResult<T>(List<T> items, Exception error)`**
（`:360-372`）。`submitBranch` 里用 `try/catch` 把异常收进结果（`:171-179`），
所以 `finishContext`（`:145-160`）能同时看到「一边成功一边失败」并继续：
两路都失败才抛 `IllegalStateException("ASR 和 OCR 分支均失败")`，
任一路失败计 `asrBranchFailures` / `ocrBranchFailures` 并继续，最后
`segments.isEmpty()` 才判定整体失败（`:162`）。

截止时间是两路**共享**的同一个 deadline：`System.nanoTime() + 60 分钟`（`:90`），
`awaitBranch` 用 `future.get(remainingNanos, NANOSECONDS)` 逐路等待（`:186-191`）。
超时后 `cancelBranches` 会 `cancel(true)` 中断两路，并**最多等 10 秒**
（`branchesFinished.await(10, SECONDS)`，`:198`）让分支自己退出；
10 秒内没退干净就保留工作目录不删（`cleanupWorkDir=false`，`:113`），避免删掉仍在写的文件。
这是 60 分钟预算之外的第二个隐性边界。

`VideoContext.VideoSegment` 的字段是：

```java
record VideoSegment(long startMs, long endMs, String transcript,
                    List<String> ocrTexts, List<String> evidenceFrames)
```

`evidenceFrames` 存的是 MinIO 上的关键帧地址；帧上传失败时退化为
`videoPath + "#timestampMs=" + timestampMs`（`:255`）。

语音分支中，音频文件序号直接映射为起止时间，例如第二段对应 60 到 120 秒。视觉分支中，关键帧上传 MinIO 失败时会退化为原视频时间戳引用。两路都失败，或者最终没有任何有效 Segment，VideoContext 才整体失败。工作完成后删除本地音频、图片和 FFmpeg 日志等临时文件。

### Q24：场景变化检测、保底抽帧和感知哈希分别解决什么？

这里不是自己训练视觉模型，而是使用 FFmpeg `select` 过滤器提供的场景变化分数。FFmpeg 解码时比较当前画面与前面画面的视觉差异，得到归一化的 scene score。当前代码满足三个条件之一就选帧：第一帧必须保留；场景变化分数大于 0.35 时立即选帧；距离上一次选帧已经达到 30 秒时强制保底。

三层机制分别解决不同问题：

1. **场景变化检测（> 0.35）**：抓 PPT 翻页、窗口切换和页面跳转，减少固定频率抽帧产生的大量重复图片。
2. **30 秒保底**：兜住缓慢板书和长时间静态页面——它们可能一直达不到变化阈值。
3. **dHash 感知哈希去重**：把图片缩放成 9×8 灰度图，逐行比较相邻像素亮度生成 64 位差异哈希，再与上一张图片异或统计不同位数；汉明距离不超过 5 时认为高度相似，跳过 OCR。这样一页静态 PPT 即使被保底多次抽到，也不会反复识别。

**代码细节：抽帧的完整命令行和阈值**

一条 FFmpeg 命令同时实现三层机制（`VideoContextService.java:211-216`）：

```
ffmpeg -y -i <video> \
  -vf "select=eq(n\,0)+gt(scene\,0.35)+gte(t-prev_selected_t\,30),showinfo" \
  -vsync vfr frame_%06d.jpg
```

三个条件是**加号相或**：`eq(n,0)` 第一帧、`gt(scene,0.35)` 场景变化、
`gte(t-prev_selected_t,30)` 距上次选中 30 秒。这三个常量在代码里分别是
硬编码的 `0.35` 和 `30`（`:213`），只有 `FALLBACK_FRAME_INTERVAL_MS = 30_000L`
（`:42`）和 `SEGMENT_MS = 60_000L`（`:41`）是命名常量——**0.35 是字符串里写死的，改它要改命令行**。

dHash 的实现（`differenceHash`，`:286-305`）：缩放到 **9×8** 灰度图，
逐行比较相邻像素（`getRGB(x,y) > getRGB(x+1,y)`）生成 **64 位**哈希；
`Long.bitCount(previousHash ^ imageHash) <= 5` 判为重复并跳过 OCR（`:228`）。
注意哈希是**只和上一张保留的帧比**，不是和全部历史帧比。

`showinfo` 打出的 `pts_time` 由正则 `pts_time:([0-9.]+)` 解析（`:43`、`:334-340`），
乘 1000 转毫秒。这里有个**兜底**要讲：如果日志里解析到的帧数少于实际抽出的帧数
（`i >= timestamps.size()`），时间戳退化为 `i * 30_000`（`:232`）——由序号推算，不再精确。

`showinfo` 同时在日志里记录每帧的 `pts_time`，Java 解析并换算成毫秒，所以 OCR 文本最终能回到原视频时间轴。

0.35 和汉明距离 5 是课程视频的工程起点，不是行业标准；鼠标移动、动画和镜头抖动仍可能产生噪声。验证时应在 PPT、板书和操作录屏上标注重要页面，比较关键页面召回、重复帧比例、OCR 调用数和漏帧率，再调整阈值。

关于「有没有调研过更好的场景分割算法」：**场景检测这里没有自研**，直接用 FFmpeg 内置的 scene score。原因是它零额外依赖、和已经在用的 FFmpeg 是同一条命令，够用。如果要做更准的镜头分割，可选方向有两类：一类是传统方法，比如 PySceneDetect 的内容感知 / 阈值检测；另一类是基于深度学习的镜头边界检测，比如 TransNetV2。它们通常比 FFmpeg 的场景分数更准，代价是引入额外依赖或模型推理成本。当前课程视频以 PPT 翻页为主，变化明显，所以先用零成本的方案；如果验证发现关键页面漏召回，再考虑换算法。

### Q25：场景变化检测的底层原理是什么？

⚠️ **这题是 Q24 的深水区，也是基础概念题，文档其他地方没有。**
它和 Q24 末段「调研过别的算法吗」是同一个考点的两半，要连起来准备。

**第一段必须主动界定自己的理解深度**，不要装作读过源码：

> 这里**我没有自研**，用的是 FFmpeg `select` 过滤器内置的 `scene` 变量。
> 我理解它的原理是**亮度平面的帧间绝对差**：把当前帧和前一帧的 Y（亮度）平面
> 逐像素做差、求和，再按像素数归一化到 0~1 区间，得到一个 scene score。
> 本质是一个**帧间差分度量**。
>
> 我要说清楚边界：**我没有读过 FFmpeg 的源码**，这是我从它的行为和文档里理解到的，
> 不是我能逐行指认的实现细节。如果面试官追问具体的归一化公式或者有没有做
> 下采样，我会直接说我不确定，而不是编一个。

⚠️ **绝对不要编公式。** 编一个错的公式比说「我没读过源码」糟糕得多——
前者是诚信问题，后者只是知识边界。

**第二段：主动说出这个原理的局限。** 这一步比背原理更值钱：

> 正因为它是**无运动补偿的整帧差分**，对三类情况会误判：
>
> 1. **全局亮度突变**——开灯、关灯、闪光、曝光调整，整帧像素一起变，差分值很大
> 2. **镜头抖动或手持拍摄**——画面整体位移，像素差大幅上升
> 3. **画面内物体大幅移动但内容没变**——比如鼠标快速划过、讲师在镜头前走动
>
> 它**不是**直方图比较（直方图对整体位移更鲁棒），也**不是**基于颜色分布，
> 更不是模型——就是像素级差分。
>
> 我选它是因为课程视频以 PPT 翻页为主，变化干净、差异明显，这三类问题都不突出。
> **换成手持拍摄的 vlog，0.35 这个阈值就不 work 了。**

**第三段：阈值是怎么来的，要诚实**：

> **0.35 是经验值，不是调参调出来的。** 代码里它是硬编码在 FFmpeg 命令字符串里的，
> 不是配置项——改它要改命令行。
>
> 真要验证，应该在 PPT、板书、操作录屏三类素材上标注重要页面，
> 比较**关键页面召回率、重复帧比例、OCR 调用数和漏帧率**，再回头调阈值。
> 这个实验我还没做，所以我说不出「0.35 比 0.25 好多少」。

**第四段：调研过的替代方案**（接 Q24 末段）：

| 方向 | 代表 | 相对 FFmpeg scene score 的优势 | 代价 |
| :--- | :--- | :--- | :--- |
| 传统方法 | PySceneDetect（内容感知检测 / 阈值检测） | 能区分渐变与突变，对整体亮度变化更鲁棒 | 额外依赖 |
| 深度学习 | TransNetV2 等镜头边界检测模型 | 准确率明显更高，能处理复杂转场 | 模型推理成本 |

> 我选 FFmpeg 内置方案的原因是**零额外依赖**，和已经在用的 FFmpeg 是同一条命令。
> 而且抽帧这一层是隔离的——如果验证发现关键页面漏召回，换成 PySceneDetect
> 只改 `extractKeyFrames` 一个方法，不影响 OCR、融合和下游任何东西。

### Q26：为什么 ASR 固定切成 60 秒？融合窗口为什么也是 60 秒？

当前托管 ASR 返回文本，但不能稳定提供句子级时间戳，所以固定切片给每段转写建立最低限度的时间范围。切得太短会让两小时视频产生大量网络请求，增加失败率和重试成本；切得太长会让定位过粗，后续检索和 Critic 很难把结论落到具体片段。

60 秒是请求数量和证据粒度之间的折中，**只能保证分钟级定位**，我不会说成句子级精确证据。以后 ASR 能稳定返回句子或词级时间戳时，可以保持 VideoContext 结构不变，只替换更细的 TranscriptSegment。

融合时用时间戳整除 60 秒得到窗口起点，窗口采用左闭右开，例如恰好位于 120 秒的帧只进入 120 到 180 秒，不会同时属于前后两个片段。先建立统一窗口再填充两路数据，可以自然保留没有语音但有画面文字的 OCR-only Segment，以及只有语音的 ASR-only Segment。

**代码细节：分片命令和 ASR 重试**

切片命令在 `SegmentedTranscriptionService.java:83-90`：

```
ffmpeg -y -i <video> -vn -acodec libmp3lame \
  -f segment -segment_time 60 -reset_timestamps 1 audio_%03d.mp3
```

`-reset_timestamps 1` 让每片时间戳从 0 重新开始，所以分片序号可以直接映射时间：
第 `i` 片对应 `[i*60000, (i+1)*60000]`（`:50`），**不依赖 ASR 返回任何时间信息**。

ASR 的重试在 `AliyunAsrUtils`（`:24`、`:52-63`）：`MAX_ATTEMPTS = 3`，
退避 `Thread.sleep(1_000L << attempt)` = 1 秒、2 秒。可重试判定很窄：
只有 `IOException` 会被重试，其中 HTTP **429 和 ≥500** 被包装成
`RetryableAsrException extends IOException`（`:85-86`），
**其他 4xx 抛 `IllegalArgumentException`，不重试**（`:90`）；空文本
抛 `IllegalStateException("ASR 返回空文本")`，也不重试。
对比之下 LLM 侧是「408/429/≥500 可重试」，两边口径一致但实现独立。

**如果 60 秒刚好切在话中间怎么办？要主动承认：当前是硬切，没有重叠窗口。** 切片用的是 FFmpeg `-f segment -segment_time 60 -reset_timestamps 1`，相邻切片之间没有 overlap，所以一句话被切断时，前半句和后半句会分别落在相邻两个 Segment 里。这带来两个影响：单看某一个 Segment，语义可能是残缺的；但因为两个 Segment 在时间上相邻，检索命中其中一段时通常也能带出另一段，所以影响有限。改进方向是给切片加 5 到 10 秒重叠，或者接入能返回句子级时间戳的 ASR，按语义边界切分。

**错别字同样没有自动纠正环节。** ASR 常见的同音字、专有名词和英文缩写误识别，OCR 常见的空格、重复字符和复杂版式误识别，目前都只做文本规范化（去标点、空白、统一大小写）用于匹配和生成，**不会去改原文**。这是有意的：清洗后的文本可以用于检索和生成，但原始转写和关键帧必须保留，否则 Critic 和用户就无法回到原视频核验。真要纠错，合理做法是单独引入一层带词典或 LLM 的纠错，并把纠错结果和原文分开存储，而不是覆盖证据。

### Q27：为什么使用 CompletableFuture，而不是再拆两个 RocketMQ Topic？

RocketMQ 负责整个视频分析任务离开 Web 请求线程，ASR 和 OCR 是同一任务内部需要汇合的两个分支。当前没有独立部署和扩缩容诉求，使用 CompletableFuture 配合两个专用线程池就能并行，总耗时接近较慢分支，而不是两路耗时相加。

拆成两个 Topic 后还要增加子任务状态、结果聚合器、超时收敛、重复消息和一边成功一边失败的协调。当前规模下这些分布式编排成本没有收益。等两路需要不同硬件、由不同服务维护或需要独立扩容时再拆服务。

当前也没有使用公共 ForkJoinPool：ASR 使用独立有界线程池控制网络调用，OCR 根据机器 CPU 数量配置有界线程池，队列满时直接拒绝，避免无上限堆积。

**代码细节：这里有两层「并行」，粒度不同，别混为一谈**

1. **视频级**：`VideoContextService` 把「整条 ASR 分支」和「整条 OCR 分支」分别提交到
   `asrExecutor` 和 `ocrExecutor`（`:79-88`）。**这一层是并行的**，所以总耗时接近较慢的那一路。
2. **分片级**：`SegmentedTranscriptionService.transcribe` 内部是**串行 for 循环**
   （`:44-58`），一个 60 秒切片调完 ASR 才调下一个。

所以准确说法是「**语音分支和视觉分支并行，但语音分支内部的分片是串行的**」。
Q91 里「长视频的 ASR 按 60 秒切片并发提交」这句话与代码不符，应改为：
长视频被切成多个 60 秒分片**逐个**调用 ASR，切片的作用是把失败范围缩小到单片
（单片失败只计 `asrSegmentFailures` 并继续，`:52-57`），不是提高吞吐。

这确实是一个明确的改进点：把分片提交到线程池可以让长视频的 ASR 显著加速，
但受第三方限流和 `asrExecutor` 的 4 个核心线程约束，需要配合并发度控制一起做。

### Q28：ASR 或 OCR 局部失败、识别错误或内容冲突时怎么办？

两条 Future 不直接向外抛出单路异常，而是返回分支结果。ASR 按 60 秒片段隔离失败，OCR 按关键帧隔离失败；单个片段失败不会删除其他成功结果。

**代码细节：隔离的具体判据和清理顺序**

- ASR 分片：`catch (RuntimeException e)` 只累加 `failedSegments` 和 `lastSegmentError` 后继续
  （`SegmentedTranscriptionService.java:52-57`）。
- OCR 关键帧：`catch (RuntimeException e)` 计 `ocrFrameFailures` 后 `continue`（`:237-243`）。
  接着**还有一道判据**：`result.isEmpty() && failedFrames > 0` 时抛
  「所有 OCR 关键帧均处理失败」（`:259-261`）——全失败和部分失败区别对待。
- 失败分支的**资源清理**要单独讲：OCR 分支失败时，
  `finishContext` 会调 `deleteEvidenceFrames(uploadedEvidenceFrames)` 把**已经上传到 MinIO
  的关键帧删掉**（`:155-160`）。理由是整条 OCR 分支没有产出，留着这些帧既是孤儿对象也浪费空间。
  这是「先上传、失败后补偿删除」，不是事务性回滚。如果一整条分支失败，系统记录分支缺失并保留另一条证据；只有两路都失败或最终没有任何有效 Segment 才让 VideoContext 构建失败。

后续 Agent 只能使用实际存在的证据。**只有 OCR 时可以描述画面文字，不能推测讲解；只有 ASR 时可以总结语音，不能声称读取了 PPT。** 某个目标依赖缺失模态时，应返回部分结果或证据不足，而不是静默降级后生成完整答案。

内容冲突（语音说周一上线、PPT 写周三上线）时，系统不让某一路覆盖另一路，而是**把冲突本身作为结果**，分别给出证据；两路明显冲突时应输出「不一致」，Critic 也会阻止把冲突内容包装成确定事实。离线评测里专门保留了上线日期冲突样本。

明显音画不同步时，当前系统只能降低时间戳置信度，无法自动修复所有偏移；后续可以通过抽样估计统一 offset 或使用句子级 ASR 缩小误差。

### Q29：为什么不直接使用多模态视频大模型？

端到端视频模型适合短视频和一次性理解，但当前项目强调长视频复用、继续追问和证据追溯。每换一个目标都重新上传整段视频，成本和延迟难以控制；一次性模型回答也不容易沉淀成可检索、可恢复的资产。

ASR + OCR 的链路更长，但结果可以持久化和复用，证据能定位到时间段。代价是 OCR 只能理解文字，复杂图表、动作和非文字视觉语义会漏掉。合理的升级方式是对低置信度或强视觉任务定向调用视觉模型，而不是所有关键帧全量调用。

### Q30：如何证明 VideoContext 比纯 ASR 有价值？

对照组只提供 transcript，实验组提供 ASR + OCR VideoContext，样本重点选择 PPT、代码和操作画面密集的视频。结果看视觉知识点召回、目标覆盖、时间戳覆盖和最终 Claim 证据支持；代价看 OCR 调用数、重复帧比例、构建耗时和上下文长度。

当前代码已经记录 ASR / OCR 调用、分支失败、OCR 帧失败和上下文长度，但没有形成真实实验报告。因此面试中可以讲清验证方法和过程指标，不能编造提升百分比。

---

## 四、分层摘要、Embedding 混合检索与 Checkpoint

### Q31：为什么不能把完整 VideoContext 直接交给模型？

两小时视频会产生大量 ASR 和 OCR 文本。每次分析都全量输入会重复消耗 Token，大量无关片段也会干扰模型判断，继续追问时还会反复发送相同内容。

我的设计是「**摘要负责找路，原文负责作证**」。分两层上下文：Chunk 摘要、关键词和向量负责定位相关片段；命中后展开原始 VideoSegment 负责生成和校验。摘要是**有损压缩，不能作为最终事实来源**，最终 Claim 必须回到原始 ASR、OCR 和时间戳作证。

长视频最终输入还受 24000 字符预算限制，候选证据超过预算时跳过后续片段，避免检索后再次形成超长上下文。短于 5 分钟的视频直接使用原始 Segment，不额外调用模型生成摘要和向量。

### Q32：五分钟分块和关键词、Embedding 混合检索的完整流程是什么？

```
1. 长视频把连续 VideoSegment 聚合成 5 分钟 Chunk
2. 每个 Chunk 生成：摘要（≤200 字）+ 关键词 + 摘要 Embedding
3. 用户目标 → 生成查询向量 → Qdrant 在当前 mediaId 范围内召回候选 Chunk
4. 与关键词分数融合，选 Top3 → 展开 Chunk 内原始 Segment，受 24000 字符预算限制
5. 短于 5 分钟的视频直接用原始 Segment，不额外生成摘要和向量
```

**代码细节：Chunk 的切法和「短于 5 分钟」的判据**

切块在 `VideoChunkingService.build`（`:32-64`）：从 `start = 0` 开始以 5 分钟步长推进，
取 `segment.startMs() >= chunkStart && segment.startMs() < chunkEnd` 的片段。
`CHUNK_MS = 5 * 60 * 1000L`（`:18`）——**按时间轴切，不是按片段数切**，
所以块与块之间不会重叠，但可能有的块为空（空块被 `continue` 跳过，`:50`）。

Chunk 的字段和入库内容：

```java
record VideoChunk(long startTime, long endTime, String segmentSummary,
                  List<String> keywords, List<VideoContext.VideoSegment> rawSegments,
                  List<Double> embedding)
```

注意 `rawSegments` **也被存进 Checkpoint**——检索命中后展开原文靠的就是它，
不需要二次查询原始视频。

「短于 5 分钟」的判据在 `LongVideoContextService.selectRelevant`（`:41-44`）：
用**最后一个片段的 `endMs() <= CHUNK_MS`** 判断，为真则直接返回全部原始片段，
连 `VideoChunkingService` 都不调用，所以确实不产生摘要和向量。

字符预算 `MAX_CONTEXT_CHARS = 24_000`（`:19`）在 `withinBudget`（`:90-105`）里生效：
按候选顺序累加 `transcript.length() + ocrTexts` 总长，超预算的片段**跳过但继续尝试后面的**
（`if (!selected.isEmpty() && ...) continue;`）——第一个片段无论多长都保留，
避免预算过小时返回空上下文。丢弃数量记 `contextSegmentsDropped`，实际用量记 `contextChars`。
最后按 `startMs` 重新排序（`:103`），因为检索给出的是相关度顺序，模型需要的是时间顺序。

五分钟是当前课程视频场景的工程折中：一分钟块数量太多，知识点容易跨块；十五分钟块命中后又会带入大量无关内容。它不是行业标准，视频类型变化后应该重新评估。

### Q33：为什么同时需要关键词和 Embedding？各自权重是多少？

Embedding 能处理「递归效率」和「递归时间复杂度」这类语义相近但字面不同的表达；关键词更适合 B+树、HashMap、函数名、数字和章节名等精确符号。单独使用向量可能弱化精确符号，单独使用字符串又容易漏同义表达。

当前是**三路打分**，权重以代码为准：

```
Chunk 级：  语义（向量）0.6  + 语音关键词 0.25  + 视觉关键词（OCR）0.15
Segment 级：chunk 得分 0.55 + 语音关键词 0.25  + 视觉关键词 0.20
```

**代码细节：三路打分的实现和查询意图**

权重在 `VideoEvidenceRetrievalService` 里是**字面量**，不在配置里：

```java
// Chunk 级 —— score()，:100-102
semanticScore * 0.6 + termScore(intent.keywords(), searchableText(chunk)) * 0.25
                    + termScore(intent.visualKeywords(), visualText(chunk)) * 0.15

// Segment 级 —— scoreSegment()，:123-127
chunkScore * 0.55 + transcriptScore * 0.25 + visualScore * 0.20
```

检索前先用一次 LLM 调用把用户目标拆成三路查询（`planRetrieval`，`DeepSeekUtils.java:180`），
返回的字段名就是 `VideoRetrievalIntent` 的三个字段：

```java
record VideoRetrievalIntent(String semanticQuery, List<String> keywords, List<String> visualKeywords)
```

- `semanticQuery` 只用于生成 embedding（`:60`），不参与关键词匹配；
- `keywords` 去查语音文本（`searchableText`，含摘要 + 关键词 + 全部 transcript，`:151-158`）；
- `visualKeywords` **只查 OCR 文本**（`visualText`，`:160-164`）——这是「画面里写了什么」的独立通道。

`termScore`（`:175-184`）就是**命中词数 / 总词数**的比值，匹配方式是
`normalizedContent.contains(normalizedTerm)`，只做包含判断。

`VideoRetrievalIntent` 构造时会把词表归一化并**截断到 16 个**（`dto/VideoRetrievalIntent.java:18-27`），
所以单次检索最多 16+16 个关键词。

LLM 拆查询失败时有确定性兜底（`:200-208`）：用中英文标点切分原目标，
取长度 ≥2 的词，**去重后取前 8 个**（`fallbackTerms`，`:223-232`），
且语音和视觉两路用**同一份兜底词表**。计 `retrievalIntentFallbacks`。

排序规则里还有一个细节：Segment 按分数降序后，**用起始时间做第二排序键**
（`.thenComparingLong(startMs)`，`:77-78`），保证同分片段按时间先后稳定输出。

视觉关键词是独立通道，回答的是「画面里写了什么」。要主动指出：作者早期文档写过「语义 0.7 / 关键词 0.3」，那已经过时，当前实现是 0.6 / 0.25 / 0.15。

诚实边界：当前关键词匹配很轻，只做包含判断，适合单视频几十个 Chunk 的 Demo，不是成熟搜索引擎。数据规模和检索要求上升后，可以引入 BM25 和 Reranker，但在有实际召回问题前不需要继续堆组件。

### Q34：Qdrant 或 Embedding 服务不可用怎么办？

| 故障 | 降级行为 |
| :--- | :--- |
| Embedding 失败 | 返回空向量，Chunk 仍保留摘要和关键词 |
| Qdrant 写入或查询失败 | 回到应用内已有向量的余弦相似度 + 关键词排序 |
| 摘要生成失败 | 截取原始文本作为退化摘要 |
| 向量和关键词都失效 | 关键词仍提供最低限度召回 |

这个降级优先保证链路能继续，但质量可能下降，所以**不能静默声称与正常检索等价**。Telemetry 会记录 vectorStoreFallbacks 和 embeddingFallbacks 次数，后续应该把降级次数和低分命中展示到 Trace 或告警中。

### Q35：为什么先检索再 Planner，会不会因为第一次检索不完整导致计划遗漏？

这是当前方案最值得主动解释的取舍。Planner 如果读取完整长视频，成本和干扰都很高；Planner 如果完全不看视频、只根据一句 userGoal 拆任务，又可能生成与视频内容无关的计划。因此第一版先用 userGoal 做**目标级粗召回**，让 Planner 同时看到用户目标和少量相关证据。

粗召回确实可能偏向目标中的某一部分，所以 Critic 承担第二道纠偏：发现 missingRequirements 时触发 Replan，发现 unsupportedClaims 时把无证据结论变成新查询，发现 requiredTimestamps 时直接加载相应时间附近的原文。

更完整的方案是先给 Planner 一个轻量视频概览，再让每个子任务生成独立检索查询，但这会增加多次查询和编排复杂度。当前目标任务最多 5 个，先采用「目标级粗召回 + Critic 定向补召回」的最小闭环。

### Q36：Planner 和 Critic 分别怎样使用检索？

第一轮先按完整 userGoal 粗召回，让 Planner 在少量相关证据上拆任务。这种顺序成本低，但复杂目标可能有多个意图，粗召回会偏向其中一部分。

Critic 发现遗漏任务或无证据 Claim 后，会把 feedback、missingRequirements、unsupportedClaims 和 requiredTimestamps 交给检索层重新查询。第二轮上下文由上一轮证据、Critic 指定时间附近的 Segment 和新查询召回的 Chunk 合并而成，这样 Critic 反馈真的改变上下文。

### Q37：摘要漏信息、TopK 没命中或向量服务失败怎么办？

摘要漏信息是分层检索的主要风险，当前有三层应对：关键词和向量互补；Critic 的 requiredTimestamps 绕过摘要直接加载指定时间附近的原始 Segment；把 unsupportedClaims、missingRequirements 和反馈组合成新查询再召回候选 Chunk。

检索命中只是候选证据，不代表结论正确，Executor 仍需绑定原文，Critic 和程序规则再检查证据是否存在。

当前固定 Top3 和字符预算是可控起点，**没有实现低分自动扩大 TopK，也没有命中后自动加载相邻 Chunk**。面试中应把这两项说成明确改进方向（当最高分过低或 Claim 无法取证时扩大候选范围），而不是把它包装成已经完整解决。

**代码细节：两个 TopK 是分开的常量**

| 常量 | 值 | 用途 | 位置 |
| :--- | :--- | :--- | :--- |
| `TOP_K` | 3 | 目标级检索返回的 Chunk 数 | `VideoEvidenceRetrievalService.java:20` |
| `TOP_K * 2` | 6 | 向 Qdrant 请求的候选数 | `:109` |
| `MAX_USER_HITS` | 8 | 用户主动查证据时返回的片段数 | `:21` |
| `MAX_SNIPPET_LENGTH` | 180 | 证据片段摘要的截断长度 | `:22` |

**向 Qdrant 取 6 条、只用 3 条**是有意的余量：因为最终排序是
「语义 + 语音关键词 + 视觉关键词」三路融合，纯向量 Top3 未必是融合后的 Top3，
多取一倍给关键词通道留出翻盘空间。这个细节能体现「混合检索不是把两路结果拼起来」。

**还有一层降级不体现在常量上**：`vectorScores`（`:105-115`）失败时返回空 Map，
`score` 里对 `remoteScore == null` 的 Chunk 回落到
`cosine(queryEmbedding, chunk.embedding())`（`:96-99`）——
即**用 Checkpoint 里存的本地向量算余弦相似度**，而不是直接丢掉语义分。
所以 Qdrant 挂掉后仍保留完整的三路打分，只是候选集从「Qdrant 近邻」变成「全部 Chunk」，
性能下降但召回逻辑不变。只有 embedding 也为空时（`embed()` 返回 `List.of()`，`:210-217`）
语义分才真的退化为 0。

### Q38：Checkpoint 与 MQ 重试、幂等和缓存有什么区别？

四者解决的是不同问题，这是最容易讲混的考点：

| 机制 | 解决什么 |
| :--- | :--- |
| MQ 重试 | 失败任务**有没有机会再执行** |
| 幂等（锁 + completedKey + 状态检查） | 重复执行**会不会产生重复副作用** |
| 缓存（Redis） | 相同输入**能不能更快读到** |
| Checkpoint | 任务内部**应该从哪个阶段继续** |

举例：Executor 已经生成草稿，但 Critic 调用失败。MQ 会重新投递任务，目标级锁避免两个消费者同时执行，Checkpoint 让新消费者读取草稿直接从 Critic 继续，Redis 命中只是加快读取；Redis 丢失后仍然可以从 MySQL 恢复。

### Q39：Checkpoint 保存什么？为什么要区分视频级和目标级？

- **视频级**（只由视频内容决定，可跨目标复用）：VideoContext、5 分钟 Chunk、摘要、关键词和 Embedding。
- **目标级**（由 userGoal 决定，以 goalDigest 隔离）：Plan、Executor 草稿、CriticState 和最终 Result。

同一视频生成复习笔记和操作步骤时，可以共享 ASR / OCR 和索引，不能共享 Plan 和结果。这个划分同时服务于继续追问、内容级去重和目标级幂等，避免旧目标状态污染新任务。

**代码细节：目标摘要到底怎么算**

`goalDigest` 就在 `utils/AnalysisTaskKeys.java`，是两个重载（`:26-49`）：

```java
goalDigest(goal)          → sha256(goal.trim())
goalDigest(goal, mode)    → GENERAL 时直接委托上面那个（逐字节不变）
                            其余模式 sha256(mode.name() + '␟' + goal.trim())
```

三个细节值得说：

1. **是 SHA-256 十六进制，不是 MD5**，长度 64。`analysis:*` 系列 Key 都带上它。
2. 分隔符是 `U+241F`（`␟`，UNIT SEPARATOR）这个不可见字符，注释里写明是为了避免
   「模式名 + 目标」与某个真实目标文本发生摘要碰撞（`:47`）。
3. **GENERAL 走的是不含模式的那条路径**，所以引入模式体系前后，GENERAL 目标的
   所有 Key 和缓存**逐字节不变**，历史数据不会失效。这是个刻意的兼容设计。

`contentHash` 侧也有归一化（`normalizeContentHash`，`:19-24`）：用正则 `[a-fA-F0-9]{32}`
校验，**合法才用真实 MD5 并转小写，否则退化为字符串 `"media-" + mediaId`**。
所以上传完成前的视频、或者 MD5 缺失的记录，仍然有稳定的幂等键可用，不会因为拿不到 MD5 而失效。

**代码细节：Checkpoint 键和数据库行的完整格式**

Redis Key 由 `AgentCheckpointService` 构造（`:291-321`）：

| 层级 | Redis Key / 字段 | 说明 |
| :--- | :--- | :--- |
| 媒体级 Key | `agent:checkpoint:{mediaId}` | Hash，字段 `stage` 等 |
| 目标级 Key | `agent:checkpoint:{mediaId}:goal:{goalDigest}` | 每个目标一个独立 Hash |
| 目标索引 | `agent:checkpoint:{mediaId}:goals` | Set，存所有目标级 Key，便于按视频清理 |
| 反馈 | `agent:feedback:{mediaId}` | List，TTL 30 天 |
| 修订暂存 | `...:goal:{goalDigest}:revision` | 修订中间态 |

MySQL 表 `agent_checkpoints` 的列是
`media_id` / `checkpoint_key` / `stage` / `payload` / `updated_at`，
**主键是 `(media_id, checkpoint_key)`**，`payload` 为 `LONGTEXT`。
`checkpoint_key` 的取值有两类：

```
media:context              media:chunks              media:stage
goal:{goalDigest}:plan     goal:{goalDigest}:result
goal:{goalDigest}:criticState                        goal:{goalDigest}:stage
revision:{goalDigest}
```

清楚看到「要点 Q39 说的两类划分在这个列值里就是 `media:` 和 `goal:` 前缀」。

**代码细节：状态和产物怎么保证一起写**

这是 Q42「假完成」问题的具体答案。`AgentCheckpointRepository.write(...)`（`:70-87`）在
**同一个 `@Transactional`** 里发两条 upsert：

```java
upsert(mediaId, checkpointName,      stage.name(), payload);  // 产物行
upsert(mediaId, stageCheckpointName, stage.name(), null);     // 状态行（payload 为空）
```

所以不存在「写了 `PLAN_COMPLETED` 却没有 Plan」——两条在同一事务里，要么都在要么都不在。
Redis 缓存则挂在 `afterCommit` 回调上（`:83`、`:189-200`），**只有事务提交成功才更新缓存**，
避免 MySQL 回滚而 Redis 留下了脏值。

upsert 用的是 MySQL 原生
`INSERT ... ON DUPLICATE KEY UPDATE stage = VALUES(stage), payload = VALUES(payload), updated_at = CURRENT_TIMESTAMP(3)`
（`mapper/AgentCheckpointMapper.java:20-28`），靠 `(media_id, checkpoint_key)` 主键收敛。
**这不是 MyBatis-Plus 的 `saveOrUpdate`**，是手写注解 SQL。

修订场景用的是 `writeStandalone(...)`（`:89-103`），只写一行、不写配对的状态行。

需要注意：目标级键实际是 `goalDigest(goal, mode)`，**包含模式维度**，所以同一目标在不同分析模式下互不覆盖。

### Q40：Checkpoint 怎样持久化？Redis 挂了怎么办？

写入时先把序列化产物和阶段在事务中 upsert 到 MySQL，事务提交后再更新 Redis Hash；读取时先查 Redis，缓存缺失或内容损坏再读 MySQL 并回填。Redis 缓存 TTL 是 7 天，MySQL 没有跟着过期，因此用户任务不会因为缓存淘汰永久丢失。

大型视频和关键帧仍在 MinIO，Checkpoint 只保存结构化上下文和引用。用户反馈和 Trace 当前主要放 Redis，分别保留 30 天和 7 天，它们还不属于长期业务事实，这是后续评测闭环需要补的地方。

状态与产物必须一起保存，不能只写「PLAN_COMPLETED」却没有 Plan。

### Q41：不同阶段失败后怎样恢复？

| 已保存的产物 | 恢复行为 |
| :--- | :--- |
| VideoContext | 跳过 ASR / OCR |
| Chunk | 跳过摘要和 Embedding |
| Plan | 从 Executor 开始 |
| Executor 草稿（尚未 Critic） | 直接从 Critic 继续 |
| Critic 未通过 | 带着反馈和时间戳补证 |
| Result | 只补最终落库和展示 |

**代码细节：恢复判据分别在哪一行**

- **跳过 ASR / OCR**：`AiService.resolveContext`（`:148-152`）先读
  `checkpointService.loadContext(mediaId)`。
- **跳过摘要和 Embedding**：`LongVideoContextService.resolveChunks`（`:113-127`）先读
  `loadChunks(mediaId)`，命中计 `chunkCheckpointHits`。
- **从 Executor 开始**：`AgentLoopService.resolvePlan`（`:158-161`）读到 Plan 就直接跳过 Planner。
- **从 Critic 继续**：判据是 `state.result() != null && state.critique() == null && state.round() > 0`
  （`:127`），命中计 `criticCheckpointResumes`。
- **带反馈补证**：`contextForRetry` → `refineForCritique`，计 `criticEvidenceRefreshes`。

边界：ASR 单个切片和 OCR 单帧失败目前主要在一次构建内部隔离，没有持久化到每个切片和帧的 Checkpoint。当前优先保存重算成本最高、边界最清晰的阶段，等超长视频失败率和成本确实上升后再细化。

### Q42：如何避免假完成、并发恢复和版本污染？

Checkpoint 写入把产物和阶段同时持久化，读取终态时还会校验 Plan 和 Result 结构，非法终态不会直接复用，避免假完成。消费者恢复前先获取「内容指纹 + goalDigest」锁，阶段推进同时参考数据库状态，避免两个消费者覆盖同一任务。

边界：当前 Checkpoint 名称没有完整包含 Prompt、Schema 和 Embedding 版本，版本升级的局部失效仍是需要完善的边界。正确策略是 VideoContext Schema 变化重建上下文，Embedding 模型变化重建向量，Executor Prompt 变化只重跑 Executor 之后的阶段。

### Q43：如何证明检索和 Checkpoint 确实有价值？

检索需要和全量输入做对照，观察相关时间段 Recall@K、最终 Claim 证据支持、输入 Token、上下文长度和总延迟。Checkpoint 通过故障注入验证，在 VideoContext、Chunk、Plan、Executor 和 Critic 后主动失败，检查恢复起点、重复模型调用、最终结果和数据库重复记录。

**代码细节：现有单测覆盖了哪三件事**

`server/src/test/java/com/example/server/service/` 下只有 3 个测试类、4 个测试方法，
都是纯 Mockito 单测，不依赖真实 Redis / MySQL：

| 测试类 | 测什么 | 值得说的点 |
| :--- | :--- | :--- |
| `EvidenceVerificationServiceTest` | 证据校验的正反例 | 正例：125000ms 处原文「根节点、左子树、右子树」判定支持；反例：**相似但不相同的文本必须判不通过**。这个反例正好守住 Q15 说的「不能只看像不像」 |
| `AgentExecutionBudgetTest` | 时长预算到期后必须抛 `DeadlineExceededException`，`Scope` 关闭后恢复 | 验证 ThreadLocal 死线没有泄漏到后续调用 |
| `AgentCheckpointServiceTest` | 修订状态机：先 `stageRevision` 暂存 → `beginStagedRevision` 清旧产物并写新 Plan | 断言了 `deleteByPrefix` 被调用、阶段从 `REVISION_PENDING` 变 `REVISION_APPLIED` |

边界要主动说：**这 4 个测试覆盖的是三个最容易被写错的纯逻辑点**（证据包含方向、
ThreadLocal 死线、修订的清理顺序），不是完整回归。
AgentLoop 的多轮编排、MQ 消费的失败分流、上传合并的幂等都没有自动化测试，
所以 Q51/Q60/Q69 里那些「怎么验证」的答案目前都还是方案而不是已执行的结果。

当前 Trace 已记录检索最高分、召回 Chunk 数、上下文长度、Checkpoint 命中、模型调用和阶段耗时，为验证提供了数据入口。仓库没有实际压测和回归结果，因此只能讲验证方案与已埋指标，不能写「降低多少 Token」或「恢复成功率多少」。

**代码细节：已埋指标的确切名字**

被追问「你说埋了指标，具体叫什么」时可以直接报。`AgentTelemetry` 自动记录的：
`modelCalls`、`inputTokensEstimated`、`outputTokensEstimated`、`estimatedCost`、
每个阶段一个 `{stage}Calls`、`failedStages`；`stageDurationMs` 是阶段耗时 Map。

各调用方 `incrementCurrent` 的自定义计数器（按关注点分组）：

| 关注点 | 计数器 |
| :--- | :--- |
| 检索 | `retrievalChunks`、`retrievalTopScore`（gauge）、`retrievalIntentFallbacks`、`vectorStoreWrites`、`vectorStoreFallbacks`、`embeddingFallbacks`、`summaryFallbacks` |
| 上下文 | `contextChars`（gauge）、`contextSegmentsDropped`、`chunkCheckpointHits`、`contextCheckpointHits`、`contextContentReuses`、`contextLockContentions` |
| Agent | `criticRounds`、`criticPassed`、`criticEvidenceRefreshes`、`criticRewriteOnlyRetries`、`planRevisions`、`planRevisionFallbacks`、`planStructureRepairs` |
| Checkpoint | `checkpointHits`、`terminalCheckpointHits`、`criticCheckpointResumes`、`invalidTerminalCheckpointRepairs` |
| 预算 / 输出 | `budgetTerminations`、`structuredOutputRetries`、`modelCallFailures` |
| 模态分支 | `asrCalls`、`asrSegmentFailures`、`asrBranchFailures`、`ocrCalls`、`ocrFrameFailures`、`ocrBranchFailures`、`frameUploadFailures` |

**Token 估算口径**（`AgentTelemetry.java:227-232`）：非 ASCII 字符**每个算 1 token**，
ASCII 字符按 `(n + 3) / 4`。这是「字符规则估算」的具体规则——
中文按 1:1 计，英文按 4 字符 1 token 计。已知这会偏离真实 tokenizer，
所以 `max-estimated-tokens = 50000` 是一个**保守**的阈值。

Trace 的存储（`:211-221`）：`agent:trace:{traceId}`、
`agent:trace:task:{taskId}:{goalDigest}`、索引 `agent:trace:task:{taskId}:goals`，
`TRACE_TTL = 7 天`、`MAX_TRACES = 500`。**`MAX_TRACES = 500` 是一个硬上限**，
意味着 Trace 会被裁剪——拿它做长期回归数据是不够的，这也是 Q94 里
「评测集没有形成长期闭环」的一部分原因。

---

## 五、分片上传与断点续传

### Q44：为什么普通 Multipart 上传不适合长视频？当前完整流程是什么？

长视频通常几百 MB 到 1GB 以上，一次请求上传完整文件时，网络只要在最后阶段中断，用户就要从头重传；服务端也需要长时间维持连接，失败粒度是整个文件。分片上传把失败范围缩小到单个数据块，成功分片可以保留，网络恢复后只补缺失部分。

```
初始化：文件名 + 总分片数 → 校验后缀 / 数量 / 用户 → 生成随机 uploadId
       → Redis Hash 存 {filename, totalChunks, userId}，TTL 1 天
上传每片：校验 uploadId 归属 + 序号 + ≤5MB → 先写 MinIO 分片 → 再把成功序号加入 Redis Set
恢复：前端查询 Set，计算总序号与已上传序号的差集，只重传缺失分片
完成：调 complete → 持 Redisson 合并锁 → 查 completedKey → 校验 Set 数量 == totalChunks
     → 按 0..N-1 顺序读取 MinIO 分片写入本地临时文件，DigestOutputStream 边写边算 MD5
     → 上传完整文件 → 存 media_files → 写 completedKey → 清理分片与 Redis 元数据
```

**代码细节：三组 Key 和对象路径**

全部在 `ChunkUploadService` 里，常量 `UPLOAD_KEY_PREFIX = "upload:chunked:"`（`:38`）：

| 用途 | 格式 | TTL |
| :--- | :--- | :--- |
| 元数据 Hash | `upload:chunked:{uploadId}`（字段 `filename` / `totalChunks` / `userId`） | 1 天 |
| 已完成分片 Set | `upload:chunked:{uploadId}:parts`（成员是分片序号的字符串） | 1 天 |
| 合并完成标记 | `upload:chunked:{uploadId}:completed`（值是 `mediaId`） | 1 天 |
| 合并锁 | `lock:upload:merge:{uploadId}`（Redisson） | WatchDog 续期 |
| 分片对象 | `chunk-uploads/{uploadId}/part-{chunkIndex}` | 合并后逐片删除 |

两个常量：`MAX_CHUNK_BYTES = 5L * 1024 * 1024`（`:39`）、`MAX_TOTAL_CHUNKS = 410`（`:40`）。

合并的流式实现值得指出具体写法（`:135-144`）：用 `Files.createTempFile` 建临时文件，
外面套 `BufferedOutputStream` 再套 `DigestOutputStream`，循环
`minioUtils.copyObjectTo(chunkObjectName(uploadId, i), output)` 按 0..N-1 顺序拉取。
MD5 是在**写入过程中**由 `DigestOutputStream` 累积的，不是写完再扫一遍文件。
`finally` 里 `Files.deleteIfExists(mergedFile)` 保证临时文件清理。

### Q45：为什么使用 uploadId 管上传，MD5 在哪里发挥作用？

当前上传任务主键是**服务端生成的 uploadId**，不是前端提前计算的整文件 MD5。这样初始化不需要让浏览器先完整读取 GB 级文件，也避免把用户提供的哈希直接当作任务授权和唯一身份。

完整 MD5 在服务端合并分片时通过 DigestOutputStream 一边写文件一边计算，不需要再次扫描整个文件。得到的 contentHash 用于后续视频内容识别、解析结果复用和 Agent 任务幂等。

因此当前能力是「**合并后内容级解析去重**」，不是上传前跨用户秒传。代码也没有前端分片 MD5 与服务端重算的对比，不能讲双端完整性校验。现有完整性主要依赖分片数量、顺序读取和对象存储写入成功。

**代码细节：contentHash 从哪来、存在哪**

`contentHash` 由合并时算出的 MD5 传入 `mediaService.saveUploadedMedia(...)`
（`ChunkUploadService.java:147-148`）并写入 `media_files.content_hash` 列，
该列有普通索引 `idx_media_content_hash`（**不是唯一索引**）。

读取时的缓存路径在 `MediaService.contentHash(mediaId)`（`:131-143`）：
先查 Redis `media:md5:{mediaId}`，未命中再查 `media_files.content_hash` 并回填。
**这个缓存写入没有设置 TTL**（`rememberContentHash`，`:81` 只调 `set` 不带 expire），
理由是 MD5 对一条媒体记录永不变化。Redis 异常在这里被吞掉并记 warning（`:135-137`），
因为读 MD5 失败最多导致退化成 `media-{id}` 形式的键，不该让分析失败。

MD5 计算本身用的是 `MessageDigest.getInstance("MD5")` + 8192 字节缓冲，
`HexFormat.of().formatHex(...)` 输出**小写**十六进制（`calculateMd5`，`:223-231`），
正好匹配 `AnalysisTaskKeys` 里那条 `[a-fA-F0-9]{32}` 的校验正则。

### Q46：为什么坚持「先写 MinIO，再记录 Redis」？

Redis Set 中的分片序号代表「**这个对象已经成功落盘**」。如果先记 Redis 再写 MinIO，存储失败后 Redis 仍然显示分片完成，直到合并阶段才暴露缺文件，形成假上传。先落盘再记状态可以让进度记录更可信。

反过来的小窗口是 MinIO 已成功、Redis 写入失败。此时前端会认为该片缺失并再次上传，但对象路径由 uploadId 和 chunkIndex 唯一确定，重传会覆盖同一逻辑分片，不会产生两份业务结果。这是用**可重复写换取状态正确性**。

### Q47：合并过程怎样保证顺序和幂等？

- **并发**：complete 接口先按 uploadId 获取 Redisson 合并锁，同一 uploadId 同时只有一个线程进入合并区。
- **重复请求**：先检查 completedKey，已合并则校验媒体归属并返回同一记录，不重复生成文件和数据库记录。
- **顺序**：先校验 Redis Set 数量与 totalChunks 一致，再按 0 到 N-1 顺序读取 MinIO 分片写入服务端临时文件，同时计算完整文件 MD5。
- **状态冲突返回 409**：合并中或分片不全都属于客户端可重试的「状态冲突」，不和 500 混。

锁解决并发执行，completedKey 解决完成后的重复请求，两层职责不同。

边界：如果进程在完整文件上传成功、数据库记录写入前宕机，可能遗留孤立对象；如果数据库成功、completedKey 写入前宕机，再次合并可能重复。当前实现没有跨 MinIO、MySQL 和 Redis 的事务，生产化需要确定性完整文件路径和数据库唯一业务键做补偿。

**大文件会不会一次性读进内存？不会，整条链路是流式的。** 上传分片时用 `chunk.getInputStream()` 流式写入 MinIO；合并时按序把每个分片 `copyObjectTo` 到本地临时文件，并用 `DigestOutputStream` 边写边算 MD5，不需要把整个文件读进堆内存；FFmpeg 也是从本地文件读取。所以内存占用和视频大小基本无关。**但要注意一个边界：合并阶段会在本地临时磁盘落一个完整文件**，所以磁盘容量和视频大小是线性关系，这也是 410 片上限（约 2GB）的由来之一——更大视频需要同时调整临时磁盘预算和合并方式。

### Q48：为什么分片限制在 5MB、总分片限制为 410？

当前接口限制单片不超过 5MB，总分片不超过 410，因此覆盖约 2GB 视频。5MB 能把弱网重传成本控制在一个较小块，同时避免产生过多 HTTP 请求和对象；410 是当前 Demo 对上传体积和服务端临时合并成本的保护。

这些值不是通用最优参数。更大视频需要同时调整服务端请求上限、临时磁盘容量和对象存储合并方式，不能只把 totalChunks 配大。简历写「GB 级」成立，面试时不能声称当前支持任意 10GB 文件。

### Q49：上传到 99% 断网、Redis 故障或合并中宕机会怎样？

上传到 99% 断网后，成功分片仍在 MinIO，序号仍在 Redis。前端通过原 uploadId 查询已上传集合，只补最后的差集，然后重新调用 complete。恢复依赖 uploadId 元数据仍在 Redis，**TTL 是一天**，超过一天后任务被视为过期需要重新初始化。这个边界是为了避免临时状态永久占用内存，不能包装成无限期断点续传。

Redis 不可用时当前接口直接失败，不会绕过归属校验继续写入。Redis 数据彻底丢失时，当前代码不会扫描 MinIO 自动重建 Set，需要用户重新初始化上传。

正常合并后代码会逐片删除临时对象，但放弃上传或 Redis 元数据过期后的 MinIO 分片还缺少自动生命周期清理。最小改进是给 `chunk-uploads/` 前缀配置对象存储生命周期；当前只能把它写成明确边界，不能说已经实现。

### Q50：怎样防止用户访问别人的上传任务？

uploadId 本身不可作为授权凭证。Redis 元数据同时保存 userId，每次查询进度、上传分片和合并都会比较当前登录用户；完成后返回媒体记录时还会再次检查归属。即使用户猜到或拿到别人的 uploadId，也不能读取进度或触发合并。

服务端还会校验：文件名去除路径并限制视频后缀，uploadId 必须是合法 UUID，分片序号和总数都在服务端校验。这些边界校验比在前端隐藏按钮更重要。

### Q51：如何证明断点续传真的有效？

我会选固定大小视频，在上传 30%、70% 和 99% 时主动断网或刷新页面，记录恢复后重传字节数、完成时间和最终 MD5。还要测试重复分片、缺失分片、并发 complete、Redis 短暂不可用和 MinIO 写入失败。

结果指标是恢复成功率和最终文件一致性，过程指标是重复上传字节、分片重试次数和合并耗时，代价指标是 Redis 临时状态和 MinIO 临时对象数量。当前仓库没有这组测试结果，因此只保留定性亮点。

---

## 六、RocketMQ 异步调度与 Redisson 幂等

### Q52：为什么需要 RocketMQ，而不是本地线程池、Kafka 或 RabbitMQ？

分析链路包含 FFmpeg、ASR、OCR、Embedding 和多轮 LLM，耗时远超普通 HTTP 请求。同步执行会长期占用 Web 工作线程，客户端断线还会让任务结果失去承接点。本地线程池能把任务挪出请求线程，但任务仍然只存在当前进程内，服务重启后难以恢复，也不方便独立扩展消费者。

RocketMQ 在这里负责持久化任务入口和重新调度。Web 层只完成参数校验、限流、活动任务登记和消息投递，然后返回 202；消费者独立执行长链路。它不会让视频真正处理得更快，改善的是请求线程占用、失败重投和接入层与计算层解耦。

选型对比：Kafka 更适合日志和大规模流处理，当前项目不需要它的吞吐优势；RabbitMQ 也能完成任务队列，但项目需要任务重试和失败主题，RocketMQ 与现有 Java 技术栈更贴合。这个选型不是说 RocketMQ 全面优于其他 MQ，而是当前业务特征与已有学习成本下的取舍。

### Q53：从提交分析到消费者完成，完整流程是什么？

```
投递方（提交接口）：
  1. 校验视频归属，读 contentHash，算 goalDigest
  2. SETNX activeKey（TTL 6 小时）→ 重复提交直接返回 DUPLICATE
  3. 用户级（5 次 / 分钟）+ 全局（30 次 / 分钟）限流 → 不过返回 RATE_LIMITED
  4. 发送到 video-analysis-topic，返回 202 ACCEPTED
  5. MQ 成功后才发布 QUEUED 事件
  ※ MQ 投递失败：删除 activeKey；修订任务还要撤销暂存修订
    → 避免「永远显示处理中但没有消息」的幽灵任务

消费者：
  1. 结构校验，非法消息 → 走毒消息收敛流程
  2. 拿 Redisson 锁（contentHash + goalDigest）
  3. 检查视频是否还存在
  4. Redis 递增 attemptsKey（记录投递次数）
  5. 检查 completedKey：已有同内容同目标完成结果 → 复用（COMPLETED_REUSED）
  6. 修订任务 → 切换 Checkpoint，删 completedKey
  7. 执行主链：读 Checkpoint、构建上下文、执行 AgentLoop、落库
  8. 成功 → 写 completedKey（7 天）→ 发布 COMPLETED
```

**代码细节：消息体和常量**

`dto/AnalysisTaskMsg.java` 的字段只有五个（`:7-30`）：

```java
Long mediaId; String action; String contentHash; String userGoal; String mode;
```

两个 action 常量：`START_ANALYSIS = "START_ANALYSIS"`、`REVISE_ANALYSIS = "REVISE_ANALYSIS"`。
`isRevision()` 就是判断 action 是否等于后者（`:43`）。mode 是**字符串**不是枚举，
消费端用 `AnalysisMode.fromNullable(msg.getMode())` 宽松解析（`:92`），
所以老消息（没有 mode 字段）会安全降级为 GENERAL。

消费端的常量（`VideoAnalysisConsumer.java`）：

| 常量 | 值 | 位置 |
| :--- | :--- | :--- |
| `MAX_DELIVERY_ATTEMPTS` | 3 | `:46` |
| `ACTIVE_TTL` | 6 小时 | `:47` |
| `MAX_CAUSE_DEPTH` | 16 | `:49` |
| completedKey TTL | 7 天 | `:150-151` |
| 注解 `maxReconsumeTimes` | 2 | `:41` |

`MAX_DELIVERY_ATTEMPTS = 3` 是「投递次数」，注解上的 `maxReconsumeTimes = 2` 是
「重投次数」，两者差 1 是吻合的。代码注释里专门写了这一点（`:33-35`），
并说明**不设这个注解会退化成 MQ 默认的 16 次重投空转**——因为应用侧计数依赖 Redis，
一旦异常发生在递增之前，应用侧上限就失效了。

**代码细节：Topic 名称和配置项**

| 配置项 | 默认值 |
| :--- | :--- |
| `rocketmq.topic.video-analysis` | `video-analysis-topic` |
| `rocketmq.topic.video-analysis-dead` | `video-analysis-dead-topic`（项目自建失败主题） |
| `rocketmq.consumer.group` | `video-analysis-consumer` |
| `rocketmq.producer.group` | `video-analysis-producer` |

有个**不一致**值得知道：`application.properties` 里 `rocketmq.consumer.group` 的默认值是
`video-analysis-consumer`，而 `VideoAnalysisConsumer` 注解上 `${rocketmq.consumer.group:...}`
的兜底值是 `video-analysis-group`（`:32`）。正常启动时 properties 会覆盖注解兜底，
所以实际生效的是 `video-analysis-consumer`；但如果配置文件缺失该属性，两边就会不一致。
这是配置冗余带来的隐患，不是设计意图。

消费并发刻意没有配置（`consumeThreadNumber` / `consumeThreadMax`），
代码注释里说明原因是**这两个属性名在 rocketmq-spring 各版本间变过**，
配错会和容器默认值冲突导致启动期抛
`consumeThreadMin is larger than consumeThreadMax`（`:37-40`）。

### Q54：任务长时间没响应，或者用户手动点了重新执行，分别怎么处理？

这两种情况走的是不同路径，要分开讲。

**任务卡住（长时间无响应）** 由三层机制兜住。第一层是单次外部调用的超时：ASR 和 LLM 调用都有超时，OCR 单帧 `waitFor(2, TimeUnit.MINUTES)`，LLM 默认 300 秒（`LLM_TIMEOUT_SECONDS`）。第二层是阶段 deadline：VideoContext 构建的 ASR 和 OCR 两路共享一个 **60 分钟总预算**，超时会取消两条分支并让本次构建失败，由 Checkpoint 和任务级重试接管；AgentLoop 内部还有 `agent.budget.max-duration-ms` 默认 120 秒的阶段间检查。第三层是 MQ 消费超时，超时后 Broker 重投，消费者按 attemptsKey 计数，最多 3 次投递后进失败主题。

**用户手动重新执行** 走的是 `/agent-revise` 修订接口，不是简单地重投原任务。流程是：用户提交反馈 → 服务端把反馈合成 `revisedGoal` → 走正常投递链路，但消息带 `revision` 标记。消费者看到 revision 标记后：

1. 调用 `beginStagedRevision` 切换 Checkpoint 到暂存的修订状态（把修订后的 Plan 写入，标记为已应用）；
2. **删掉 completedKey**，让这次执行不会被当成「已完成」直接复用旧结果；
3. 执行主链；
4. 成功后 `completeStagedRevision` 确认修订生效。

如果 MQ 投递失败，会调用 `cancelStagedRevision` 把暂存修订撤销掉，避免出现「修订计划已经切了、但消息根本没发出去」的中间态。

所以「重跑」不是从头再来：视频级 Checkpoint（VideoContext、Chunk、向量）依然复用，只有目标级的 Plan、草稿和结果会被重算。

**代码细节：暂存修订的状态机**

修订中间态存在 `agent_checkpoints` 表的一个独立行，`checkpoint_key` 是
`revision:{goalDigest}`，payload 是一个私有 record
`RevisionCheckpoint(AgentPlan plan, boolean applied)`（`AgentCheckpointService.java:323`）。
三个阶段对应三个方法：

| 方法 | 动作 | 阶段 |
| :--- | :--- | :--- |
| `stageRevision` | 写 `revision:{digest}`，`applied=false`（投递前） | `REVISION_PENDING` |
| `beginStagedRevision` | 若 `applied=true` 直接返回；否则删掉该目标全部旧 Checkpoint，写入新 Plan，改 `applied=true`（消费端接手时） | `REVISION_APPLIED` |
| `completeStagedRevision` / `cancelStagedRevision` | 删除 `revision:{digest}` 行 | — |

`beginStagedRevision` 上的清理动作值得单独讲（`:202-203`）：

```java
checkpointRepository.deleteByPrefix(mediaId, goalCheckpoint(goal, mode, ""));  // SQL LIKE 'goal:{digest}:%'
redisTemplate.delete(goalKey(mediaId, goal, mode));                            // 删 Redis Hash
```

`deleteByPrefix` 的实现在 `mapper/AgentCheckpointMapper.java:30-31`，
是 `checkpoint_key LIKE CONCAT(#{prefix}, '%')`，前缀正好是 `goal:{digest}:`，
所以**只清掉这个目标的 plan / result / criticState / stage 四类产物，不碰 media 级数据**
（`media:context`、`media:chunks` 前缀不同）。这就是「重跑不掉视频级缓存」的实现细节。

方法上有 `@Transactional`（`:194`），保证「清理旧产物 → 写新 Plan → 标记 applied」
三步原子。如果 MQ 投递失败，`cancelStagedRevision` 会删掉 revision 行，
且因为 `beginStagedRevision` 还没被消费端调用过，旧结果其实是**原封不动**的
（投递方的注释明确写了「MQ 投递失败时用户还有结果可看」，`AnalysisDispatchService.java:81`）。

### Q55：为什么幂等 Key 不能只有视频 MD5？activeKey、锁、completedKey、Checkpoint 各自解决什么？

MD5 只描述视频内容。同一视频生成复习笔记和操作步骤，可以复用 ASR、OCR、VideoContext 和 Chunk，**不能复用相同的 Plan 和 AnalysisResult**。如果只按 MD5 加锁，两个不同目标会被错误互斥，甚至返回错误结果。

所以当前分析任务用 `contentHash + goalDigest` 作为任务级 Key，contentHash 负责识别同一视频，goalDigest 负责隔离目标。

| 机制 | 时机 | 职责 |
| :--- | :--- | :--- |
| activeKey（SETNX） | 请求入口 | 拦截「正在处理中」的重复提交，让用户快速拿到 DUPLICATE |
| Redisson 锁 | 消费者拿到消息后 | 处理 MQ 重投或多实例消费者同时拿到任务的**瞬时并发** |
| completedKey | 消费者执行前 | 判断「同内容同目标是否已经完成」 |
| Checkpoint + MySQL | 执行中和恢复时 | 判断任务内部从哪继续，并保存最终事实 |

**代码细节：四个 Key 的完整字符串格式**

全在 `utils/AnalysisTaskKeys.java:60-91`，参数都是 `(contentHash, goalDigest)`：

```java
active(contentHash, goalDigest)     → "analysis:active:"    + contentHash + ":" + goalDigest
lock(contentHash, goalDigest)       → "lock:analysis:"      + contentHash + ":" + goalDigest
completed(contentScope, goalDigest) → "analysis:completed:" + contentScope + ":" + goalDigest
attempts(contentScope, goalDigest)  → "analysis:attempts:"  + contentScope + ":" + goalDigest
```

注意 `active` 和 `lock` 的第二个参数**必须是 contentHash**，而 `completed` 和 `attempts`
的参数名叫 `contentScope`——因为修订任务会传 `"media-" + mediaId` 而不是真实 MD5
（`AnalysisDispatchService.java:69` 的三元表达式）。这个差异是有意的：修订是同一条媒体的
目标级操作，不应该因为 MD5 索引缺失而拿不到同一个键。

**代码细节：两个参数各自从哪里来**

| 参数 | 来源 | 方法 |
| :--- | :--- | :--- |
| `contentHash` | `MediaService.contentHash(mediaId)` 再经 `normalizeContentHash` 兜底 | `AnalysisDispatchService.java:143-146` |
| `goalDigest` | `AnalysisTaskKeys.goalDigest(goal, mode)` | `AnalysisDispatchService.java:70` |

投递方（`AnalysisDispatchService.submit`，`:63-102`）和消费方
（`VideoAnalysisConsumer.onMessage`，`:92-98`）**各自独立重算这四个键**，
消息体里只带 `contentHash` 和原始 `userGoal`，不带 GoalDigest——
两边用同一份 `AnalysisTaskKeys` 代码保证一致。这是「同一个 Key 的另一半不落在消息里」的设计，
避免消息体成为键的事实来源。

**代码细节：activeKey 的值和 TTL**

`setIfAbsent(activeKey, String.valueOf(mediaId), ACTIVE_TTL)`（`:72-73`），
**值存的是 mediaId**，`ACTIVE_TTL = Duration.ofHours(6)`（`:31`）。
所以除了判断「是否在处理中」，还能反查出是哪条媒体占着这个键——
这也是 Q53 里「幂等键残留 6 小时」那个数字的出处。

关键认知：**锁只能控制持锁期间的并发，不能证明任务历史上从未完成**；Redis Key 有 TTL，也不是永久事实。所以消费者拿锁后仍然要检查 completedKey 和 MySQL Checkpoint。真正的幂等来自「稳定业务 Key + 状态检查 + 可重复写」共同收敛，而不是单独依赖一把锁。

### Q56：为什么使用 Redisson，而不是 JVM 锁或手写 SETNX？WatchDog 怎样工作？

JVM 锁只在单实例有效，消费者横向扩容后不同进程之间看不到彼此。手写 SETNX 还需要自己处理锁值归属、原子释放和过期续期。项目已经依赖 Redis，使用 Redisson 不增加新的基础设施，能直接获得分布式锁和 WatchDog，适合处理时长难以预估的 Agent 任务。

当前获取锁时**没有指定固定 leaseTime**，因此持锁线程存活期间 WatchDog 会周期性延长锁过期时间；进程退出后续期停止，锁最终过期。释放前还会检查当前线程是否持有锁，避免误删其他线程重新获得的锁。

**代码细节：项目里三把锁，语义各不相同**

| 锁 Key | 获取方式 | 位置 | 语义 |
| :--- | :--- | :--- | :--- |
| `lock:upload:merge:{uploadId}` | `tryLock()` 不等待，失败抛 409 | `ChunkUploadService.java:115-120` | 合并期间互斥，快速失败 |
| `lock:analysis:{contentHash}:{goalDigest}` | `tryLock()` 不等待，失败直接 return | `VideoAnalysisConsumer.java:104-108` | MQ 重投的瞬时并发 |
| `lock:analysis-context:{contentHash}` | `tryLock(300, SECONDS)` **带等待** | `AiService.java:163` | 同视频并发构建上下文，只让一个跑 ASR/OCR |

三把锁里**只有第三把带等待时间**，这是有意的：合并和目标锁是「别人在做就别做」，
快速失败即可；上下文锁是「别人在做就等一等，做完我直接复用」，因为 ASR/OCR 是分钟级作业，
等待比重复烧算力划算。等待超过 `CONTEXT_LOCK_WAIT_SECONDS = 300` 秒（`AiService.java:34`）后
**不自己跑**，而是抛异常交给 MQ 重投（`:175-183`），计 `contextLockContentions`——
注释里写明本地等待只负责消化短时争用，跨时间的重投才是兜底。

`AiService` 里那段加锁后的二次检查逻辑值得单独看（`:164-173`）：
**无论是否抢到锁都要重查，且必须先查自己的 Checkpoint 再查归属索引**，
注释解释了原因——同一个 mediaId 换目标并发提交时，先完成者登记的 owner 正是自己，
只查归属索引会被「owner == 自己」判空而漏掉，于是又重跑一遍完整 ASR/OCR。
这是并发场景下很容易踩的坑，可以作为「我检查过并发正确性」的具体例子。

诚实边界：WatchDog 只能降低「任务处理中锁提前过期」的风险，**不能提供业务上的绝对一次**。Redis 故障、长时间 JVM 停顿和进程崩溃仍可能导致重新执行，因此数据库状态、Checkpoint 和幂等写才是最终兜底。

### Q57：重复消费、执行失败三次和失败消息分别怎么处理？

RocketMQ 按至少一次语义设计，消息可能因为消费超时、进程异常或确认丢失被重新投递。重复消息先竞争目标级锁，再检查 completedKey 和 MySQL Checkpoint：已经完成就复用结果，执行到一半就从最近阶段继续，不重新产生完整副作用。

投递次数口径（以代码为准）：`MAX_DELIVERY_ATTEMPTS = 3`，注解 `maxReconsumeTimes = 2`（2 次重投 = 最多 3 次投递），用 Redis attemptsKey 计数。

- 前两次（attempt 1、2）失败 → 状态写为 RETRYING，**保留 activeKey**（防止前端以为任务结束又提交一份），重新抛出异常交给 RocketMQ 重投。
- 第 3 次仍失败 → 把失败原因写入失败任务表，同时把原消息投递到**项目自建的失败主题**，阶段改为 DEAD_LETTERED。

另外还有**永久失败**判定：沿异常 cause 链（最多 16 层）查找 IllegalArgumentException、SecurityException、NoSuchElementException，命中则直接收敛，不浪费后续的 ASR 和 LLM 调用。

**代码细节：attemptsKey 的递增时机和计数语义**

递增发生在**拿到锁、确认视频存在之后**，不是消息一进来就加：

```java
Long currentAttempt = redisTemplate.opsForValue().increment(attemptsKey);   // :113
attempt = currentAttempt == null ? 1 : currentAttempt;
redisTemplate.expire(attemptsKey, ACTIVE_TTL);                              // 重置为 6 小时
```

`increment` 每次 +1 并把 TTL 重置，所以 6 小时是**滑动窗口**而不是绝对过期。

分流的三个条件（`:168-179`）：

| 条件 | 结果 |
| :--- | :--- |
| `!permanent && attempt < 3` | 状态写 `RETRYING`，**保留 activeKey**，抛异常让 MQ 重投 |
| `permanent \|\| attempt >= 3` | 落失败台账 + 转投失败主题，状态 `DEAD_LETTERED` |
| 未拿到锁 / attempt == 0（递增前就失败） | 落到最后的 `throw`，交给 MQ |

`isPermanentFailure`（`:303-317`）遍历 cause 链时有个**自引用保护**：
`if (current.getCause() == current) break;`（`:313`）防御异常把自己设为自己的 cause 导致死循环。
注释里还专门说明 `NumberFormatException` 也被算作永久失败（它是 `IllegalArgumentException`
的子类），因为「解析脏数据是确定性失败，重投拿到的还是同一份数据」。

`finally` 里的清理（`:205-212`）用 `retrying` 标志区分：

```java
if (!retrying) redisTemplate.delete(List.of(activeKey, attemptsKey));
if (lock.isHeldByCurrentThread()) lock.unlock();
```

**重试期间故意不删 activeKey**，注释写明「不然前端会以为任务结束，又塞进来一份相同工作」。
解锁前的 `isHeldByCurrentThread()` 检查是 Redisson 的标准防误删。

必须讲准确：这是**自建失败主题 + 失败任务表**，不是已经完整搭建 RocketMQ 原生 DLQ 运营体系。管理接口可以查看和重新投递失败任务，但生产环境还需要告警、责任人和防止同一毒消息无限回放的审批规则。预算耗尽会进入独立终态，不参与普通 MQ 重试。

### Q58：毒消息（结构非法的消息）怎么处理？

结构非法的消息重投多少次都不会变好，所以走专门的毒消息收敛流程：落失败台账 + 转投失败主题，然后**正常 ACK**。如果不这样做，消息会被 Broker 按默认次数反复重投，而且台账、事件和日志三处都看不到。

确认前提是：**台账和失败主题至少一个写成功才 ACK**；两个都失败必须拒绝确认（消息会静默丢失），并且不清理 activeKey，让用户保持「处理中」状态，而不是让任务凭空消失。

毒消息在 try 之前返回，走不到 finally，所以要单独清理 activeKey，否则幂等键会残留 6 小时，用户之后所有重复提交都会被判 DUPLICATE。

**代码细节：结构校验的四个条件和 ACK 判据**

`rejectionReason(msg)`（`:216-222`）检查四件事，任一条不过就是毒消息：

```java
msg == null                              → "消息体为空"
msg.getMediaId() == null                 → "缺少 mediaId"
goal 为 null 或 blank                     → "缺少分析目标"
!msg.hasSupportedAction()                → "不支持的 action=" + action
```

`discardPoisonMessage`（`:232-261`）里两个独立的 boolean 标志 `recorded` / `deadLettered`
分别标记两条出路是否成功，**只有两个都是 false 才拒绝确认**：

```java
if (!recorded && !deadLettered) {
    throw new IllegalStateException("毒消息无法收敛：失败台账与失败主题均不可用，拒绝确认以避免消息丢失", error);
}
releasePoisonTaskState(msg);
```

还有个细节：`msg == null` 时 `discardPoisonMessage` 直接 `return`（`:234`），
**不写台账、不转投、也不补清理**——因为连 mediaId 都没有，四条 Key 一个都算不出来。
这种情况只能留下日志，是唯一「纯日志丢弃」的分支。

日志隐私也做了处理：`describe(msg)`（`:290-296`）只打印 mediaId、action、contentHash
和 **goalLength**（目标文本的长度，不是内容），注释说明是为了避免长文本或用户敏感内容进日志。

### Q59：SSE 断开、消息积压或视频被删除时，业务状态怎么闭环？

**SSE 断开不会丢任务**。MQ 投递成功表示任务已经被 Broker 接收，SSE 只是用户通知。事件发布失败时系统记录日志，不会把任务伪装成投递失败，也不会删除 activeKey 让用户重复提交。前端 SSE 断线会指数退避重连，每次订阅先返回当前 MySQL / Checkpoint 状态，因此能恢复当前进度和终态。当前没有持久化完整事件日志，断线期间的中间事件可能看不到，但最终业务状态不依赖 SSE。

**代码细节：投递成功和事件发布的分界线**

`AnalysisDispatchService.submit`（`:83-100`）的顺序是：
`rocketMQTemplate.convertAndSend(...)` 成功之后，**才**发 QUEUED 事件，
并且事件发布**单独包一层 try/catch**：

```java
try {
    taskEventService.publishAnalysis(mediaId, goal, resolvedMode, ...);
} catch (RuntimeException eventError) {
    // MQ 已经接单，通知失败不能把任务伪装成投递失败。
    log.warn("analysis_queued_event_failed ...");
}
return SubmissionResult.ACCEPTED;
```

对比**投递异常**的分支（`:86-91`）会执行三件事：删 `activeKey`、撤销暂存修订、返回 FAILED。
两条路径的区别就是「MQ 接没接单」——接了单就只记日志，没接单才回滚。这个区分可以作为一个
「状态机边界在哪里」的具体例子。

**当前状态怎么恢复**：SSE 订阅时（`TaskEventService.subscribe`，`:52-66`）会把
emitter 注册进 `ConcurrentHashMap` 后**立刻发一条初始事件**，前端据此渲染首屏；
真正的「当前状态」由 `GET /analysis/analysis-status?id=&goal=&mode=` 提供，
它读 MySQL / Checkpoint，不依赖事件流。所以断线重连的完整流程是
「重连 SSE + 拉一次 status」，中间事件丢了也不影响最终态。

**消息积压**：先区分生产突然加快、消费者整体变慢和毒消息反复重试。视频任务最可能的瓶颈是 ASR、OCR 和 LLM 外部依赖，盲目增加消费者可能反而打满模型配额或本机 CPU。应先看队列等待、阶段耗时、重试率和第三方限流，再决定扩容、限制生产速率或隔离失败任务。当前项目有 Trace 和失败表，但没有完整监控大盘，积压治理属于可讲的设计方法而非已验证能力。

**视频被删除**：消费者执行前和完成后都会检查视频是否仍然存在。用户在分析期间删除视频时，系统清理运行时产物并停止继续发布有效结果。

### Q60：如何证明异步和幂等设计有效？

异步化应比较同步接口与任务提交接口的响应时间、Web 线程占用和任务最终成功率；幂等应并发提交同一内容同一目标、制造 MQ 重投并在不同阶段故障，观察模型调用次数、结果记录数、Checkpoint 恢复点和最终状态。

最新版简历已经删除「60 秒压缩到 50ms」这类无法复现的数字，文档也不再保留。更稳的表达是：接口快速返回任务状态、长耗时处理转入 MQ，并通过状态和 SSE 完成业务闭环。

---

## 七、Redis 令牌桶限流与指数退避重试

### Q61：为什么视频 Agent 必须限流，当前令牌桶怎样实现？

一次分析不是一次轻量数据库查询，它可能触发多段 ASR、关键帧 OCR、Embedding、Planner、Executor 和 Critic。恶意点击或脚本请求会同时消耗本机 CPU、模型配额和 Token，最终拖慢所有用户。

限流位置放在 MQ 投递前，目的是避免无效任务先进入队列再排队烧资源。当前**没有自己维护 Redis Hash 和 Lua，而是使用 Redisson 的 RRateLimiter**：

- 用户级 `limit:ai:user:{userId}`：默认每分钟最多 5 次
- 全局 `limit:ai:global`：默认每分钟最多 30 次
- 两个许可都拿到后才能发送 MQ，多实例共享 Redis 中的同一配额

**代码细节：两行常量和一个方法**

```java
USER_REQUESTS_PER_MINUTE   = 5;    // AnalysisDispatchService.java:29
GLOBAL_REQUESTS_PER_MINUTE = 30;   // :30
ACTIVE_TTL = Duration.ofHours(6);  // :31

// tryAcquireQuota，:132-141
userLimiter.trySetRate(RateType.OVERALL, USER_REQUESTS_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
if (!userLimiter.tryAcquire()) return false;
globalLimiter.trySetRate(RateType.OVERALL, GLOBAL_REQUESTS_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
return globalLimiter.tryAcquire();
```

`RateType.OVERALL` 表示**全局限额而不是单连接限额**，这是多实例共享同一配额的关键；
用 `trySetRate` 而不是 `setRate`，意味着**只在限流器不存在时初始化**，
不会每次请求都重置已有计数器（否则限额永远无法耗尽）。

**顺序是「先用户后全局」，这个方向有代价**：用户许可先被消耗，若全局许可失败，
用户那一次配额不会归还。代码选择接受这个损耗换取简单（见 Q63），
要精确的话应该反过来先拿全局。

追问和证据检索走的是同一个 `requireAiQuota`（`:119-130`），
所以它们和分析任务**共用 5/30 这一组配额**，不能绕过成本护栏。
`requireAiQuota` 对 Redis 异常的处理是 fail closed：转成
`SERVICE_UNAVAILABLE`（`:127-129`），不是放行。

还有第二组别混：模式路由接口是**用户 10 次 / 分钟 + 全局 60 次 / 分钟**。追问和证据检索也会复用分析配额，避免绕过成本护栏。

这属于**请求级限流，不等价于 Token 限流**。一个 5 分钟视频和两小时视频消耗不同，当前方案只能先挡住请求洪峰。需要严格控制模型预算时，应根据上下文长度预估权重，或者在消费者侧按平台 TPM 再加一层加权配额。

### Q62：为什么选择令牌桶，而不是固定窗口或漏桶？

固定窗口在时间边界处可能连续放过两批请求，瞬时压力高于表面阈值；漏桶强调匀速流出，会让系统空闲时的正常小突发也排队。AI 分析希望限制长期速率，同时允许少量用户在令牌充足时快速提交，因此令牌桶更符合交互体验。

当前参数来自 Demo 容量保护，不是压测得到的最优值。生产环境需要结合模型 TPM、本机 CPU、队列等待和用户等级动态调整，而不是背住「5 和 30」。

### Q63：用户许可拿到了，但全局许可失败或 MQ 投递失败怎么办？

全局许可失败时请求返回 429，用户级已经消耗的许可当前不会归还。这会造成少量配额损耗，但不会超发请求，第一版选择简单和安全。要求更精确时可以先拿全局许可再拿用户许可，或者失败后显式归还。

MQ 投递异常会删除 activeKey 并撤销暂存修订，返回任务提交失败。令牌会随着限流器时间自然恢复，不尝试跨 Redis 和 MQ 做分布式事务。真正关键的是**不能留下一个永远显示「处理中」但实际没有消息的任务**。

### Q64：Redis 不可用时选择放行还是拒绝？

当前限流和活动任务判断都依赖 Redis，异常会导致提交失败，属于 **fail closed**。对于付费模型调用，这比 Redis 挂掉后无条件放行更安全，因为放行可能造成成本和并发同时失控。

代价是 Redis 故障期间新任务不可用。可以通过 Redis 高可用和明确的错误提示降低影响，不需要为了短暂可用性在每台实例里增加无法统一的本地限流器。

### Q65：第三方 API 重试和 RocketMQ 重试有什么区别？

| 层次 | 粒度 | 作用 | 当前实现 |
| :--- | :--- | :--- | :--- |
| 第三方 API 内部重试 | 一次 ASR 片段或一次模型节点 | 处理短暂网络错误、429、5xx | ASR 最多 3 次，失败后等待 1 秒、2 秒 |
| MQ 任务重试 | 整个分析任务 | 失败后重新进消费者，靠 Checkpoint 跳过已成功阶段 | 最多 3 次投递（2 次重投），之后进失败主题 |

API 内部重试保持当前任务上下文不变，它的粒度是一次具体外部调用。整个分析任务仍然失败后，消费者才抛异常交给 RocketMQ 重投，MQ 重投会重新进入消费者并利用 Checkpoint 跳过已成功阶段。

两层不能无限叠加，避免「每个 API 重试多次 × 整个任务再重试多次」无限放大。参数错误、鉴权失败、模型不存在、权限不足和预算耗尽不属于瞬时故障，不应该交给 MQ 继续重试。

必须讲准确：旧文档把第三方 API 指数退避完全写成 RocketMQ 的 10 秒、30 秒、1 分钟阶梯延迟，那是两层机制混淆。正确口径是 API 内部做短退避，任务级失败再由 MQ 重投，最后进入项目自建失败主题。

**代码细节：三层的具体参数**

| 层 | 最大次数 | 退避 | 可重试判定 |
| :--- | :--- | :--- | :--- |
| ASR 分片 | `MAX_ATTEMPTS = 3` | `1_000L << attempt` = 1s、2s | 仅 `IOException`；429 / ≥500 包装成 `RetryableAsrException` |
| LLM 调用 | `MAX_MODEL_ATTEMPTS = 3` | 同上 1s、2s | 408 / 429 / ≥500 可重试，其他 4xx 抛 `IllegalArgumentException` |
| MQ 任务 | `MAX_DELIVERY_ATTEMPTS = 3` | **无退避**，由 Broker 重投节奏决定 | 非永久失败且 attempt < 3 |

三层退避值一样、判定口径也基本一致，但**实现是三份独立代码**（`AliyunAsrUtils:94-101`、
`DeepSeekUtils:465-467`、`VideoAnalysisConsumer:168-178`），没有抽公共组件。
面试被问「能不能统一」时可以答：可以抽成一个 `RetryPolicy`，但三者失败成本和
计数载体（第三方 SDK / 本地状态 / Redis 计数）不同，第一版优先保证各自的边界清晰。

**LLM 侧比旧文档多一层，要讲全**：`chat()` 的最外层重试之下，
`structuredChat`（`DeepSeekUtils.java:380-388`）还有一层**只针对 JSON 解析失败的格式修复**——
追加「请严格返回合法 JSON」再问一次。所以一次模型节点最坏情况是
3 次网络重试 × 2 次解析尝试，不是 3 次调用。这个乘法是真实存在的放大。

### Q66：哪些错误应该重试，哪些应该立即失败？

ASR 当前只对网络异常、429 和 5xx 重试，其他 4xx 直接失败，这个边界比较清楚。LLM 目前对 RuntimeException 统一重试，可能把参数错误、鉴权失败等永久错误也重复三次，**这是当前实现明确需要继续细化的地方**，面试可以主动提。

更合理的分类是：超时、连接失败、429 和部分 5xx 可重试；鉴权失败、参数错误、模型不存在和内容策略拒绝直接失败；结构化 JSON 解析只做一次格式修复。错误分类比「任何异常都重试」更能防止重试放大。

**代码细节：分类器长什么样**

`DeepSeekUtils.isRetriableModelFailure`（`:450-463`）的判定顺序：

```java
if (current instanceof NonRetriableException) return false;   // 显式标记永久失败
if (current instanceof RetriableException)    return true;    // 显式标记可重试
if (current instanceof HttpException http)                    // 按状态码
    return http.statusCode() == 408 || == 429 || >= 500;
// 其他类型继续沿 cause 链向下找，最多 8 层
```

关键是**先查两个显式标记类再查状态码**，允许调用方主动把某个异常定性。
`MAX_CAUSE_DEPTH = 8` 和消费者侧的 16 是两个独立常量，容易记混。

对比 ASR 侧（`AliyunAsrUtils:85-90`）只有**两分支**：429/≥500 → `RetryableAsrException`，
其他非 2xx → `IllegalArgumentException`。没有显式标记类，也没有 408 分支。
**两边不对称是当前的真实状态**，被问到「为什么 LLM 能重试 408 而 ASR 不能」时，
诚实回答是「没有刻意设计，是两次独立实现的差异，可以对齐」。

`SegmentedTranscriptionService` 在抛「所有 ASR 分片均处理失败」时
**显式带上 `lastSegmentError` 作为 cause**（`:59-64`），注释解释了原因：
丢掉 cause 后消费者只看得到笼统的 `IllegalStateException`，
参数错误会被误判成抖动而反复重试整条 ASR + LLM 流水线。这是「cause 链保真」的实例。

### Q67：为什么使用指数退避，当前方案还有什么不足？

立即重试容易在下游仍未恢复时继续失败，固定间隔会让大量任务同时形成周期性冲击。指数退避让故障持续越久、请求间隔越长，适合短暂网络和限流恢复。

当前退避通过 Thread.sleep 实现，**会占用执行线程，而且没有随机抖动**，多个任务同时失败仍可能一起醒来。当前并发小可以接受，规模上升后应增加 jitter，或者把长等待交给延迟消息和调度器，避免阻塞工作线程。

### Q68：重试会不会造成重复扣费和重复结果？

外部模型请求是否已经成功但响应丢失，调用方通常无法完全确认。对于纯生成接口，再次调用可能产生第二次费用，**无法保证第三方绝不重复计费**。

但**业务结果不会重复**：最终结果按 contentHash + goalDigest 进入同一 Checkpoint，数据库使用 upsert 保存阶段，多次执行收敛到同一任务。

能传幂等键的第三方接口应传 taskId 或请求摘要；不能支持时，只能通过有限重试、预算统计和业务落库幂等控制影响。这里要区分「业务结果不重复」和「第三方绝不重复计费」，后者当前无法保证。

### Q69：怎样验证限流和重试不是摆设？

限流测试需要并发使用同一用户和多个用户提交任务，验证用户级与全局阈值、HTTP 429、MQ 实际消息数和 activeKey 清理。重试测试分别模拟超时、429、500、鉴权失败和结构化输出错误，记录调用次数、等待时间、最终阶段和是否进入失败主题。

结果指标看任务成功率和错误分类正确率，过程指标看限流拒绝数、平均重试次数、MQ 重投次数和预算终止次数，代价指标看额外延迟、线程占用和重复 Token。当前代码已经有这些 Trace 入口，但没有正式实验数据，面试中不虚构成功率。

---

## 八、底层技术地基

### Q70：RocketMQ 消息是「至少一次」还是「恰好一次」？怎么应对重复？

至少一次。消费者按幂等设计应对：稳定业务 Key（contentHash + goalDigest）+ 状态检查 + Checkpoint + 可重复写。项目不追求分布式事务或引入去重中间件。

### Q71：Redisson 分布式锁原理，什么是 WatchDog？

基于 Redis SETNX 加过期时间实现，通过 Lua 保证加锁和释放的原子性；WatchDog 是后台定时续期的看门狗，线程存活就续期，线程退出就停止。项目用它做上传的「合并锁」和分析任务的「目标级锁」，锁 Key 由业务标识构造。

### Q72：SETNX 和 Redisson 锁的区别？

SETNX 只有「占位 / 幂等拦截」语义，没有自动续期和归属校验；Redisson 提供线程归属、原子释放和 WatchDog 续期。项目中 activeKey 用 SETNX 做入口拦截，并发执行控制用 Redisson 锁做消费者互斥，两者职责不同。

### Q73：CompletableFuture 和线程池怎么配合？为什么不直接用多线程？

两个任务分支并行执行，用 CompletableFuture 组合汇合；配独立有界线程池，避免公共 ForkJoinPool 被阻塞、避免无上限堆积。什么时候该拆 Topic 或拆服务：当两路需要独立硬件或独立扩容时。

**代码细节：ASR/OCR 两路用的是 `Future`，不是 `CompletableFuture`**

这一点要讲准确，否则容易被追问倒。`VideoContextService.submitBranch`（`:166-184`）调的是
`ThreadPoolTaskExecutor.submit(...)`，返回 **`java.util.concurrent.Future`**，
汇合用的是 `future.get(remainingNanos, NANOSECONDS)`（`awaitBranch`，`:186-191`）。
`CountDownLatch` 出现在 `branchesFinished`（`:74`），用于超时取消后等待分支真正退出。

**但 `CompletableFuture` 在项目里确实存在，只是用在别处**，别把整个项目说成没用它：

| 位置 | 用法 |
| :--- | :--- |
| `controller/AnalysisController.java:263-265` | 追问和证据检索走 `CompletableFuture.supplyAsync(..., aiTaskExecutor)`，接口返回 `CompletableFuture<Result<T>>` |
| `service/VideoContextService.java:182` | `executor.submit` 被拒绝时，用 `CompletableFuture.completedFuture(BranchResult.failure(e))` 构造一个「已完成但失败」的结果，保证两条分支的**返回类型一致** |

所以正确的说法是：**两路模态分支用 `Future` 汇合，交互式接口用 `CompletableFuture` 异步化**。
`VideoContextService:182` 那个用法是个好细节——线程池队列满被 `AbortPolicy` 拒绝时，
不抛异常而是把拒绝包装成 `BranchResult.failure`，让 `finishContext` 的统一降级逻辑接管，
而不是让一次线程池拒绝直接打到用户。

如果要把两路也改成 `CompletableFuture`，自然写法是 `supplyAsync(..., asrExecutor)` 配
`thenCombine`；但当前两路**互不依赖**，`CompletableFuture` 的编排能力用不上，
`Future` 反而更直白。

四组线程池的实际用途也要对齐（`config/ThreadPoolConfig.java`）：

| 线程池 | 谁在用 |
| :--- | :--- |
| `aiTaskExecutor` | 分析任务主线程、`TranscriptionTaskService` 的 `@Async`、控制器的交互式调用 |
| `asrExecutor` | `VideoContextService` 提交**整条 ASR 分支**（不是单个分片） |
| `ocrExecutor` | `VideoContextService` 提交**整条 OCR 分支** |
| `modelCallExecutor` | `DeepSeekUtils` 的模型调用 |

`ocrExecutor` 的核心/最大线程数都是 `min(8, max(1, cores/2))`（`:26`），
**core == max** 且队列只有 20，所以它是「按 CPU 定量的固定并发」，
不像前两个有 4→8 的弹性扩容空间。

### Q74：FFmpeg 的 select 过滤器、scene score、showinfo 是干什么的？

`select='gt(scene,0.35)'` 按场景变化选帧；`showinfo` 把每帧的 `pts_time` 打到日志，Java 解析后换算成毫秒。这是 OCR 文本能回到视频时间轴的机制。场景检测负责找「可能发生有效变化」的画面，并不负责理解画面内容。

**代码细节**：`select` 过滤器的表达式在 `VideoContextService.java:213`，
`pts_time` 的解析正则是 `PTS_TIME = Pattern.compile("pts_time:([0-9.]+)")`（`:43`）。
FFmpeg 的 stderr 被合并进日志并重定向到临时文件（`runCommand`，`:307-332`），
超时是 `process.waitFor(15, TimeUnit.MINUTES)`（`:315`）——比 ASR 侧同一常量
在 `SegmentedTranscriptionService.java:92` 的值一样，但两处硬编码，没有抽公共常量。
命令执行完在 `finally` 里删日志文件（`:330`）。

### Q75：什么是感知哈希（dHash），为什么能去重？

把图片缩放到 9×8 灰度图，逐行比较相邻像素亮度，生成 64 位差异哈希；两张图异或后统计汉明距离，不超过 5 就判为相似，跳过后续 OCR。它计算快、对缩放和轻微偏移鲁棒，但不适合检测内容本质相同而构图不同的两张图。

### Q76：Embedding 和余弦相似度是什么？和关键词检索各擅长什么？

Embedding 把文本映射成向量，语义相近的文本向量夹角小，用余弦相似度打分；关键词擅长精确符号。项目里两者混合排序，具体是语义 + 语音关键词 + 视觉关键词三路打分。

### Q77：SSE 和 WebSocket、轮询的区别？为什么用 SSE？

SSE 是单向的服务端推流，走 HTTP 长连接，自动重连机制简单，适合「服务端 → 前端」的任务进度推送。WebSocket 是双向全双工，这里不需要客户端推流。轮询浪费请求且有延迟。断线后前端指数退避重连，重新订阅时先返回当前状态。

**代码细节：事件名、载荷和多实例广播**

`TaskEventService` 里的常量（`:27-32`）：
`ANALYSIS = "analysis"`、`TRANSCRIPTION = "transcription"`、
**Redis 频道 `dovideo:task-events`**、`STREAM_TIMEOUT_MS = 30 * 60 * 1000L`（30 分钟）。

SSE 事件名是 **`"task-status"`**（`:116-128`），载荷是：

```java
record TaskEvent(TaskStatus.State state, String result, String message, TaskStage stage)
```

订阅 Key 的格式（`:137-142`）：`{type}:{mediaId}:{suffix}`，
其中 `suffix` 对分析任务是 `goalDigest(goal, mode)`，对转写任务是固定字符串 `"default"`。
**所以不同目标、不同模式的 SSE 订阅天然隔离**，不会互相串进度。

多实例广播的做法值得讲：发布时先 `convertAndSend` 到 Redis 频道，
**如果本地没有订阅者或发布抛异常，就退化为 `publishLocal` 只发本机**（`:68-96`）。
其他实例收到频道消息后各自 `publishLocal`（`onMessage`，`:98-108`）。
这个设计的含义是：**进度推送尽力而为，不保证送达**——Redis 挂了本实例的用户仍能收到进度，
但跨实例的推送会丢。由于前端重连时会先拿当前状态（见下方 Q59），丢了中间事件不影响最终态。

终态事件发送后 emitter 会被 `complete()` 关闭（`:116-128` 里的 `event.terminal()` 分支），
避免连接悬挂。

### Q78：什么是 Flyway？项目里怎么用？

数据库迁移工具，按版本顺序执行 SQL，保证表结构可以版本化管理。项目启动时会自动初始化所需数据表。

**代码细节**：迁移脚本在 `src/main/resources/db/migration/`，命名规则 `V{n}__{description}.sql`。
当前有 `V1__create_core_tables.sql`（`users` / `media_files` / `agent_checkpoints` 等核心表）
和 `V2__add_media_content_hash.sql`（给 `media_files` 加 `content_hash` 列）。
配置是 `spring.flyway.enabled=true`、`locations=classpath:db/migration`、
**`baseline-on-migrate=true`**——后者让 Flyway 能接管一个已有数据的库，否则在非空库上启动会直接失败。

### Q79：MyBatis-Plus 的 upsert 是干什么的？

存在则更新、不存在则插入，依赖唯一键。项目里 Checkpoint 用 upsert 收敛重复执行的结果，让同一任务的多次执行落库到同一行。

**代码细节：项目里其实是手写 SQL，不是 MyBatis-Plus 的 API**

这点容易被追问，要讲准确。`agent_checkpoints` 表的 upsert 没走 MyBatis-Plus，
而是 `mapper/AgentCheckpointMapper.java:20-28` 的注解 SQL：

```sql
INSERT INTO agent_checkpoints (media_id, checkpoint_key, stage, payload)
VALUES (...) ON DUPLICATE KEY UPDATE
  stage = VALUES(stage), payload = VALUES(payload), updated_at = CURRENT_TIMESTAMP(3)
```

依赖 MySQL 的 `ON DUPLICATE KEY UPDATE` 语义，冲突键是主键 `(media_id, checkpoint_key)`。
MyBatis-Plus 在这个项目里主要用于 `MediaFile` 这类实体的 `selectById` / `updateById` / `insert`
（见 `MediaService.java`），Checkpoint 这一层是手写 SQL + `@Transactional`。
所以「用 MyBatis-Plus 的 upsert」这个说法不成立，正确说法是「MySQL 原生 ON DUPLICATE KEY」。

---

## 九、高频陷阱追问

### Q80：你刚说「内容级去重」，是不是实现了文件秒传？

**不是。** 当前最完整的复用发生在分析阶段：两个媒体记录拥有相同 contentHash 时，相同目标可以复用已有 AnalysisResult，不同目标可以复用视频级上下文和索引，避免重复 ASR、OCR 和 Embedding。

上传阶段仍然按 uploadId 保存各自文件，合并后才得到 MD5；media_files 对 content_hash 只有普通索引，**没有实现跨用户文件对象秒传**。所以简历里的「内容级去重」应解释为视频解析与 Agent 消费去重，不能扩大成对象存储层只保存一份文件。

### Q81：你说了半天幂等，分布式锁能保证「恰好一次」吗？

**不能。** 锁只能约束持锁期间的并发；进程崩溃、Redis 故障和长时间停顿都会导致重新执行。真正的兜底是稳定业务 Key（contentHash + goalDigest）+ completedKey + MySQL Checkpoint + upsert 可重复写。消费幂等是**共同收敛的结果，不是一把锁**。

### Q82：Critic 和 Executor 用同一个模型，是不是自我开卷？

**是同一个模型，确实可能共享盲区。** 所以我的设计是让确定性程序先兜住结构和证据存在性（时间戳、来源、原文匹配），Critic 只负责语义支持这一层。质量要求更高时可以换独立的 Critic 模型或扩大人工评测集，但第一版不为了 Multi-Agent 形式增加成本。

### Q83：检索语义权重是 0.7 吗？

**不是，现在是 0.6。** 作者早期文档写过「语义 0.7 / 关键词 0.3」，代码已经演进为三路打分：Chunk 级是**语义 0.6 + 语音关键词 0.25 + 视觉关键词 0.15**，Segment 级是 **chunk 得分 0.55 + 语音 0.25 + 视觉 0.20**。以代码为准，顺带可以展示「画面文字是独立评分通道」。

### Q84：你说「并行 ASR + OCR」，是并行调两个大模型吗？

**不是。** ASR 是按 60 秒切片调用托管接口；OCR 是本地 Tesseract 跑关键帧。两路是在同一个分析任务内部并行，用 CompletableFuture 汇合，不是两个独立部署的微服务。只有需要独立硬件或独立扩容时才会考虑拆成两个 Topic 或服务。

### Q85：视频处理这么快，是不是把整个视频给模型了？

**不是。** 两小时视频直接喂模型会 Token 爆炸、无关片段干扰判断。实际链路是：先抽 VideoContext → 聚合成 5 分钟 Chunk 并生成摘要、关键词和向量 → 按用户目标检索 Top3 → 展开原始 Segment 且受 24000 字符预算限制。模型看到的是**目标相关的原始证据**，不是全量文本。

### Q86：Token 预算耗尽重试一下不就好了？

**不行。** 重试不改变预算条件（超时、超 Token、超费用），只会继续消耗资源。所以预算耗尽进入独立终态 `BUDGET_EXHAUSTED`，不参与 MQ 重试。

### Q87：为什么不用现成的向量检索 + 大模型 RAG 框架（LangChain / LlamaIndex）？

本项目的检索和编排是**定制但轻量**的：分块粒度、证据核验、Checkpoint 和预算终止都和视频领域强绑定，用通用框架反而要额外适配 VideoContext 的时序结构和 Claim 校验。可以这样回答：框架适合通用场景，而这里证据校验和断点恢复才是核心价值，自己实现更可控。

### Q88：两小时视频，ASR 60 秒一片要调 120 次，不会很慢很贵吗？

会，这正是设计里权衡过的：60 秒是「请求数量」和「定位精度」的折中。缓解方式是 ASR 结果作为**视频级 Checkpoint 缓存**，同一视频的多个目标或继续追问可以复用一次转写，不重复调用。成本护栏还有限流和预算终止。

### Q89：既然有 Checkpoint，为什么还需要 MQ 重投？

Checkpoint 决定「**从哪继续**」，MQ 决定「**会不会再来一次**」。没有 MQ，任务失败后没人会重新触发消费者；没有 Checkpoint，重投就会从头再来。两者配合才是「重投 + 从最近阶段继续」。

---

## 十、容量、性能与规模

> 这一节把散落在各处的数字集中起来。面试问「能处理多大视频、多快、扛多少并发」时按这里回答。

### Q90：它能处理多大的视频？多长时间的视频？

**大小上限约 2GB，时长没有硬性上限。** 需要把两个约束分开讲，因为它们的来源不同：

| 约束 | 来源 | 数值 |
| :--- | :--- | :--- |
| 单个分片 | `ChunkUploadService.MAX_CHUNK_BYTES` | 5 MB |
| 分片总数 | `ChunkUploadService.MAX_TOTAL_CHUNKS` | 410 |
| 因此文件上限 | 5 MB × 410 | 约 2 GB |
| HTTP 层兜底 | `spring.servlet.multipart.max-file-size` | 2048 MB |
| 视频时长 | — | **无显式限制**，由超时预算间接约束 |

所以简历写「GB 级」是成立的，但**不能声称支持任意 10GB 文件**——要支持更大视频，必须同时调整分片上限、服务端请求体上限、本地临时磁盘预算和合并方式，不是改一个 totalChunks 就行。

**代码细节：这几个数字在代码里的位置**

| 约束 | 常量 / 配置 | 值 |
| :--- | :--- | :--- |
| 单分片上限 | `ChunkUploadService.MAX_CHUNK_BYTES`（`:39`） | `5L * 1024 * 1024` |
| 分片总数上限 | `ChunkUploadService.MAX_TOTAL_CHUNKS`（`:40`） | `410` |
| HTTP 请求体上限 | `spring.servlet.multipart.max-file-size` | `2048MB` |
| 请求总大小 | `spring.servlet.multipart.max-request-size` | `2048MB` |
| VideoContext 总 deadline | `VideoContextService.java:90` 硬编码 | 60 分钟 |
| FFmpeg 单次执行超时 | `SegmentedTranscriptionService.java:92` / `VideoContextService.java:315` | 15 分钟 |
| AgentLoop 时长预算 | `agent.budget.max-duration-ms` | 120000 ms |

**注意 410 × 5MB = 2050MB，比 multipart 的 2048MB 略大**，
所以理论上限其实是 HTTP 层的 2048MB 先触发，410 片这个数字留了一点余量。
这不是设计巧合就是没对齐，被问到可以诚实说两者是同量级约束，实际以先触发的为准。

另一个点：`MAX_TOTAL_CHUNKS = 410` 同时被用来校验 `initialize` 时的 `totalChunks` 参数（`:60-62`），
所以**前端必须按 ≤410 来分片**，超过直接抛 `IllegalArgumentException`。
简历如果写了具体分片数，要和这个常量对上。

时长方面代码里**没有对视频时长做校验**，真正的约束来自两个时间预算：VideoContext 构建的 ASR + OCR 两路共享 **60 分钟**总 deadline；AgentLoop 的阶段间时长预算默认 **120 秒**。所以一个两小时课程视频，ASR 按 60 秒切片会产生约 120 次调用，这是设计时权衡过的成本点，但因为 ASR 结果进入视频级 Checkpoint 缓存，同一视频的多个分析目标和后续追问只转写一次。

### Q91：处理能力、处理效率怎么样？

**先给结论：没有任何压测数据，只有架构上的量级判断，不能编耗时数字。**

可以说清楚的是并发容量由四组有界线程池和限流共同决定：

| 线程池 | 核心 / 最大 / 队列 | 用途 |
| :--- | :--- | :--- |
| `aiTaskExecutor` | 4 / 8 / 100 | 分析任务主线程 |
| `asrExecutor` | 4 / 8 / 50 | ASR 切片调用 |
| `ocrExecutor` | CPU 核数 / 2，上限 8 / 20 | 本地 Tesseract |
| `modelCallExecutor` | 4 / 8 / 20 | LLM 调用 |

四组都用 `AbortPolicy`，队列满直接拒绝，避免无上限堆积拖垮进程。数据库连接池 Hikari 默认 10。

**代码细节：线程池参数是硬编码的，不是配置项**

`config/ThreadPoolConfig.java:14-33` 里四组线程池全部写死在代码里，
用同一个私有工厂方法 `executor(prefix, coreSize, maxSize, queueCapacity)`（`:35-46`）构造。
共同设置：`AbortPolicy`、`setWaitForTasksToCompleteOnShutdown(true)`、
`setAwaitTerminationSeconds(30)`。

线程名前缀分别是 `AI-Thread-`、`ASR-Thread-`、`OCR-Thread-`、`LLM-Thread-`，
排查线程栈时可以直接对应到用途。**改并发度要改代码重新编译**，
不是改一行配置——被问到「怎么调优」时这是要承认的边界。

队列容量也值得对比：`aiTaskExecutor` 100、`asrExecutor` 50、`ocrExecutor` 20、
`modelCallExecutor` 20。AI 主任务队列最长，因为它承接的是用户提交；
模型调用队列只有 20，因为每次调用都占着第三方配额，堆积没有意义。

请求入口有全局限流 **30 次/分钟**，这实际上就是当前单机部署的吞吐上限——**不是处理能力只有 30，而是有意把进入队列的速率压在 30/分钟以内**，避免无效任务排队烧资源。

效率上能讲的是：ASR 和 OCR 两路并行，总耗时接近较慢的那一路而不是相加；同一视频的解析结果跨目标复用，第二个目标不用重新转写。

**更正：长视频的 ASR 分片是「串行逐个调用」，不是「并发提交」。**
`SegmentedTranscriptionService.transcribe`（`:44-58`）是一个普通 `for` 循环，
第 `i` 片调完 `aliyunAsrUtils.audioToText` 才处理第 `i+1` 片。
切成 60 秒的作用是**把失败范围缩小到单片**（单片失败只记 `asrSegmentFailures` 继续），
以及**让时间戳可以由序号推算**，不是为了提速。这一点如果被追问「120 次调用怎么扛」，
诚实回答是「串行调用，两小时视频的 ASR 耗时是 120 次调用的累加；
要提速需要把分片提交到 `asrExecutor`，但必须配合第三方限流控制并发度，当前没做」。

**必须主动说的边界**：以上都是架构设计，仓库里没有压测和回归数据。被追问「一个 2 小时视频要跑多久」时，正确回答是「取决于第三方 ASR 和 LLM 的响应速度，本地处理不是瓶颈，我没有做过端到端计时」，而不是编一个数字。

### Q92：如果同时有 1000 个解析请求，系统怎么处理？

答案分两段：**绝大多数请求在入口就被挡住了，进去的按队列排队。**

第一段是入口拦截。1000 个请求同时到达时，全局限流是 30 次/分钟，也就是说**大约 970 个会直接拿到 429**，根本不会进入 MQ。这是有意的设计——限流放在 MQ 投递前，就是为了避免无效任务先入队再排队烧资源。同时同一「内容指纹 + 目标」的重复提交会被 activeKey 拦成 DUPLICATE。

第二段是队列排队。真正进入 MQ 的任务由消费者按 FIFO 顺序取出，消费并发用的是 RocketMQ 容器默认值（代码里刻意没有覆盖 consumeThreadNumber，因为属性名在 rocketmq-spring 各版本间变更过）。每个消费者拿到任务后要竞争目标级 Redisson 锁，再走主链。

所以「1000 个请求」的正确回答是：**入口限流把瞬时洪峰削成 30/分钟的稳定流，风险不在打挂系统，而在于用户等待时间和任务积压**——1000 个任务按 30/分钟算需要约 33 分钟才能全部入队。诚实边界：当前没有队列等待时长的监控大盘，也没有按用户等级的优先级，这是一个只能讲设计思路、不能讲治理结果的点。

### Q93：怎么处理长短视频的资源矛盾，防止长视频饿死短视频？

**要主动承认：当前没有实现防饥饿机制。** 我搜过代码，没有优先级队列、没有按视频时长分队列、也没有公平调度。当前就是一条 FIFO 队列加一组有界线程池。

实际的饥饿风险是这样的：一个 2 小时视频会产生约 120 个 ASR 切片任务，提交到只有 4 个核心线程的 `asrExecutor`；这些任务会把线程池占住相当长时间，同时提交的短视频任务只能排在其后。**这是当前实现明确的能力缺口。**

如果要设计和实现，我会分三步走：

1. **按视频时长分队列**——短任务进高优先级队列，长任务进低优先级队列，用两级队列保证短视频先走。这是最小改动，也最直接解决「短视频等长视频」的问题。
2. **给长任务拆分子任务并让出调度**——把长视频的 ASR 切片提交成独立的小任务，而不是一个大任务占住线程，这样短视频有机会插进线程池的空隙。这也是当前架构离得最近的一步，因为 ASR 本来就是按 60 秒切片提交的。
3. **在消费者侧做加权配额**——按视频时长预估资源权重，长视频消耗更多配额，从入口就控制长视频的并发量，而不是等它进了线程池再抢。

再多说一句：真正生产环境还需要配合公平性指标（比如短视频 P99 等待时间）来验证，光有优先级队列不解决所有问题，因为长视频也可能会被无限推后导致饿死。

### Q94：如果要把这个项目推到生产环境，还需要处理哪些问题？

我会按「不解决就会出事」的顺序来排，而不是按工作量。

**第一优先级：正确性和数据安全**

1. **跨存储没有事务**——上传合并阶段跨 MinIO、MySQL、Redis 三个存储，进程宕机可能留下孤立对象或重复记录。生产化需要确定性完整文件路径 + 数据库唯一业务键 + 对账补偿任务。
2. **限流是请求级，不是 Token 级**——一个 5 分钟视频和两小时视频成本差几十倍，用同一个配额挡不住成本失控。需要按上下文长度预估加权配额。
3. **Checkpoint 键没有编码 Prompt / Schema / Embedding 版本**——模型或 Prompt 升级后，旧 Checkpoint 会被错误复用。需要按影响范围做局部重建。

**第二优先级：可观测性和运维**

4. **没有监控大盘**——现在只有 Trace 和失败任务表，但队列积压、降级次数、预算终止率都没有告警。生产环境需要接入指标系统并对关键阈值告警。
5. **失败主题缺少运营闭环**——当前是自建失败主题 + 失败任务表，能查能重投，但没有告警、责任人和防止毒消息无限回放的审批规则。
6. **没有 MinIO 临时对象的生命周期清理**——放弃的上传和过期的分片会一直占着对象存储，需要给 `chunk-uploads/` 前缀配生命周期策略。

**第三优先级：容量和体验**

7. **没有防饥饿调度**（见 Q93），长短视频混跑时短视频会被拖慢。
8. **降级发生时用户无感知**——向量服务挂掉后退回关键词排序，质量下降但前端没有任何提示，应该把降级状态透出到 Trace 或前端。

**第四优先级：质量闭环**

9. **评测集只有 4 个 Golden Case 且没跑出数据**，用户反馈也没有形成长期数据集。生产环境需要有持续回归和人工标注的评测闭环。

这些里面，**第 1、2、5 条是上线前必须解决的，其余可以边跑边补**。

### Q95：如果面对上千用户上传长视频并反复追问，你会怎么设计缓存？

先看清楚当前的缓存现状和瓶颈在哪。现在 Redis 承担的是上传进度、activeKey、限流状态、Checkpoint 热缓存（7 天）和 Trace。**真正会随用户规模爆炸的是 Trace 和 Checkpoint 这两类。**

我会分四层来设计：

**第一层：明确缓存分级，不把所有东西都塞进 Redis。** 按「丢失后的代价」分三类——丢了任务就永久丢失的（业务事实，只能放 MySQL）；丢了要重算的（VideoContext、Chunk、向量，可以缓存但要有 MySQL 真源）；丢了只影响速度的（Trace、进度、限流状态，可以放心 LRU）。当前前三类的划分是对的，问题是 Trace 这类低价值数据也在占 Redis 内存。

**第二层：把「热」的粒度从视频级下沉到目标级和片段级。** 用户反复追问时，真正高频访问的是同一个视频的 VideoContext 和向量，而不是每个目标的 Plan 和 Result。这两类的 TTL 应该分开：视频级产物（重算成本最高）TTL 长甚至常驻，目标级结果 TTL 短。当前统一 7 天的策略在这个规模下既浪费内存又不够灵活。

**第三层：加本地缓存挡在 Redis 前面。** VideoContext 这类「写一次读多次」的数据适合加一层进程内缓存（比如 Caffeine），配合版本号或 Redis pub/sub 做失效通知。这样反复追问不会每次都打 Redis。注意要避免本地缓存和 Redis 不一致，所以只缓存视频级产物这种不可变数据。

**第四层：热点隔离。** 如果一个视频被反复追问（比如一个爆款课程），它的 Chunk 向量查询会成为热点。可以在 Qdrant 前面加一层查询结果缓存，key 是「mediaId + 归一化后的 query」。

要主动说的边界：**以上都是设计思路，当前项目没有到需要这些的规模**，而且加缓存层本身会引入一致性问题，所以引入时机应该是「监控显示 Redis 或 Qdrant 成为瓶颈」之后，而不是提前堆组件。

---

