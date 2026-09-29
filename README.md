# WorkPath 外勤人员工作轨迹智能分析与管理系统

课程《专项设计及综合拓展》作品。系统由三个客户端组成，各自在本仓库的不同分支维护。

## 分支结构

| 分支 | 内容 | 技术栈 |
| --- | --- | --- |
| [`backend-admin`](https://gitee.com/z-jjjjjy/workpath/tree/backend-admin) | 后端服务 + Web 管理端 | Spring Boot 3.5.4 + MyBatis-Plus + MySQL / Vue 3 + Element Plus |
| [`mobile`](https://gitee.com/z-jjjjjy/workpath/tree/mobile) | 移动端（外勤人员 App） | Flutter + 高德地图 SDK |

## 目录说明

```
backend-admin 分支：
  backend/                Spring Boot 服务
    src/main/resources/db/  建表脚本（init.sql + init_ai.sql，幂等）
    src/test/java/           单元测试（66 个，mvn -o test 全绿）
  admin/                 Vue3 管理端
    src/views/policy/     制度知识库（M6）
    src/views/modelProvider/  模型接入配置

mobile 分支：
  lib/                    Flutter 业务代码
    config/api_config.dart   baseUrl 与 key 配置（构建期注入）
    services/               网络与本地存储
  third_party/            高德 Flutter 插件本地副本
```

## 本地启动

### 后端

```bash
cd backend
export JAVA_HOME="/c/Program Files/Zulu/zulu-17"   # 需 JDK 17，不能是 JDK 25
export PATH="$JAVA_HOME/bin:$PATH"
export DB_USERNAME=root DB_PASSWORD=<你的本地密码>
export JWT_SECRET='<任意足够长的随机串>'
mvn spring-boot:run
```

先执行 `backend/src/main/resources/db/init.sql` 与 `init_ai.sql` 建表。端口 8080。

### 管理端

```bash
cd admin
npm install
cp .env.local.example .env.local   # 填入高德前端 Key
npm run dev                        # 端口 5173
```

### 移动端

```bash
flutter pub get
flutter run --dart-define=AMAP_KEY=<你的高德Key>
```

## 关于密钥

所有第三方凭据（数据库密码、JWT 签名、AI 模型 API Key、高德 Key）**均不在仓库中**，通过环境变量或 `--dart-define` 在构建期注入。仓库内 `.gitignore` 已排除 `scripts/`、`admin/.env.local`、`android/local.properties` 等本地配置。

若发现历史提交中残留过密钥，请**前往对应平台重置该密钥**——改写本地文件无法从 git 历史中移除已泄露的值。

## AI 模型接入

后端内置三类模型的可视化配置（管理端「模型接入」页，仅超级管理员可见）：

- CHAT：语义标注与核查说明（DeepSeek）
- EMBEDDING：制度条款向量化（硅基流动 BAAI/bge-m3，实测 1024 维）
- GEO：逆地理编码（高德）

配置后系统才能使用制度知识库检索与轨迹智能分析能力。
