# TripStar Java 智能体学习导读

这份文档用于学习 Java 版 TripStar 后端。它不是运行手册，而是解释代码为什么这样分层、智能体流程如何拆解，以及后续如何从当前真实配置强依赖版本演进到更完整的旅游规划智能体。

## 1. 当前代码调用链

当前版本已经保留了原 Vue 前端需要的交互流程：

```text
Vue 前端
  -> POST /api/trip/plan
  -> TripController
  -> TripTaskService.submit()
  -> 后台线程执行规划任务
  -> WebSocket /api/trip/ws/{taskId} 推送进度
  -> TravelContentService 采集小红书游记/图片内容
  -> MapContextService 采集地图/POI/天气上下文
  -> XhsExtractionAgent 提炼游记景点候选
  -> TripPlannerAgent 生成 TripPlan JSON
  -> TripReviewAgent 质检 TripPlan
  -> AI TripPlan 转 TripPlanResponse + graph_data
  -> 返回 TripPlanResponse(data + graph_data)
```

关键代码：

- `app/src/main/java/com/zkry/api/trip/TripController.java`
- `app/src/main/java/com/zkry/api/trip/TripTaskWebSocketHandler.java`
- `modules/trip/src/main/java/com/zkry/trip/service/TripTaskService.java`
- `modules/trip/src/main/java/com/zkry/trip/service/TripAiPlannerService.java`
- `modules/trip/src/main/java/com/zkry/trip/service/TripPlanResponseFactory.java`
- `modules/ai/src/main/java/com/zkry/ai/service/AiAgentService.java`
- `modules/ai/src/main/java/com/zkry/ai/service/AiTextService.java`
- `modules/ai/src/main/java/com/zkry/ai/service/PromptResourceService.java`
- `modules/content/src/main/java/com/zkry/content/service/XhsContentService.java`
- `modules/content/src/main/java/com/zkry/content/service/XhsNativeClient.java`
- `modules/content/src/main/java/com/zkry/content/service/XhsSignService.java`
- `modules/map/src/main/java/com/zkry/map/service/MapContextService.java`
- `modules/map/src/main/java/com/zkry/map/service/AmapMapContextService.java`

## 2. 为什么先用 Service 编排，而不是一上来多 Agent

旅游规划看起来适合多智能体，但第一版不要直接做复杂 Agent Graph。原因是：

- 前端契约、任务状态、WebSocket、JSON 结构必须先稳定。
- LLM 输出不稳定，如果没有解析、校验和明确失败机制，多 Agent 只会把问题放大。
- 旅游规划本身可以先拆成普通服务流程，等行为稳定后再把服务包装成工具或子 Agent。

当前 `TripTaskService` 扮演“工作流调度器”：

```text
initializing
  -> attraction_search
  -> weather_search
  -> hotel_search
  -> planning
  -> graph_building
  -> completed
```

`TripTaskService` 仍然是工作流调度器，但部分阶段已经由 ReactAgent 承担。后续可以继续把外部能力包装成 Tool，让 Agent 在受控范围内调用。

当前 `attraction_search` 阶段会先调用 `TravelContentService` 采集小红书游记，再调用 `MapContextService` 采集地图上下文。如果没有配置小红书 Cookie、高德 Web Service Key 或 AI Key，任务会明确失败并提示去 Vue 设置页补配置；配置齐全后，会把游记提炼、真实 POI、酒店、餐饮和天气一起交给 Planner。

## 3. 当前 AI 接入方式

当前 AI 层分成三层：

```text
PromptResourceService
  -> 从 resources/prompts/tripstar/*.md 读取 Prompt

AiTextService
  -> 根据 Vue 设置页运行时配置创建 DashScopeChatModel

AiAgentService
  -> 使用 Spring AI Alibaba ReactAgent 调用模型
```

当前主流程已经不是单纯 `ChatClient` 调用，而是第一版受控 ReactAgent 工作流：

