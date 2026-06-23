// Mirrors the JSON shapes returned by the Spring Boot REST API.

export interface SkillCategory { category: string; items: string[]; }
export interface Experience { role?: string; company?: string; date?: string; location?: string; bullets?: string[]; skills?: string[]; links?: Record<string, string>; }
export interface Project { name?: string; subtitle?: string; date?: string; tech?: string; bullets?: string[]; skills?: string[]; links?: Record<string, string>; }
export interface Education { school?: string; location?: string; degree?: string; detail?: string; dates?: string; }

export interface Profile {
  name?: string; email?: string; phone?: string;
  links?: Record<string, string>;
  summary?: string;
  skillCategories?: SkillCategory[];
  skills?: string[]; tools?: string[]; domains?: string[];
  experience?: Experience[]; projects?: Project[]; education?: Education[];
  targetRoles?: string[]; rawText?: string;
}

export interface JobPosting {
  title: string; company: string; location: string; jobLink: string;
  source: string; description: string; workMode: string; postedDate: string; error?: string;
}

export interface ScoredJob {
  job: JobPosting; score: number; band: string; matched: string[]; missing: string[];
}

export interface SourcesStatus {
  adzuna: boolean; serpapi: boolean; jsearch: boolean; careerjet: boolean; jooble: boolean;
  jobApiFallback: boolean; remoteApis: boolean; workday: boolean; smartrecruiters: boolean;
  discovery: boolean; discoveryProvider: string;
}

export interface Application {
  id: string; company: string; title: string; jobLink: string;
  source: string; status: string; appliedDate: string; notes: string;
  hasResume?: boolean; resumeName?: string;
}
