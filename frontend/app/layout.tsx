import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Job Automation",
  description: "Next.js + Tailwind frontend for the JobMatch Engine (Spring Boot) — resume parsing, ATS scoring, multi-source job search, H1B lookup, tracker.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