```text
XhsContentService
  -> xhs-extraction-agent

TripAiPlannerService
  -> trip-planner-agent
  -> trip-review-agent

ChatController
  -> trip-chat-agent
```

设计重点：

- 不直接把 Spring AI Alibaba 的 API 泄露给业务 Controller。
- 由 `AiAgentService` 统一创建和调用 `ReactAgent`。
- 由 `AiTextService` 统一创建运行时 `ChatModel`。
- Prompt 统一放在资源目录，不硬编码在 Java 方法中。
- 没有配置模型时返回 `Optional.empty()`，上层会返回明确业务错误。
- 模型调用失败时记录日志并失败，不再回退到模拟数据。

当前默认配置仍可从 `application.yml`/环境变量初始化，但运行时以 Vue 设置页保存到 `/api/settings` 的值为准：

```yaml
spring:
  ai:
    dashscope:
      enabled: ${AI_DASHSCOPE_ENABLED:false}
      api-key: ${AI_DASHSCOPE_API_KEY:}
      chat:
        options:
          model: ${AI_DASHSCOPE_CHAT_MODEL:qwen-plus}
```

要尝试真实 DashScope，可以在 Vue 设置页填写：

- `openai_api_key`：Java 版会作为 DashScope API Key 使用。
- `openai_model`：例如 `qwen-plus`。
- `openai_base_url`：可选，仅在你确认它兼容 DashScope 原生 API 时填写。

注意：当前 Spring AI Alibaba `2.0.0-M1.1` 是 milestone 版本，项目里暂时排除了一个缺失的自动配置类：

```yaml
spring.autoconfigure.exclude:
  - com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeMultimodalEmbeddingAutoConfiguration
```

这是为了让 Boot 4 项目能启动，后续 Spring AI Alibaba v2 稳定后可以复查并移除。

## 4. Prompt 在哪里

Prompt 统一放在资源目录：

```text
modules/ai/src/main/resources/prompts/tripstar/
```

当前文件：

- `xhs-extraction-system.md`
- `xhs-extraction-user.md`
- `planner-system.md`
- `planner-user.md`
- `review-system.md`
- `review-user.md`
- `chat-system.md`
- `chat-user.md`

Java 中只保留变量组装逻辑：

```text
modules/trip/src/main/java/com/zkry/trip/prompt/TripPlannerPrompts.java
```

`TripPlannerPrompts` 不再保存大段 Prompt 文本，只负责把用户请求、地图上下文、小红书上下文整理成模板变量。

Planner Prompt 做了几件事：

- 给模型一个系统角色：TripStar 旅行规划智能体。
- 要求模型只输出合法 JSON。
- 固定 JSON key 为英文 snake_case。
- 明确 `TripPlan` 的完整 schema。
- 要求每天有景点、三餐、酒店、天气、预算。
- 多城市时要求标记城市和移动日。

这是后续学习 LLM 应用的重点：**Prompt 不只是自然语言，它也是接口契约的一部分。**

## 5. Structured Output 与容错

当前主流程只保留 Spring AI `BeanOutputConverter` 这一条结构化输出路线。这样学习路径更清楚：所有需要结构化结果的 Agent 都走同一个解析入口。

核心封装在：

```text
modules/ai/src/main/java/com/zkry/ai/service/AiStructuredOutputService.java
```

调用方式是：

```text
structuredOutputService.format(TripPlan.class)
  -> 把格式要求写入 prompt 的 {{format}}

structuredOutputService.callForObject(...)
  -> 调用 ReactAgent
  -> BeanOutputConverter 转成 Java DTO
```

现在主要结构化 DTO 是：

```text
TravelResearchResult
TripPlan
ReviewResult
List<ContentAttractionCandidate>
```

