# TripStar Java 后端迁移计划

> 目标：在现有 `backend_java` 多模块 Spring Boot 脚手架上，用 Java + Spring AI Alibaba 重建 TripStar 的 Python FastAPI 后端，同时尽量保持现有 Vue 前端可直接复用或只做极少改动。

## 0. 当前决策

- 第一阶段继续复用现有 Vue 前端。
- 尽量保持 Python 后端现有 API 契约不变。
- 基于 `backend_java` 开发，不另起一个 Spring Boot 项目。
- 核心链路不再强依赖小红书 Cookie，小红书后续只作为可选增强源。
- 使用 Spring AI Alibaba 承接 LLM 调用、智能体编排、工具调用等能力。
- 行程生成仍然采用长任务模式：先提交任务，再通过 WebSocket 推送进度，同时保留轮询兜底接口。

## 0.1 当前实现进度

截至当前版本，已经完成第一批 Java 骨架代码：

- 已新增 `modules/trip`，包含前端兼容 DTO、mock 行程生成、任务状态服务、WebSocket 事件模型。
- 已新增 `modules/ai`、`modules/map`、`modules/content` 占位模块。
- 已引入 Spring AI Alibaba `v2.0.0-M1.1` 相关 BOM 和 AI 模块依赖。
- 已新增 `/api/trip/plan`、`/api/trip/status/{taskId}`、`/api/trip/history`。
- 已新增 `/api/trip/ws/{taskId}` WebSocket 进度推送。
- 已新增 `/api/chat/ask` mock 伴游问答。
- 已新增 `/api/poi/photo` mock 图片接口。
- 已新增 `/api/settings` mock 运行时配置接口。
- 已新增 `/health` 健康检查接口。
- 已将 TripStar 兼容接口加入 Sa-Token 放行。
- 已把应用名和默认数据库名从脚手架残留的 `voice-cloning` 改为 `tripstar`。

当前阶段仍然是 mock 版：

- 还没有接入真实 Spring AI Alibaba 调用。
- 还没有接入真实高德/Google 地图数据源。
- 还没有接入真实图片源。
- 小红书仍然不作为核心链路依赖。

当前构建验证：

```powershell
$env:JAVA_HOME='D:\software\Java\IntelliJ IDEA 2025.1.1.1\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -DskipTests package
```

注意：系统默认 Maven 当前使用的是 Java 8，会报 `无效的目标发行版: 21`。需要切到 JDK/JBR 21 后再构建。

## 1. 脚手架适配判断

这个脚手架适合承接 TripStar Java 后端，因为模块边界比较清楚：

- `app`：只放启动类和 Controller。
- `modules/*`：放业务模块、领域对象、业务服务和基础设施实现。
- `common/*`：放跨业务通用能力。

建议新增业务模块：

- `modules/trip`：旅行规划领域模型、任务状态、编排服务、DTO/domain。
- `modules/ai`：Spring AI Alibaba 集成、Prompt 执行、JSON 修复、Agent/Tool 抽象。
- `modules/map`：高德/Google 地图集成、地理编码、天气、酒店/POI 搜索。
- `modules/content`：景点内容源和图片源聚合。先做地图/POI 数据源，后续再加可选的小红书公开数据源。

建议新增 Controller：

- `com.zkry.api.publicapi.TripController` 或 `com.zkry.api.user.TripController`
- `com.zkry.api.publicapi.TripWebSocketController` 或 WebSocket Handler 配置
- `com.zkry.api.publicapi.ChatController`
- `com.zkry.api.publicapi.PoiController`
- 如果仍需要运行时配置，则增加 `com.zkry.api.publicapi.SettingsController`

注意：脚手架里的 Sa-Token 默认保护 `/api/**`。学习阶段可以先把 TripStar 接口放到 `/api/public/**`，或者后续单独调整鉴权放行规则。

## 2. 需要保持的 API 契约

现有 Vue 前端主要依赖下面这些接口和数据结构。

### 提交旅行规划任务

`POST /api/trip/plan`

请求结构需要兼容 Python 版 `TripRequest`：

- `city`
- `cities: [{ city, days }]`
- `start_date`
- `end_date`
- `travel_days`
- `transportation`
- `accommodation`
- `preferences`
- `free_text_input`
- `language`

立即返回：

```json
{
  "task_id": "abc123",
  "plan_id": "abc123",
  "status": "processing",
  "ws_url": "/api/trip/ws/abc123",
  "message": "任务已提交，正在初始化流程..."
}
```

### WebSocket 进度推送

`WS /api/trip/ws/{taskId}`

事件结构：

```json
{
  "task_id": "abc123",
  "plan_id": "abc123",
  "status": "processing|completed|failed",
  "stage": "submitted|initializing|attraction_search|weather_search|hotel_search|planning|graph_building|completed|failed",
  "progress": 0,
  "message": "正在处理...",
  "error": "",
  "result": {}
}
```

