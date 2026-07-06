# TripStar Java 代码学习导读

这份文档按“一个任务来了，代码怎么跑”的顺序讲 Java 版 TripStar。当前版本已经进入 Agent Tool 驱动阶段：

- 高德 POI、酒店、餐饮、天气由 `TravelResearchAgent` 调用 `AmapTravelTools` 获取。
- 小红书支持 `service` / `tool` / `both` 三种模式。
- LLM JSON 输出主要使用 Spring AI `BeanOutputConverter` 结构化输出解析。
- Prompt 统一放在 `modules/ai/src/main/resources/prompts/tripstar/`。

## 1. 模块分层

```text
app
  Controller、WebSocket、启动配置、运行时设置接口

modules/trip
  旅行规划主流程、ResearchAgent 编排、PlannerAgent 编排、DTO、知识图谱转换

modules/content
  小红书搜索、详情、签名、图片、小红书 Tool、游记内容提炼

modules/map
  高德 REST 客户端能力、高德 Tool、POI/天气 DTO

modules/ai
  Spring AI Alibaba、ReactAgent、Structured Output、Prompt 资源读取、Agent 常量

common/*
  通用异常、JSON、Web、日志、运行时配置
```

核心类先看这些：

```text
app/src/main/java/com/zkry/api/trip/TripController.java
app/src/main/java/com/zkry/api/trip/TripTaskWebSocketHandler.java
app/src/main/java/com/zkry/api/trip/SettingsController.java

modules/trip/src/main/java/com/zkry/trip/service/TripTaskService.java
modules/trip/src/main/java/com/zkry/trip/service/TripTaskStatus.java
modules/trip/src/main/java/com/zkry/trip/service/TripTaskStage.java
modules/trip/src/main/java/com/zkry/trip/constant/TripTaskMessages.java
modules/trip/src/main/java/com/zkry/trip/constant/TravelResearchMessages.java
modules/trip/src/main/java/com/zkry/trip/service/TripResearchService.java
modules/trip/src/main/java/com/zkry/trip/service/TripAiPlannerService.java
modules/trip/src/main/java/com/zkry/trip/service/TripPlanResponseFactory.java

modules/map/src/main/java/com/zkry/map/service/AmapTravelTools.java
modules/map/src/main/java/com/zkry/map/service/AmapMapContextService.java

modules/content/src/main/java/com/zkry/content/service/XhsTravelTools.java
modules/content/src/main/java/com/zkry/content/service/XhsContentService.java
modules/content/src/main/java/com/zkry/content/service/XhsNativeClient.java
modules/content/src/main/java/com/zkry/content/service/XhsSignService.java

modules/ai/src/main/java/com/zkry/ai/service/AiAgentService.java
modules/ai/src/main/java/com/zkry/ai/service/AiStructuredOutputService.java
modules/ai/src/main/java/com/zkry/ai/agent/TripstarAgent.java
modules/ai/src/main/java/com/zkry/ai/prompt/TripstarPrompt.java
modules/ai/src/main/java/com/zkry/ai/prompt/TripstarPromptVariable.java

common/core/src/main/java/com/zkry/common/core/constant/TravelDataSource.java
common/core/src/main/java/com/zkry/common/core/constant/TravelToolResponseFields.java
```

## 2. 一次任务的完整流转

前端提交：

```text
frontend/src/services/api.ts
  -> submitTripPlan()
  -> POST /api/trip/plan
```

后端入口：

```text
TripController.plan()
  -> TripTaskService.submit()
```

`submit()` 做这些事：

1. 校验城市、天数等请求参数。
2. 校验运行时配置：小红书 Cookie、高德 Web Service Key、AI API Key、模型名。
3. 创建 `taskId`，把状态放入内存 Map。
4. 后台线程执行 `runPlanning(taskId, request)`。

前端会连接：

```text
WS /api/trip/ws/{taskId}
```

后端 WebSocket：

```text
TripTaskWebSocketHandler
  -> TripTaskService.subscribe()
  -> 每次 update() 推送 TripTaskEvent
```

## 3. 主流程现在怎么跑

主流程在：

