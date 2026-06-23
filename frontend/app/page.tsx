"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { Profile, JobPosting, ScoredJob, SourcesStatus, Application } from "@/lib/types";

type Tab = "resume" | "jobs" | "match" | "tailor" | "tracker";
const TABS: { id: Tab; label: string }[] = [
  { id: "resume", label: "1 · Resume" },
  { id: "jobs", label: "2 · Find Jobs" },
  { id: "match", label: "3 · Match & Score" },
  { id: "tailor", label: "4 · Tailor & Apply" },
  { id: "tracker", label: "5 · Tracker" },
];

const NEWGRAD_FOCUS: [string, string][] = [
  ["newgrad_swe", "New Grad Software Engineer"], ["entry_swe", "Entry Level Software Engineer"],
  ["junior_dev", "Junior Software Developer"], ["associate_swe", "Associate Software Engineer"],
  ["swe_i", "Software Engineer I"], ["new_college_grad_swe", "New College Grad SWE"],
];
const SPEC_FOCUS: [string, string][] = [
  ["backend", "Backend"], ["fullstack", "Full Stack"], ["frontend", "Frontend"],
  ["data", "Data / Analytics"], ["mlai", "ML / AI"], ["devops", "DevOps / SRE"], ["mobile", "Mobile"],
];
const ALL_FOCUS = [...NEWGRAD_FOCUS, ...SPEC_FOCUS];
const DEFAULT_FOCUS_KEYS = NEWGRAD_FOCUS.map(([k]) => k);
const POSTED = [["", "Any time"], ["24", "Past 24 hours"], ["72", "Past 3 days"],
  ["168", "Past 7 days (1 week)"], ["336", "Past 14 days"], ["720", "Past 30 days"]];

const scoreColor = (s: number) => (s >= 75 ? "text-green-700 bg-green-100" : s >= 55 ? "text-amber-700 bg-amber-100" : "text-red-700 bg-red-100");

function jobKey(j: JobPosting): string {
  return (j.jobLink || `${j.company}__${j.title}`).trim().toLowerCase();
}

function appKey(a: Application): string {
  return (a.jobLink || `${a.company}__${a.title}`).trim().toLowerCase();
}

type JobStatus = "saved" | "applied" | "rejected";

interface Filters {
  focusKeys: string[]; workMode: string; posted: string; keywords: string;
  entry: boolean; years: number; limit: number;
  srcBoards: boolean; srcMuse: boolean; srcWorkday: boolean; srcSr: boolean;
  srcAdzuna: boolean; srcJobApi: boolean; srcRemote: boolean;
  discSites: Record<string, boolean>;
}
const DEFAULT_FILTERS: Filters = {
  focusKeys: DEFAULT_FOCUS_KEYS, workMode: "Any", posted: "168", keywords: "",
  entry: true, years: 2, limit: 150,
  srcBoards: true, srcMuse: false, srcWorkday: false, srcSr: false,
  srcAdzuna: false, srcJobApi: false, srcRemote: false, discSites: {},
};

function Chips({ items, kind }: { items?: string[]; kind?: "hit" | "miss" }) {
  if (!items?.length) return <span className="text-muted text-xs">none</span>;
  const cls = kind === "hit" ? "chip chip-hit" : kind === "miss" ? "chip chip-miss" : "chip";
  return <div className="flex flex-wrap gap-1.5 my-1.5">{items.map((s, i) => <span key={i} className={cls}>{s}</span>)}</div>;
}

export default function Page() {
  const [tab, setTab] = useState<Tab>("resume");
  const [profile, setProfile] = useState<Profile | null>(null);
  const [resumeName, setResumeName] = useState("");
  const [jobs, setJobs] = useState<JobPosting[]>([]);
  const [scored, setScored] = useState<ScoredJob[]>([]);
  const [ranFind, setRanFind] = useState(false);
  const [tailorJob, setTailorJob] = useState<ScoredJob | null>(null);
  const [filters, setFilters] = useState<Filters>(DEFAULT_FILTERS);
  const [jobStatus, setJobStatus] = useState<Record<string, JobStatus>>({});
  const [trackedApps, setTrackedApps] = useState<Application[]>([]);

  function patchFilters(p: Partial<Filters>) { setFilters(f => ({ ...f, ...p })); }
  function setStatus(key: string, status: JobStatus | null) {
    setJobStatus(s => { const n = { ...s }; if (status) n[key] = status; else delete n[key]; return n; });
  }
  async function refreshTrackedJobs() {
    const d = await api.tracker();
    setTrackedApps(d.applications);
    setJobStatus(s => {
      const next = { ...s };
      for (const a of d.applications) {
        const k = appKey(a);
        if (!k) continue;
        const st = (a.status || "").toLowerCase();
        if (st === "saved") next[k] = "saved";
        else if (st && st !== "saved") next[k] = "applied";
      }
      return next;
    });
  }
  async function saveJob(j: JobPosting, score: number) {
    await api.addApp({
      company: j.company,
      title: j.title,
      jobLink: j.jobLink,
      source: j.source,
      status: "Saved",
      notes: `Saved from Match & Score. ATS ${score}.`,
    });
    setStatus(jobKey(j), "saved");
    await refreshTrackedJobs();
  }

  return (
    <div className="max-w-[1280px] mx-auto px-6 pt-9 pb-16">
      <h1 className="text-[2rem] font-bold tracking-tight">🎯 Resume-to-Job Automation</h1>
      <p className="text-muted text-sm mt-1">
        Searches · scores · tailors · tracks — <b className="text-ink">never auto-applies.</b> You press the buttons.
      </p>
      <p className="text-muted text-xs mt-1">Next.js + Tailwind frontend · Spring Boot REST backend</p>

      {!profile && (
        <div className="alert alert-warn mt-3">⚠️ <b>No resume uploaded.</b> Upload your résumé in <b>tab 1 · Resume</b> to use it this session for matching, scoring, tailoring, and tracking.</div>
      )}

      <nav className="flex gap-1 border-b border-line my-6 flex-wrap">
        {TABS.map(t => (
          <button key={t.id} className={`seg ${tab === t.id ? "seg-active" : ""}`} onClick={() => setTab(t.id)}>{t.label}</button>
        ))}
      </nav>

      {/* All tabs stay mounted — switching tabs must never reset filters/state. */}
      <div className={tab === "resume" ? "" : "hidden"}>
        <ResumeTab {...{ profile, setProfile, resumeName, setResumeName, setJobs, setScored, setRanFind, setTailorJob }} />
      </div>
      <div className={tab === "jobs" ? "" : "hidden"}>
        <FindJobsTab {...{ profile, setJobs, setScored, setRanFind, jobs, filters, patchFilters, goMatch: () => setTab("match") }} />
      </div>
      <div className={tab === "match" ? "" : "hidden"}>
        <MatchTab {...{ profile, scored, ranFind, jobStatus, setStatus, saveJob, trackedApps, refreshTrackedJobs, openTailor: (s: ScoredJob) => { setTailorJob(s); setTab("tailor"); } }} />
      </div>
      <div className={tab === "tailor" ? "" : "hidden"}>
        <TailorTab tailorJob={tailorJob} resumeName={resumeName} profile={profile} onApplied={(j: JobPosting) => { setStatus(jobKey(j), "applied"); refreshTrackedJobs(); }} />
      </div>
      <div className={tab === "tracker" ? "" : "hidden"}>
        <TrackerTab active={tab === "tracker"} />
      </div>
    </div>
  );
}