### 轮询兜底

`GET /api/trip/status/{taskId}`

即使 WebSocket 可用，也建议保留这个接口。后续迁 uni-app、小程序或 App 时，WebSocket 受平台限制时可以退回轮询。

### AI 伴游问答

`POST /api/chat`

请求字段：

- `message`
- `trip_plan`
- `history`

响应：

```json
{
  "success": true,
  "reply": "..."
}
```

### 景点图片

`GET /api/poi/photo?name=xxx&city=xxx`

响应：

```json
{
  "success": true,
  "message": "获取图片成功",
  "data": {
    "name": "xxx",
    "photo_url": "..."
  }
}
```

## 3. 需要迁移的领域模型

把 Python 版 `backend/app/models/schemas.py` 中的 Pydantic 模型迁移成 Java DTO/record/class：

- `CityStay`
- `TripRequest`
- `Location`
- `Attraction`
- `Meal`
- `Hotel`
- `DayPlan`
- `WeatherInfo`
- `Budget`
- `TripPlan`
- `GraphNode`
- `GraphEdge`
- `GraphCategory`
- `KnowledgeGraphData`
- `TripPlanResponse`
- `TripTaskEvent`
- `ChatMessage`
- `TripChatRequest`
- `TripChatResponse`

为了减少前端改动，JSON 字段名继续使用 snake_case。如果 Java 字段使用 camelCase，需要用 Jackson `@JsonProperty` 显式指定字段名。

## 4. 实施阶段

### 阶段 1：基础骨架

目标：让 Java 后端先能编译，并暴露 TripStar 形状的占位接口。

任务：

- 把脚手架残留配置名从 `voice-cloning` 等旧名字改成 `tripstar`。
- 引入 Spring AI Alibaba 相关依赖管理和 DashScope/Agent 依赖。
- 新增 `modules/trip`、`modules/ai`、`modules/map`、`modules/content`。
- 新增与 Python API 契约一致的 DTO。
- 新增占位 Controller，先返回固定 mock 数据，确保前端能解析。
- 加入 WebSocket 基础设施，或者先实现等价的进度推送能力；尽量保持当前 WebSocket URL 契约。

验证：

- `mvn clean package -DskipTests`
- 现有 Vue 可以提交到 Java 后端，并拿到一份 mock 的 completed 行程。

### 阶段 2：任务运行时

目标：复刻 FastAPI 版异步任务行为。

任务：

- 实现 `TripTaskService`。
- 任务状态先用内存保存，行为稳定后再考虑 Redis。
- 实现进度阶段：
  - `submitted`
  - `initializing`
  - `attraction_search`
  - `weather_search`
  - `hotel_search`
  - `planning`
  - `graph_building`
  - `completed`
  - `failed`
- 按 `taskId` 管理 WebSocket 订阅者。
- 实现轮询查询接口。
- 如果需要历史记录，后续再把完成结果持久化到 Redis/数据库；Python 版是写磁盘，Java 版可以先不用完全照搬。

验证：

- 提交接口立即返回。
- WebSocket 能收到进度事件。
- 轮询接口能拿到当前任务状态。
- 失败任务能返回错误信息和必要的 `request_payload`。

### 阶段 3：AI 能力接入

目标：用 Spring AI Alibaba 替代 Python `hello_agents` 的 LLM 调用。

任务：

- 基于 Spring AI Alibaba `ChatClient` 或 DashScope model 实现 `AiChatService`。
- 实现 Prompt 模板：
  - 景点提取 Prompt
  - 天气/酒店查询 Prompt
  - 最终行程规划 Prompt
  - JSON 修复 Prompt
  - AI 伴游问答 Prompt
- 实现结构化 JSON 生成和解析流程。
- 迁移 Python 版 JSON 防御逻辑：
  - 从 markdown 中提取 JSON
  - 清理非法字符
  - 修复截断 JSON
  - 超时或失败后重试一次
  - 最后用 LLM 修复 JSON

验证：

- 给定固定的景点、天气、酒店上下文，Java 能生成合法 `TripPlan`。
- 模型输出 markdown 包裹或轻微格式错误时，解析流程仍能兜住。

### 阶段 4：地图和 POI 数据源

目标：不再把小红书 Cookie 当作核心依赖，同时保持产品可用。

任务：

- 定义 `AttractionSource` 接口。
- 实现 `AmapAttractionSource`，使用高德 Web 服务 API。
- 如果配置了 Google Key，再实现 `GooglePlacesAttractionSource`。
- 实现统一地理编码。
- 实现天气查询。
- 实现酒店/POI 搜索。
- 定义并实现 `ImageSource`：
  - 优先用高德/Google Place photo，如果可用。
  - 可接入官方图、公开图库或其他合法公开图片源。
  - 找不到图片时返回空字符串或默认图，不能让整个任务失败。