```text
TripTaskService.runPlanning()
```

当前阶段：

```text
submitted
  -> initializing
  -> travel_research
  -> weather_search
  -> hotel_search
  -> planning
  -> graph_building
  -> completed / failed
```

这些状态和阶段常量集中在：

```text
TripTaskStatus
  -> PROCESSING / COMPLETED / FAILED

TripTaskStage
  -> SUBMITTED / INITIALIZING / TRAVEL_RESEARCH / WEATHER_SEARCH
  -> HOTEL_SEARCH / PLANNING / GRAPH_BUILDING / COMPLETED / FAILED
```

所以后端不要再裸写 `"travel_research"` 这类字符串；新增阶段时先加常量，再同步前端 `TripTaskStage` 类型。

进度条文案集中在：

```text
TripTaskMessages
  -> SUBMITTED / INITIALIZING / TRAVEL_RESEARCH / PLANNING
  -> GRAPH_BUILDING / COMPLETED / FAILED
```

这样 `TripTaskService` 只负责状态机推进，不再散落页面展示文案。

核心代码流：

```text
runPlanning()
  -> tripResearchService.research()
       -> 小红书 service/tool/both
       -> TravelResearchAgent 调用高德/小红书工具
       -> 返回 MapPlanningContext + ContentPlanningContext
  -> tripAiPlannerService.plan()
       -> TripPlannerAgent 结构化输出 TripPlan
       -> TripReviewAgent 结构化输出 ReviewResult
  -> TripPlanResponseFactory.fromPlan()
       -> 派生 graph_data
  -> update(completed)
```

如果任意关键阶段失败，会进入 `failed`：

```text
catch Exception
  -> update(failed)
  -> WebSocket 推送错误给前端
```

## 4. ResearchAgent 是什么

研究阶段入口：

```text
TripResearchService.research(taskId, request)
```

它负责把“查资料”从主流程里拆出来：

```text
TripResearchService
  -> 读取 xhs_mode
  -> 必要时先跑小红书 service
  -> 调用 TravelResearchAgent
  -> 给 Agent 挂载 AmapTravelTools / XhsTravelTools
  -> 合并 service/tool 结果
```

Prompt：

```text
research-system.md
research-user.md
```

Agent 常量：

```text
TripstarAgent.TRAVEL_RESEARCH
```

结构化返回：

```text
TravelResearchResult
  -> map_context
  -> content_context
  -> user_constraints
  -> excluded_places
  -> tool_calls
  -> summary
```

这里的重点是：Agent 不是直接生成最终行程，而是先理解用户需求并调用工具收集真实上下文。

## 5. 高德 Tool 怎么实现

高德工具类：

```text
AmapTravelTools
```

暴露给 ReactAgent 的工具：

```text
amap_geocode
amap_poi_search
amap_hotel_search
amap_restaurant_search
amap_weather
amap_collect_city_context
```

工具名统一放在：

```text
AmapToolNames
```

这些 Tool 没有重新写 HTTP 逻辑，而是复用：

```text
AmapMapContextService
  -> geocode()
  -> searchPois()
  -> weatherForecasts()
  -> collectCity()
```

所以代码分工是：

```text
AmapMapContextService
  负责真实高德 REST API 调用

AmapTravelTools
  负责把 Java 方法包装成 Spring AI Tool
```

每个 Tool 返回给 LLM 的 JSON 字段固定为：

```json
{
  "success": true,
  "source": "amap",
  "tool": "amap_hotel_search",
  "data": {}
}
```

字段名集中在 `TravelToolResponseFields`，数据来源集中在 `TravelDataSource`。这样你看日志时能确认：Agent 调的是 `amap_hotel_search`，而不是混成普通 `amap_poi_search`。

为什么本期没有直接用高德 MCP？

因为 Java 后端里直接写 `@Tool` 更适合学习 Spring AI Alibaba：依赖少、日志清楚、DTO 可控。以后要接 MCP，可以把 MCP 返回再包装成 Tool，但本期先把 Agent Tool 机制跑顺。

## 6. 小红书 service/tool/both

配置项：

