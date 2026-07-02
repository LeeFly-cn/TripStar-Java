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
  -> TripAiPlannerService 调用 Spring AI Alibaba
  -> AI TripPlan 转 TripPlanResponse + graph_data
  -> 返回 TripPlanResponse(data + graph_data)
```

关键代码：

- `app/src/main/java/com/zkry/api/trip/TripController.java`
- `app/src/main/java/com/zkry/api/trip/TripTaskWebSocketHandler.java`
- `modules/trip/src/main/java/com/zkry/trip/service/TripTaskService.java`
- `modules/trip/src/main/java/com/zkry/trip/service/TripAiPlannerService.java`
- `modules/trip/src/main/java/com/zkry/trip/service/TripPlanResponseFactory.java`
- `modules/ai/src/main/java/com/zkry/ai/service/AiTextService.java`
- `modules/ai/src/main/java/com/zkry/ai/service/LlmJsonExtractor.java`
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

这相当于先用 Java service 模拟智能体协作。后续可以把每个阶段替换成真正的 Agent 或 Tool。

当前 `attraction_search` 阶段会先调用 `TravelContentService` 采集小红书游记，再调用 `MapContextService` 采集地图上下文。如果没有配置小红书 Cookie、高德 Web Service Key 或 AI Key，任务会明确失败并提示去 Vue 设置页补配置；配置齐全后，会把游记提炼、真实 POI、酒店、餐饮和天气一起交给 Planner。

## 3. 当前 AI 接入方式

`AiTextService` 是最小 AI 网关：

```text
TripAiPlannerService
  -> AiTextService.generate(systemPrompt, userPrompt)
  -> Optional<文本结果>
```

设计重点：

- 不直接把 Spring AI Alibaba 的 API 泄露给业务层。
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

## 4. Planner Prompt 在哪里

旅行规划 Prompt 在：

```text
modules/trip/src/main/java/com/zkry/trip/prompt/TripPlannerPrompts.java
```

它做了几件事：

- 给模型一个系统角色：TripStar 旅行规划智能体。
- 要求模型只输出合法 JSON。
- 固定 JSON key 为英文 snake_case。
- 明确 `TripPlan` 的完整 schema。
- 要求每天有景点、三餐、酒店、天气、预算。
- 多城市时要求标记城市和移动日。

这是后续学习 LLM 应用的重点：**Prompt 不只是自然语言，它也是接口契约的一部分。**

## 5. LLM JSON 解析与容错

LLM 经常会输出：

- markdown 包裹的 JSON
- JSON 前后的解释文字
- 截断 JSON
- 字段缺失
- 非法引号或多余符号

当前 JSON 提取在：

```text
modules/ai/src/main/java/com/zkry/ai/service/LlmJsonExtractor.java
```

它负责从模型文本中提取 JSON 对象或数组，并生成多个候选 JSON：

- 去掉 markdown fence。
- 清理 BOM、控制字符和常见中文弯引号。
- 提取第一段平衡的 JSON 对象或数组。
- 去掉对象/数组末尾多余逗号。
- 尝试补齐截断时缺失的 `}` 或 `]`。

`TripAiPlannerService` 会逐个解析这些候选，只要有一个能转成 `TripPlan` 就成功。后续还可以继续迁移 Python 版 `_llm_repair_json`：当本地修复失败时，再让模型只负责“修 JSON”，而不是重新规划。

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
  -> TripPlannerPrompts.plannerPrompt(request, mapContext)
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

- `XhsSignService`：调用本地 Node.js 和旧 Python 项目里的签名 JS，生成小红书请求头。
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

Spring AI Alibaba 里的 `ReactAgent`、Tool callback、子 Agent 工具化能力，适合放到第三阶段。不要在第一版就把所有逻辑塞进 Agent。

## 10. 面向用户端旅游规划的演进方向

如果未来要开发真正用户端产品，可以继续加：

- 用户画像：预算、体力、亲子/情侣/老人、饮食禁忌、节奏偏好。
- 收藏系统：用户收藏景点、酒店、餐厅。
- 可编辑行程：拖拽调整景点顺序，重新计算路线和预算。
- 追问式规划：用户问“能不能轻松一点”“换成亲子路线”时，局部重排。
- 历史行程：保存、复制、二次编辑。
- 反馈闭环：用户对景点/餐厅/路线打分，用于下一次推荐。
- 缓存和限流：地图、天气、POI、LLM 调用都需要缓存。
- 隐私和 Key 管理：API Key 只放服务端，不放移动端。

## 11. 当前下一步建议

下一步不要急着写多 Agent，建议按这个顺序：

1. 在 Vue 设置页保存小红书 Cookie、高德 Web Service Key、AI API Key 和模型名。
2. 验证 `XhsContentService` 能搜索笔记、拉详情并提炼景点。
3. 验证 `AmapMapContextService` 能返回 POI、酒店、餐饮、天气。
4. 同时启用小红书 + 高德 + AI，验证 `TripAiPlannerService` 能稳定生成 `TripPlan`。
5. 加一层 JSON repair Agent：本地候选都解析失败时，让 LLM 只修 JSON。
6. 最后再引入 ReactAgent 和工具调用。