- 预留 `OptionalXhsSource`，但不要让它阻塞主流程。

验证：

- 只配置 LLM Key + 高德 Key 时，也能完成行程生成。
- 图片缺失不会导致任务失败。

### 阶段 5：旅行规划工作流

目标：复刻 Python 版 `MultiAgentTripPlanner.plan_trip` 的整体行为。

流程：

1. 标准化 `cities`。
2. 按城市收集信息：
   - 景点
   - 天气
   - 酒店
   - 推送进度
3. 拼接最终 planner prompt。
4. 调用 Spring AI Alibaba 模型或 Agent。
5. 解析响应为 `TripPlan`。
6. 补全模型可能遗漏的 `cities` 和每日 `city` 字段。
7. 构建知识图谱。
8. 返回 `TripPlanResponse`。

实现建议：

- 第一版先用普通 Java service 编排 + `ChatClient`，不要一开始就上复杂多 Agent 图。
- 行为跑通后，再考虑用 Spring AI Alibaba `ReactAgent` 和工具回调增强。
- 真正需要多智能体/Graph 时，再把天气、酒店、景点等能力包装成工具或子 Agent。

验证：

- 单城市行程可用。
- 多城市行程可用。
- 英文/日文等语言选项能影响 value 内容，但 JSON key 保持英文。
- 生成的知识图谱能被现有 Vue Result 页面渲染。

### 阶段 6：前端兼容

目标：尽量不改现有 Vue 前端。

任务：

- 把 `frontend` 的运行时 API Base URL 指向 Java 后端。
- TripStar 相关接口尽量返回 raw JSON，而不是脚手架统一的 `R<T>` 包装。
- 如果坚持使用 `R<T>`，则需要改 `frontend/src/services/api.ts` 适配 `data.data`。
- 为 Vue dev server 配置 CORS。

重要决策：

- 现有 Vue 期望的是原始 JSON，不是 `{ code, message, data }`。
- 为了少改前端，建议 `/api/trip/**`、`/api/chat/**`、`/api/poi/**` 先直接返回原始 JSON。

验证：

- 现有 Vue 的 `generateTripPlan` 可直接工作。
- Result 页面可以渲染 Java 返回的 `TripPlanResponse`。
- AIChat 可以调用 Java `/api/chat`。

### 阶段 7：可选的 uni-app 迁移

等 Java 后端稳定后再做。

任务：

- 迁移 `frontend/src/types/index.ts`。
- 用 `uni.request` 替代 Axios。
- 用 `uni.connectSocket` 替代浏览器 `WebSocket`。
- 保留 `/status/{taskId}` 轮询兜底。
- 地图组件按目标平台重做：
  - H5 可以较多复用 Web 地图方案。
  - 小程序/App 可能需要原生 map 组件或 WebView。

### 阶段 8：学习文档和代码导读

目标：Java 版实现完成后，必须补一份面向学习的代码文档，让后续开发者能看懂智能体旅行规划系统是怎么搭起来的，而不是只会运行项目。

建议文档路径：

- `backend_java/docs/TRIPSTAR_AGENT_LEARNING_GUIDE.md`

文档需要重点讲清楚：

- 整体调用链：
  - Vue 提交旅行请求。
  - Java 后端创建异步任务。
  - 任务服务推送 WebSocket 进度。
  - 旅行规划工作流收集景点、天气、酒店、地图信息。
  - Spring AI Alibaba 生成结构化行程。
  - Java 解析、修复、校验 LLM 输出。
  - 生成知识图谱并返回前端。
- 智能体协作设计：
  - 为什么不要一开始就做复杂多 Agent。
  - 第一版如何用普通 service 编排模拟多智能体协作。
  - 后续如何用 Spring AI Alibaba `ReactAgent`、Tool callback、子 Agent 工具化来升级。
  - 天气 Agent、酒店 Agent、景点 Agent、规划 Agent 各自负责什么。
- LLM 内容提炼：
  - 如果后续接入小红书或其他游记内容，如何把杂乱文本提炼成结构化景点候选。
  - Prompt 如何要求模型输出 JSON 数组。
  - 如何识别景点名、推荐理由、游玩时长、预约提醒、避坑提示、图片候选、坐标候选。
  - 为什么小红书不作为强依赖，只作为可选内容增强源。
- LLM JSON 解析和容错：
  - 为什么 LLM 输出不能直接信任。
  - 如何从 markdown 中提取 JSON。
  - 如何修复截断 JSON。
  - 如何处理未转义引号、额外说明文字、字段缺失。
  - 什么时候用 LLM 再修复一次 JSON。