```yaml
tripstar:
  content:
    xhs:
      mode: service # service | tool | both
```

也可以通过 Vue 设置页切换“小红书采集模式”。

三种模式：

```text
service
  TripResearchService 先调用 XhsContentService.collect()
  TravelResearchAgent 不挂小红书 Tool

tool
  TravelResearchAgent 挂 XhsTravelTools
  Agent 自己调用 xhs_collect_city_context / xhs_search_notes / xhs_note_detail

both
  先跑 XhsContentService.collect()
  再让 TravelResearchAgent 调 XhsTravelTools
  最后合并两边 ContentPlanningContext
```

小红书 service 路径：

```text
XhsContentService.collect()
  -> collectCity()
  -> XhsNativeClient.searchNotes()
  -> XhsNativeClient.noteDetail()
  -> XhsExtractionAgent 提炼景点候选
```

小红书 Tool 路径：

```text
XhsTravelTools
  -> xhs_collect_city_context()
  -> xhs_search_notes()
  -> xhs_note_detail()
```

工具名统一放在：

```text
XhsToolNames
```

小红书 Tool 返回结构也使用同一套 `success/source/tool/data/error` 字段。成功时 `source=xhs`，失败时 `error` 会带上 Cookie、签名、接口等具体失败原因，但不会打印完整 Cookie。

小红书签名仍在：

```text
XhsSignService
```

它默认调用 Java content 模块内置的签名 JS 资源：

```text
modules/content/src/main/resources/xhs_sign/
```

默认配置是 `XHS_SIGN_DIR=classpath:xhs_sign`。由于 Node.js 的 `require()` 需要真实文件路径，`XhsSignService` 第一次签名时会把 classpath 里的 JS 文件抽取到临时目录，后续请求复用这个目录。日志里看到的 `signDir` 临时路径是正常现象。

## 7. ReactAgent 入口

统一入口：

```text
AiAgentService.call(...)
```

现在支持两类调用：

```text
call(TripstarAgent, instruction, userPrompt, threadId)
call(TripstarAgent, instruction, userPrompt, threadId, methodTools...)
```

核心构造：

```java
ReactAgent.builder()
    .name(agentName)
    .model(chatModel)
    .instruction(instruction)
    .methodTools(methodTools)
    .enableLogging(true)
    .build()
```

工具挂载发生在：

```text
TripResearchService.collectByAgent()
```

```java
Object[] tools = mode.useTool()
    ? new Object[] {amapTravelTools, xhsTravelTools}
    : new Object[] {amapTravelTools};
```

这就是 ReactAgent 发挥作用的地方：它可以根据 prompt 自己决定调用哪些工具。

当前工具策略：

```text
xhs_mode=service
  -> Agent 只挂 AmapTravelTools

xhs_mode=tool/both
  -> Agent 挂 AmapTravelTools + XhsTravelTools
```

所以你想观察 Agent 自主调用小红书，就把设置页的小红书模式切到 `tool` 或 `both`。

## 8. Structured Output 怎么简化代码

结构化输出封装在：

```text
AiStructuredOutputService
```

它基于：

```text
org.springframework.ai.converter.BeanOutputConverter
```

用法分两步：

1. 把格式说明塞进 Prompt：

```java
variables.put("format", structuredOutputService.format(TripPlan.class));
```

2. 直接调用并转换：

```java
Optional<TripPlan> plan = structuredOutputService.callForObject(
    TripstarAgent.TRIP_PLANNER,
    TripPlan.class,
    systemPrompt,
    userPrompt,
    threadId
);
```

现在主要结构化输出：

```text
TravelResearchResult
TripPlan
ReviewResult
List<ContentAttractionCandidate>
```

当前主线只有 Structured Output：模型输出不能被 `BeanOutputConverter` 转成 DTO 时，本次任务会明确失败并打印结构化解析日志。旧的手写 JSON 提取/修复路线已经删除。

## 9. PlannerAgent 和 ReviewAgent

规划入口：

```text
TripAiPlannerService.plan()
```

流程：

