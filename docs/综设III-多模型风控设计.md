# 综设 III · 多模型风控设计（岳炜杰主责）

**状态：** 组内已定稿。实现仍在 `backend` 的 `modules/risk/`；**独立 `risk-service`（8081）后拆，不要第 2 周为拆而拆。**  
**按周任务：** 见 `docs/综设III-12周开发计划.md` 第 4 节（岳 / 钟妮）。  
**进程：** 信贷与平台是**同一个** Spring Boot（主业务 8080）；风控是另一个业务服务；网关 8088 只做入口。  
**契约：** 第 1 周已冻结的 `POST /api/risk/assess` → `RiskReportDTO` 继续当跨服务合同。

对外一句话：

> 四个模型：申请卡（WOE）、硬规则、外部公示、行为分；后台可自选启用哪几套授信标准；启用两套及以上时用 AHP 融合，判断矩阵由真实公示数据（LPR、失信名单统计）算出，不是手写 50/30/20。

全程 **Java**。WOE 分箱仍是专家表；AHP **权重必须现场算**（特征值/几何平均 + CR），矩阵元素来自已爬到的公开数据。不上 Python / 随机森林 / Drools。不做 A/B/C/F 四卡。

---

## 1. 四个模型（难易搭配）

| 编号 | 名称 | 难度 | II 现状 | III 要做成（须自己写的） |
|------|------|------|---------|--------------------------|
| M1 | 申请卡（WOE） | 中 | 分档加减分 | 新写 `WoeScoringCardEngine`：分箱表 + `分数 = 基准分 + Σ(系数 × WOE)`，`cardVersion` 如 `woe-card-v1` |
| M2 | 硬规则链 | 易 | `BasicRiskRuleEvaluator` | 沿用 Java `@Order`；补失信命中硬拒，原因码 `DISHONEST_PUBLIC`。不上 Drools |
| M3 | 外部公示 | 难 | Mock / 演示 HTML | 真爬失信名单 + LPR，**单独计分**，不要塞进评分卡 |
| M4 | 贷后行为分 | 中 | 无 | 新写 `BehaviorScoreEngine`；新用户中性分；可关。`reevaluate` 走它，不改借据 |

授信标准可自选启用（硬规则准入**不能关**，失信仍硬拒）：

```
risk.scorecard.enabled=true
risk.external.enabled=true
risk.behavior.enabled=true
```

运行时以管理端策略为准（见第 3.1 节），不要只改 properties 却无法在 Web 勾选。  
启用 **≥2** 套授信模型 → AHP 加权；只启用 **1** 套 → 该模型分直接当授信结论（等价 SINGLE）；**0** 套 → 只出准入结论，授信标 `skipped`。  
关掉某一模型后，对剩余维**重新用当前公示数据算 AHP**，禁止把缺项当分 0 去乘旧的三元权重。

---

## 2. 两阶段决策

```
阶段一 准入（只跑 M2）
  ├─ 硬拒（失信 / 额度违法等）→ REJECTED，报告仍可附上后阶段试算分供讲解
  ├─ 灰区 → MANUAL_REVIEW
  └─ 通过 → 进入阶段二

阶段二 授信（M1 + M3 + 可选 M4）
  ├─ 各模型先归一到 0～100
  ├─ AHP 得到权重（卡 / 外部 / 行为）
  ├─ totalScore = 加权和
  └─ 给出 decision + suggestLimit（建议额度，不改信贷账）

落库：RiskReport + FeatureSnapshot
      （WOE 输入、启用集合、失信命中、LPR、名单统计、AHP 矩阵与权重、CR、阶段结论）
```

`RiskReportDTO.breakdown` 必须能拆开：

```json
{
  "stage": "CREDIT",
  "admission": "PASS",
  "scorecard": 68,
  "scorecardVersion": "woe-card-v1",
  "rules": "PASS",
  "external": 72,
  "behavior": 50,
  "enabledModels": ["WOE", "EXTERNAL", "BEHAVIOR"],
  "behaviorEnabled": true,
  "ahpWeights": { "scorecard": 0.54, "external": 0.30, "behavior": 0.16 },
  "ahpConsistencyRatio": 0.04,
  "ahpSource": { "lpr1y": 3.10, "dishonestListSize": 128, "stale": false },
  "suggestLimit": 8000
}
```

Web 展示：每个模型的分、是否启用、AHP 权重、处于准入还是授信。  
回放 `POST /api/risk/replay/{reportId}`：**只读快照重算**，不访问外网、不读最新还款。

---

## 3. AHP 融合（必须用公开数据实算）

不要把 `0.54 / 0.30 / 0.16` 写死当正式权重。分两步，都在 Java 里完成：

**① `AhpMatrixBuilder`（输入来自真爬，不是拍脑袋）**

用当次快照里的公开数据填 Saaty 1～9 判断矩阵（映射表写进配置，可答辩展示）：

| 公开数据 | 怎么进矩阵 |
|----------|------------|
| 央行 LPR 1Y / 5Y（真爬） | 相对课设基线（如 3.45%）偏高 → 「外部/宏观」相对申请卡更重要 |
| 失信名单条数或是否命中 | 名单规模大或本申请人命中 → 「外部」相对另外两维提高 |
| 是否有还款史 | 无还款史则行为维与另外两维的比较降为接近 1（弱区分）；有历史则按逾期率映射 |