如果模型输出不能转换成 DTO，`AiStructuredOutputService` 会记录 `[AI-STRUCTURED] 结构化输出解析失败`，上层返回明确失败。后续如果真要增加修复链路，建议单独设计成可观测、可测试的功能，而不是保留未使用代码。

## 6. 地图上下文如何进入规划

地图模块在：

```text
modules/map/src/main/java/com/zkry/map
```

核心类：

- `MapContextService`：地图上下文接口，业务层只依赖它。
- `AmapMapContextService`：高德地图实现，负责地理编码、POI 搜索、天气查询。
- `MapPlanningContext`：一次旅行的地图上下文。
- `MapCityContext`：单个城市的景点、酒店、餐饮、天气。

默认配置可以初始化运行时配置，但后端请求时读取的是 `/api/settings` 里的 `vite_amap_web_key`：

```yaml
tripstar:
  map:
    amap:
      enabled: ${AMAP_ENABLED:false}
      key: ${AMAP_KEY:}
      base-url: ${AMAP_BASE_URL:https://restapi.amap.com}
```

开启高德地图需要 `tripstar.map.amap.enabled=true`，并在 Vue 设置页填写高德 Web Service Key。

当前数据流：

```text
TripTaskService
  -> MapContextService.collect()
  -> MapPlanningContext
  -> PromptResourceService 渲染 planner-user.md
  -> LLM 优先使用真实 POI/酒店/餐饮/天气
```

如果 LLM 没有启用，任务会明确失败。这样做是为了避免“看起来成功但其实是模拟行程”的误判。你仍然可以单独调用/调试地图服务日志，观察真实 POI、酒店、餐饮和天气是否采集成功。

这个设计对应智能体里的“工具调用”：现在它还是普通 Java service，将来可以包装成 `WeatherTool`、`PoiSearchTool`、`HotelSearchTool` 给 Spring AI Alibaba Agent 使用。

## 7. 小红书/游记内容提炼如何设计

当前 Java 主流程已经接入小红书内容源，代码在：

```text
modules/content/src/main/java/com/zkry/content
```

关键类：

- `XhsSignService`：调用本地 Node.js 和 Java 项目内置的 `xhs_sign` JS 资源，生成小红书请求头。
- `XhsNativeClient`：调用小红书搜索和详情接口。
- `XhsContentService`：搜索游记、拼接正文、调用 LLM 提炼景点候选、提供景点搜图。
- `TripstarRuntimeSettingsService`：承接前端设置页保存的 `xhs_cookie`、`vite_amap_web_key`、`openai_api_key`、`openai_model`，让运行时配置被内容、地图和 AI 服务共同读取。

推荐设计：

```text
ContentSource
  -> MapPoiContentSource
  -> PublicTravelNoteSource
  -> XhsContentService

AttractionExtractionService
  -> 输入：游记正文、标题、城市、用户偏好
  -> LLM 提炼：景点名、推荐理由、游玩时长、预约提醒、避坑提示
  -> 输出：AttractionCandidate JSON
```

提炼 Prompt 应要求模型输出 JSON 数组，例如：

```json
[
  {
    "name": "景点展示名",
    "name_zh": "中文官方名",
    "name_en": "英文官方名",
    "reason": "推荐理由",
    "duration": 120,
    "reservation_required": true,
    "reservation_tips": "提前预约说明"
  }
]
```

小红书为什么当前不降级：

- 你当前学习目标是复刻 Python 项目的真实内容链路，所以缺 Cookie 时必须暴露问题。
- 无 Cookie 搜索和批量详情不稳定，静默降级会掩盖真实集成问题。
- 后续用户端产品可以把小红书替换为更稳定、合规的数据源组合，但这个学习版先保持小红书强依赖。

## 8. 路线规划应该交给谁

路线规划不要全部交给 LLM。

建议分工：

- 地图/POI 服务负责：坐标、距离、路线时间、酒店位置、天气。
- 规则服务负责：每天景点数量、移动日轻量安排、预算汇总、营业时间校验。
- LLM 负责：偏好理解、推荐理由、行程节奏、文本组织、复杂取舍。

