# 抽奖插件开发进度

## 已完成功能

### 后端

#### 实体设计
- `LotteryActivity` - 抽奖活动实体
  - 内部类使用唯一名称避免 OpenAPI 冲突：`LotteryActivitySpec`、`LotteryActivityStatus`
  - 状态：PENDING（待开始）、RUNNING（进行中）、ENDED（已结束）、DRAWN（已开奖）
  - 抽奖类型：SCHEDULED（定时开奖）、WHEEL（大转盘）、SCRATCH（刮刮乐）
  - 参与类型：NONE（匿名+邮箱）、LOGIN（需登录）、COMMENT（需评论）、LOGIN_AND_COMMENT
  - 奖品列表内嵌在 spec 中，包含 `probability`（概率）和 `remaining`（剩余数量）字段

- `LotteryParticipant` - 参与者实体
  - Token 机制：`hash(activityName + email + salt)` 用于匿名用户识别

#### 索引注册（LotteryPlugin.java）
- LotteryActivity: `status.state`、`spec.participationType`、`spec.title`
- LotteryParticipant: `spec.token`、`spec.activityName`

#### Service 层
- `getActivity()` - 获取活动详情，自动检查状态和开奖
- `listActivities()` - 分页查询，自动更新状态
- `draw()` - 手动开奖（仅 SCHEDULED 类型）
- `participateAnonymous()` - 匿名参与（需邮箱）
  - SCHEDULED：仅记录参与，等待统一开奖
  - WHEEL/SCRATCH：立即执行概率抽奖，返回结果
- `findByToken()` / `findByActivityAndEmail()` - 查询参与记录
- `getWinnerByToken()` - 查询中奖结果
- `drawByProbability()` - 统一概率抽奖算法（内部方法）

#### 状态自动更新逻辑
- 访问活动时根据时间自动更新状态（不用 Reconciler 轮询）
- `startTime` ~ `endTime` = 参与时间窗口
- `drawTime` = 开奖时间（可选，为空则使用 endTime）

#### Endpoint
- Console API (`console.api.lottery.xhhao.com/v1alpha1`)
  - `GET /lotteries` - 列表查询（支持 keyword、state、participationType 筛选）
  - `POST /draw` - 手动开奖

- Public API (`api.lottery.xhhao.com/v1alpha1`)
  - 待完善

#### 权限配置
- `role-template-anonymous.yaml` - 匿名访问权限
- `roleTemplate.yaml` - 管理权限

### 前端

#### 管理界面
- `LotteryList.vue` - 列表页（筛选、分页、批量删除）
- `LotteryListItem.vue` - 列表项（状态点、下拉操作）
- `LotteryEditingModal.vue` - 新建/编辑弹窗（FormKit 表单）

#### 组件使用
- 使用 Halo 2.22 组件：VModal、VEntity、VEntityField、VAlert、FormKit
- 奖品图片使用 `attachment` 组件（支持上传和附件库选择）
- 时间说明使用 VAlert 提示

---

## 待实现功能

### 后端

1. **登录用户参与**
   - `participateWithLogin()` - 登录用户参与
   - 需要获取当前用户信息

2. **评论参与**
   - `participateWithComment()` - 评论后参与
   - 需要关联文章和评论

3. **Public API 完善**
   - 前台获取活动详情
   - 前台参与接口
   - 前台查询中奖结果

4. **定时任务（可选）**
   - 如果需要精确到秒的自动开奖，可能需要定时任务
   - 目前是访问时触发检查

### 前端

1. **前台抽奖页面**
   - 活动展示
   - 参与表单（邮箱输入）
   - 中奖结果展示
   - 抽奖动画效果

2. **管理界面增强**
   - 参与者列表查看
   - 中奖者管理
   - 活动数据统计

3. **编辑器扩展（可选）**
   - 抽奖组件嵌入文章

---

## 设计决策记录

### 抽奖类型

