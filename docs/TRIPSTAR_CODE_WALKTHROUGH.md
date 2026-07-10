# TripStar Java 代码学习导读

这份文档按“一个任务来了，代码怎么跑”的顺序讲 Java 版 TripStar。当前版本已经进入 Spring AI Alibaba Graph + 分阶段 Agent Workflow 阶段：

- `TripResearchService` 使用 Spring AI Alibaba `StateGraph` 控制资料研究阶段顺序。
- 每个资料研究节点内部由一个 ReactAgent 负责。
- 高德 POI、酒店、餐饮、天气分别由不同 Agent 调用白名单 Tool 获取。
- 小红书支持 `service` / `tool` / `both` 三种模式。
- LLM JSON 输出主要使用 Spring AI `BeanOutputConverter` 结构化输出解析。
- Prompt 统一放在 `modules/ai/src/main/resources/prompts/tripstar/`。

## 1. 模块分层

```text
app
  Controller、WebSocket、启动配置、运行时设置接口

modules/trip
  旅行规划主流程、Research Workflow 编排、PlannerAgent 编排、DTO、知识图谱转换

modules/content
  小红书搜索、详情、签名、图片、小红书 Tool、游记内容提炼

modules/map
  高德 REST 客户端能力、高德 Tool、POI/天气 DTO

modules/ai
  Spring AI Alibaba、ReactAgent、Structured Output、Prompt 资源读取、Agent 常量、AI Trace 文件

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
modules/map/src/main/java/com/zkry/map/service/AmapGeoPoiTools.java
modules/map/src/main/java/com/zkry/map/service/AmapWeatherTools.java
modules/map/src/main/java/com/zkry/map/service/AmapHotelTools.java
modules/map/src/main/java/com/zkry/map/service/AmapMapContextService.java

modules/content/src/main/java/com/zkry/content/service/XhsTravelTools.java
modules/content/src/main/java/com/zkry/content/service/XhsSearchTools.java
modules/content/src/main/java/com/zkry/content/service/XhsDetailTools.java
modules/content/src/main/java/com/zkry/content/service/XhsContentService.java
modules/content/src/main/java/com/zkry/content/service/XhsNativeClient.java
modules/content/src/main/java/com/zkry/content/service/XhsSignService.java

modules/ai/src/main/java/com/zkry/ai/service/AiAgentService.java
modules/ai/src/main/java/com/zkry/ai/service/AiPromptTraceService.java
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
  -> xhs_search
  -> xhs_detail
  -> amap_poi_search
  -> weather_search
  -> hotel_search
  -> research_merge
  -> planning
  -> graph_building
  -> completed / failed
```

这些状态和阶段常量集中在：

```text
TripTaskStatus
  -> PROCESSING / COMPLETED / FAILED

TripTaskStage
  -> SUBMITTED / INITIALIZING / TRAVEL_RESEARCH / XHS_SEARCH / XHS_DETAIL
  -> AMAP_POI_SEARCH / WEATHER_SEARCH / HOTEL_SEARCH / RESEARCH_MERGE
  -> PLANNING / GRAPH_BUILDING / COMPLETED / FAILED
```

所以后端不要再裸写 `"travel_research"` 这类字符串；新增阶段时先加常量，再同步前端 `TripTaskStage` 类型。

进度条文案集中在：

```text
TripTaskMessages
  -> SUBMITTED / INITIALIZING / TRAVEL_RESEARCH / XHS_SEARCH / XHS_DETAIL
  -> AMAP_POI_SEARCH / WEATHER_SEARCH / HOTEL_SEARCH / RESEARCH_MERGE
  -> PLANNING / GRAPH_BUILDING / COMPLETED / FAILED
```

进度百分比集中在：

```text
TripTaskProgress
  -> 0~30：景点资料搜索，包括 xhs_search、xhs_detail、amap_poi_search
  -> 31~50：weather_search
  -> 51~70：hotel_search
  -> 70 以后：research_merge、planning、graph_building、completed
```

注意：后端阶段可以很细，但前端 Landing 页的 stepper 是按进度百分比展示大阶段。
为了适配原 Vue 项目，WebSocket 推给前端的景点资料阶段统一使用 `attraction_search`，
小红书搜索、小红书详情、高德 POI 的区别只体现在 `message` 和后端日志里。
所以小红书详情只应该改变文案，不应该把 stage 或进度推到天气区间。
同理，研究结果合并阶段推给前端的 stage 使用 `planning`，避免原 Vue 项目不识别 `research_merge`。

