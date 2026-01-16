# Halo 抽奖插件

给 Halo 站点加个抽奖功能，搞搞活动用。

## 功能

- **大转盘** - 转一转，立即出结果
- **抽签** - 点一下就知道有没有中
- **定时开奖** - 先报名，到点统一开奖
- 支持匿名参与（填邮箱）、登录参与、评论后参与
- 邮箱验证码防刷
- 中奖邮件通知

## 使用

在编辑器里输入 `/抽奖` 或 `/lottery`，选择活动插入到文章里就行。

## 开发

```bash
./gradlew haloServer
```

前端开发：

```bash
cd ui && pnpm install && pnpm dev
```

## 构建

```bash
./gradlew build
```

产物在 `build/libs` 目录。

## 许可证

[GPL-3.0](./LICENSE)