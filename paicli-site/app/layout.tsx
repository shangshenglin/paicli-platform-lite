import type { Metadata } from "next";
import { headers } from "next/headers";
import "./globals.css";

const title = "PaiCLI Platform Lite｜可恢复、可审批的 Agent Runtime";
const description =
  "面向个人开发者与私有环境的受管 Agent Runtime。持久化工具调用、安全审批、分层 Memory、知识检索与 Docker Sandbox。";

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const host = requestHeaders.get("x-forwarded-host") ?? requestHeaders.get("host") ?? "localhost:3000";
  const protocol = requestHeaders.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  const image = `${protocol}://${host}/og.png`;

  return {
    title,
    description,
    openGraph: {
      title,
      description,
      type: "website",
      locale: "zh_CN",
      images: [{ url: image, width: 1200, height: 630, alt: "PaiCLI Platform Lite 持久化运行流程" }],
    },
    twitter: {
      card: "summary_large_image",
      title,
      description,
      images: [image],
    },
  };
}

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
