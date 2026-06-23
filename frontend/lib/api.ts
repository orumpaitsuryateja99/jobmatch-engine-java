// Thin fetch wrapper. All calls hit /api/* which next.config rewrites to the
// Spring Boot backend, so there is no CORS and no hard-coded host in the client.
import type { Profile, JobPosting, ScoredJob, SourcesStatus, Application } from "./types";

async function jget<T>(url: string): Promise<T> {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}
async function jpost<T>(url: string, body: unknown): Promise<T> {
  const r = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}

export const api = {
  parseResume: async (file: File): Promise<Profile> => {
    const fd = new FormData(); fd.append("file", file);
    const r = await fetch("/api/resume/parse", { method: "POST", body: fd });
    if (!r.ok) throw new Error(`HTTP ${r.status} — upload a text-based PDF/DOCX/TXT/TEX`);
    return r.json();
  },
  h1b: (company: string) => jget<{ company: string; sponsor: boolean; confidence: string; label: string; badge: string }>(
    `/api/h1b/status?company=${encodeURIComponent(company)}`),

  catalog: () => jget<{ total: number; greenhouse: number; lever: number; ashby: number }>("/api/jobs/catalog"),
  sourcesStatus: () => jget<SourcesStatus>("/api/jobs/sources/status"),
  discoveryLabels: () => jget<{ labels: string[]; provider: string; available: boolean }>("/api/jobs/discovery/labels"),

  sponsorSearch: (b: object) => jpost<JobPosting[]>("/api/jobs/sponsor-search", b),
  apiSearch: (b: object) => jpost<JobPosting[]>("/api/jobs/api-search", b),
  discovery: (b: object) => jpost<JobPosting[]>("/api/jobs/discovery", b),
  themuse: (ng: boolean, my: number, age: number | null) =>
    jget<JobPosting[]>(`/api/jobs/themuse?newGradOnly=${ng}&maxYears=${my}${age ? `&maxAgeHours=${age}` : ""}`),

  scoreJobs: (profile: Profile, jobs: JobPosting[]) => jpost<ScoredJob[]>("/api/ats/score-jobs", { profile, jobs }),
  template: async (profile: Profile): Promise<string> => {
    const r = await fetch("/api/resume/template", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(profile) });
    return r.text();
  },
  tailorPrompt: (profile: Profile, jdText: string, jdTitle: string) =>
    jpost<{ prompt: string }>("/api/resume/tailor/prompt", { profile, jdText, jdTitle }),
  tailorImport: (json: string, jdText: string, jdTitle: string, master: Profile) =>
    jpost<{ profile: Profile; score: number; band: string; matched: string[]; missingSkills: string[]; missingKeywords: string[] }>(
      "/api/resume/tailor/import", { json, jdText, jdTitle, master }),
  tailorAuto: (profile: Profile, jdText: string, jdTitle: string) =>
    jpost<{ profile: Profile; score: number; band: string; matched: string[]; missingSkills: string[]; missingKeywords: string[] }>(
      "/api/resume/tailor/auto", { profile, jdText, jdTitle }),
  pdfStatus: () => jget<{ available: boolean; engine: string }>("/api/resume/pdf/status"),
  pdf: async (profile: Profile): Promise<Blob> => {
    const r = await fetch("/api/resume/pdf", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(profile) });
    if (!r.ok) throw new Error((await r.text()).slice(0, 300) || `HTTP ${r.status}`);
    return r.blob();
  },
  latexMatch: async (latex: string, jdText: string) => {
    const r = await fetch("/api/ats/latex-match", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ latex, jdText }) });
    const t = await r.text();
    return t ? JSON.parse(t) as { score: number; matched: string[]; missing: string[]; total: number } : null;
  },

  pathbPrompt: (b: object) => jpost<{ prompt: string }>("/api/pathb/prompt", b),
  pathbImport: (b: object) => jpost<{ jobs: JobPosting[]; kept: number; filteredSenior: number; filteredFocus: number; filteredDupes: number; empty: boolean; error: string | null }>("/api/pathb/import", b),

  tracker: () => jget<{ applications: Application[]; statuses: string[]; appliedToday: number }>("/api/tracker"),
  addApp: (a: Partial<Application>) => jpost<Application>("/api/tracker", a),
  updStatus: (id: string, status: string) => fetch(`/api/tracker/${id}/status`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ status }) }),
  delApp: (id: string) => fetch(`/api/tracker/${id}`, { method: "DELETE" }),
  uploadResume: async (id: string, file: File) => {
    const fd = new FormData(); fd.append("file", file);
    const r = await fetch(`/api/tracker/${id}/resume`, { method: "POST", body: fd });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
  },
  resumeUrl: (id: string) => `/api/tracker/${id}/resume`,
};