/* ---------------- TAB 1 · RESUME ---------------- */
function ResumeTab({ profile, setProfile, resumeName, setResumeName, setJobs, setScored, setRanFind, setTailorJob }: any) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  async function onParse(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0]; if (!f) return;
    setBusy(true); setErr("");
    try {
      const p = await api.parseResume(f);
      setProfile(p); setResumeName(f.name); setJobs([]); setScored([]); setRanFind(false); setTailorJob(null);
    } catch (e: any) { setErr(e.message); } finally { setBusy(false); }
  }

  return (
    <div>
      <h2 className="text-[1.4rem] font-semibold tracking-tight">Your profile</h2>
      <div className="card">
        <label className="lbl">Upload your master resume (PDF, DOCX, TXT, or TEX)</label>
        <input type="file" accept=".pdf,.docx,.txt,.md,.tex" onChange={onParse} className="text-sm text-muted" />
        {busy && <p className="text-muted text-sm mt-2">Parsing…</p>}
        {err && <div className="alert alert-bad mt-2">Couldn&apos;t parse: {err}</div>}
      </div>

      {!profile ? (
        <div className="alert alert-info"><b>No resume uploaded.</b> Upload one to use it this session — nothing is preloaded.</div>
      ) : <ProfileView profile={profile} resumeName={resumeName} />}
    </div>
  );
}

function ProfileView({ profile: p, resumeName }: { profile: Profile; resumeName: string }) {
  const [open, setOpen] = useState(true);
  const warn: string[] = [];
  if (!p.skills?.length) warn.push("No skills were parsed — ATS scoring can't work.");
  if (!p.experience?.length && !p.projects?.length) warn.push("No work experience or projects parsed — tailoring will be thin.");
  return (
    <div>
      <div className="alert alert-good">✅ Using <b>{resumeName}</b> for this session — matching, scoring, tailoring. Not saved; reload and you start fresh.</div>
      {warn.length
        ? <div className="alert alert-warn">⚠️ <b>Parse check:</b><ul className="list-disc ml-5">{warn.map((w, i) => <li key={i}>{w}</li>)}</ul></div>
        : <p className="text-muted text-xs">✅ Parse check passed: {p.skills?.length} skills · {p.experience?.length} experience · {p.projects?.length} projects.</p>}
      <div className="card mt-3">
        <button className="font-semibold w-full text-left" onClick={() => setOpen(!open)}>{open ? "▾" : "▸"} Parsed profile details used for matching</button>
        {open && (
          <div className="mt-3 text-sm space-y-1">
            <div className="text-base font-semibold">{p.name}</div>
            <div className="text-muted">{[p.email, p.phone, ...Object.entries(p.links || {}).map(([k, v]) => `${k}: ${v}`)].filter(Boolean).join(" · ") || "No contact details parsed"}</div>
            {p.summary && <><div className="text-muted font-semibold mt-2">Professional summary</div><div>{p.summary}</div></>}
            {!!p.skillCategories?.length && <><div className="text-muted font-semibold mt-2">Technical skills from resume</div>{p.skillCategories.map((c, i) => <div key={i}>• <b>{c.category}</b>: {c.items.join(", ")}</div>)}</>}
            <div className="text-muted font-semibold mt-2">Normalized skills</div><Chips items={p.skills} />
            <div className="text-muted font-semibold">Tools</div><Chips items={p.tools} />
            <div className="text-muted font-semibold">Domains</div><Chips items={p.domains} />
            <div className="text-muted font-semibold mt-2">Work experience</div>
            {p.experience?.length ? p.experience.map((e, i) => (
              <div key={i} className="mt-1"><b>{e.role || "Experience"}</b>{e.company ? <i> — {e.company}</i> : null}{(e.bullets || []).map((b, j) => <div key={j} className="text-muted">• {b}</div>)}</div>
            )) : <div className="text-muted">none</div>}
            <div className="text-muted font-semibold mt-2">Projects</div>
            {p.projects?.length ? p.projects.map((pr, i) => (
              <div key={i} className="mt-1"><b>{pr.name || "Project"}</b>{(pr.bullets || []).map((b, j) => <div key={j} className="text-muted">• {b}</div>)}</div>
            )) : <div className="text-muted">none</div>}
            <div className="text-muted font-semibold mt-2">Education</div>
            {p.education?.length ? p.education.map((ed, i) => <div key={i}><b>{ed.school}</b> — {ed.location} · {[ed.degree, ed.detail, ed.dates].filter(Boolean).join(" · ")}</div>) : <div className="text-muted">none</div>}
          </div>
        )}
      </div>
    </div>
  );
}

