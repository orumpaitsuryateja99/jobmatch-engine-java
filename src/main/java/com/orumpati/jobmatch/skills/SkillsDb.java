package com.orumpati.jobmatch.skills;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Java port of app/skills_db.py — curated controlled vocabulary for skill/tool detection. */
public final class SkillsDb {

    public static final Map<String, List<String>> SKILLS = new LinkedHashMap<>();
    public static final Map<String, List<String>> TOOLS = new LinkedHashMap<>();
    public static final Map<String, List<String>> DOMAINS = new LinkedHashMap<>();
    public static final Map<String, String> SKILL_ALIASES;
    public static final Map<String, String> TOOL_ALIASES;

    static {
        SKILLS.put("python", List.of("python", "py"));
        SKILLS.put("java", List.of("java"));
        SKILLS.put("javascript", List.of("javascript", "js", "ecmascript"));
        SKILLS.put("typescript", List.of("typescript", "ts"));
        SKILLS.put("c++", List.of("c++", "cpp"));
        SKILLS.put("c", List.of("c language", "ansi c"));
        SKILLS.put("c#", List.of("c#", "csharp"));
        SKILLS.put("go", List.of("golang", "go lang"));
        SKILLS.put("rust", List.of("rust"));
        SKILLS.put("ruby", List.of("ruby"));
        SKILLS.put("php", List.of("php"));
        SKILLS.put("scala", List.of("scala"));
        SKILLS.put("kotlin", List.of("kotlin"));
        SKILLS.put("swift", List.of("swift"));
        SKILLS.put("sql", List.of("sql"));
        SKILLS.put("bash", List.of("bash", "shell scripting"));
        SKILLS.put("flask", List.of("flask"));
        SKILLS.put("django", List.of("django"));
        SKILLS.put("fastapi", List.of("fastapi"));
        SKILLS.put("node.js", List.of("node.js", "nodejs", "node js", "node"));
        SKILLS.put("express", List.of("express", "express.js", "expressjs"));
        SKILLS.put("react", List.of("react", "react.js", "reactjs"));
        SKILLS.put("angular", List.of("angular"));
        SKILLS.put("vue", List.of("vue", "vue.js"));
        SKILLS.put("spring", List.of("spring", "spring boot", "springboot"));
        SKILLS.put("rest apis", List.of("rest api", "rest apis", "restful", "rest", "restful api", "restful apis"));
        SKILLS.put("graphql", List.of("graphql"));
        SKILLS.put("grpc", List.of("grpc"));
        SKILLS.put("microservices", List.of("microservices", "microservice"));
        SKILLS.put("html5", List.of("html", "html5"));
        SKILLS.put("css3", List.of("css", "css3"));
        SKILLS.put("json", List.of("json"));
        SKILLS.put("chart.js", List.of("chart.js", "chartjs", "chart js"));
        SKILLS.put("postgresql", List.of("postgresql", "postgres", "psql"));
        SKILLS.put("mysql", List.of("mysql"));
        SKILLS.put("mongodb", List.of("mongodb", "mongo"));
        SKILLS.put("redis", List.of("redis"));
        SKILLS.put("sqlite", List.of("sqlite"));
        SKILLS.put("dynamodb", List.of("dynamodb"));
        SKILLS.put("elasticsearch", List.of("elasticsearch", "elastic search"));
        SKILLS.put("aws", List.of("aws", "amazon web services"));
        SKILLS.put("gcp", List.of("gcp", "google cloud", "google cloud platform"));
        SKILLS.put("azure", List.of("azure", "microsoft azure"));
        SKILLS.put("docker", List.of("docker"));
        SKILLS.put("kubernetes", List.of("kubernetes", "k8s"));
        SKILLS.put("terraform", List.of("terraform"));
        SKILLS.put("ci/cd", List.of("ci/cd", "cicd", "continuous integration", "continuous deployment"));
        SKILLS.put("kafka", List.of("kafka"));
        SKILLS.put("rabbitmq", List.of("rabbitmq"));
        SKILLS.put("tensorflow", List.of("tensorflow"));
        SKILLS.put("keras", List.of("keras"));
        SKILLS.put("pytorch", List.of("pytorch"));
        SKILLS.put("scikit-learn", List.of("scikit-learn", "sklearn", "scikit learn"));
        SKILLS.put("nlp", List.of("nlp", "natural language processing"));
        SKILLS.put("computer vision", List.of("computer vision", "cnn", "convolutional"));
        SKILLS.put("llm", List.of("llm", "large language model", "gemini", "gpt", "claude api"));
        SKILLS.put("google gemini api", List.of("google gemini api", "gemini api"));
        SKILLS.put("sam", List.of("sam", "segment anything model"));
        SKILLS.put("pandas", List.of("pandas"));
        SKILLS.put("numpy", List.of("numpy"));
        SKILLS.put("data structures", List.of("data structures", "dsa", "algorithms"));
        SKILLS.put("oop", List.of("oop", "object oriented", "object-oriented"));
        SKILLS.put("system design", List.of("system design", "distributed systems"));
        SKILLS.put("unit testing", List.of("unit testing", "unit tests", "pytest", "junit", "jest"));
        SKILLS.put("agile", List.of("agile", "scrum"));
        SKILLS.put("api design", List.of("api design"));
        SKILLS.put("client-server", List.of("client-server", "client server"));
        SKILLS.put("oauth", List.of("oauth", "oauth2", "oauth-based"));
        SKILLS.put("google calendar api", List.of("google calendar api"));

        TOOLS.put("git", List.of("git", "gitlab", "version control"));
        TOOLS.put("github", List.of("github"));
        TOOLS.put("postman", List.of("postman"));
        TOOLS.put("vs code", List.of("vs code", "vscode", "visual studio code"));
        TOOLS.put("jira", List.of("jira"));
        TOOLS.put("linux", List.of("linux", "unix"));
        TOOLS.put("jenkins", List.of("jenkins"));
        TOOLS.put("figma", List.of("figma"));

        DOMAINS.put("ai/ml", List.of("machine learning", "deep learning", "ai", "ml", "nlp", "computer vision", "data science"));
        DOMAINS.put("fintech", List.of("fintech", "payments", "banking", "trading", "financial"));
        DOMAINS.put("full-stack", List.of("full stack", "full-stack", "frontend", "backend", "web application"));
        DOMAINS.put("cloud", List.of("cloud", "infrastructure", "devops", "platform"));
        DOMAINS.put("climate", List.of("climate", "weather", "environmental", "sustainability"));
        DOMAINS.put("healthcare", List.of("healthcare", "health", "medical", "clinical"));
        DOMAINS.put("ecommerce", List.of("ecommerce", "e-commerce", "retail", "marketplace"));

        SKILL_ALIASES = buildReverse(SKILLS);
        TOOL_ALIASES = buildReverse(TOOLS);
    }

    private static Map<String, String> buildReverse(Map<String, List<String>> mapping) {
        Map<String, String> rev = new HashMap<>();
        for (var entry : mapping.entrySet()) {
            for (String alias : entry.getValue()) {
                rev.put(alias, entry.getKey());
            }
        }
        return rev;
    }

    public static Map<String, String> domainKeywordMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (var entry : DOMAINS.entrySet()) {
            for (String kw : entry.getValue()) {
                out.put(kw, entry.getKey());
            }
        }
        return out;
    }

    private SkillsDb() {}
}