后续可以拆成：

```text
AttractionCollector
WeatherCollector
HotelCollector
RouteOptimizer
BudgetCalculator
TripPlannerAgent
```

其中 `TripPlannerAgent` 不直接查所有外部接口，而是消费前面服务整理好的结构化上下文。

## 9. 如何升级到真正的多 Agent

第一阶段：

```text
TripTaskService 普通流程编排
  -> TripAiPlannerService
  -> AiTextService
```

第二阶段：

```text
WeatherTool
HotelTool
AttractionTool
RouteTool
  -> PlannerService 统一调用
```

第三阶段：

```text
ReactAgent: 天气专家
ReactAgent: 酒店专家
ReactAgent: 景点专家
ReactAgent: 行程规划专家
Coordinator Agent 把子 Agent 当工具调用
```

Spring AI Alibaba 里的 `ReactAgent` 已经用于第一版受控工作流。Tool callback、子 Agent 工具化和 Supervisor 编排适合放到后续阶段，不要一次性把所有逻辑都塞进一个自治 Agent。

## 10. 多 Agent 路线

当前已经接入第一版“可控 ReactAgent 工作流”，但还没有让 Agent 自主调用外部工具。旅游规划更适合“确定性工具 + Agent 推理”的组合：

```text
TripTaskService
  -> 配置校验
  -> XhsCollectorTool：搜索小红书、拉取笔记详情和图片
  -> XhsExtractionAgent：从游记正文提炼景点、理由、预约、避坑
  -> AmapContextTool：补 POI、酒店、餐饮、天气、坐标
  -> PlannerAgent：融合用户需求和上下文，生成 TripPlan JSON
  -> ReviewAgent：检查天数、城市、酒店、餐饮、预算、字段完整性
  -> TripPlanResponseFactory：构建前端响应和 graph_data
```

当前第一版多 Agent 是顺序工作流：

```text
XhsExtractionAgent -> PlannerAgent -> ReviewAgent
```

这一版最适合学习，因为每个 Agent 的职责很清楚：

- `XhsExtractionAgent` 学习非结构化内容提炼：小红书笔记 -> 景点候选 JSON。
- `PlannerAgent` 学习复杂规划生成：用户需求 + 景点候选 + 地图上下文 -> 完整行程。
- `ReviewAgent` 学习 LLM 质检：检查缺字段、天数不一致、城市不一致、预算异常。

下一版可以引入工具和并行：

```text
ParallelAgent
  -> XhsExtractionAgent
  -> AmapPoiAgent/Tool
  -> WeatherAgent/Tool
  -> HotelAgent/Tool

PlannerAgent 汇总并生成 TripPlan
```

再下一版考虑 Supervisor：

```text
SupervisorAgent
  -> 根据任务状态决定调用哪个 Agent 或 Tool
  -> 控制最大轮次、超时、失败重试和停止条件
```

生产环境建议：

- 外部接口调用仍然放在 Java Tool/Service 中，不让 Agent 自己随意访问网络。
- Agent 只处理理解、提炼、规划、检查、修复。
- 每个 Agent 都要有输入/输出 schema。
- 每个 Agent 调用都记录模型名、耗时、输入摘要、输出长度、解析结果。
- 小红书内容是不可信输入，要防 prompt injection。
- Planner 输出必须经过 ReviewAgent 和 Java 规则校验后才能返回前端。

## 11. 知识图谱是什么，怎么实现

Vue Result 页面里展示的“知识图谱”，当前不是一个单独的图数据库，也不是从外部直接爬到的知识库。它是后端根据最终 `TripPlan` 派生出来的可视化关系图。

后端生成位置：

```text
modules/trip/src/main/java/com/zkry/trip/service/TripPlanResponseFactory.java
```

核心方法：

