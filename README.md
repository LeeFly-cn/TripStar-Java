# Spring Boot 多模块脚手架

这是一个偏单体应用的 Spring Boot 多模块脚手架。项目先用单体部署降低复杂度，但模块边界按业务能力拆开，后续业务体量上来时，可以比较自然地迁移到微服务。

## 核心约定

- `app` 是启动模块和接口层入口，只放启动类、Controller、接口 DTO 适配。
- `modules/*` 是业务层，放业务服务、领域对象、仓储接口和基础设施实现。
- `common/*` 是通用基础能力，不能写具体业务逻辑。
- 用户端和管理端通过 `app/src/main/java/com/zkry/api/user`、`app/src/main/java/com/zkry/api/admin` 区分。
- 同一张表、同一个领域概念只在所属业务模块维护一份 domain，不要因为管理端和用户端重复建实体。

## 技术栈

- Java 21
- Spring Boot 4
- Maven 多模块
- MyBatis-Plus
- Sa-Token
- Spring Data Redis
- Logback

## 当前结构

```text
backend
├── pom.xml
├── app
│   ├── pom.xml
│   └── src/main/java/com/zkry
│       ├── BackendApplication.java
│       └── api
│           ├── user
│           └── admin
├── common
│   ├── core
│   ├── json
│   ├── web
│   ├── mybatis
│   ├── satoken
│   └── redis
└── modules
    ├── demo
    └── identity
```

## 分层说明

`app` 只做接口入口，例如：

```text
app/src/main/java/com/zkry/api/user/DemoController.java
```

`modules/demo` 放业务逻辑，例如：

```text
modules/demo/src/main/java/com/zkry/demo
├── domain
│   └── Demo.java
├── mapper
│   └── DemoMapper.java
└── service
    ├── DemoService.java
    └── impl
        └── DemoServiceImpl.java
```

推荐业务模块逐步扩展为：

```text
modules/xxx/src/main/java/com/zkry/xxx
├── domain
│   └── Xxx.java
├── mapper
│   └── XxxMapper.java
└── service
    ├── XxxService.java
    └── impl
        └── XxxServiceImpl.java
```

如果业务复杂，可以在模块内部继续增加 `application`、`repository`、`infrastructure` 等目录；如果是代码生成器生成的 CRUD 模块，优先保持 MyBatis-Plus 常见结构，便于维护和迁移。

## 最小示例

示例接口在 `app`：

```text
GET /api/public/demo
```

接口调用链：

```text
DemoController -> DemoService -> DemoServiceImpl -> Demo
```

这表示 Controller 只负责 HTTP 入口，真正业务能力由 `modules/demo` 提供。

## 认证说明

`app` 默认引入 `common/satoken`，因此 Sa-Token 会保护 `/api/**` 下的大部分接口。默认放行路径包括：

```text
/api/public/**
/api/auth/**
/api/user/auth/**
/api/admin/auth/**
```

新增业务接口时，如果路径放在 `/api/**` 下，默认需要登录；登录、注册、验证码、公开测试接口等无需登录的接口，应放到上面的放行路径中。具体登录流程由业务模块自行实现，脚手架只提供 Sa-Token 基础接入和统一异常返回。

## 快速启动

确保本地使用 JDK 21。

```bash
cd backend
./mvnw clean package -DskipTests
java -jar app/target/app-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

Windows PowerShell 使用：

```powershell
.\mvnw.cmd clean package -DskipTests
java -jar app/target/app-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

开发阶段也可以直接在 IDE 中运行：

```text
com.zkry.BackendApplication
```

启动后访问：

```text
http://localhost:8080/api/public/demo
```

## 给 AI 代码助手的约束

- 不要把 Controller 放到 `modules/*`。
- 不要把业务逻辑写在 `app` 的 Controller 里。
- 新增业务能力时，优先在 `modules/<业务名>` 中建 `domain`、`mapper`、`service`、`service.impl`。
- 代码生成器生成的简单业务模块，推荐使用 `domain`、`mapper`、`service`、`service.impl`。
- `app` 可以依赖业务模块，业务模块不要反向依赖 `app`。
- `common` 只放跨业务复用能力，例如统一返回、异常处理、JSON、MyBatis、Sa-Token、Redis。
- 管理端接口放 `com.zkry.api.admin`，用户端接口放 `com.zkry.api.user`。