映射必须是**单调、可复述的公式**（例如 `|LPR-基线|` 落在区间 → 标度 1/2/3/5），禁止神经网络。公示失败且 `stale=true` 时：用上次成功爬到的数算，并在报告标明 stale；完全没有历史则回退均等权重并打日志。

**② `AhpWeightCalculator`（算法要真算）**

- 几何平均或主特征值 → 权重向量（和为 1）
- 一致性比率 CR；`CR > 0.1` 时打日志并回退均等或 50/30/20
- 只对**当前启用**的维建 n×n 矩阵，不要拿三元权重去乘关掉的模型

快照必须留下：原始 LPR、名单统计、构造出的矩阵、权重、CR、`stale`。回放只读这些，**禁止**用「现在网上的新 LPR」重算历史单。

答辩准备一句：没有违约标注样本，所以不用训练；AHP 的相对重要性来自**当场爬到的公开宏观/公示数据**，权重是矩阵算出来的。

---

## 3.1 自选启用（薛 / 熊 / 钟妮 可分工，岳只消费）

| 谁 | 做什么 | 不做什么 |
|----|--------|----------|
| **薛雨宸** | `modules/ops/` 提供策略读写：`GET/PUT /api/ops/risk-policy`（`enabledModels`、策略版本号）；Redis 缓存当前策略；网关放行该路径；管理员 JWT | 不写 WOE/AHP 公式 |
| **熊梓伊** | 申贷调 `assess` 时带上当前 `enabledModels` + `policyVersion`；申请表记下这次用了哪套策略，便于对照 | 不在信贷里重算分、不改勾选存储结构（存 ops 即可） |
| **钟妮** | 管理端「授信标准」勾选：WOE / 外部 / 行为；硬规则展示为必开；报告页展示本次启用集合、AHP 矩阵来源（LPR 等） | 不写 Java 引擎 |
| **岳炜杰** | 编排按请求里的启用集合跑模型；≥2 套走 AHP；快照写入启用集合 | 不改 ops 策略表、不改 Web |

硬规则不出现在勾选里（或勾了也关不掉）。现场演示：同一申请人先三套全开，再关掉行为分重评，权重和总分都变，准入（失信）不变。

---

## 4. 对外接口（进程迁出后路径不变）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/risk/assess` | 主业务申贷后调用；走两阶段 |
| POST | `/api/risk/replay/{reportId}` | 只用快照回放 |
| POST | `/api/risk/reevaluate` | 贷中重评（偏 M4）；可出 `suggestLimit`，不改借据 |
| GET | `/api/risk/features/{userId}` | 当前特征（含 stale） |
| POST | `/api/admin/crawler/run` | 管理员手动爬一次公示页 |
| GET/PUT | `/api/ops/risk-policy` | 当前启用哪些授信模型（薛，ops） |

网关：`/api/risk/**` 等拆出后转到 **8081**。过渡期 `RISK_SERVICE_URI` 仍指向 8080。  
主业务（信贷包）只收 `decision` / `reasonSummary` / `cardVersion` / `suggestLimit`，不在信贷里重算分。额度落地走熊的 `PATCH /api/loans/{id}/limit`。

---

## 5. 建议新写的类（岳）

| 类 | 作用 |
|----|------|
| `WoeScoringCardEngine` | WOE 分箱与计分 |
| `ExternalPublicModel` | 失信 + LPR 单独模型分 |
| `BehaviorScoreEngine` | 贷后行为分 |
| `AhpMatrixBuilder` | 用 LPR / 失信统计填判断矩阵 |
| `AhpWeightCalculator` | 特征值（或几何平均）+ CR |
| `TwoStageRiskOrchestrator` | 准入 → 按启用集合授信 |
| 快照读写 | 评估写入、回放只读 |

硬规则继续用现有 `RiskRuleEvaluator`，加失信一条即可。

---

## 6. 按周（风控）

| 周 | 岳做什么 |
|----|----------|
| 1 | 契约已交，本方案按本文实现 |
| 3 | 契约补 `enabledModels`（不改第 1 周已冻字段名） |
| 4 | WOE 卡跑通 + 两阶段骨架 + 原因码/去重；消费 `enabledModels` |
| 5 | M3 真爬；`AhpMatrixBuilder`+实算 AHP；快照回放 |
| 7 | M4 行为分；按策略开关少算维并重算 AHP |
| 8～10 | `reevaluate` + `suggestLimit` |
| 9 | 多模型能演示后再迁 8081 |

---

## 7. 拆成 8081 的时机

1. 四模型 + AHP + 两阶段 + 回放能在现仓库演示。  
2. 再迁 `modules/risk`、`modules/crawler`。  
3. 库切 `risk_db`；网关改 `RISK_SERVICE_URI`。  
4. 主业务改为 HTTP 调 assess。

---

## 8. 谁改什么

- **岳炜杰：** WOE / 外部模型 / 行为分 / 矩阵构造 / AHP 求解 / 编排；不准改申请状态机、合同、放款、还款。  
- **钟妮：** 报告/回放（第 2 周骨架已完成）+ **第 3 周起授信标准勾选页**；展示 AHP 来源（LPR、CR）；**不写**引擎类。  
- **熊梓伊：** HTTP 调 assess 时传入策略；回写结论；申请上留下 `policyVersion`；接 `suggestLimit`。不要单独起 `loan-service`。  
- **薛雨宸：** 网关、`risk-policy` 与缓存、压测脚本；**不写** WOE / AHP 公式。延迟催收不依赖单独「催收卡」。