这样 `TripTaskService` 只负责状态机推进，不再散落页面展示文案。

核心代码流：

```text
runPlanning()
  -> tripResearchService.research(..., progressReporter)
       -> 小红书 service/tool/both
       -> XhsSearchAgent 只拿 xhs_search_notes
       -> XhsDetailAgent 只拿 xhs_note_detail
       -> AmapPoiAgent 只拿 amap_geocode / amap_poi_search
       -> AmapWeatherAgent 只拿 amap_weather
       -> AmapHotelAgent 只拿 amap_hotel_search / amap_restaurant_search
       -> Java 合并 MapPlanningContext + ContentPlanningContext
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

## 4. Spring AI Alibaba Graph 资料研究

研究阶段入口：

```text
TripResearchService.research(taskId, request)
```

它负责把“查资料”从主流程里拆出来，并用 `StateGraph` 保证阶段顺序：

```text
StateGraph
  START
    -> xhs_mode_route
       service -> xhs_service_optional
       tool    -> xhs_search_agent -> xhs_detail_agent
       both    -> xhs_service_optional -> xhs_search_agent -> xhs_detail_agent
    -> xhs_ready_check
    -> amap_poi_agent
    -> amap_weather_agent
    -> amap_hotel_agent
    -> merge_research_context
  END
```

读源码时先看 `buildResearchGraph()`：这里就是 Graph 的节点和边。再顺着
`graphRouteXhsMode()`、`graphCollectServiceContent()`、`graphSearchXhs()`、`graphReadXhsDetails()` 这些方法往下看，
就能知道每个节点实际做了什么。

如果你想对照真实运行过程，打开 `TripResearchService.buildResearchGraph()`，按下面顺序看：

```text
addNode(...)              注册节点
addConditionalEdges(...)  注册小红书 service/tool/both 条件边
addEdge(...)              注册固定阶段顺序
compile()                 编译并校验 Graph
```

小红书 `service/tool/both` 已经用 `addConditionalEdges(...)` 表达：

- `service` 只走小红书 service 采集，成功后直接进入小红书 ready 校验。
- `tool` 跳过 service，直接进入小红书搜索 Agent 和详情 Agent。
- `both` 先走 service，再走 Agent tool，最后要求两边都拿到真实小红书内容。

这样你看 `buildResearchGraph()` 就能知道当前模式会跑哪些节点，不需要再去每个节点里找“是否跳过”的判断。

Prompt：

```text
research-xhs-search-system.md / research-xhs-search-user.md
research-xhs-detail-system.md / research-xhs-detail-user.md
research-amap-poi-system.md / research-amap-poi-user.md
research-amap-weather-system.md / research-amap-weather-user.md
research-amap-hotel-system.md / research-amap-hotel-user.md
```

Agent 常量：

```text
TripstarAgent.XHS_SEARCH
TripstarAgent.XHS_DETAIL
TripstarAgent.AMAP_POI_RESEARCH
TripstarAgent.AMAP_WEATHER_RESEARCH
TripstarAgent.AMAP_HOTEL_RESEARCH
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

为什么要分阶段？

```text
一个 Agent 拿全部工具
  优点：看起来最自由
  缺点：无法保证先搜小红书、再查 POI、再查天气和酒店，前端进度也不真实

StateGraph + 分阶段 Agent
  优点：阶段顺序确定，工具调用仍由 Agent 自主决定参数，前端进度对应真实执行
  适合学习：能看到 Spring AI Alibaba Graph 的节点、边、编译和执行
```

## 4.1 Agent Trace 文件怎么用

每次 ReactAgent 调用都会由 `AiPromptTraceService` 写一个 Markdown 文件：

```text
logs/ai-trace/yyyy-MM-dd/{time}_{threadId}_{agent}.md
```

文件里会包含：

```text
System Prompt
User Prompt
Model Output
tools
elapsedMs
```

排查 Agent 问题时，建议先看对应 `threadId`：