```text
TripPlanResponseFactory.fromPlan(planId, plan)
  -> createKnowledgeGraph(plan)
  -> KnowledgeGraphData(nodes, edges, categories)
```

数据结构：

```text
KnowledgeGraphData
  -> nodes: GraphNode[]
  -> edges: GraphEdge[]
  -> categories: GraphCategory[]
```

当前节点类型：

- 城市
- 天数
- 景点
- 酒店
- 餐饮
- 天气
- 预算
- 建议

当前边关系：

- 城市 -> 第 N 天：`行程`
- 第 N 天 -> 酒店：`入住`
- 第 N 天 -> 景点：`游览`
- 第 N 天 -> 餐饮：`breakfast/lunch/dinner`
- 城市 -> 总预算：`预算`

前端渲染位置：

```text
frontend/src/views/Result.vue
```

Vue 从接口响应里拿：

```text
response.graph_data
```

然后用 ECharts 的 `graph` series 渲染：

```text
series: [{ type: 'graph', layout: 'force', data: nodes, links: edges }]
```

所以这个图谱的作用是：把一份行程计划变成“城市、天数、景点、酒店、餐饮、预算”的关系网络，方便用户快速看清楚行程结构。

它是不是“真实数据”要分层看：

- 图谱结构是真实的：节点和边是 Java 根据最终 `TripPlan` 确定性生成的。
- 节点内容来自最终行程：比如景点名、酒店名、餐饮名、预算、日期。
- 最终行程由 LLM 生成，但 Prompt 中已经喂入小红书游记和高德地图上下文。
- 因此它是“真实上下文 + LLM 规划结果”的派生图谱，不是纯外部事实库。
- 边关系不是外部 API 返回的事实，而是行程结构关系，例如“第 1 天游览某景点”。

后续如果要做更真实的旅游知识图谱，可以升级为：

```text
POI 实体：来自高德/Google
游记实体：来自小红书笔记
用户偏好实体：来自用户画像
路线实体：来自地图路线规划
关系：
  景点 -> 位于 -> 城市
  景点 -> 适合 -> 亲子/情侣/摄影
  景点 -> 需要 -> 预约
  景点 -> 附近 -> 餐厅/酒店
  用户 -> 偏好 -> 美食/自然/人文
  DayPlan -> 包含 -> 景点/餐饮/酒店
```

再往后可以把图谱持久化到数据库或图数据库中，用它做推荐、去重、路线重排、用户画像匹配。

## 12. 面向用户端旅游规划的演进方向

如果未来要开发真正用户端产品，可以继续加：

- 用户画像：预算、体力、亲子/情侣/老人、饮食禁忌、节奏偏好。
- 收藏系统：用户收藏景点、酒店、餐厅。
- 可编辑行程：拖拽调整景点顺序，重新计算路线和预算。
- 追问式规划：用户问“能不能轻松一点”“换成亲子路线”时，局部重排。
- 历史行程：保存、复制、二次编辑。
- 反馈闭环：用户对景点/餐厅/路线打分，用于下一次推荐。
- 缓存和限流：地图、天气、POI、LLM 调用都需要缓存。
- 隐私和 Key 管理：API Key 只放服务端，不放移动端。

## 13. 当前下一步建议

下一步不要急着写多 Agent，建议按这个顺序：

1. 在 Vue 设置页保存小红书 Cookie、高德 Web Service Key、AI API Key 和模型名。
2. 验证 `XhsContentService` 能搜索笔记、拉详情并提炼景点。
3. 验证 `AmapMapContextService` 能返回 POI、酒店、餐饮、天气。
4. 同时启用小红书 + 高德 + AI，验证 `TripAiPlannerService` 能稳定生成 `TripPlan`。
5. 继续观察 Structured Output 失败日志，必要时再单独设计可测试的修复链路。
6. 再引入 Tool callback、并行 Agent 和 Supervisor 编排。
