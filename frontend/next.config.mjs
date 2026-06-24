/** @type {import('next').NextConfig} */
// API calls to /api/* are handled at runtime by app/api/[...path]/route.ts, which
// proxies to the Spring Boot backend (SPRING_API_URL). No build-time rewrite, so
// the backend URL can be injected at deploy time (Render) rather than baked in.
const nextConfig = {
  reactStrictMode: true,
};

export default nextConfig;
