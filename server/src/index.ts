// Why Not You server entry. The OAuth provider guards /mcp (the AI connection) and serves the
// OAuth endpoints AI apps need (discovery, dynamic client registration, token). Everything else
// goes to appHandler.
import OAuthProvider from "@cloudflare/workers-oauth-provider";
import type { Env, Props } from "./env";
import { handleMcp } from "./mcp";
import { appHandler } from "./app";

const mcpHandler = {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const props = (ctx as unknown as { props: Props }).props;
    return handleMcp(request, env, props);
  },
};

export default new OAuthProvider<Env>({
  apiRoute: "/mcp",
  apiHandler: mcpHandler as never,
  defaultHandler: appHandler as never,
  authorizeEndpoint: "/authorize",
  tokenEndpoint: "/token",
  clientRegistrationEndpoint: "/register",
  scopesSupported: ["alarms"],
  // Must match PUBLIC_URL in wrangler.toml.
  resourceMetadata: { resource: "https://mcp.getwhynotyou.com/mcp", resource_name: "Why Not You", scopes_supported: ["alarms"] },
});
