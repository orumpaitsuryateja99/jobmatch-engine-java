import type { Config } from "tailwindcss";

// Theme mirrors the Streamlit app's iOS-polish look: light surfaces, #2563eb accent,
// SF Pro system font, soft shadows, springy easing.
const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        accent: { DEFAULT: "#2563eb", dark: "#1d4ed8" },
        ink: "#0f172a",
        muted: "#64748b",
        line: "#e6e9ef",
        surface: "#ffffff",
        canvas: "#f7f8fa",
      },
      fontFamily: {
        sans: ["-apple-system", "BlinkMacSystemFont", "SF Pro Display", "SF Pro Text",
               "Segoe UI", "Inter", "system-ui", "sans-serif"],
      },
      boxShadow: {
        card: "0 1px 3px rgba(15,23,42,.06), 0 12px 32px rgba(15,23,42,.05)",
        soft: "0 1px 2px rgba(15,23,42,.05)",
        ring: "0 0 0 4px rgba(37,99,235,.18)",
      },
      borderRadius: { xl2: "18px" },
      transitionTimingFunction: { spring: "cubic-bezier(.32,.72,0,1)" },
    },
  },
  plugins: [],
};
export default config;