/* ---------------- TAB 2 · FIND JOBS ---------------- */
function FindJobsTab({ profile, setJobs, setScored, setRanFind, jobs, filters, patchFilters, goMatch }: any) {
  const [status, setStatus] = useState<SourcesStatus | null>(null);
  const [catalog, setCatalog] = useState<{ total: number; greenhouse: number; lever: number; ashby: number } | null>(null);
  const [discLabels, setDiscLabels] = useState<string[]>([]);
  const [discProvider, setDiscProvider] = useState("");
  const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<{ k: string; t: string } | null>(null);

  const {
    focusKeys, workMode, posted, keywords, entry, years, limit,
    srcBoards, srcMuse, srcWorkday, srcSr, srcAdzuna, srcJobApi, srcRemote, discSites,
  } = filters;

  useEffect(() => {
    api.catalog().then(setCatalog).catch(() => {});
    api.sourcesStatus().then(setStatus).catch(() => {});
    api.discoveryLabels().then(d => { setDiscLabels(d.labels); setDiscProvider(d.available ? d.provider : ""); }).catch(() => {});
  }, []);

  function toggleFocus(key: string) {
    patchFilters({ focusKeys: focusKeys.includes(key) ? focusKeys.filter((k: string) => k !== key) : [...focusKeys, key] });
  }

  async function search() {
    if (!profile) { setMsg({ k: "warn", t: "Upload a résumé in tab 1 first." }); return; }
    const age = posted ? +posted : null;
    setBusy(true); setMsg(null);
    try {
      const calls: Promise<JobPosting[]>[] = [];
      const wantBoards = srcBoards || srcWorkday || srcSr;
      if (wantBoards) calls.push(api.sponsorSearch({ newGradOnly: entry, maxYears: years, maxAgeHours: age, includeMuse: srcMuse, limit, includeWorkday: srcWorkday, includeSmartRecruiters: srcSr, focusKeys }));
      else if (srcMuse) calls.push(api.themuse(entry, years, age));
      const apiSrcs = [srcAdzuna && "adzuna", srcJobApi && "jobApi", srcRemote && "remote"].filter(Boolean) as string[];
      if (apiSrcs.length) calls.push(api.apiSearch({ query: keywords.trim() || "software engineer new grad", sources: apiSrcs, newGradOnly: entry, maxYears: years, maxAgeHours: age, focusKeys }));
      const sites = Object.entries(discSites).filter(([, v]) => v).map(([k]) => k);
      if (sites.length) calls.push(api.discovery({ siteLabels: sites, newGradOnly: entry, maxYears: years, maxAgeHours: age, focusKeys, maxResults: 10 }));
      if (!calls.length) { setMsg({ k: "warn", t: "Select at least one source." }); setBusy(false); return; }
      let result = (await Promise.all(calls)).flat().filter(j => j && !j.error);
      const kw = keywords.toLowerCase().split(",").map((s: string) => s.trim()).filter(Boolean);
      if (workMode !== "Any") result = result.filter(j => (j.workMode || "").toLowerCase() === workMode.toLowerCase());
      if (kw.length) result = result.filter(j => kw.some((k: string) => `${j.title} ${j.description}`.toLowerCase().includes(k)));
      setJobs(result); setRanFind(true);
      if (!result.length) { setMsg({ k: "warn", t: "Search ran, but every result was filtered out. Widen filters or add sources." }); setBusy(false); return; }
      const sc = await api.scoreJobs(profile, result); setScored(sc);
      setMsg({ k: "good", t: `Pulled & scored ${result.length} job(s). See them in 3 · Match & Score.` });
    } catch (e: any) { setMsg({ k: "bad", t: "Search failed: " + e.message }); } finally { setBusy(false); }
  }

  const sb = (ok: boolean) => (ok ? "✅" : "🔒");
  return (
    <div>
      <h2 className="text-[1.4rem] font-semibold tracking-tight">🔍 Find Jobs</h2>
      {!profile && <div className="alert alert-warn">⚠️ Upload a résumé in <b>tab 1</b> first — jobs are scored against it.</div>}

      <div className="rounded-lg px-4 py-2.5 my-2 bg-[#1a3a5c] text-white">
        <div className="text-[17px] font-bold">🅐 Path A — Search / Pull Jobs Automatically</div>
        <div className="text-xs text-[#aad4ff]">Sponsor boards · Workday/SmartRecruiters · Aggregator APIs · Discovery — all wired to the Spring Boot backend</div>
      </div>

      <div className="card grid md:grid-cols-2 gap-6">
        <div>
          <div className="font-semibold mb-2">🎛️ Search Filters</div>
          <label className="lbl">Role focus — defaults to new-grad/entry titles; check more to widen, uncheck to narrow</label>
          <div className="text-[0.8rem] font-semibold mt-1">New Grad / Entry titles</div>
          <div className="grid grid-cols-2 gap-x-3">
            {NEWGRAD_FOCUS.map(([k, l]) => (
              <label key={k} className="check my-0.5"><input type="checkbox" checked={focusKeys.includes(k)} onChange={() => toggleFocus(k)} /> {l}</label>
            ))}
          </div>
          <div className="text-[0.8rem] font-semibold mt-2">Specializations (optional, narrows further)</div>
          <div className="grid grid-cols-2 gap-x-3">
            {SPEC_FOCUS.map(([k, l]) => (
              <label key={k} className="check my-0.5"><input type="checkbox" checked={focusKeys.includes(k)} onChange={() => toggleFocus(k)} /> {l}</label>
            ))}
          </div>
          <div className="grid grid-cols-2 gap-3 mt-2.5">
            <div><label className="lbl">Work mode</label><select className="field" value={workMode} onChange={e => patchFilters({ workMode: e.target.value })}>{["Any", "Remote", "Hybrid", "Onsite"].map(x => <option key={x}>{x}</option>)}</select></div>
            <div><label className="lbl">Posted within</label><select className="field" value={posted} onChange={e => patchFilters({ posted: e.target.value })}>{POSTED.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></div>
          </div>
          <div className="mt-2.5"><label className="lbl">Extra keywords (also the aggregator/Path-B query)</label><input className="field" placeholder="Java, fintech, AWS…" value={keywords} onChange={e => patchFilters({ keywords: e.target.value })} /></div>
          <div className="flex gap-4 mt-3">
            <label className="check"><input type="checkbox" checked={entry} onChange={e => patchFilters({ entry: e.target.checked })} /> Entry-level only</label>
          </div>
          <div className="mt-2"><label className="lbl">Max yrs exp: {years}</label><input type="range" min={0} max={5} value={years} onChange={e => patchFilters({ years: +e.target.value })} className="w-full accent-accent" /></div>
          <div className="mt-2"><label className="lbl">Boards actually scanned: {limit} of {catalog?.total || 267} (higher = more jobs, slower)</label><input type="range" min={10} max={catalog?.total || 267} step={10} value={limit} onChange={e => patchFilters({ limit: +e.target.value })} className="w-full accent-accent" />
            {entry && limit < 150 && <p className="text-amber-700 text-[0.7rem] mt-0.5">⚠️ Entry-level + few boards = very few hits. Scan more boards or widen "Posted within".</p>}</div>
        </div>

        <div>
          <div className="font-semibold mb-2">📦 Job Sources</div>
          <div className="text-[0.9rem] font-semibold">🏢 Core sponsor boards</div>
          <label className="check mt-1.5"><input type="checkbox" checked={srcBoards} onChange={e => patchFilters({ srcBoards: e.target.checked })} /> {catalog ? `${catalog.total} sponsor boards (${catalog.greenhouse} GH · ${catalog.lever} Lever · ${catalog.ashby} Ashby)` : "sponsor boards"}</label>
          <label className="check mt-1.5"><input type="checkbox" checked={srcMuse} onChange={e => patchFilters({ srcMuse: e.target.checked })} /> The Muse</label>

          <div className="text-[0.9rem] font-semibold mt-3">🏭 Workday / SmartRecruiters ATS</div>
          <label className="check mt-1.5"><input type="checkbox" checked={srcWorkday} onChange={e => patchFilters({ srcWorkday: e.target.checked })} /> Workday</label>
          <label className="check mt-1.5"><input type="checkbox" checked={srcSr} onChange={e => patchFilters({ srcSr: e.target.checked })} /> SmartRecruiters</label>

          <div className="text-[0.9rem] font-semibold mt-3">🌐 Aggregator APIs {status && <span className="text-muted text-xs font-normal">— keyed from .env</span>}</div>
          <label className="check mt-1.5"><input type="checkbox" checked={srcAdzuna} disabled={!status?.adzuna} onChange={e => patchFilters({ srcAdzuna: e.target.checked })} /> Adzuna {status && sb(status.adzuna)}</label>
          <label className="check mt-1.5"><input type="checkbox" checked={srcJobApi} disabled={!status?.jobApiFallback} onChange={e => patchFilters({ srcJobApi: e.target.checked })} /> Job API fallback (SerpApi → JSearch → Careerjet → Jooble) {status && sb(status.jobApiFallback)}</label>
          <label className="check mt-1.5"><input type="checkbox" checked={srcRemote} onChange={e => patchFilters({ srcRemote: e.target.checked })} /> Remote APIs (Remotive + RemoteOK) ✅</label>

          <div className="flex items-center gap-2 mt-3 flex-wrap">
            <span className="text-[0.9rem] font-semibold">🌐 Discovery websites {discProvider ? <span className="text-muted text-xs font-normal">✅ via {discProvider}</span> : <span className="text-muted text-xs font-normal">🔒</span>}</span>
            <button type="button" className="btn btn-sm" disabled={!discProvider} title="Select every discovery website"
              onClick={() => patchFilters({ discSites: Object.fromEntries(discLabels.map(l => [l, true])) })}>✓ All On</button>
            <button type="button" className="btn btn-sm" disabled={!discProvider} title="Deselect every discovery website"
              onClick={() => patchFilters({ discSites: {} })}>✕ All Off</button>
          </div>
          <div className="grid grid-cols-2 gap-x-3">
            {discLabels.map(l => (
              <label key={l} className="check my-0.5"><input type="checkbox" disabled={!discProvider} checked={!!discSites[l]} onChange={e => patchFilters({ discSites: { ...discSites, [l]: e.target.checked } })} /> {l}</label>
            ))}
          </div>
        </div>
      </div>

      <div className="flex gap-2 mt-4">
        <button className="btn-primary" disabled={busy} onClick={search}>{busy ? "Searching…" : "🔎 Search selected sources"}</button>
        <button className="btn btn-sm" disabled={busy} onClick={() => patchFilters(DEFAULT_FILTERS)} title="Reset all filters back to defaults before your next search">🔄 Reset filters</button>
      </div>
      {msg && <div className={`alert alert-${msg.k} mt-3`}>{msg.t} {msg.k === "good" && <button className="btn btn-sm ml-2" onClick={goMatch}>Go to Match & Score →</button>}</div>}

      <PathB profile={profile} focusKeys={focusKeys} keywords={keywords} workMode={workMode} years={years} entry={entry} posted={posted}
        onImport={async (imported: JobPosting[]) => { const merged = [...jobs, ...imported]; setJobs(merged); setRanFind(true); if (profile) setScored(await api.scoreJobs(profile, merged)); goMatch(); }} />
    </div>
  );
}

function PathB({ profile, focusKeys, keywords, workMode, years, entry, posted, onImport }: any) {
  const [prompt, setPrompt] = useState(""); const [paste, setPaste] = useState("");
  const [out, setOut] = useState<{ k: string; t: string } | null>(null);
  const freshness = ({ "24": "the last 24 hours", "72": "the last 3 days", "168": "the last 7 days", "336": "the last 14 days", "720": "the last 30 days" } as any)[posted] || "recently";
  const roleLabels = focusKeys.length ? focusKeys.map((k: string) => ALL_FOCUS.find(([fk]) => fk === k)?.[1] || k) : ["Software Engineer (general)"];

  async function gen() {
    if (!profile) return;
    const d = await api.pathbPrompt({ profile, prefs: keywords, location: "Remote, US", maxYears: years, roleLabels, workMode, h1bOnly: false, freshnessLabel: freshness });
    setPrompt(d.prompt);
  }
  async function imp() {
    if (!profile || !paste.trim()) { setOut({ k: "bad", t: "Paste the AI JSON first." }); return; }
    try {
      const d = await api.pathbImport({ text: paste, newGradOnly: entry, maxYears: years, focusKeys });
      if (d.error) { setOut({ k: "bad", t: d.error }); return; }
      if (d.empty) { setOut({ k: "info", t: "The AI returned an empty list — widen the window or focus." }); return; }
      if (!d.jobs.length) { setOut({ k: "warn", t: `Imported 0 — ${d.filteredSenior} senior, ${d.filteredFocus} off-focus filtered.` }); return; }
      await onImport(d.jobs);
      setOut({ k: "good", t: `Imported ${d.jobs.length} role(s) → scored in Match & Score.` });
    } catch (e: any) { setOut({ k: "bad", t: e.message }); }
  }
  return (
    <div className="mt-6">
      <div className="rounded-lg px-4 py-2.5 my-2 bg-[#3a1a5c] text-white">
        <div className="text-[17px] font-bold">🅑 Path B — AI Search → Paste JSON</div>
        <div className="text-xs text-[#ddbfff]">Use Claude.ai / ChatGPT with web search → paste results back (widest reach)</div>
      </div>
      <div className="card grid md:grid-cols-2 gap-6">
        <div>
          <div className="font-semibold mb-1.5">1 · Generate the search prompt</div>
          <button className="btn-primary" onClick={gen}>🪄 Generate prompt</button>
          {prompt && <><button className="btn btn-sm ml-2" onClick={() => navigator.clipboard.writeText(prompt)}>📋 Copy</button>
            <pre className="mt-2 bg-slate-900 text-slate-100 rounded-xl p-3 text-[0.7rem] max-h-72 overflow-auto whitespace-pre-wrap">{prompt}</pre></>}
        </div>
        <div>
          <div className="font-semibold mb-1.5">2 · Paste the AI&apos;s JSON here</div>
          <textarea className="field min-h-[120px]" value={paste} onChange={e => setPaste(e.target.value)} placeholder='[{"company":"…","role":"…","apply_link":"…","job_description":"…"}]' />
          <button className="btn-primary mt-2" onClick={imp}>📥 Import pasted jobs</button>
          {out && <div className={`alert alert-${out.k} mt-2`}>{out.t}</div>}
        </div>
      </div>
    </div>
  );
}

/* ---------------- TAB 3 · MATCH & SCORE ---------------- */
function MatchTab({ profile, scored, ranFind, jobStatus, setStatus, saveJob, trackedApps, refreshTrackedJobs, openTailor }: any) {
  const [sort, setSort] = useState("score"); const [src, setSrc] = useState(""); const [wm, setWm] = useState("Any"); const [min, setMin] = useState(0);
  useEffect(() => { if (ranFind) refreshTrackedJobs?.(); }, [ranFind]);
  if (!profile) return <div className="alert alert-warn">⚠️ Upload a résumé in <b>1 · Resume</b>, then run <b>Find Jobs</b>.</div>;
  if (!ranFind) return <div className="alert alert-info"><b>No matched jobs yet. Upload your resume and run Find Jobs first.</b></div>;
  if (!scored.length) return <div className="alert alert-warn"><b>Your search ran, but nothing scored.</b> Widen filters in Find Jobs.</div>;

  const sources = Array.from(new Set(scored.map((s: ScoredJob) => s.job.source))).sort();
  const trackedByKey = new Map<string, string>();
  for (const a of trackedApps as Application[]) {
    const k = appKey(a);
    if (k) trackedByKey.set(k, (a.status || "").toLowerCase());
  }
  let rows = [...scored] as ScoredJob[];
  rows = rows.filter(s => {
    const persisted = trackedByKey.get(jobKey(s.job));
    return !persisted || persisted === "saved";
  });
  if (src) rows = rows.filter(s => s.job.source === src);
  if (wm !== "Any") rows = rows.filter(s => (s.job.workMode || "").toLowerCase() === wm.toLowerCase());
  rows = rows.filter(s => s.score >= min);
  if (sort === "company") rows.sort((a, b) => (a.job.company || "").localeCompare(b.job.company || ""));
  else if (sort === "source") rows.sort((a, b) => (a.job.source || "").localeCompare(b.job.source || ""));
  else rows.sort((a, b) => b.score - a.score);

  return (
    <div>
      <h2 className="text-[1.4rem] font-semibold tracking-tight">Match &amp; score</h2>
      <div className="card-tight grid grid-cols-2 md:grid-cols-4 gap-3">
        <div><label className="lbl">Sort by</label><select className="field" value={sort} onChange={e => setSort(e.target.value)}><option value="score">ATS match</option><option value="company">Company</option><option value="source">Source</option></select></div>
        <div><label className="lbl">Source</label><select className="field" value={src} onChange={e => setSrc(e.target.value)}><option value="">All</option>{sources.map(s => <option key={s as string}>{s as string}</option>)}</select></div>
        <div><label className="lbl">Work mode</label><select className="field" value={wm} onChange={e => setWm(e.target.value)}>{["Any", "Remote", "Hybrid", "Onsite"].map(x => <option key={x}>{x}</option>)}</select></div>
        <div><label className="lbl">Min score: {min}</label><input type="range" min={0} max={100} step={5} value={min} onChange={e => setMin(+e.target.value)} className="w-full accent-accent" /></div>
      </div>
      <p className="text-muted text-xs"><b>{rows.length}</b> of {scored.length} job(s)</p>
      {rows.map((s, i) => {
        const key = jobKey(s.job);
        const persisted = trackedByKey.get(key);
        const status = jobStatus[key] || (persisted === "saved" ? "saved" : undefined);
        return <JobCard key={key} idx={i + 1} s={s} status={status}
          onTailor={() => openTailor(s)}
          onSave={() => saveJob(s.job, s.score)}
          onReject={() => setStatus(key, jobStatus[key] === "rejected" ? null : "rejected")} />;
      })}
    </div>
  );
}

function JobCard({ idx, s, status, onTailor, onSave, onReject }: { idx: number; s: ScoredJob; status?: JobStatus; onTailor: () => void; onSave: () => Promise<void>; onReject: () => void }) {
  const [open, setOpen] = useState(false); const [saving, setSaving] = useState(false); const j = s.job;
  const statusCls = status === "applied" ? "border-green-400 bg-green-50" : status === "saved" ? "border-blue-300 bg-blue-50" : status === "rejected" ? "border-red-400 bg-red-50" : "border-line bg-white";
  async function save() {
    setSaving(true);
    try { await onSave(); } finally { setSaving(false); }
  }
  return (
    <div className={`border rounded-2xl p-4 my-2.5 shadow-soft ${statusCls}`}>
      <div className="flex justify-between gap-3 items-start">
        <div className="flex gap-2">
          <span className="text-xs font-bold text-muted bg-slate-100 border border-line rounded px-1.5 py-0.5 h-fit">#{idx}</span>
          <div>
            <h4 className="font-semibold">{j.jobLink ? <a className="text-accent hover:underline" href={j.jobLink} target="_blank" rel="noopener">{j.title}</a> : j.title}</h4>
            <div className="text-xs text-muted">{[j.company, j.location, j.workMode, j.postedDate].filter(Boolean).join(" · ")} <span className="ml-1 px-2 py-0.5 rounded bg-slate-100 border border-line">{j.source}</span></div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {status === "applied" && <span className="text-xs font-bold text-green-700 bg-green-100 rounded-full px-2.5 py-1">✅ Applied</span>}
          {status === "saved" && <span className="text-xs font-bold text-blue-700 bg-blue-100 rounded-full px-2.5 py-1">💾 Saved</span>}
          {status === "rejected" && <span className="text-xs font-bold text-red-700 bg-red-100 rounded-full px-2.5 py-1">❌ Rejected</span>}
          <div className={`text-sm font-extrabold px-3 py-1.5 rounded-lg whitespace-nowrap ${scoreColor(s.score)}`}>{s.score}<span className="text-[0.7rem]"> ATS</span></div>
        </div>
      </div>
      <button className="text-xs text-muted mt-2" onClick={() => setOpen(!open)}>{open ? "▾" : "▸"} Match detail · Tailor & Apply</button>
      {open && (
        <div className="mt-2 text-sm">
          <span className={`px-2.5 py-0.5 rounded-full text-xs font-bold ${s.score >= 55 ? "bg-green-100 text-green-800" : "bg-amber-100 text-amber-800"}`}>{s.band}</span>
          <div className="text-muted font-semibold mt-2">Matched skills</div><Chips items={s.matched} kind="hit" />
          <div className="text-muted font-semibold">Missing skills</div><Chips items={s.missing} kind="miss" />
          {j.description && <><div className="text-muted font-semibold mt-2">JD excerpt</div><div className="text-muted text-xs">{j.description.slice(0, 260)}…</div></>}
          <div className="flex gap-2 mt-2">
            <button className="btn btn-sm" disabled={saving || status === "saved" || status === "applied"} onClick={save}>{saving ? "Saving…" : status === "saved" || status === "applied" ? "💾 Saved" : "💾 Save job"}</button>
            <button className="btn-primary btn-sm" onClick={onTailor}>Tailor &amp; Apply this job →</button>
            <button className="btn btn-sm" onClick={onReject}>{status === "rejected" ? "↺ Undo reject" : "❌ Reject"}</button>
          </div>
        </div>
      )}
    </div>
  );
}

/* Readable preview of a tailored Profile — so the user sees exactly what the
 * PDF/.tex download will contain (distinct from the raw upload / Claude prompt). */
function ResumePreview({ p }: { p: Profile }) {
  const linkBits = (links?: Record<string, string>) => {
    const live = links?.live || links?.demo || links?.website;
    const gh = links?.github;
    return (
      <>
        {gh ? <a className="text-accent" href={gh.startsWith("http") ? gh : `https://${gh}`} target="_blank" rel="noopener"> [GitHub]</a> : <span className="text-muted"> [GitHub: Not provided]</span>}
        {live ? <a className="text-accent" href={live.startsWith("http") ? live : `https://${live}`} target="_blank" rel="noopener"> [Live]</a> : null}
      </>
    );
  };
  return (
    <div className="bg-white border border-line rounded-xl p-3 text-xs max-h-96 overflow-auto">
      <div className="text-center font-bold text-sm">{p.name}</div>
      <div className="text-center text-muted">{[p.email, p.phone, ...Object.entries(p.links || {}).map(([, v]) => v)].filter(Boolean).join(" · ")}</div>
      {p.summary && <><div className="font-semibold mt-2 border-b border-line">Professional Summary</div><div>{p.summary}</div></>}
      {!!p.skillCategories?.length && <><div className="font-semibold mt-2 border-b border-line">Technical Skills</div>
        {p.skillCategories.map((c, i) => <div key={i}><b>{c.category}:</b> {c.items.join(", ")}</div>)}</>}
      {!!p.experience?.length && <><div className="font-semibold mt-2 border-b border-line">Work Experience</div>
        {p.experience.map((e, i) => (
          <div key={i} className="mt-1">
            <div><b>{e.role}</b>{e.company ? ` — ${e.company}` : ""} {linkBits(e.links)} <span className="text-muted">{[e.location, e.date].filter(Boolean).join(" · ")}</span></div>
            {!!e.skills?.length && <div className="text-muted"><i>Skills: {e.skills.join(", ")}</i></div>}
            {(e.bullets || []).map((b, j) => <div key={j}>• {b}</div>)}
          </div>
        ))}</>}
      {!!p.projects?.length && <><div className="font-semibold mt-2 border-b border-line">Projects</div>
        {p.projects.map((pr, i) => (
          <div key={i} className="mt-1">
            <div><b>{pr.name}</b> {linkBits(pr.links)} <span className="text-muted">{pr.date}</span></div>
            {pr.tech && <div className="text-muted"><i>{pr.tech}</i></div>}
            {!!pr.skills?.length && <div className="text-muted"><i>Skills: {pr.skills.join(", ")}</i></div>}
            {(pr.bullets || []).map((b, j) => <div key={j}>• {b}</div>)}
          </div>
        ))}</>}
      {!!p.education?.length && <><div className="font-semibold mt-2 border-b border-line">Education</div>
        {p.education.map((ed, i) => <div key={i}><b>{ed.school}</b> — {[ed.degree, ed.detail, ed.dates].filter(Boolean).join(" · ")}</div>)}</>}
    </div>
  );
}

/* ---------------- TAB 4 · TAILOR & APPLY (Claude-Pro paste path) ---------------- */
function TailorTab({ tailorJob, resumeName, profile, onApplied }: { tailorJob: ScoredJob | null; resumeName: string; profile: Profile | null; onApplied: (j: JobPosting) => void }) {
  const [jd, setJd] = useState(""); const [prompt, setPrompt] = useState(""); const [paste, setPaste] = useState("");
  const [tailored, setTailored] = useState<Profile | null>(null);
  const [result, setResult] = useState<{ score: number; band: string; matched: string[]; missingSkills: string[]; missingKeywords: string[] } | null>(null);
  const [busy, setBusy] = useState(""); const [err, setErr] = useState("");
  const [applied, setApplied] = useState(false); const [copied, setCopied] = useState(false);
  const [genSource, setGenSource] = useState<"" | "auto" | "claude">("");
  const [pdfEngine, setPdfEngine] = useState<{ available: boolean; engine: string } | null>(null);
  useEffect(() => { api.pdfStatus().then(setPdfEngine).catch(() => {}); }, []);
  useEffect(() => { if (tailorJob) { setJd(tailorJob.job.description || ""); setPrompt(""); setPaste(""); setTailored(null); setResult(null); setErr(""); setApplied(false); setGenSource(""); } }, [tailorJob]);
  if (!tailorJob) return <div className="alert alert-info">Pick a job in <b>3 · Match &amp; Score</b> (▸ <i>Tailor &amp; Apply this job</i>) to load it here.</div>;
  const j = tailorJob.job;

  async function genPrompt() {
    if (!profile) return;
    setBusy("prompt"); setErr("");
    try { const d = await api.tailorPrompt(profile, jd, j.title || ""); setPrompt(d.prompt); }
    catch (e: any) { setErr("Couldn't build prompt: " + e.message); } finally { setBusy(""); }
  }
  async function importJson() {
    if (!profile || !paste.trim()) { setErr("Paste the JSON Claude returned first."); return; }
    setBusy("import"); setErr("");
    try {
      const d = await api.tailorImport(paste, jd, j.title || "", profile);
      setTailored(d.profile); setResult({ score: d.score, band: d.band, matched: d.matched, missingSkills: d.missingSkills, missingKeywords: d.missingKeywords }); setGenSource("claude");
    } catch (e: any) { setErr(e.message); } finally { setBusy(""); }
  }
  async function autoGenerate() {
    if (!profile) return;
    setBusy("auto"); setErr("");
    try {
      const d = await api.tailorAuto(profile, jd, j.title || "");
      setTailored(d.profile); setResult({ score: d.score, band: d.band, matched: d.matched, missingSkills: d.missingSkills, missingKeywords: d.missingKeywords }); setGenSource("auto");
    } catch (e: any) { setErr("Auto-generate failed: " + e.message); } finally { setBusy(""); }
  }
  function resumeFileName(ext: string) {
    const slug = (s?: string) => (s || "").trim().replace(/[^A-Za-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
    const first = slug(tailored?.name).split("_")[0].toLowerCase() || "resume";
    const parts = [first, slug(j.company), slug(j.title)].filter(Boolean);
    return parts.join("_") + "." + ext;
  }
  async function download(kind: "pdf" | "tex") {
    if (!tailored) return;
    setBusy(kind); setErr("");
    try {
      if (kind === "pdf") {
        const blob = await api.pdf(tailored);
        dl(blob, resumeFileName("pdf"));
      } else {
        const tex = await api.template(tailored);
        dl(new Blob([tex], { type: "text/plain" }), resumeFileName("tex"));
      }
    } catch (e: any) { setErr((kind === "pdf" ? "PDF compile failed: " : "") + e.message); } finally { setBusy(""); }
  }
  function dl(blob: Blob, name: string) { const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = name; a.click(); URL.revokeObjectURL(a.href); }
  async function record() { await api.addApp({ company: j.company, title: j.title, jobLink: j.jobLink, source: j.source, status: "Applied" }); setApplied(true); onApplied(j); }

  function resultBlock() {
    if (!result || !tailored) return null;
    const ok = result.score >= 80;
    return (
      <div className="mt-3 border-2 border-accent/40 rounded-2xl p-4 bg-accent/5">
        <div className="font-bold text-[15px]">📄 Recommended tailored résumé <span className="text-muted text-xs font-normal">— built for {j.title || "this job"}</span></div>
        <div className="mt-2">
          <span className={`inline-block rounded-full px-3 py-1 text-sm font-extrabold ${ok ? "bg-green-100 text-green-800" : result.score >= 60 ? "bg-amber-100 text-amber-800" : "bg-red-100 text-red-700"}`}>
            ATS {result.score} · {result.band}{ok ? " ✅" : " — see missing skills below"}
          </span>
        </div>
        <div className="text-muted font-semibold text-sm mt-2">✅ Matched JD skills ({result.matched.length})</div><Chips items={result.matched} kind="hit" />
        <div className="text-muted font-semibold text-sm">❌ Missing / recommended to add (NOT added to your résumé)</div><Chips items={[...result.missingSkills, ...result.missingKeywords]} kind="miss" />
        {!ok && <p className="text-amber-700 text-xs mt-1">Score under 80: the JD wants skills your résumé doesn&apos;t genuinely show (listed above). Add real ones to your master résumé and re-run — nothing is faked.</p>}

        <div className="text-sm font-semibold mt-3 mb-1">Preview</div>
        <ResumePreview p={tailored} />

        <div className="flex flex-wrap gap-2 mt-3 items-center">
          <button className="btn-primary" disabled={busy === "pdf" || !pdfEngine?.available} onClick={() => download("pdf")}
            title={pdfEngine?.available ? `Compiled with ${pdfEngine.engine}` : "No LaTeX engine on server (brew install tectonic)"}>
            {busy === "pdf" ? "Compiling…" : "📥 Download Recommended Résumé (PDF)"}</button>
          <button className="btn" disabled={busy === "tex"} onClick={() => download("tex")}>
            {busy === "tex" ? "…" : "💾 Download Recommended Résumé (.tex)"}</button>
        </div>
        {pdfEngine && !pdfEngine.available && <p className="text-amber-700 text-xs mt-1.5">⚠️ Server has no LaTeX engine — run <code>brew install tectonic</code> for PDF. The .tex download still works.</p>}
        <p className="text-muted text-xs mt-1.5">This PDF/.tex is the tailored résumé above — not your raw upload and not the Claude prompt. It keeps your real categorized skills, per-role/-project links &amp; dates, reordered &amp; reworded for this JD.</p>
      </div>
    );
  }

  return (
    <div>
      <h2 className="text-[1.4rem] font-semibold tracking-tight">✍️ Tailor &amp; Apply</h2>
      <p className="text-muted text-xs mt-1">Two ways, both built from <b>{resumeName || "your résumé"}</b> and neither fabricates: <b>(A)</b> let the site instantly recommend a résumé for this job, or <b>(B)</b> use your <b>Claude/ChatGPT Pro</b> via the prompt-paste path.</p>

      <div className="text-sm font-semibold mt-4 mb-1">1 · The job &amp; its description</div>
      <div className="card-tight"><h4 className="font-semibold">{j.company} — {j.title}</h4>
        <div className="text-xs text-muted mb-2">{[j.location, j.workMode, j.source].filter(Boolean).join(" · ")}{j.jobLink && <> · <a className="text-accent" href={j.jobLink} target="_blank" rel="noopener">open posting ↗</a></>}</div>
        <label className="lbl">Job description (edit/paste the full JD for best tailoring)</label>
        <textarea className="field min-h-[110px] text-xs" value={jd} onChange={e => setJd(e.target.value)} />
      </div>

      <div className="rounded-lg px-4 py-2.5 mt-5 mb-2 bg-[#0f5132] text-white">
        <div className="text-[17px] font-bold">⚡ A — Recommended résumé (instant, no Claude needed)</div>
        <div className="text-xs text-[#bfe6cf]">The site tailors your master résumé to this JD itself — reorders skills &amp; sections toward the JD, adds honest per-role/-project skill lines, scores it, and gives you the PDF/.tex right here.</div>
      </div>
      <div className="card-tight">
        <button className="btn-primary" disabled={busy === "auto" || !profile} onClick={autoGenerate}>{busy === "auto" ? "Generating…" : "⚡ Generate recommended résumé"}</button>
        <p className="text-muted text-xs mt-1.5">Pure reorder + surface of your real content (never invents skills). Want a deeper rewrite of the bullet wording? Use path B below.</p>
        {err && genSource !== "claude" && <div className="alert alert-bad mt-2 whitespace-pre-wrap text-xs">{err}</div>}
        {genSource === "auto" && resultBlock()}
      </div>

      <div className="rounded-lg px-4 py-2.5 mt-5 mb-2 bg-[#1a3a5c] text-white">
        <div className="text-[17px] font-bold">🅑 B — Claude/ChatGPT Pro prompt-paste path</div>
        <div className="text-xs text-[#aad4ff]">Generate a prompt → paste into Claude → paste its JSON back for a deeper, reworded tailoring.</div>
      </div>

      <div className="text-sm font-semibold mt-4 mb-1">B1 · Generate the Claude tailoring prompt</div>
      <div className="card-tight">
        <button className="btn-primary" disabled={busy === "prompt" || !profile} onClick={genPrompt}>{busy === "prompt" ? "Building…" : "🪄 Generate Claude prompt"}</button>
        {prompt && <><button className="btn btn-sm ml-2" onClick={() => { navigator.clipboard.writeText(prompt); setCopied(true); setTimeout(() => setCopied(false), 1500); }}>{copied ? "✅ Copied" : "📋 Copy"}</button>
          <p className="text-muted text-xs mt-1.5"><b>This is the Claude PROMPT</b> (not a résumé). Paste it into <b>claude.ai</b>; it returns one JSON object — paste that into B2.</p>
          <pre className="mt-2 bg-slate-900 text-slate-100 rounded-xl p-3 text-[0.68rem] max-h-64 overflow-auto whitespace-pre-wrap">{prompt}</pre></>}
      </div>

      <div className="text-sm font-semibold mt-4 mb-1">B2 · Paste Claude&apos;s JSON → score &amp; build</div>
      <div className="card-tight">
        <textarea className="field min-h-[120px] text-xs" value={paste} onChange={e => setPaste(e.target.value)} placeholder='Paste the { "name": ... } JSON Claude returned. Code fences / extra text are fine.' />
        <button className="btn-primary mt-2" disabled={busy === "import"} onClick={importJson}>{busy === "import" ? "Scoring…" : "📥 Import & score"}</button>
        {err && genSource !== "auto" && <div className="alert alert-bad mt-2 whitespace-pre-wrap text-xs">{err}</div>}
        {genSource === "claude" && resultBlock()}
      </div>

      <div className="text-sm font-semibold mt-4 mb-1">5 · Apply</div>
      <div className="card-tight"><p className="text-muted text-xs">Record this application once you&apos;ve applied — it&apos;ll turn green in Match &amp; Score. Attach the exact PDF in the Tracker for interview prep later.</p>
        <button className="btn-primary mt-2" onClick={record}>✅ I applied — record it</button>
        {applied && <div className="alert alert-good mt-2">✅ Recorded in Tracker — marked green in Match &amp; Score.</div>}</div>
    </div>
  );
}

/* save / view the exact résumé PDF used for an application — for interview prep later */
function ResumeCell({ app, onChange }: { app: Application; onChange: () => void }) {
  const [busy, setBusy] = useState(false);
  return (
    <div className="flex items-center gap-2 whitespace-nowrap">
      {app.hasResume && <a className="text-accent text-xs" href={api.resumeUrl(app.id)} target="_blank" rel="noopener" title={app.resumeName}>📄 view</a>}
      <label className="btn btn-sm cursor-pointer" title="Upload the PDF you applied with">
        {busy ? "…" : app.hasResume ? "↻" : "⬆ PDF"}
        <input type="file" accept=".pdf" className="hidden" disabled={busy}
          onChange={async e => { const f = e.target.files?.[0]; if (!f) return; setBusy(true); try { await api.uploadResume(app.id, f); onChange(); } finally { setBusy(false); } }} />
      </label>
    </div>
  );
}

/* ---------------- TAB 5 · TRACKER ---------------- */
function TrackerTab({ active }: { active: boolean }) {
  const [apps, setApps] = useState<Application[]>([]); const [statuses, setStatuses] = useState<string[]>([]); const [today, setToday] = useState(0);
  const [goal, setGoal] = useState(10);
  const [form, setForm] = useState({ company: "", title: "", source: "", jobLink: "" });
  async function load() { const d = await api.tracker(); setApps(d.applications); setStatuses(d.statuses); setToday(d.appliedToday); }
  useEffect(() => { if (active) load(); }, [active]);

  return (
    <div>
      <h2 className="text-[1.4rem] font-semibold tracking-tight">Application tracker</h2>
      <div className="card-tight">
        <div className="font-semibold mb-1.5">📅 Daily goal</div>
        <div className="flex items-center gap-4 flex-wrap">
          <div className="text-[2.4rem] font-extrabold text-accent leading-none">{today} / {goal}</div>
          <div><label className="lbl">Goal</label><input type="number" className="field w-24" value={goal} min={1} onChange={e => setGoal(+e.target.value || 10)} /></div>
          <div className="text-muted text-xs">{today >= goal ? "🎉 Goal hit for today!" : `${goal - today} more to hit today's goal.`}</div>
        </div>
      </div>
      <div className="card">
        <div className="font-semibold mb-1.5">➕ Record an application</div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <div><label className="lbl">Company</label><input className="field" value={form.company} onChange={e => setForm({ ...form, company: e.target.value })} /></div>
          <div><label className="lbl">Title</label><input className="field" value={form.title} onChange={e => setForm({ ...form, title: e.target.value })} /></div>
          <div><label className="lbl">Source</label><input className="field" value={form.source} onChange={e => setForm({ ...form, source: e.target.value })} /></div>
        </div>
        <div className="mt-3"><label className="lbl">Job link</label><input className="field" value={form.jobLink} onChange={e => setForm({ ...form, jobLink: e.target.value })} /></div>
        <button className="btn-primary mt-3" onClick={async () => { if (!form.company.trim()) return; await api.addApp({ ...form, status: "Applied" }); setForm({ company: "", title: "", source: "", jobLink: "" }); load(); }}>✅ Record application</button>
      </div>
      {apps.length === 0 ? <div className="alert alert-info">No applications recorded yet.</div> : (
        <div className="card overflow-x-auto">
          <div className="font-semibold mb-1">📋 {apps.length} application(s)</div>
          <table className="w-full text-sm border-collapse">
            <thead><tr className="text-muted text-xs uppercase">{["Company", "Title", "Source", "Applied", "Status", "Résumé", ""].map(h => <th key={h} className="text-left py-2 border-b border-line">{h}</th>)}</tr></thead>
            <tbody>{apps.map(a => (
              <tr key={a.id} className="hover:bg-slate-50">
                <td className="py-2 border-b border-line font-semibold">{a.company}</td>
                <td className="py-2 border-b border-line">{a.jobLink ? <a className="text-accent" href={a.jobLink} target="_blank" rel="noopener">{a.title}</a> : a.title}</td>
                <td className="py-2 border-b border-line">{a.source}</td>
                <td className="py-2 border-b border-line">{a.appliedDate}</td>
                <td className="py-2 border-b border-line"><select className="field" value={a.status} onChange={async e => { await api.updStatus(a.id, e.target.value); load(); }}>{statuses.map(s => <option key={s}>{s}</option>)}</select></td>
                <td className="py-2 border-b border-line"><ResumeCell app={a} onChange={load} /></td>
                <td className="py-2 border-b border-line"><button className="btn btn-sm" onClick={async () => { await api.delApp(a.id); load(); }}>🗑</button></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </div>
  );
}