| 类型 | 说明 | 开奖时机 |
|------|------|----------|
| SCHEDULED（定时开奖） | 传统抽奖，到时间统一开奖 | drawTime 或 endTime |
| WHEEL（大转盘） | 即时开奖，转盘动画 | 参与时立即抽奖 |
| SCRATCH（刮刮乐） | 即时开奖，刮卡动画 | 参与时立即抽奖 |

### 概率抽奖逻辑

三种抽奖类型使用统一的概率算法 `drawByProbability()`：

**奖品配置示例**：
| 奖品 | 概率 | 数量 |
|------|------|------|
| 一等奖 | 5% | 1 |
| 二等奖 | 15% | 3 |
| 三等奖 | 30% | 10 |

**计算规则**：
- 总中奖率 = 所有奖品概率之和 = 5% + 15% + 30% = **50%**
- 谢谢参与 = 100% - 总中奖率 = **50%**

**抽奖过程**：
1. 生成 0-99 随机数
2. 按概率区间匹配：
   - 0-4 (5%) → 一等奖
   - 5-19 (15%) → 二等奖
   - 20-49 (30%) → 三等奖
   - 50-99 (50%) → 谢谢参与

**库存限制**：
- 奖品数量为 0 时自动跳过该奖品
- 即时开奖：中奖后立即扣减 `remaining` 字段
- 定时开奖：使用内存 Map 跟踪剩余数量

**定时开奖流程**：
1. 随机打乱所有参与者顺序（`Collections.shuffle`）
2. 依次为每位参与者执行概率抽奖
3. 中奖者记录到 `status.winners` 列表

**即时开奖流程**：
1. 用户参与时立即执行概率抽奖
2. 中奖结果存入 `LotteryParticipant.spec`：
   - `isWinner`: 是否中奖
   - `prizeName`: 奖品名称
   - `winTime`: 中奖时间

### 时间逻辑
- `startTime` ~ `endTime`：参与时间窗口
- `drawTime`：开奖时间，为空则默认等于 endTime
- 支持"结束后延迟开奖"场景

### 状态更新策略
- 不使用 Reconciler 后台轮询
- 访问时检查并更新状态（懒更新）
- 减少资源消耗

### Token 机制
- 匿名用户通过 `hash(activityName + email + salt)` 生成唯一 token
- 用于防止重复参与和查询中奖结果

### API 设计
- 使用 `Queries` 而非已废弃的 `QueryFactory`
- Query 类继承 `SortableRequest`
- 使用 `fieldQuery()` 构建查询条件

---

## 技术栈

- 后端：Halo 2.22 Plugin API
- 前端：Vue 3 + @halo-dev/components + FormKit + TanStack Query
- 构建：Gradle + Rsbuild

---

## 下一步计划

1. 完善 Public API
2. 实现前台抽奖页面
3. 添加登录用户参与功能
4. 测试完整流程
校验逻辑
NONE:
  → 前端传邮箱 + token → 后端验证

LOGIN:
  → 后端取当前登录用户 → 检查是否已参与

COMMENT（匿名）:
  → 前端传邮箱 → 后端查该邮箱在目标文章下的评论

COMMENT（登录）/ LOGIN_AND_COMMENT:
  → 后端取当前登录用户 → 查该用户在目标文章下的评论
只有 NONE 和 匿名评论 需要前端缓存邮箱，其他都走后端登录态。

参与抽奖：
1. 获取邮箱（登录用户取账号邮箱，匿名取输入/评论邮箱）
2. 后端校验条件 → 生成 token = hash(email + activityName + salt)
3. 返回 token → 前端存 IndexedDB { activityName, email, token }

查询状态：
1. 前端从 IndexedDB 取 { email, token }
2. 后端验证 token 是否匹配 → 返回状态
3. IndexedDB 没有 → 用户输入邮箱 → 后端重新生成 token 返回

统一方案
场景	邮箱来源	存储
NONE（无条件）	用户输入	前端 IndexedDB
LOGIN	从登录用户信息获取邮箱	前端 IndexedDB
COMMENT（登录评论）	从用户信息获取邮箱	前端 IndexedDB
COMMENT（匿名评论）	评论时填的邮箱	前端 IndexedDB