- 小红书搜索没有笔记：看 `*-xhs-search_*` 文件，确认关键词和模型输出。
- 小红书详情没有正文：看 `*-xhs-detail_*` 文件，确认 note_id / xsec_token 是否传入。
- 高德 POI、天气、酒店失败：看 `*-amap-poi_*`、`*-amap-weather_*`、`*-amap-hotel_*` 文件，确认工具返回和 `realData` 判断。
- 结构化输出解析失败：看 `Model Output` 是否严格是 JSON，字段是否符合 `{{format}}`。

这个 trace 是学习和排查用的，不参与业务逻辑。生产环境如果担心记录用户输入，可以设置：

```bash
AI_TRACE_ENABLED=false
```

## 5. 高德 Tool 怎么实现

高德工具类：

```text
AmapTravelTools
AmapGeoPoiTools
AmapWeatherTools
AmapHotelTools
```

暴露给 ReactAgent 的工具：

```text
amap_geocode
amap_poi_search
amap_hotel_search
amap_restaurant_search
amap_weather
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
```

所以代码分工是：

```text
AmapMapContextService
  负责真实高德 REST API 调用

AmapTravelTools
  负责统一调用高德 service，并返回 success/source/tool/data/error JSON

AmapGeoPoiTools / AmapWeatherTools / AmapHotelTools
  负责按阶段暴露 Spring AI Tool 白名单
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

高德阶段现在是严格校验：

```text
高德 POI Agent 返回后 -> 必须有 map_context，且 realData=true
高德天气 Agent 返回后 -> 必须有 map_context，且 realData=true
高德酒店餐饮 Agent 返回后 -> 必须有 map_context，且 realData=true
```

如果 Agent 结构化输出缺少 `map_context`，或者工具返回失败后 Agent 没有拿到真实数据，`TripResearchService` 会在当前阶段直接抛错。这样不会出现“小红书或高德已经失败，但后面酒店、天气还在继续跑”的情况。

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
  小红书 Agent Tool 阶段不执行

tool
  XhsSearchAgent 先调用 xhs_search_notes
  XhsDetailAgent 再调用 xhs_note_detail

both
  先跑 XhsContentService.collect()
  再跑 XhsSearchAgent + XhsDetailAgent
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

`noteDetail()` 只调用 `/api/sns/web/v1/feed`。如果接口返回“笔记不存在”、Cookie 失效、风控、登录异常或其它业务失败，错误会进入 `[XHS-API]` / `[XHS-Tool]` 日志，并由 `TripResearchService` 在小红书阶段直接终止任务；当前版本不再通过页面解析补数据。

小红书 Tool 路径：

```text
XhsSearchTools
  -> xhs_search_notes()

XhsDetailTools
  -> xhs_note_detail()

XhsTravelTools
  -> 底层搜索/详情实现，供阶段白名单包装类复用
```

小红书是必需数据源。工具单次失败会先以 `success=false` 返回给 Agent，让 Agent 有机会换笔记继续尝试；但 `TripResearchService` 会在 `xhs_search` 和 `xhs_detail` 阶段结束后做硬校验。如果当前启用的小红书路径没有拿到可读取笔记或真实游记正文，任务会立刻失败，不再继续调用高德 POI、天气和酒店。

小红书景点坐标校准：

```text
XhsDetailAgent / XhsContentService
  -> 提炼 ContentAttractionCandidate（景点名、理由、预约、避坑）
  -> TripPlannerPrompts.xhsAttractionCandidatesBlock()
  -> 作为 {{xhs_attractions}} 传给 AmapPoiAgent
  -> AmapPoiAgent 调用 amap_geocode / amap_poi_search 校准高德真实 POI 和经纬度
```

这条链路对齐 Python 原项目的思路：小红书决定“哪些景点值得去”，高德负责“这些景点在哪”。区别是 Java 版把坐标校准交给 POI Agent，而不是让小红书详情 Agent 同时拿地图工具。

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
TripResearchService.research()
```

这就是 ReactAgent 发挥作用的地方：每个阶段只拿自己的工具白名单，阶段内部由模型决定关键词和参数。

当前工具策略：

