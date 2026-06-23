/** @type {import('next').NextConfig} */
const API = process.env.SPRING_API_URL || "http://localhost:8080";

const nextConfig = {
  reactStrictMode: true,
  // Proxy every /api/* call to the Spring Boot backend so the browser only ever
  // talks to the Next.js origin — no CORS config needed in dev or prod.
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${API}/api/:path*` }];
  },
};

export default nextConfig;
