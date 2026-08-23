# DoVideoAI 面试学习问答题

> 用途：给准备用 DoVideoAI 面试的人，按「先理解、再能讲、最后经得起拷打」的顺序，整理成一份问答题学习手册。
> 与仓库内另外两份文档的关系：
> - `interview-qa-v2.md` / `interview-qa-six-pillars.md`：**作者完整版答案**，内容最全，适合最后通读校准。
> - 本文档：**学习地图**，告诉你「先学什么、学到什么程度、面试会怎么追问、哪里容易说错」，每条答案都标了源码位置，并按当前代码核实过。
>
> ⚠️ **重要**：本文档中的数字和实现口径都以当前代码为准。文档里标了「⚠️雷区」的地方，是作者旧文档写法和当前代码不一致的点，面试时**必须按代码口径讲**，否则一问细节就露馅。

---

## 目录

1. [学习路线：先学什么、学完是什么水平](#0-学习路线总览)
2. [第一层：项目整体认知（自我介绍用）](#1-项目整体认知)
3. [第二层：六大核心模块（主战场）](#2-六大核心模块)
4. [第三层：底层技术地基（被追问的弹药）](#3-底层技术地基)
5. [第四层：诚实边界（决定面试成败）](#4-诚实边界)
6. [第五层：高频追问与陷阱问题](#5-高频追问与陷阱)
7. [附录A：代码速查表](#附录a代码速查表)
8. [附录B：学习节奏计划](#附录b学习节奏计划)

---

## 0. 学习路线总览

```
目标：能在 1-2 分钟内讲清项目 → 能对六大模块各讲 5 分钟 → 能扛住 10+ 连珠炮追问
   ↓
第 1 步  读本文档「第一层」+「第二层」整体，配合 README 的 mermaid 时序图，把链路走一遍
   ↓
第 2 步  按「附录B」节奏，逐个模块「读代码 → 对本文档答案 → 自己复述」
   ↓
第 3 步  通读两份完整版面试文档，校准细节
   ↓
第 4 步  找朋友模拟面试，专挑「为什么/失败怎么办/边界在哪」三类问题
```

**前置要求（面试官默认你会，不会先补课）：**

| 主题 | 至少要懂 | 项目里用在哪 |
| :--- | :--- | :--- |
| Java / Spring Boot | 依赖注入、异步、事务、@Service/@Component | 全项目 |
| MySQL / MyBatis-Plus | 表、CRUD、upsert、索引 | media_files、checkpoint |
| Redis | String/Hash/Set、TTL、SETNX | 上传分片、幂等键、限流 |
| HTTP / REST | 状态码、202/409/429 | 上传、分析接口 |
| 多线程 | 线程池、CompletableFuture | ASR 与 OCR 并行 |

**进阶前置（讲 Agent 模块前补）：**
- LangChain4j / OpenAI 兼容接口调用（chat + 结构化 JSON 输出）
- 基本的 prompt 工程（如何让模型稳定返回 JSON）
- Embedding 与余弦相似度的直觉

---

## 1. 项目整体认知

> 目标：1-2 分钟讲清「这是什么 + 两条主线 + 一句话核心思路」。

### Q1-1：请用一两分钟介绍 DoVideoAI

**要讲的骨架：**

1. **一句话定位**：面向长视频内容理解的 Video Agent——把课程/会议/录屏转化成「可检索、可追溯、可继续追问」的结构化知识。
2. **两条主线**：
   - **工程主线**：视频文件大、处理慢、第三方接口不稳定 → 分片续传、RocketMQ、Redisson、限流、重试、Checkpoint。
   - **Agent 主线**：单次总结无法适配不同目标、长视频上下文太多、结论缺依据 → VideoContext、分层摘要、混合检索、Planner-Executor-Critic。
3. **一句话核心思路**：先把视频加工成**可复用、可追溯的证据资产**，再让 Agent 在明确的**证据 / 状态 / 预算边界**内做目标驱动的分析，而不是把完整视频直接丢给模型碰运气。
4. **一个完整例子**（背熟，最有说服力）：用户上传 2 小时课程，目标「整理二叉树知识点 + 复习题」。系统先抽语音+关键帧文字 → 检索相关片段 → Planner 拆任务 → Executor 逐项生成并绑定时间戳证据 → Critic 校验 → 返回可点击回看原视频的笔记。

**源码锚点**：`README.md`（mermaid 时序图是完整链路）、`docs/interview-qa-six-pillars.md` Q1。

---

### Q1-2：从用户上传视频到拿到结果，完整流程是什么？

按这条链背（面试官最爱让人串一遍）：

```
1. 前端初始化上传任务 → 后端生成 uploadId，存 Redis Hash（文件名/总分片数/userId，TTL 1 天）
2. 前端 ≤5MB 分片上传 → 后端「先写 MinIO 分片，再记 Redis Set 成功序号」
3. 全部分片完成 → 调 complete → 后端持 Redisson 合并锁，按序合并分片、边写边算 MD5 → 存完整文件 + media_files 记录
4. 用户提交分析目标 → 校验视频归属 + 计算 contentHash 与 goalDigest → SETNX activeKey（幂等）→ 用户级+全局限流 → 投递 RocketMQ → 返回 202
5. 消费者收到消息 → 拿 Redisson 锁 → 查 Checkpoint（有可复用结果直接返回；没有则往下）
6. 没有 VideoContext 才构建：FFmpeg 60s 切片 + ASR ‖ 场景检测抽帧 + OCR（两路并行）→ 按 60s 窗口融合成 VideoSegment
7. 长视频聚合成 5 分钟 Chunk → 摘要 + 关键词 + Embedding → Qdrant 索引
8. 按 userGoal 混合检索 TopK 证据 → Planner 拆任务 → Executor 生成结论+证据 → Critic 校验
9. Critic 不通过 → 定向补证据/改计划，最多两轮
10. 结果写 MySQL（Checkpoint 真源）+ Redis（7 天热缓存）→ SSE 推阶段与终态 → 前端展示，可继续追问
```

**源码锚点**：`ChunkUploadService.java`（上传）、`AnalysisDispatchService.java`（投递）、`VideoAnalysisConsumer.java`（消费）、`AiService.asyncAnalyze`（主链）。

---

### Q1-3：这个项目真正解决的用户问题 / 难点是什么？

- **用户价值三层**：稳定上传（长视频进得来）→ VideoContext+检索（证据找得到）→ AgentLoop（不同目标产出不同结构化产物）。
- **工程难点**：长耗时链路的可靠性。上传/FFmpeg/ASR/OCR/Embedding/LLM 任意一步失败，不能让人从头再来，也不能因 MQ 重投产生重复副作用。
- **Agent 难点**：结果质量。模型要看到「足够但不过量」的证据；每条结论要能回到原视频核验；Critic 只回一句「不通过」没用，下一轮必须真的有**新计划或新证据**。

---

### Q1-4：主要数据分别存在哪里？为什么这样分？

| 存储 | 存什么 | 角色 |
| :--- | :--- | :--- |
| MySQL | 用户、视频资产、最终结果、失败任务、Checkpoint | 业务事实与恢复真源 |
| MinIO | 完整视频、上传分片、关键帧 | 大二进制对象 |
| Redis | 上传进度、activeKey、限流状态、Checkpoint 热缓存、Trace、短期反馈 | 热数据 / 临时状态 |
| Qdrant | 按视频隔离的 Chunk 向量 | 语义召回 |

**设计原则（背这个，比背表有用）：**
- Redis 丢失只能影响速度，不能让人任务永久丢失 → 所以 Checkpoint 以 MySQL 为真源，Redis 只是 7 天缓存。
- Qdrant 不可用只降级语义召回，不能阻断主链 → 有内存向量 + 关键词兜底。
- 视频和图片不进 MySQL；最终任务状态不能只放缓存。

---

### Q1-5：这是 AI Coding 辅助的项目，你自己的价值在哪？

面试官几乎必问。**主动承认 + 讲清分工**：
- AI 负责展开方案空间、机械编码；**我**负责：识别业务矛盾、比较方案、确定状态边界、审查 AI 代码、补异常链路。
- 举例（背 2 个最能体现判断的）：
  - **上传为什么用 uploadId 而不是前端 MD5**（避免浏览器先读 GB 级文件、避免把用户哈希当授权）。
  - **分析幂等为什么是 contentHash + goalDigest**（同一视频不同目标不能互相复用 Plan/结果）。
  - **Critic 失败后必须改变上下文**（否则第二轮是重复生成）。
  - **先写 MinIO 再记 Redis**（否则存储失败会形成假上传）。

---

## 2. 六大核心模块

> 这是面试主战场。每个模块都按「Q 问题 → A 答案 → 源码锚点 → 追问」组织。
> 学的时候：先能**讲对**，再能**讲深**，最后能**主动说边界**。

---

### 模块一：Planner-Executor-Critic 受控 Agent 工作流 ⭐⭐⭐ 最重点

#### Q2-1：为什么一次大模型总结不够？

- 用户目标不固定：同一视频可能要做笔记/会议纪要/操作步骤/观点核验。全塞进一次 Prompt，模型漏了哪项、用了哪段证据、为什么下某个结论，**程序无法判断**；不合格只能整篇重写。
- 拆成计划→执行→校验后，后端能知道：进行到哪、为什么失败、该补哪部分。

#### Q2-2：为什么它算 Agent，而不是连续调三次 LLM？

判断标准**不是调用次数**，而是：
- 系统围绕目标**维护状态**（userGoal、Plan、检索上下文、Result、CriticResult、轮次、预算）；
- **根据反馈改变后续动作**（补计划 / 补证据 / 只重写结构，三种路径不同）；
- **有终止条件**（预算）。

准确措辞：**受控 Video Agent Workflow**——模型负责开放式理解与判断，程序负责工具边界、状态、预算、重试与终止。它不是全自治 Agent，但在视频分析这个确定业务里具备目标、计划、工具、反馈、循环五个要素。

**源码锚点**：`AgentLoopService.runWithinBudget`（`AgentLoopService.java:89`）就是整个循环的主入口。

#### Q2-3：Planner / Executor / Critic / 确定性程序各自负责什么？

| 角色 | 职责 | 约束 |
| :--- | :--- | :--- |
| Planner | 理解目标 → 生成 1-5 个可执行任务 | 任务必须能靠当前视频证据完成；不能编「搜索互联网」这类无工具任务 |
| Executor | 按 Plan + 相关 VideoContext 生成结构化结果（标题/结论/证据/建议） | 不能引入证据之外的事实 |
| Critic | 检查目标覆盖、任务完成、证据支持、结构完整，返回结构化反馈 | 输出 missingRequirements / unsupportedClaims / requiredTimestamps / feedback |
| 确定性程序 | 限任务数、校验结构字段、检查证据时间戳与来源、决定是否刷新上下文、记 Checkpoint、统计 Token、预算终止 | 模型输出不稳定也不能随意改系统状态 |

#### Q2-4：AgentLoop 完整执行顺序？（背流程图）

```
1. 从完整 VideoContext 按 userGoal 粗召回相关 Chunk（避免 Planner 读整条长视频）
2. 加载目标级 Checkpoint：有合法 Plan 复用，没有才调 Planner；Plan 结构不完整先 repairPlan
3. Executor 按 Plan 生成草稿，每条结论必须绑证据；草稿先写 Checkpoint
4. Critic 校验 → 之后还有程序规则兜底（enforceStructureBounds + enforceEvidenceBounds）
5. 通过 → 保存结果；未通过 → 按反馈类型分流：
   - missingRequirements（漏了用户目标）→ Replan
   - unsupportedClaims / requiredTimestamps（缺证据）→ 组成新查询，从完整上下文补相关片段
   - 纯 feedback（结构/表达问题）→ 带反馈重写
6. 进入下一轮，直到通过或达到终止条件（轮数/时长/Token/费用）
```

**源码锚点**：`AgentLoopService.java:109`（粗召回）→ `resolvePlan`（:154）→ `executeRound`（:179）→ `critiqueRound`（:199）→ `contextForRetry`（:367）→ `revisePlanForRetry`（:391）。

#### Q2-5：Claim 级证据校验到底校验什么？（高频）

三层，前两层是**确定性程序**，第三层是 **LLM**：

1. **结构绑定**：每条 conclusion 必须有对应 evidence，evidence.claim 与结论绑定（代码里要求 `normalize(claim) == normalize(evidence.claim)`，防止给一堆引用却不知道支持哪条结论）。
2. **证据存在性**（`EvidenceVerificationService`）：
   - 时间戳是否落在某个 VideoSegment 的 `[startMs, endMs)` 内；
   - 来源必须包含 ASR 或 OCR，且只到对应文本里查；
   - 证据原文经过规范化（去标点/空白/大小写）后，是原文的**包含关系**。
   - ⚠️雷区：当前代码**只有 contains 包含判断**，文档里写的「轻量二元组覆盖」在现版本不存在，别乱讲。
3. **语义支持**：LLM Critic 判断证据是否足以推出结论、是否遗漏目标、是否逻辑冲突。这是**唯一**不能确定性保证的一层。

**结论表述**：Claim 校验**不能证明「消除幻觉」**，它的价值是把「无依据结论」变成**可检测、可追踪、可重试**的问题（伪造时间戳、引用不存在的原文、结论不给依据，都会被确定性规则拦下）。

**源码锚点**：`EvidenceVerificationService.java` 全文（64 行，很短，读一遍）。

#### Q2-6：Critic 不通过后，系统怎样真正改变下一轮（而不是重复生成）？

关键在 **Critic 必须返回结构化失败原因**，程序按类型分流：

| 反馈类型 | 含义 | 程序动作 |
| :--- | :--- | :--- |
| `missingRequirements` | 用户要求/计划任务被漏 | **Replan**，把缺失交给 Planner 修订 |
| `unsupportedClaims` | 某条结论没证据 | 把它作为**新的检索查询** |
| `requiredTimestamps` | 需要特定时间段原文 | 绕过摘要，**直接加载该时间附近的原始 Segment** |
| `feedback` | 证据够但写法/结构要改 | 沿用当前上下文重写 |

**第二轮上下文 = 上一轮证据 + Critic 指定时间片段 + 新查询召回的 Chunk**，并且仍受字符预算限制。

一句话记：**上下文没有变化的循环不算有效修正。**

**源码锚点**：`requiresEvidenceRefresh`（`AgentLoopService.java:384`）决定走「补证据」还是「只重写」。

#### Q2-7：模型输出非法/证据不足/两轮后仍不过怎么办？

- **结构化 JSON 非法**：一次严格格式重试；仍失败抛异常，交给 Checkpoint + MQ 恢复。**不会拿空对象伪装成功**。
- **证据不足**：保留能确认的部分，无法确认的 Claim 给警告，**不允许用常识补齐视频没有的信息**。
- **达到最大轮次**：保存带警告的最佳结果，保留 Critic 反馈供用户查看。

#### Q2-8：为什么不能只设「最多两轮」？

轮数只限制循环次数，管不了**单次调用过慢、上下文过大、模型涨价**。所以同时设四个预算：**轮数 / 执行时长 / 预估 Token / 预估费用**。每个关键模型阶段结束后检查，任一超限就终止（`AgentLoopService.checkBudget` :445）。

预算耗尽进入独立终态 `BUDGET_EXHAUSTED`，**不再交给 MQ 重试**——重试不改变预算条件，只会继续烧钱。

⚠️ 诚实边界（主动说）：
- 时长只能**在模型阶段之间**检查，不能强行取消已发出的阻塞式 HTTP 请求；
- Token 是**字符规则估算**，不是精确计费；
- 费用预算只有在配置了真实模型单价后才有约束意义（默认 0 = 关闭）。

**源码锚点**：`AgentExecutionBudget.java`（阶段 deadline）、`AgentLoopService.checkBudget`。

#### Q2-9：四种分析模式是什么？自动路由怎么工作？

- 四种模式：`GENERAL`（结论+证据+建议）/ `LEARNING`（大纲+自测题+易错点）/ `REVIEW`（逻辑漏洞+存疑结论）/ `CREATION`（爆点+标题+口播脚本）。
- 每种模式是一个 `ModeProfile`（计划/执行/Critic 三段指令 + 必须的 section key），注册在 `ModeRegistry`。
- `AUTO` 是**纯前端概念**：提交前先调 `/analysis/route`，由 `ModeRouter` 让 AI 判定模式，再按具体模式提交。AUTO 本身**永远不进入后端带 key 的接口**，从根上避免读写端 key 不对称。
- 路由不可用时回退 GENERAL，不阻断任务。

**源码锚点**：`service/mode/ModeRegistry.java`、`ModeRouter.java`、`client/src/useAnalysisWorkspace.js:11`（ANALYSIS_MODES）。

#### Q2-10：怎么证明 AgentLoop 优于单次 Prompt？（讲「评测设计」，别编数据）

- 对照组：固定同一批 VideoContext + 用户目标，比较「单次 Prompt / Planner+Executor / 完整 P-E-C」三组。
- 结果指标：结构完整率、目标覆盖、时间戳覆盖、证据支持、Claim 支持。
- 过程指标：模型调用次数、平均轮次、耗时、Token、预算终止次数。
- 仓库有 **4 个离线 Golden Case**（多模态互补 / 纯语音 / 纯画面 / 音画冲突），走真实 AgentLoop。
- ⚠️ **没有实际运行数据** → 只能讲评测设计，不能编通过率/提升百分比。

---

### 模块二：ASR + OCR 多模态 VideoContext ⭐⭐

#### Q2-11：为什么纯 ASR 不够？VideoContext 具体是什么？

- 课程/会议/录屏信息不只存在于语音：老师说「看这个公式」ASR 只有指代；代码演示可能不讲解但画面有命令和报错；PPT 有日期、负责人、关键结论。**输入阶段丢的信息，后面换更强模型也补不回**。
- VideoContext 是项目自定义的**统一时序模型**：一组 `VideoSegment`，每个 Segment 保存 `[startMs, endMs)`、ASR 转写、OCR 文本、关键帧引用。上层（检索/Planner/Executor/Critic）只认 VideoContext，不用适配 ASR/OCR/FFmpeg 各自的返回格式。
- 定位要准确：这是「语音 + 字幕 + 关键帧文本」的**轻量多模态**，不是完整视觉理解。

#### Q2-12：VideoContext 怎么构建？（含三层抽帧）

```
1. 查视频级 Checkpoint，有则复用（跨目标共享，避免重复烧钱）
2. 建临时工作目录，ASR 与 OCR 提交到两个独立有界线程池，并行执行
3. 语音分支：FFmpeg 抽音频 → 编码 MP3 → 按 60s 切片 → 逐片调 ASR
4. 视觉分支：抽帧（三层机制）→ dHash 去重 → Tesseract OCR（中英文包）→ 关键帧传 MinIO
5. 按 60s 窗口融合两路：左闭右开，ASR 按开始时间进窗，OCR 帧按时间点进窗
6. 生成 ASR-only / OCR-only / 双模态 三种 Segment
```

**三层抽帧机制（背熟，面试官爱细问）：**
1. **场景变化检测**：FFmpeg `select` 过滤器给 scene score，> 0.35 选帧 → 抓 PPT 翻页、窗口切换。
2. **30 秒保底**：距离上次选帧 ≥30s 强制选一帧 → 兜住缓慢板书、长时间静态画面。
3. **dHash 感知哈希去重**：图缩成 9×8 灰度，逐行比相邻像素生成 64 位哈希，汉明距离 ≤5 判相似跳过 OCR → 减少动画/光标/保底帧的重复识别。

`showinfo` 在日志里输出每帧 `pts_time`，Java 解析换算成毫秒 → OCR 文本回到原视频时间轴。

**源码锚点**：`VideoContextService.java`、`AudioExportService.java`、`OcrUtils.java`、`SegmentedTranscriptionService.java`。

#### Q2-13：为什么 ASR 固定切 60 秒？

- 当前托管 ASR 返回文本但**没有句子级时间戳** → 固定切片给每段转写建立最低限度的分钟级时间范围。
- 折中：切太短 → 2 小时视频产生大量网络请求，失败率和重试成本高；切太长 → 定位太粗，检索和 Critic 难落到具体片段。
- 诚实口径：只保证**分钟级定位**，不说成句子级精确证据。以后 ASR 有句子级时间戳，可以保持 VideoContext 结构不变，只换更细的 `TranscriptSegment`。

#### Q2-14：为什么用 CompletableFuture，不拆两个 RocketMQ Topic？

- RocketMQ 解决的是「整个视频分析任务离开 Web 请求线程」；ASR 和 OCR 是**同一任务内部需要汇合的两个分支**，没有独立部署/扩容诉求 → 用两个专用线程池 + CompletableFuture 并行即可，总耗时接近较慢分支。
- 拆两个 Topic 要额外做子任务状态、聚合器、超时收敛、重复消息、单边失败协调——当前规模下成本没有收益。
- ⚠️ 当前**没有用公共 ForkJoinPool**：ASR 用独立有界线程池控制网络调用，OCR 按 CPU 核数配线程池，队列满直接拒绝，避免无上限堆积。

#### Q2-15：ASR/OCR 局部失败、冲突、音画不同步怎么办？

- **局部失败**：ASR 按 60s 片段隔离，OCR 按关键帧隔离，单个失败不删其他成功结果；一整条分支失败保留另一条。**只有两路都失败/无任何有效 Segment 才整体失败**。
- **内容冲突**（语音说周一、PPT 写周三）：不让一路覆盖另一路，**把冲突本身作为结果**分别给证据；Critic 阻止把冲突包装成确定事实。
- **音画不同步**：无法自动修复，只能降低时间戳置信度；升级路径是估计统一 offset 或换句子级 ASR。
- **降级纪律**：只有 OCR 时不能声称「老师讲了 X」，只有 ASR 时不能声称「读到了 PPT」。**该说部分结果/证据不足就直说**，不静默降级生成完整答案。

#### Q2-16：为什么不直接用多模态视频大模型？

- 端到端视频模型适合短视频+一次性理解；本项目强调**长视频复用、继续追问、证据追溯**——每次换目标都重传整段视频，成本和延迟不可控，一次性回答也没法沉淀成可检索资产。
- ASR+OCR 链路更长，但结果可持久化、可复用、证据可定位。
- 代价：OCR 只懂文字，不懂动作/图表/公式。合理升级：**只对低置信度或强视觉任务定向调用视觉模型**，不全量。

---

### 模块三：分层摘要 + 混合检索 + Checkpoint ⭐⭐

#### Q2-17：为什么不能把完整 VideoContext 直接给模型？

- 两小时视频 → 海量 ASR+OCR 文本。全量输入：每次分析重复烧 Token；无关片段干扰模型判断；继续追问还要反复发送相同内容。
- 设计一句话：**摘要负责找路，原文负责作证。**
  - Chunk 摘要 + 关键词 + 向量 → 定位相关片段；
  - 命中后展开**原始 VideoSegment** → 生成与校验证据。
  - ⚠️ 摘要是**有损压缩**，不是最终事实来源；最终 Claim 必须回到原始 ASR/OCR/时间戳。

#### Q2-18：分块、摘要、混合检索的完整流程？

```
1. 长视频把连续 VideoSegment 聚合成 5 分钟 Chunk（VideoChunkingService，CHUNK_MS = 5*60*1000）
2. 每 Chunk 生成：摘要（≤200 字）+ 关键词 + 摘要 Embedding
3. 用户目标 → 生成查询向量 → Qdrant 在当前 mediaId 内召回候选
4. 与关键词分数融合，选 Top3 → 展开 Chunk 内原始 Segment，受 24000 字符预算限制
5. 短于 5 分钟的视频直接用原始 Segment，不额外生成摘要/向量
```

⚠️雷区——**实际检索权重以代码为准**（`VideoEvidenceRetrievalService.java:100`）：

```java
// Chunk 级：语义(向量) 0.6 + 语音关键词 0.25 + 视觉关键词(OCR) 0.15
return semanticScore * 0.6 + termScore(intent.keywords(), searchableText(chunk)) * 0.25
       + termScore(intent.visualKeywords(), visualText(chunk)) * 0.15;
// Segment 级：chunk 分 0.55 + 语音 0.25 + 视觉 0.20
```

作者旧文档写的是「语义 0.7 / 关键词 0.3」，**已过时**。面试讲 0.6/0.25/0.15，并解释 0.25 是语音关键词、0.15 是 OCR 视觉关键词（「画面写了什么」是独立通道）。

#### Q2-19：为什么同时要关键词和 Embedding？

- Embedding 解决语义相近但字面不同的表达（「递归效率」vs「递归时间复杂度」）；
- 关键词解决精确符号：B+树、HashMap、函数名、数字、章节名。
- 诚实边界：当前关键词是**轻量 contains 匹配**，适合单视频几十个 Chunk，不是成熟搜索引擎；数据规模上来后演进到 BM25 / Reranker。

#### Q2-20：Qdrant 或 Embedding 挂了怎么办？（优雅降级）

| 故障 | 降级行为 |
| :--- | :--- |
| Embedding 失败 | 返回空向量 |
| Qdrant 写/查失败 | 回到**应用内内存向量余弦相似度** + 关键词排序（`QdrantVectorStore` 与 `cosine()` 兜底） |
| 摘要生成失败 | 截取原始文本作退化摘要 |
| 向量、关键词都失效 | 关键词仍是最低召回 |

降级保证**链路能继续**，但质量下降 → 不能静默声称与正常检索等价；Telemetry 会记录 `vectorStoreFallbacks` / `embeddingFallbacks` 次数。

**源码锚点**：`VideoEvidenceRetrievalService.java:82`（index 的 fallback）、`:105`（vectorScores 的 fallback）、`QdrantVectorStore.java`。

#### Q2-21：Planner 和 Critic 分别怎么用检索？

- 第一轮：按完整 userGoal **粗召回**，Planner 在少量相关证据上拆任务（成本低，但复杂目标可能偏向一个意图）。
- 第二轮（Critic 发现缺口后）：把 `feedback + missingRequirements + unsupportedClaims + requiredTimestamps` 交给检索层重新查，并强制加入指定时间片段 → 反馈真的改变上下文。

#### Q2-22：Checkpoint 与 MQ 重试 / 幂等 / 缓存有什么区别？（背这个）

> 四者解决**不同问题**，这是最容易讲混的考点。

| 机制 | 解决什么 |
| :--- | :--- |
| MQ 重试 | 失败任务**有没有机会再执行** |
| 幂等（锁 + completedKey + 状态检查） | 重复执行**会不会产生重复副作用** |
| 缓存（Redis） | 相同输入**能不能更快读到** |
| Checkpoint | 任务内部**应该从哪个阶段继续** |

例子：Executor 草稿已生成但 Critic 调用失败 → MQ 重投任务，目标级锁避免双消费，Checkpoint 让新消费者**从 Critic 接着走**，Redis 命中只是更快，MySQL 才是真源。

#### Q2-23：Checkpoint 保存什么？为什么分视频级和目标级？

- **视频级**（只由视频内容决定，可跨目标复用）：VideoContext、5 分钟 Chunk、摘要、关键词、Embedding。
- **目标级**（由 userGoal 决定，以 `goalDigest` 隔离）：Plan、Executor 草稿、CriticState、最终 Result。
- ⚠️ 键还包含**模式**维度：`goalDigest(goal, mode)`，同一目标不同模式互不覆盖（`AgentCheckpointService`）。
- 同一视频做笔记和做操作步骤：共享 ASR/OCR/索引，**不共享** Plan/结果。继续追问 = 新目标级状态，不必重新上传或重建视频级产物。

**源码锚点**：`AgentCheckpointService.java:291` 起的 key 构造、`AgentCheckpointRepository`（MySQL upsert + Redis 7 天）。

#### Q2-24：Checkpoint 怎么持久化？Redis 挂了 / 各阶段失败怎么恢复？

- 写入：先**事务 upsert 到 MySQL**，提交后再更新 Redis → 避免假完成。
- 读取：先查 Redis，**缓存缺失或内容损坏再读 MySQL 并回填**。
- Redis TTL 7 天，MySQL 不过期 → 用户任务不会因缓存淘汰永久丢失。
- **各阶段恢复点**（背表）：
  - VideoContext 已保存 → 跳过 ASR/OCR
  - Chunk 已保存 → 跳过摘要/Embedding
  - Plan 已保存 → 从 Executor 开始
  - Executor 草稿已保存、未 Critic → 直接从 Critic 继续
  - Critic 未通过 → 带反馈和时间戳补证
  - Result 已保存 → 只补最终落库与展示

⚠️ 边界：ASR 单片 / OCR 单帧目前只在一次构建内部隔离，**没有逐片 Checkpoint**；优先保存「重算成本最高、边界最清晰」的阶段。

#### Q2-25：如何避免假完成、并发恢复、版本污染？

- 写入把「产物 + 阶段」一起持久化；读取终态校验 Plan/Result 结构，**非法终态不直接复用**（`AgentLoopService` 的 terminalCheckpoint 检查）。
- 消费者恢复前先拿「内容指纹 + goalDigest」锁；阶段推进参考数据库状态，避免双消费者互相覆盖。
- ⚠️ 边界：Checkpoint 键没编码 Prompt/Schema/Embedding 版本 → 版本升级可能局部失效。正确策略：VideoContext Schema 变化重建上下文、Embedding 模型变化重建向量、Executor Prompt 变化只重跑 Executor 之后。

---

### 模块四：分片上传与断点续传 ⭐⭐

#### Q2-26：为什么普通 Multipart 上传不行？当前流程是什么？

- 长视频几百 MB~GB，一次请求传完，最后阶段断网就得**从头重传**；服务端长时间维持连接，失败粒度是整个文件。
- 分片把失败范围缩小到单个数据块，成功分片保留，恢复后只补缺失。

```
初始化：文件名 + 总分片数 → 校验后缀/数量 → 生成随机 uploadId → Redis Hash 存
       {filename, totalChunks, userId}，TTL 1 天
上传每片：校验 uploadId 归属 + 序号 + ≤5MB → 先写 MinIO 分片 → 再往 Redis Set 加成功序号
恢复：前端查 Redis Set，算差集，只重传缺失片
完成：调 complete → 持 Redisson 合并锁 → 查 completedKey → 校验 Set 数量 == totalChunks
     → 按序把 MinIO 分片 copyObjectTo 到本地临时文件，DigestOutputStream 边写边算 MD5
     → 传完整文件 → 存 media_files → 写 completedKey → 清理分片与 Redis 元数据
```

**源码锚点**：`ChunkUploadService.java` 全文（239 行）。

#### Q2-27：为什么用 uploadId，不用前端 MD5？（⚠️ 必考 + 文档已纠正过）

- 上传任务主键是**服务端生成的 uploadId**，不是前端提前算的整文件 MD5。
- 理由：初始化不必让浏览器先完整读 GB 级文件；不把用户提供的哈希当授权/唯一身份。
- MD5 在哪发挥作用：**服务端合并分片时**用 `DigestOutputStream` 一边写临时文件一边算，得到 contentHash → 用于**视频内容识别、解析结果复用、Agent 任务幂等**。
- 所以能力定位是「**合并后内容级解析去重**」，**不是**上传前跨用户秒传；也**没有**前端分片 MD5 与服务端重算对比（不能讲双端完整性校验）。

#### Q2-28：为什么先写 MinIO，再记 Redis？（⚠️ 高频）

- Redis Set 里的序号含义是「**这个分片已成功落盘**」。
- 如果先记 Redis 再写 MinIO：存储失败后 Redis 仍显示完成 → 合并阶段才暴露缺文件，**形成假上传**。
- 反向小窗口（MinIO 成功、Redis 失败）：前端以为缺失会重传，但对象路径 `chunk-uploads/{uploadId}/part-{i}` 唯一确定，**重传覆盖同一逻辑分片**，不产生两份业务结果 → 用「可重复写」换「状态正确」。

#### Q2-29：合并怎么保证顺序和幂等？

- **并发**：`lock:upload:merge:{uploadId}` Redisson 锁，同一 uploadId 同时只有一个线程进合并区。
- **重复请求**：先查 `completedKey`，已合并则校验媒体归属后**返回同一记录**，不重复生成文件和记录。
- **顺序**：按 0..N-1 顺序 `copyObjectTo` 到临时文件。
- **状态冲突返回 409**（`BusinessException(CONFLICT)`）：合并中 / 分片不全，都是客户端可重试的「状态冲突」，不和 500 混。
- ⚠️ 边界：MinIO 传完但 DB 记录前宕机 → 可能留孤立对象；DB 成功但 completedKey 前宕机 → 重复合并。**当前没有跨 MinIO/MySQL/Redis 事务**，生产化需要确定性完整文件路径 + DB 唯一业务键补偿。

#### Q2-30：5MB / 410 片意味着什么？弱网恢复边界在哪？

- 单片 ≤5MB、总分片 ≤410 → 覆盖约 2GB。5MB 控制重传粒度又不至于产生太多 HTTP 请求；410 是 Demo 对上传体积和临时磁盘的保护。
- 诚实口径：简历写「GB 级」成立，但**不能声称支持任意 10GB**；更大文件要调服务端请求上限、临时磁盘、合并方式。
- 弱网恢复依赖 uploadId 元数据仍在 Redis，**TTL 1 天**，过期要重新初始化——不是无限期续传。
- Redis 彻底丢失：当前**不会扫描 MinIO 重建 Set**，只能重新初始化；放弃上传/过期后的 MinIO 分片**缺自动生命周期清理**（可给 `chunk-uploads/` 前缀配生命周期策略，但那是未实现项）。

#### Q2-31：怎么防止越权访问别人的上传任务？

- uploadId **本身不是授权**：Redis Hash 存了 userId，查询进度/上传分片/合并都校验当前用户；合并后返回媒体记录**再查归属**。
- 服务端还校验：文件名去路径 + 限视频后缀、uploadId 必须是 UUID、分片序号与总数范围。

---

### 模块五：RocketMQ 异步调度 + 幂等 ⭐⭐

#### Q2-32：为什么需要 RocketMQ，不用本地线程池 / Kafka / RabbitMQ？

- 分析链路（FFmpeg/ASR/OCR/Embedding/多轮 LLM）耗时远超普通 HTTP 请求。同步执行长时间占 Web 线程；客户端断线任务失去承接点。
- 本地线程池能把任务移出请求线程，但任务只在当前进程内，**服务重启难恢复、不便独立扩消费者**。
- RocketMQ 提供：持久化任务入口、失败重投、接入层与计算层解耦。它不会让处理变快，改善的是**请求线程占用 + 任务承接 + 重新调度**。
- 选型对比：Kafka 适合大规模流处理，本项目不需要那吞吐；RabbitMQ 也能做队列，但 RocketMQ 与 Java 技术栈贴合、有重试和失败主题支持。**是取舍，不是说 RocketMQ 全面碾压**。

#### Q2-33：从提交分析到消费者完成，完整流程？（与 Q1-2 的步骤 4-5 对应）

```
投递方（AnalysisDispatchService）：
  1. 校验视频归属，读 contentHash，算 goalDigest
  2. SETNX activeKey（TTL 6h）→ 重复提交直接返回 DUPLICATE
  3. 用户级(5/min) + 全局(30/min) 限流 → 不过返回 RATE_LIMITED（并删 activeKey）
  4. convertAndSend 到 video-analysis-topic，返回 ACCEPTED
  5. MQ 成功后才发布 QUEUED 事件（SSE 通知失败不能伪装成投递失败）
  ※ MQ 投递失败：删 activeKey；修订任务还要 cancelStagedRevision，避免「永远处理中」的幽灵任务

消费者（VideoAnalysisConsumer.onMessage）：
  1. 结构校验（rejectionReason），非法消息 → 毒消息收敛流程
  2. 拿 Redisson 锁（contentHash + goalDigest）
  3. 检查视频是否还存在
  4. Redis increment attemptsKey（记投递次数）
  5. 检查 completedKey：已完成同内容同目标 → 复用结果（COMPLETED_REUSED）
  6. 修订任务（isRevision）→ beginStagedRevision 切 Checkpoint，删 completedKey
  7. aiService.asyncAnalyze(...) 执行主链
  8. 成功 → 写 completedKey(7天) → 发布 COMPLETED
```

**源码锚点**：`AnalysisDispatchService.java`、`VideoAnalysisConsumer.java:83`。

#### Q2-34：为什么幂等 Key 是 contentHash + goalDigest，不只 MD5？（必考）

- MD5 只描述视频内容。同一视频做笔记和做操作步骤：可复用 ASR/OCR/VideoContext/Chunk，**不能复用** Plan 和 Result。
- 只按 MD5 加锁 → 不同目标被错误互斥，甚至复用错误产物。
- 所以任务级 Key = `contentHash + goalDigest`：contentHash 识别同一视频，goalDigest 隔离目标。

#### Q2-35：activeKey / Redisson 锁 / completedKey / Checkpoint 各自解决什么？（容易混）

| 机制 | 时机 | 职责 |
| :--- | :--- | :--- |
| activeKey（SETNX） | 请求入口 | 拦截「正在处理中」的重复提交，用户快速拿到 DUPLICATE |
| Redisson 锁 | 消费者拿消息后 | 处理 MQ 重投 / 多实例同时拿到任务的**瞬时并发** |
| completedKey | 消费者执行前 | 判断「同内容同目标是否已完成」 |
| Checkpoint + MySQL | 执行中/恢复时 | 判断「任务内部从哪继续」+ 最终事实 |

⚠️ 关键认知：**锁只能控制持锁期间的并发，不能证明任务历史上从未完成**；Redis key 有 TTL 也不是永久事实。所以消费者拿锁后**仍要查 completedKey 和 MySQL Checkpoint**。真正的幂等靠「稳定业务 Key + 状态检查 + 可重复写」共同收敛，不是单独一把锁。

#### Q2-36：为什么用 Redisson？WatchDog 怎么工作？能保证绝对安全吗？

- JVM 锁单实例有效，消费者扩容后进程间看不见；手写 SETNX 要自己处理锁值归属、原子释放、过期续期。项目已依赖 Redis，Redisson 直接给分布式锁 + WatchDog。
- 当前 `tryLock` **没指定固定 leaseTime** → 持锁线程存活期间 WatchDog 周期性续期（适合时长难预估的 Agent 任务）；进程退出续期停止，锁最终过期。释放前检查 `isHeldByCurrentThread`，避免误删他人重新获得的锁。
- ⚠️ 诚实边界：WatchDog 只能降低「任务处理中锁提前过期」风险，**不能保证绝对一次**。Redis 故障、长 JVM 停顿、进程崩溃仍可能重新执行 → 最终靠 DB 状态、Checkpoint、幂等写兜底。

#### Q2-37：重复消费、失败三次、失败消息怎么处理？（⚠️ 数字口径以代码为准）

- RocketMQ 至少一次语义 → 消息可能重投。重复消息先抢目标级锁，再查 completedKey + Checkpoint，已完成复用、半途从最近 Checkpoint 继续。
- **投递次数口径**（代码 `VideoAnalysisConsumer`）：`MAX_DELIVERY_ATTEMPTS = 3`，注解 `maxReconsumeTimes = 2`（2 次重投 = 最多 3 次投递）。用 Redis `attemptsKey` 计数。
  - 前两次（attempt 1、2）失败 → 状态 RETRYING，**保留 activeKey**（防止前端以为结束又塞一份），重抛给 MQ 重投。
  - 第 3 次仍失败 → 写 `failed_analysis_tasks` 台账 + 转投**自建失败主题**（`video-analysis-dead-topic`）→ 状态 DEAD_LETTERED。
- **永久失败**（`isPermanentFailure`）：沿 cause 链（最多 16 层）找 `IllegalArgumentException` / `SecurityException` / `NoSuchElementException`，是则**直接收敛**，不浪费两轮 ASR+LLM。
- ⚠️ 准确口径：这是**自建失败主题 + 失败任务表**，不是完整的 RocketMQ 原生 DLQ 运营体系。管理接口能查/重投失败任务，但生产还需告警、责任人、防毒消息无限回放。

#### Q2-38：毒消息（结构非法的消息）怎么处理？（代码里特有的亮点）

- 结构非法消息重投多少次都不会变好 → `discardPoisonMessage`：落台账 + 转投失败主题，然后**正常 ACK**（否则被 Broker 按默认 16 次反复重投，且台账/事件/日志三处都看不到）。
- **确认前提**：台账和失败主题**至少一个写成功**才 ACK；两个都失败必须拒绝确认（消息会静默丢失），并**不清理 activeKey**，让用户保持「处理中」而非凭空消失。
- 毒消息在 try 之前返回，走不到 finally → 单独 `releasePoisonTaskState` 清理 activeKey，否则幂等键残留 6 小时，用户重复提交全被判 DUPLICATE。

#### Q2-39：SSE 断线会不会丢任务？消息积压怎么处理？

- **不会**：MQ 投递成功表示任务已被 Broker 接收，SSE 只是用户通知；事件发布失败只记日志，不把任务伪装成投递失败、不删 activeKey。前端 SSE 断线**指数退避重连**，重新订阅先返回当前 MySQL/Checkpoint 状态 → 能恢复进度和终态。可能错过中间事件，但不丢最终结果。
- **积压**：先判断是生产突增 / 消费者变慢 / 毒消息重试。视频任务常见瓶颈是 ASR/OCR/LLM 外部依赖，**盲目加消费者可能反而打满模型配额或 CPU**。应先看队列等待、阶段耗时、重试率、第三方限流，再决定扩消费者 / 限生产 / 隔离失败任务。当前有 Trace + 失败表，但没有完整监控大盘——积压治理是**设计方法**而非已验证能力。

#### Q2-40：视频在分析期间被删除怎么办？

- 消费者执行前和完成后都 `mediaService.exists(mediaId)` 检查；期间被删 → 清理运行时产物（`purgeRuntimeArtifacts`），不发布有效结果。

---

### 模块六：限流 + 指数退避重试 ⭐

#### Q2-41：为什么必须限流？怎么实现的？（⚠️ 文档与代码要分清）

- 一次分析可能触发多段 ASR、关键帧 OCR、Embedding、Planner、Executor、Critic。恶意点击/脚本会同时打满本机 CPU、模型配额、Token。
- **限流位置在 MQ 投递前**，避免无效任务先入队再排队烧资源。
- 实现：**Redisson RRateLimiter**（不是手写 Redis Hash + Lua，旧文档已纠正）：
  - 用户级 `limit:ai:user:{userId}`：5 次/分钟
  - 全局 `limit:ai:global`：30 次/分钟
  - 两个许可都拿到才发 MQ；多实例共享 Redis 里同一配额。
- ⚠️ 还有**第二组**限流器别混：`ModeRouter` 的路由接口是用户 10/分钟 + 全局 60/分钟。
- **追问/证据检索也复用分析配额**（`requireAiQuota`），避免绕过成本护栏。

**源码锚点**：`AnalysisDispatchService.java:132`（tryAcquireQuota）、`service/mode/ModeRouter.java`。

#### Q2-42：为什么选令牌桶而不是固定窗口 / 漏桶？

- 固定窗口：边界处可能连续放过两批，瞬时压力超阈值。
- 漏桶：匀速流出，系统空闲时的正常小突发也排队。
- 令牌桶：限制长期速率，又允许令牌充足时快速提交少量请求，符合交互式分析体验。
- ⚠️ 边界：这是**请求级限流**，不等价于 Token 限流——5 分钟视频和 2 小时视频消耗不同。严格控模型 TPM 应在消费者侧按上下文长度估权再加一层。当前参数是 Demo 容量保护，不是压测最优值，别背死「5 和 30」。

#### Q2-43：用户许可拿到但全局失败 / MQ 失败 / Redis 挂了怎么办？

- 用户许可成功但全局失败 → 返回 429，用户级许可**不归还**（少量配额损耗，但不会超发；要更精确可先全局后用户或显式归还）。
- MQ 投递异常 → 删 activeKey + 撤销暂存修订 → 返回提交失败；令牌随限流器时间自然恢复，不做跨 Redis/MQ 分布式事务。核心原则：**不能留下一个永远「处理中」但没有消息的任务**。
- Redis 不可用 → **fail closed（拒绝新任务）**。对付费模型调用，比 Redis 挂后无条件放行更安全（放行可能成本+并发同时失控）。代价是 Redis 故障期间新任务不可用。

#### Q2-44：API 重试 和 MQ 任务重试有什么区别？（⚠️ 旧文档最爱混淆，必考）

| 层次 | 粒度 | 作用 | 当前实现 |
| :--- | :--- | :--- | :--- |
| 第三方 API 内部重试 | 一次 ASR 片段 / 一次模型节点 | 处理短暂网络错误、429、5xx | ASR：`Thread.sleep(1_000L << attempt)` → 1s、2s、4s；最多 3 次 |
| MQ 任务重试 | 整个分析任务 | 失败后重新进消费者，靠 Checkpoint 跳过已成功阶段 | 最多 3 次投递（2 次重投），之后进失败主题 |

- 两层**不能无限叠加**：API 内部重试有限次，任务级也限 3 次，最后进失败主题。
- **不该重试的**：参数错误、鉴权失败、模型不存在、权限不足、预算耗尽 → 永久失败/独立终态。
- ⚠️ 边界：当前 **LLM 对 RuntimeException 统一重试**，可能把永久错误也重试——这是作者明确写出的「待细化」点，面试可以主动提。ASR 的错误分类更清楚（只对网络/429/5xx 重试）。
- ⚠️ 边界：退避用 `Thread.sleep` 实现，**占用执行线程且无随机抖动**；规模大了应加 jitter 或用延迟消息/调度器。

**源码锚点**：`AliyunAsrUtils.java:94`（waitBeforeRetry）、`DeepSeekUtils.java`（LLM 调用）。

#### Q2-45：重试会不会重复扣费 / 重复结果？

- 外部模型「已成功但响应丢失」调用方无法确认 → 重发可能产生**第二次费用**（无法保证第三方绝不重复计费）。
- 但**业务结果不重复**：最终按 contentHash + goalDigest 进同一 Checkpoint，DB 用 upsert 收敛到同一任务。
- 处理：能传幂等键的接口传 taskId/请求摘要；不能支持时靠「有限重试 + 预算统计 + 业务落库幂等」控制影响。

---

## 3. 底层技术地基

> 不是项目独有，但讲项目时会被追问。每题掌握到「能解释原理 + 能接一句本项目怎么用」。

### Q3-1：RocketMQ 消息是「至少一次」还是「恰好一次」？怎么应对重复？

至少一次。消费者按幂等设计应对：稳定业务 Key（contentHash+goalDigest）+ 状态检查 + Checkpoint + 可重复写。**不追求分布式事务或去重中间件**。→ 对应 Q2-35/37。

### Q3-2：Redisson 分布式锁原理，什么是 WatchDog？

基于 Redis SETNX + 过期时间实现，通过 Lua 保证加锁/释放的原子性；WatchDog 是后台定时续期的看门狗，线程存活就续期。项目用它做「合并锁」和「分析任务锁」，锁 Key 由业务标识构造。→ 对应 Q2-36。

### Q3-3：SETNX 和 Redisson 锁的区别？

SETNX 只有「占位/幂等拦截」语义，没有自动续期和归属校验；Redisson 提供线程归属、原子释放、WatchDog 续期。项目中 activeKey 用 SETNX（入口拦截），并发执行控制用 Redisson 锁（消费者互斥）。

### Q3-4：CompletableFuture 和线程池怎么配合？为什么不直接用多线程？

两个任务分支并行，用 `CompletableFuture` 组合汇合；配独立有界线程池避免公共 ForkJoinPool 被阻塞、避免无上限堆积。**什么时候拆 Topic/拆服务**：当两路需要独立硬件或独立扩容时。→ 对应 Q2-14。

### Q3-5：FFmpeg 的 select 过滤器、scene score、showinfo 是干什么的？

`select='gt(scene,0.35)'` 场景变化选帧；`showinfo` 把每帧 `pts_time` 打到日志，Java 解析换算毫秒。这是 OCR 文本能回到视频时间轴的机制。→ 对应 Q2-12。

### Q3-6：什么是感知哈希（dHash），为什么能去重？

把图片缩到 9×8 灰度，逐行比较相邻像素亮度得 64 位哈希；两张图异或统计汉明距离 ≤5 判相似，跳过重复 OCR。快、与缩放/轻微偏移鲁棒，但不适合检测内容本质相同的不同构图。

### Q3-7：Embedding 和余弦相似度是什么？和关键词检索各擅长什么？

Embedding 把文本映射成向量，语义相近向量夹角小，用余弦相似度打分；关键词擅长精确符号。项目里混合排序，语义 + 语音关键词 + 视觉关键词三路打分。→ 对应 Q2-19。

### Q3-8：SSE 和 WebSocket、轮询的区别？为什么用 SSE？

SSE 是单向服务端推流，HTTP 长连接，自动重连机制简单；适合「服务端→前端」的任务进度推送。WebSocket 双向全双工，这里不需要客户端推流。轮询浪费请求且有延迟。断线后前端指数退避重连 + 重新订阅先返回当前状态。

### Q3-9：什么是 Flyway？项目里怎么用？

数据库迁移工具，按版本顺序执行 SQL，保证表结构可版本化管理。项目启动时自动初始化数据表。配置文件 `server/src/main/resources/db/`。

### Q3-10：MyBatis-Plus 的 upsert 是干什么的？

存在则更新、不存在则插入，靠唯一键。项目里 Checkpoint 用 upsert 收敛重复执行的结果 → 同一任务的多次执行落库到同一行。

---

## 4. 诚实边界

> **面试定生死的地方**。主动说出不足，比背亮点更能加分。逐条背，别等面试官揭穿。

**关于项目本身：**
1. 这是**个人开源项目**（约 190+ Stars），大量编码由 AI Coding 辅助完成 → 主动说明，不装成大规模生产验证。
2. 当前是**受控 Video Agent Workflow**，不是自主 Agent：模型不能任意创建工具、无限循环、访问视频之外信息。
3. **没有真实实验数据**：4 个离线 Golden Case 是回归框架起点，没跑出可信结果 → 只能讲评测设计，不能编通过率/降低多少 Token 等数字。
4. 用户反馈目前只在 Redis（30 天 TTL）参与指标统计，**没有形成长期评测数据集和自动优化闭环**。

**关于各模块能力边界：**
5. OCR 只提取文字，**不能理解动作、图表、表情、复杂公式**。
6. Claim 校验含确定性匹配 + LLM Critic，**不能证明每条结论语义绝对正确**；它把无依据结论变成可检测/可追踪/可重试的问题。
7. Token 是字符估算；费用预算需配置真实单价才有意义；时长预算只能在模型阶段之间检查。
8. 检索权重与 TopK（Top3）是工程折中，**没有低分动态扩大 TopK / 相邻 Chunk 扩展**——是明确改进项。
9. 关键词检索是轻量 contains 匹配，不是成熟搜索引擎；跨视频规模增长才引入 BM25/Reranker。
10. 上传**没有秒传、没有前端分片 MD5 双端校验、没有 MinIO 生命周期清理**；合并跨 MinIO/MySQL/Redis 无事务。
11. 断点续传依赖 Redis TTL（1 天），不是无限期；Redis 丢失不能自动重建上传状态。
12. **自建失败主题 + 失败任务表**，不是完整 RocketMQ 原生 DLQ 运营体系。
13. Checkpoint 键没有编码 Prompt/Schema/Embedding 版本，版本升级局部失效仍待完善。
14. ASR 单片 / OCR 单帧没有逐片持久化 Checkpoint。

---

## 5. 高频追问与陷阱

> 面试官从「亮点」往下挖的连招。每题先想，再看答案。

### 陷阱 1：你刚说「内容级去重」，是不是实现了文件秒传？

**不是。** 当前最完整的复用发生在**分析阶段**：相同 contentHash 的媒体记录，相同目标复用 AnalysisResult，不同目标复用视频级上下文/索引（省 ASR/OCR/Embedding）。**上传阶段仍按 uploadId 存各自文件**，media_files 对 content_hash 只有普通索引。简历里的「内容级去重」= 视频解析与 Agent 消费去重，**不能扩大成对象存储层只存一份**。→ 对应 Q2-27。

### 陷阱 2：你说了半天幂等，分布式锁能保证「恰好一次」吗？

**不能。** 锁管持锁期间的并发；进程崩溃、Redis 故障、长时间停顿都会导致重新执行。真正兜底是：稳定业务 Key（contentHash+goalDigest）+ completedKey + MySQL Checkpoint + upsert 可重复写。消费幂等是**共同收敛**，不是一把锁。→ 对应 Q2-35/36。

### 陷阱 3：Critic 和 Executor 用同一个模型，是不是自我开卷？

**是同一个模型，确实可能共享盲区。** 所以我强调：确定性程序先兜住结构 + 证据存在性（时间戳/来源/原文），Critic 只负责语义这一层；质量要求更高时换独立 Critic 模型或扩大人工评测集。→ 对应 Q2-5。

### 陷阱 4：检索语义权重是 0.7 吗？

**现在是 0.6/0.25/0.15。** 旧文档写过 0.7/0.3，代码已演进为「语义 0.6 + 语音关键词 0.25 + 视觉关键词 0.15」，segment 级是 0.55/0.25/0.20。以代码为准，顺带展示「画面文字是独立评分通道」。→ 对应 Q2-18。

### 陷阱 5：你说「并行 ASR + OCR」，是并行调两个大模型吗？

**不是。** ASR 是托管接口按 60s 切片调；OCR 是本地 Tesseract 跑关键帧。两路在同一任务内部并行，用 CompletableFuture 汇合；不是两个独立部署的微服务。

### 陷阱 6：视频处理这么快，是不是把整个视频给模型了？

**不是。** 两小时视频直接喂模型，Token 爆炸、无关片段干扰。先抽 VideoContext → 5 分钟 Chunk 摘要/关键词/向量 → 按目标检索 TopK → 展开原始 Segment 且受 24000 字符预算限制。模型看到的是**目标相关的原始证据**，不是全量文本。

### 陷阱 7：Token 预算耗尽重试一下不就好了？

**不行。** 重试不改变预算条件（超时/超 Token/超费用），只会继续消耗资源。所以预算耗尽进独立终态 `BUDGET_EXHAUSTED`，不参与 MQ 重试。→ 对应 Q2-8。

### 陷阱 8：为什么不用现成的向量检索 + 大模型 RAG 框架（LangChain/LlamaIndex）？

本项目的检索/编排是**定制但轻量**的：分块粒度、证据核验、Checkpoint、预算终止都和视频领域强绑定；用通用框架反而要适配 VideoContext 时序结构和 Claim 校验。技术上可以回答「框架适合通用场景，这里证据校验和断点恢复是核心价值，自己实现更可控」。

### 陷阱 9：两小时视频，ASR 60 秒一片，要调 120 次，不会很慢/很贵吗？

会，这正是设计里权衡过的：60s 是「请求数量 vs 定位精度」的折中；ASR 结果作为**视频级 Checkpoint 缓存**，同一视频多个目标/继续追问**复用一次**，不重复转写。成本护栏还有限流 + 预算。

### 陷阱 10：既然有 Checkpoint，为什么还需要 MQ 重投？

Checkpoint 决定「从哪继续」，MQ 决定「会不会再来一次」。**没有 MQ**，任务失败后没人会重新触发消费者；**没有 Checkpoint**，重投会从头再来。两者配合：重投 + 从最近阶段继续。

---

## 附录A：代码速查表

> 学习时按「问题 → 文件 → 关键行」定位，精读核心文件即可覆盖 80% 追问。

| 主题 | 文件 | 关键点 |
| :--- | :--- | :--- |
| Agent 编排循环 | `service/AgentLoopService.java` | `runWithinBudget` :89，四类预算 `checkBudget` :445，反馈分流 `contextForRetry` :367 |
| Agent 状态/预算 | `service/AgentExecutionBudget.java`、`dto/AgentState.java` | 阶段 deadline、轮次/Token/费用 |
| Agent 模式体系 | `service/mode/ModeRegistry.java`、`ModeRouter.java` | 四种模式 Profile、自动路由 + 独立限流 |
| 证据校验 | `service/EvidenceVerificationService.java`（64 行） | 时间戳、来源、规范化 contains、Claim 绑定 |
| 上传 | `service/ChunkUploadService.java` | 先 MinIO 后 Redis、合并锁、DigestOutputStream MD5 |
| 投递/限流/幂等 | `service/AnalysisDispatchService.java` | SETNX activeKey、双 RRateLimiter、失败回滚 |
| 消费/重试/失败主题 | `consumer/VideoAnalysisConsumer.java` | attemptsKey 计数、永久失败、毒消息、DEAD_LETTERED |
| Checkpoint | `service/AgentCheckpointService.java` + `repository/AgentCheckpointRepository.java` | 视频级/目标级键、MySQL upsert + Redis 7 天 |
| 检索 | `service/VideoEvidenceRetrievalService.java` | 0.6/0.25/0.15 权重、Top3、fallback |
| 分块 | `service/VideoChunkingService.java`、`LongVideoContextService.java` | 5 分钟 Chunk、24000 字符预算 |
| 向量库 | `service/QdrantVectorStore.java` | upsert/search、异常降级 |
| 上下文构建 | `service/VideoContextService.java` | 双分支并行、60 分钟 deadline、临时文件清理 |
| ASR | `utils/AliyunAsrUtils.java`、`SegmentedTranscriptionService.java` | 60s 切片、指数退避 `1s<<attempt` |
| OCR | `utils/OcrUtils.java` | Tesseract、2 分钟超时 |
| 音频 | `service/AudioExportService.java` | FFmpeg 转 MP3 |
| 前端工作台 | `client/src/useAnalysisWorkspace.js`、`App.vue` | SSE 消费、AUTO 路由、模式选项 |
| 前端上传 | `client/src/chunkUpload.js` | 分片、断点续传、重传差集 |
| 配置 | `server/src/main/resources/application.properties`、`.env.example` | 预算参数、模型、端口 |

---

## 附录B：学习节奏计划

**两周版（每天 1-2 小时）：**

| 天 | 内容 | 产出 |
| :--- | :--- | :--- |
| D1 | README mermaid 图 + 本文档第一层 | 能 2 分钟讲清完整链路 |
| D2 | 读 `ChunkUploadService` + `AnalysisDispatchService` | 能讲分片上传、幂等、限流 |
| D3 | 读 `VideoAnalysisConsumer` | 能讲消费、重试、失败主题 |
| D4 | 读 `VideoContextService` + `OcrUtils` + `AudioExportService` | 能讲多模态构建 + 三层抽帧 |
| D5 | 读 `AgentLoopService`（重点）+ `EvidenceVerificationService` | 能讲 P-E-C 循环 + Claim 校验 |
| D6 | 读 `VideoEvidenceRetrievalService` + `QdrantVectorStore` | 能讲混合检索 + 降级 |
| D7 | 读 `AgentCheckpointService` + Repository | 能讲 Checkpoint 与恢复点 |
| D8 | 通读 `interview-qa-six-pillars.md` | 校准全部答案 |
| D9 | 通读 `interview-qa-v2.md` | 补细节 + 审计段（雷区） |
| D10 | 看前端 `useAnalysisWorkspace.js` + `chunkUpload.js` | 能讲前端侧、AUTO、SSE |
| D11-12 | 对着本文档「第五层陷阱」自测，逐题先想再对答案 | 找盲区 |
| D13-14 | 模拟面试：找朋友从「讲项目」开始连环追问 | 练输出 |

**一个衡量标准**：能在白纸上**不查资料**画出完整链路图（上传 → MQ → 消费 → VideoContext → Chunk/检索 → AgentLoop → Checkpoint → SSE），并能对图上每个箭头回答「失败了怎么办」。画得出来，就通了。