- 路线规划逻辑：
  - 如何把景点、酒店、天气、用户偏好合并成 planner prompt。
  - 如何让模型考虑地理位置、交通方式、开闭馆、预约、预算和每日节奏。
  - 多城市场景如何处理城市停留天数和城际移动日。
  - 哪些逻辑交给地图/规则，哪些逻辑交给 LLM。
- 用户端旅游规划产品演进：
  - 后续面向真实用户端时，如何加入用户画像、收藏偏好、预算等级、亲子/情侣/老人等场景。
  - 如何把一次性行程生成升级为“可编辑、可追问、可重排”的交互式规划。
  - 如何设计历史行程、收藏景点、用户反馈和推荐优化。
  - 如何处理隐私、Key 管理、限流、缓存和内容合规。

文档风格要求：

- 用中文写。
- 面向学习者，不只列代码位置，还要解释“为什么这么设计”。
- 每个重点模块都要附关键类、关键方法、调用链和小型流程图。
- 对智能体部分要给出从简单 service 编排到 Spring AI Alibaba 多 Agent 的升级路径。
- 对 Prompt 要给出示例，但不要泄露真实 API Key、Cookie 或用户隐私。

## 5. Spring AI Alibaba 版本说明

官方文档和 release 当前能看到这些能力：

- `spring-ai-alibaba-starter-dashscope`
- `spring-ai-alibaba-agent-framework`
- `ReactAgent`
- Tool callback
- 将子 Agent 作为工具暴露给主 Agent

版本选择：

- 当前脚手架使用 Spring Boot `4.0.7`。
- Spring AI Alibaba `v2.0.0-M1.1` 是预发布里程碑版本，明确升级到 Spring AI `2.0.0-M1` 和 Spring Boot `4.0.0`。
- Spring AI Alibaba `v1.1.2.2` 是较稳定版本线，但更偏 Spring Boot `3.5.x`。
- 对这个脚手架，优先尝试 Spring AI Alibaba `v2.0.0-M1.1`，保持 Spring Boot 4。
- 如果 milestone 版本依赖冲突或运行不稳定，再考虑：
  - 把这个学习后端降到 Spring Boot `3.5.x`，使用 Spring AI Alibaba `v1.1.2.2`。
  - 或者继续保持 Spring Boot 4，先直接调用 OpenAI-compatible/DashScope HTTP API，等 v2 版本稳定后再切回 Spring AI Alibaba。

## 6. 关键风险

- Spring Boot 4 + Spring AI Alibaba v2 milestone 可能存在 API 变化或依赖兼容问题。
- 现有 Vue 期望 raw JSON，而脚手架默认倾向 `R<T>` 统一返回。
- WebSocket 实现方式和 FastAPI 不同，需要处理订阅者清理、断线、任务完成关闭等细节。
- LLM 输出 JSON 不稳定，必须移植解析和修复逻辑。
- 不带 Cookie 的小红书抓取不适合作为核心稳定数据源。
- 地图图片 API 不一定能替代小红书真实用户图片效果。
- 长时间 LLM 任务需要超时、取消、重试和进度更新机制。

## 7. 建议的第一个开发 Sprint

按这个顺序做：

1. 确认 Spring Boot 4 与 Spring AI Alibaba `v2.0.0-M1.1` 的依赖能否编译。
2. 只新增 `modules/trip` 和 DTO。
3. 新增返回 raw JSON 的 `TripController` mock 接口。
4. 新增任务状态服务和 WebSocket 进度推送。
5. 验证现有 Vue 能消费 Java mock 任务。
6. 新增 `modules/ai`，先跑通一次简单 chat 调用。
7. 加入 planner prompt 和 JSON 解析。
8. 加入地图/POI 数据源。
9. 把 mock 结果替换成真实生成结果。
10. 在实现稳定后补齐 `docs/TRIPSTAR_AGENT_LEARNING_GUIDE.md`，把智能体协作、LLM 提炼、JSON 解析、路线规划和用户端产品演进写清楚。

每一步都单独构建和验证。不要把 AI、地图、任务运行时、前端兼容一次性混在一个大改动里。

## 8. 完成标准

Java 后端达到下面状态，就可以认为第一版迁移基本完成：

- 现有 Vue 只改 API Base URL，或完全不改，就能提交旅行规划。
- Java 后端能通过 WebSocket 推送任务进度。
- Java 后端能返回带 `data` 和 `graph_data` 的 `TripPlanResponse`。
- Result 页面能渲染概览、每日行程、预算、地图和知识图谱。
- AIChat 能基于生成的行程上下文回答问题。
- 主流程不需要小红书 Cookie。
- `mvn clean package` 可以通过。
- 已提供中文学习文档，能让你复盘 Java 代码结构，重点理解智能体协作、LLM 内容提炼、JSON 解析、路线规划和未来用户端旅游规划产品的演进方向。