```text
1. 读取 planner-system.md / planner-user.md
2. TripPlannerPrompts.plannerVariables() 组装用户需求、地图上下文、小红书上下文
3. 加入 Structured Output format
4. 调用 TripPlannerAgent
5. BeanOutputConverter 解析成 TripPlan
6. normalize() 补齐必要字段
7. TripReviewAgent 检查结构和可执行性
8. 生成 TripPlanResponse
```

Prompt：

```text
planner-system.md
planner-user.md
review-system.md
review-user.md
```

Agent 常量：

```text
TripstarAgent.TRIP_PLANNER
TripstarAgent.TRIP_REVIEW
```

## 10. “不想看滇池”现在怎么处理

如果用户写：

```text
我带老人去昆明，不想太累，不想看滇池，住得方便一点
```

现在 ResearchAgent prompt 明确要求：

```text
当用户表达“不想去/不要/避开/不看”等否定偏好时，要记录到 excluded_places。
```

也就是说研究阶段应该输出：

```json
{
  "excluded_places": ["滇池"]
}
```

然后规划 Agent 会看到原始用户需求和研究摘要，应该避免把滇池放进行程。后续如果要更稳，可以在 Java 层加硬规则：最终 TripPlan 里出现 `excluded_places` 就直接让 ReviewAgent 判失败或触发重规划。

## 11. 日志怎么看

### 主任务日志

```text
[TripTask]
```

能看到任务创建、阶段更新、失败原因。

### 研究阶段日志

```text
[Research]
```

重点看：

```text
开始旅行资料研究 taskId=... xhsMode=... useService=... useTool=...
调用 TravelResearchAgent toolCount=... tools=[AmapTravelTools, XhsTravelTools]
Agent 研究摘要 excludedPlaces=... constraints=... summary=...
```

### Agent 调用日志

```text
[AI-AGENT]
[AI-STRUCTURED]
```

重点看：

```text
agent
threadId
instructionLength
promptLength
toolCount
tools
responseLength
elapsedMs
结构化输出解析成功/失败
```

### 高德工具日志

```text
[AMap-Tool]
[AMap]
```

Tool 层看 Agent 调用过程：

```text
[AMap-Tool] collectCityContext city=昆明 ...
[AMap-Tool] collectCityContext 成功 city=昆明 attractions=... hotels=... restaurants=... weather=...
```

如果 Agent 分别调用酒店/餐饮工具，日志和返回 JSON 会保留真实工具名：

```text
[AMap-Tool] amap_hotel_search city=昆明 keywords=昆明 住得方便一点 limit=5
[AMap-Tool] amap_restaurant_search city=昆明 keywords=昆明 特色美食 limit=5
```

REST 层看具体 API：

```text
[AMap] 地理编码
[AMap] POI 搜索
[AMap] 天气查询
```

### 小红书工具日志

```text
[XHS-Tool]
[XHS]
[XHS-API]
[XHS-SIGN]
```

Tool 层看 Agent 调用：

```text
[XHS-Tool] collectCityContext city=昆明 ...
[XHS-Tool] collectCityContext 成功 rawLength=... candidates=...
```

API 和签名层看真实接口：

```text
[XHS-SIGN] 签名生成
[XHS-API] 搜索笔记 / 获取详情
```

日志不会打印 Cookie、API Key、完整 prompt、完整工具返回体。

## 12. 示例运行过程

请求：

```json
{
  "city": "昆明",
  "cities": [{"city": "昆明", "days": 3}],
  "start_date": "2026-07-10",
  "end_date": "2026-07-12",
  "travel_days": 3,
  "transportation": "公共交通",
  "accommodation": "住得方便一点",
  "preferences": ["自然风光", "美食", "轻松"],
  "free_text_input": "带老人，不想太累，不想看滇池",
  "language": "zh"
}
```

典型日志顺序：

