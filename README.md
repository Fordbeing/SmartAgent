SmartAgent 是一个企业级的 AI 知识库管理系统，采用检索增强生成（RAG）技术，提供智能文档处理和检索能力。

核心技术栈包括 ElasticSearch、Kafka、WebSocket、Spring Security、Docker、MySQL 和 Redis。

它的目标是帮助企业和个人更高效地管理和利用知识库中的信息，支持多租户架构，允许用户通过自然语言查询知识库，并获得基于自身文档的 AI 生成响应。

![派聪明多模块架构](https://cdn.tobebetterjavaer.com/stutymore/README-20250730102133.png)

系统允许用户：

- 上传和管理各种类型的文档
- 自动处理和索引文档内容
- 使用自然语言查询知识库
- 接收基于自身文档的 AI 生成响应

用到的技术栈包括，先说后端的：

+ 框架 : Spring Boot 3.4.2 (Java 17)
+ 数据库 : MySQL 8.0
+ ORM : Spring Data JPA
+ 缓存 : Redis
+ 搜索引擎 : Elasticsearch 8.10.0
+ 消息队列 : Apache Kafka
+ 文件存储 : MinIO
+ 文档解析 : Apache Tika
+ 安全认证 : Spring Security + JWT
+ AI集成 : DeepSeek API/本地 Ollama+豆包 Embedding
+ 实时通信 : WebSocket
+ 依赖管理 : Maven
+ 响应式编程 : WebFlux

后端的整体项目结构：

```bash
src/main/java/com/yizhaoqi/smartpai/
├── SmartPaiApplication.java      # 主应用程序入口
├── client/                       # 外部API客户端
├── config/                       # 配置类
├── consumer/                     # Kafka消费者
├── controller/                   # REST API端点
├── entity/                       # 数据实体
├── exception/                    # 自定义异常
├── handler/                      # WebSocket处理器
├── model/                        # 领域模型
├── repository/                   # 数据访问层
├── service/                      # 业务逻辑
└── utils/                        # 工具类
```

再说前端的，包括：

+ 框架 : Vue 3 + TypeScript
+ 构建工具 : Vite
+ UI组件 : Naive UI
+ 状态管理 : Pinia
+ 路由 : Vue Router
+ 样式 : UnoCSS + SCSS
+ 图标 : Iconify
+ 包管理 : pnpm

前端的整体项目结构：

```bash
frontend/
├── packages/           # 可重用模块
├── public/             # 静态资源
├── src/                # 主应用程序代码
│   ├── assets/         # SVG图标，图片
│   ├── components/     # Vue组件
│   ├── layouts/        # 页面布局
│   ├── router/         # 路由配置
│   ├── service/        # API集成
│   ├── store/          # 状态管理
│   ├── views/          # 页面组件
│   └── ...            # 其他工具和配置
└── ...               # 构建配置文件
```
