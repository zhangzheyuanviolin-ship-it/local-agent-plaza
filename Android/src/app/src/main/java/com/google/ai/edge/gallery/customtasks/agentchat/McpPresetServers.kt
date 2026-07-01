/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.agentchat

data class McpPresetServer(
  val name: String,
  val url: String,
  val description: String,
  val authHint: String = "无需登录或令牌即可尝试",
)

internal val DEFAULT_MCP_PRESET_SERVERS =
  listOf(
    McpPresetServer(
      name = "DeepWiki",
      url = "https://mcp.deepwiki.com/mcp",
      description = "读取公开 GitHub 仓库文档结构、文档内容，并围绕仓库提问。",
    ),
    McpPresetServer(
      name = "Microsoft Learn",
      url = "https://learn.microsoft.com/api/mcp",
      description = "检索 Microsoft Learn 官方文档，适合查询 Azure、Windows、开发工具等资料。",
    ),
    McpPresetServer(
      name = "Context7",
      url = "https://mcp.context7.com/mcp",
      description = "检索常见开源库的最新文档和代码示例；高频使用时可能需要 API key。",
      authHint = "可先无令牌尝试；如服务限流，再使用请求头方式添加 CONTEXT7_API_KEY。",
    ),
    McpPresetServer(
      name = "GitMCP: Google AI Edge Gallery",
      url = "https://gitmcp.io/google-ai-edge/gallery",
      description = "把 Google AI Edge Gallery 仓库转换为 MCP 文档工具，用于查询上游项目代码和文档。",
    ),
  )