```text
[TripTask] 创建旅行规划任务
[TripTask] 进度更新 stage=travel_research

[Research] 开始旅行资料研究 xhsMode=both useService=true useTool=true
[Research] 使用小红书 service 采集内容
[XHS] 开始采集小红书游记
[XHS-API] 准备搜索笔记
[XHS-SIGN] 签名生成成功
[XHS] LLM 提炼解析成功

[Research] 调用 TravelResearchAgent tools=[AmapTravelTools, XhsTravelTools]
[AI-AGENT] 开始调用 ReactAgent agent=travel-research-agent toolCount=2
[AMap-Tool] collectCityContext city=昆明
[AMap-Tool] collectCityContext 成功 attractions=... hotels=... restaurants=... weather=...
[XHS-Tool] collectCityContext city=昆明
[XHS-Tool] collectCityContext 成功 rawLength=... candidates=...
[AI-STRUCTURED] 结构化输出解析成功 agent=travel-research-agent
[Research] Agent 研究摘要 excludedPlaces=[滇池]

[AI-STRUCTURED] 开始结构化 Agent 调用 agent=trip-planner-agent
[AI-STRUCTURED] 结构化输出解析成功 agent=trip-planner-agent
[AI-REVIEW] ReviewAgent 完成 passed=true

[TripTask] 规划结果生成
[TripTask] 进度更新 stage=completed
```

## 13. 知识图谱是什么

知识图谱不是外部图数据库，也不是独立知识库。

它由 Java 从最终 `TripPlan` 派生：

```text
TripPlanResponseFactory.fromPlan()
  -> createKnowledgeGraph()
```

图结构大致是：

```text
城市 -> 第1天
第1天 -> 酒店
第1天 -> 景点
第1天 -> 早餐/午餐/晚餐
城市 -> 总预算
```

前端展示：

```text
frontend/src/views/Result.vue
  -> echarts graph series
```

所以它是真实最终行程的结构化展示，不是独立的数据采集结果。

换句话说：图谱的数据真实来自本次 LLM 生成的 `TripPlan`，但“图谱关系”是后端按展示规则派生出来的，不是从高德/小红书直接返回的一张图。

## 14. 二开入口

### 改 Prompt

```text
modules/ai/src/main/resources/prompts/tripstar/
```

优先改 prompt，不要在 Java 里硬编码大段提示词。

### 新增 Agent

1. 在 `TripstarAgent` 增加枚举。
2. 在 `TripstarPrompt` 增加 prompt 路径。
3. 新增 system/user prompt 文件。
4. 用 `AiAgentService` 或 `AiStructuredOutputService` 调用。

### 新增 Tool

1. 新建 `xxxTools` 类。
2. 方法加 `@Tool`，参数加 `@ToolParam`。
3. 方法内部调用稳定的 Service。
4. 在对应 Agent 调用时通过 `methodTools(...)` 挂上。
5. 工具名放到 `xxxToolNames`，返回字段使用 `TravelToolResponseFields`。

### 新增状态或文案

1. 状态值加到 `TripTaskStatus`。
2. 阶段值加到 `TripTaskStage`。
3. 前端展示文案加到 `TripTaskMessages`。
4. 同步前端阶段类型和进度展示。

### 强化“不想去某地”的约束

建议加在：

```text
TripResearchService
TripAiPlannerService.reviewPlan()
```

做法：

```text
ResearchAgent 提取 excluded_places
Java 检查 TripPlan attractions/hotel/meals 是否包含 excluded_places
命中则 Review 失败或触发重规划
```

### 持久化任务

现在任务状态在内存：

```text
TripTaskService.tasks
```

后续可以落：

```text
MySQL / Redis / MongoDB
```

建议字段：

```text
taskId
request
status
stage
progress
result
error
createdAt
updatedAt
```

## 15. 推荐阅读顺序

1. `TripController`
2. `TripTaskService`
3. `TripResearchService`
4. `AmapTravelTools`
5. `AmapMapContextService`
6. `XhsTravelTools`
7. `XhsContentService`
8. `XhsNativeClient`
9. `XhsSignService`
10. `AiAgentService`
11. `AiStructuredOutputService`
12. `TripAiPlannerService`
13. `TripPlanResponseFactory`
14. `prompts/tripstar/*.md`
15. `frontend/src/components/NavBar.vue`
16. `frontend/src/views/Result.vue`

按这个顺序读，你能把“前端提交 -> Agent 调工具 -> LLM 规划 -> 图谱展示”的主链路串起来。
