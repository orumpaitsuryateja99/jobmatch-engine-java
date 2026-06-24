import { NextRequest } from "next/server";

// Runtime proxy: forwards every /api/* call from the browser to the Spring Boot
// backend. SPRING_API_URL is read PER REQUEST (not baked at build) so a Render
// Blueprint can inject the backend's service host at deploy time. Locally it
// defaults to http://localhost:8080, so `npm run dev` behaves exactly as before.
export const dynamic = "force-dynamic";

function backendBase(): string {
  let base = process.env.SPRING_API_URL || "http://localhost:8080";
  if (!/^https?:\/\//i.test(base)) base = "https://" + base; // Render `property: host` gives host only
  return base.replace(/\/$/, "");
}

async function proxy(req: NextRequest, path: string[]): Promise<Response> {
  const url = `${backendBase()}/api/${path.join("/")}${req.nextUrl.search}`;
  const headers = new Headers(req.headers);
  headers.delete("host");
  headers.delete("connection");

  const init: RequestInit = { method: req.method, headers, redirect: "manual" };
  if (req.method !== "GET" && req.method !== "HEAD") {
    init.body = await req.arrayBuffer(); // preserves JSON, multipart uploads, etc.
  }

  const resp = await fetch(url, init);
  const respHeaders = new Headers(resp.headers);
  respHeaders.delete("content-encoding");
  respHeaders.delete("transfer-encoding");
  respHeaders.delete("content-length");
  return new Response(resp.body, { status: resp.status, headers: respHeaders });
}

type Ctx = { params: { path: string[] } };
export const GET = (req: NextRequest, { params }: Ctx) => proxy(req, params.path);
export const POST = (req: NextRequest, { params }: Ctx) => proxy(req, params.path);
export const PUT = (req: NextRequest, { params }: Ctx) => proxy(req, params.path);
export const PATCH = (req: NextRequest, { params }: Ctx) => proxy(req, params.path);
export const DELETE = (req: NextRequest, { params }: Ctx) => proxy(req, params.path);
