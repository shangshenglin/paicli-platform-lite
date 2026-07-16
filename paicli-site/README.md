# PaiCLI Platform Lite 产品站点

这是 PaiCLI Platform Lite 的独立中文产品站点，集中介绍可恢复 Agent Runtime、持久化安全审批、自动 Memory、知识检索和 Docker Sandbox 等能力。

## 环境要求

- Node.js `>= 22.13.0`
- npm

## 本地运行

```bash
npm install
npm run dev
```

默认访问地址为 `http://localhost:3000`。

## 验证

```bash
npm run lint
npm run build
node --test tests/rendered-html.test.mjs
```

## 目录说明

- `app/page.tsx`：产品站点首页内容与结构
- `app/globals.css`：站点视觉系统和响应式样式
- `app/layout.tsx`：中文页面元数据与社交分享信息
- `public/og.png`：站点专属社交分享封面
- `tests/`：生产构建的渲染测试
- `.openai/hosting.json`：Sites 项目标识和可选运行时绑定

## 关联项目

主项目源码与完整部署文档位于 [shangshenglin/paicli-platform-lite](https://github.com/shangshenglin/paicli-platform-lite)。

站点源码不保存模型密钥、业务数据或本地运行产生的构建目录。