```text
xhs_mode=service
  -> 小红书走 XhsContentService
  -> 高德分阶段挂 AmapGeoPoiTools / AmapWeatherTools / AmapHotelTools

xhs_mode=tool/both
  -> XhsSearchAgent 挂 XhsSearchTools
  -> XhsDetailAgent 挂 XhsDetailTools
  -> 高德分阶段挂 AmapGeoPoiTools / AmapWeatherTools / AmapHotelTools
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

现在小红书详情 Agent 和高德 POI Agent 的 prompt 都明确要求：

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
调用 XhsSearchAgent / XhsDetailAgent / AmapPoiAgent / AmapWeatherAgent / AmapHotelAgent
合并研究摘要 excludedPlaces=... constraints=... summary=...
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
[AMap-Tool] amap_poi_search city=昆明 keywords=昆明 老人 轻松 景点 limit=5
[AMap-Tool] weather city=昆明
[AMap-Tool] amap_hotel_search city=昆明 keywords=昆明 住得方便一点 limit=5
```
日志和返回 JSON 会保留真实工具名：

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
[XHS-Tool] searchNotes keyword=昆明旅游攻略 page=1 requestedPageSize=5 apiPageSize=20 returnLimit=5
[XHS-Tool] noteDetail noteId=... xsecSource=pc_search
```

API 和签名层看真实接口：

```text
[XHS-SIGN] 签名生成
[XHS-API] 准备搜索笔记 keyword=昆明旅游攻略 page=1 sortType=0 requestedPageSize=20 actualPageSize=20
[XHS-API] 接口调用成功 api=/api/sns/web/v1/search/notes itemCount=... hasMore=...
```

这里要注意：`xhs_search_notes` 里的 `pageSize` 只是兼容参数，不直接透传给小红书接口。
小红书搜索接口当前按 `page_size=5/10` 可能返回 `success=true` 但没有 `items`，所以底层统一按 20 条请求，
再由 Tool 层固定截取最多 5 条给 Agent。后面的 XhsSearchAgent 不再二次筛选这 5 条，
XhsDetailAgent 会读取搜索结果里的全部笔记详情，并在 rawText 中保留“笔记1/笔记2”的边界。

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

[Research] 开始分阶段旅行资料研究 xhsMode=both useService=true useTool=true
[Research] 使用小红书 service 采集内容
[XHS] 开始采集小红书游记
[XHS-API] 准备搜索笔记
[XHS-SIGN] 签名生成成功
[XHS] LLM 提炼解析成功

[TripTask] 进度更新 stage=attraction_search message=正在调用小红书搜索智能体...
[Research] 调用 XhsSearchAgent
[XHS-Tool] searchNotes keyword=昆明 老人 轻松 景点攻略
[AI-STRUCTURED] 结构化输出解析成功 agent=xhs-search-agent

[TripTask] 进度更新 stage=attraction_search message=正在调用小红书详情智能体...
[Research] 调用 XhsDetailAgent
[XHS-Tool] noteDetail noteId=...
[AI-STRUCTURED] 结构化输出解析成功 agent=xhs-detail-agent

[TripTask] 进度更新 stage=attraction_search message=正在调用高德 POI 智能体...
[Research] 调用地图阶段 Agent agent=amap-poi-research-agent
[AMap-Tool] amap_poi_search city=昆明 keywords=昆明 老人 轻松 景点

[TripTask] 进度更新 stage=weather_search
[Research] 调用地图阶段 Agent agent=amap-weather-research-agent
[AMap-Tool] weather city=昆明

[TripTask] 进度更新 stage=hotel_search
[Research] 调用地图阶段 Agent agent=amap-hotel-research-agent
[AMap-Tool] amap_hotel_search city=昆明 keywords=昆明 住得方便一点
[AMap-Tool] amap_restaurant_search city=昆明 keywords=昆明 特色美食

[TripTask] 进度更新 stage=planning message=正在合并小红书和高德上下文...
[Research] 合并研究摘要 excludedPlaces=[滇池]

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
研究阶段 Agent 提取 excluded_places
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
4. `AmapGeoPoiTools` / `AmapWeatherTools` / `AmapHotelTools`
5. `AmapTravelTools`
6. `AmapMapContextService`
7. `XhsSearchTools` / `XhsDetailTools`
8. `XhsTravelTools`
9. `XhsContentService`
10. `XhsNativeClient`
11. `XhsSignService`
12. `AiAgentService`
13. `AiStructuredOutputService`
14. `TripAiPlannerService`
15. `TripPlanResponseFactory`
16. `prompts/tripstar/*.md`
15. `frontend/src/components/NavBar.vue`
16. `frontend/src/views/Result.vue`

按这个顺序读，你能把“前端提交 -> Agent 调工具 -> LLM 规划 -> 图谱展示”的主链路串起来。
