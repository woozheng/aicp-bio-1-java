import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.runtime.LLM;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class skill_loader implements Plugin {

    static final Path DATA_DIR = Path.of("data", "skill_loader");
    static final Path SKILLS_DIR = Path.of("data", "skills");
    static final Path INDEX_FILE = DATA_DIR.resolve("index.json");
    static final Path ACTIVE_SKILL_DIR = Path.of("data", "memories", "main_agent", "active_skills");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> BLOCKLIST_KEYWORDS = Set.of(
        "godmode", "jailbreak", "uncensoring", "uncensor", "red-teaming",
        "redteam", "safety-bypass", "bypass-safety", "refusal-removal",
        "abliteration", "guardrail-removal", "remove-guardrails", "excision",
        "model-surgery", "g0dm0d3", "obliterator", "obliteratus", "uncensored",
        "bypass", "越狱", "绕过", "去审查", "红队"
    );

    private static final Map<String, String> CATEGORY_MAP = buildCategoryMap();

    static Map<String, String> buildCategoryMap() {
        Map<String, String> m = new HashMap<>();
        String[][] pairs = {
            {"debugging", "software-development"}, {"testing", "software-development"},
            {"tdd", "software-development"}, {"code-review", "software-development"},
            {"quality", "software-development"}, {"development", "software-development"},
            {"planning", "software-development"}, {"implementation", "software-development"},
            {"workflow", "software-development"}, {"documentation", "software-development"},
            {"subagent", "software-development"}, {"delegation", "software-development"},
            {"parallel", "software-development"},
            {"mlops", "mlops"}, {"fine-tuning", "mlops"}, {"training", "mlops"},
            {"evaluation", "mlops"}, {"benchmarking", "mlops"}, {"inference", "mlops"},
            {"huggingface", "mlops"}, {"peft", "mlops"}, {"lora", "mlops"},
            {"qlora", "mlops"}, {"trl", "mlops"}, {"rlhf", "mlops"},
            {"design", "design"}, {"ui", "design"}, {"ux", "design"},
            {"brand", "design"}, {"visual", "design"}, {"inclusive", "design"},
            {"github", "devops"}, {"git", "devops"}, {"devops", "devops"},
            {"webhook", "devops"}, {"sync", "devops"}, {"backup", "devops"},
            {"deployment", "devops"},
            {"creative", "creative"}, {"generative-art", "creative"}, {"p5js", "creative"},
            {"creative-coding", "creative"}, {"interactive", "creative"},
            {"visualization", "creative"}, {"canvas", "creative"}, {"shaders", "creative"},
            {"animation", "creative"}, {"ascii", "creative"},
            {"legal", "legal"}, {"contract", "legal"}, {"policy", "legal"}, {"compliance", "legal"},
            {"gaming", "gaming"}, {"pokemon", "gaming"}, {"emulator", "gaming"}, {"gameplay", "gaming"},
            {"hermes", "hermes"},
            {"autonomous", "autonomous-ai"}, {"agent", "autonomous-ai"},
            {"multi-agent", "autonomous-ai"}, {"spawning", "autonomous-ai"}, {"gateway", "autonomous-ai"},
            {"unity", "unity"}, {"blender", "blender"},
            {"paid-media", "paid-media"}, {"tracking", "paid-media"}, {"attribution", "paid-media"},
            {"engineering", "engineering"},
            {"media", "media"}, {"audio", "media"}, {"spectrogram", "media"},
            {"research", "research"},
            {"productivity", "productivity"}, {"google", "productivity"},
            {"gmail", "productivity"}, {"calendar", "productivity"},
            {"drive", "productivity"}, {"sheets", "productivity"},
            {"apple", "apple"}, {"imessage", "apple"}, {"macos", "apple"},
        };
        for (String[] p : pairs) m.put(p[0], p[1]);
        return m;
    }

    // ============================================================
    // 索引读写
    // ============================================================

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadIndex() {
        try {
            Files.createDirectories(DATA_DIR);
            if (!Files.exists(INDEX_FILE)) return emptyIndex();
            String raw = Files.readString(INDEX_FILE, StandardCharsets.UTF_8);
            return MAPPER.readValue(raw, LinkedHashMap.class);
        } catch (Exception e) {
            return emptyIndex();
        }
    }

    static Map<String, Object> emptyIndex() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skills", new ArrayList<>());
        m.put("categories", new ArrayList<>());
        m.put("last_scan", null);
        m.put("skipped", new ArrayList<>());
        return m;
    }

    static void saveIndex(Map<String, Object> data) {
        try {
            Files.createDirectories(DATA_DIR);
            String content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.writeString(INDEX_FILE, content, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    // ============================================================
    // 黑名单 / 分类
    // ============================================================

    static boolean isBlockedSkill(Map<String, Object> skill) {
        String title = str(skill.get("title")).toLowerCase();
        String desc = str(skill.get("description")).toLowerCase();
        Object tagsObj = skill.get("tags");
        String tags = tagsObj instanceof List<?> l
            ? l.stream().map(Object::toString).collect(Collectors.joining(" ")).toLowerCase()
            : "";
        String combined = title + " " + desc + " " + tags;
        for (String kw : BLOCKLIST_KEYWORDS) {
            if (combined.contains(kw)) return true;
        }
        return false;
    }

    static String determineCategoryFromTags(List<String> tags, String filePath) {
        for (String tag : tags) {
            String t = tag.toLowerCase();
            if (CATEGORY_MAP.containsKey(t)) return CATEGORY_MAP.get(t);
        }
        String[] parts = filePath.split("/");
        if (parts.length >= 2) {
            String dirName = parts[0];
            Collection<String> knownCats = CATEGORY_MAP.values();
            if (knownCats.contains(dirName)) return dirName;
            for (Map.Entry<String, String> e : CATEGORY_MAP.entrySet()) {
                if (dirName.toLowerCase().contains(e.getKey())) return e.getValue();
            }
            return dirName;
        }
        return "未分类";
    }

    // ============================================================
    // 解析 SKILL.md
    // ============================================================

    static Map<String, Object> parseSkillMd(Path filePath) {
        String content;
        try {
            content = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }

        String[] lines = content.split("\n", -1);
        String title = "";
        String description = "";
        String category = "未分类";
        List<String> tags = new ArrayList<>();
        int fmEnd = 0;

        if (lines.length > 0 && lines[0].trim().equals("---")) {
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].trim().equals("---")) { fmEnd = i + 1; break; }
                String line = lines[i].trim();
                if (line.startsWith("title:")) {
                    title = stripQuotes(line.substring(6).trim());
                } else if (line.startsWith("description:")) {
                    description = stripQuotes(line.substring(12).trim());
                } else if (line.startsWith("category:")) {
                    category = stripQuotes(line.substring(9).trim());
                } else if (line.startsWith("tags:")) {
                    String tagStr = line.substring(5).trim();
                    if (tagStr.startsWith("[") && tagStr.endsWith("]")) {
                        String inner = tagStr.substring(1, tagStr.length() - 1);
                        for (String t : inner.split(",")) {
                            String cleaned = stripQuotes(t.trim());
                            if (!cleaned.isEmpty()) tags.add(cleaned);
                        }
                    }
                }
            }
        }

        if (title.isEmpty()) {
            for (int i = fmEnd; i < lines.length; i++) {
                if (lines[i].startsWith("# ")) { title = lines[i].substring(2).trim(); break; }
            }
        }
        if (title.isEmpty()) {
            String fn = filePath.getFileName().toString().replace(".md", "");
            title = fn;
        }

        if (description.isEmpty()) {
            for (int i = fmEnd; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    description = line.substring(0, Math.min(200, line.length()));
                    break;
                }
            }
        }

        Path rel = SKILLS_DIR.relativize(filePath);
        String relPath = rel.toString().replace("\\", "/");

        String skillId = relPath
            .replaceAll("(?i)/SKILL\\.md$", "")
            .replaceAll("(?i)^SKILL\\.md$", "")
            .replace("/", "_")
            .toLowerCase()
            .replaceAll("[^a-z0-9_]+", "_");
        if (skillId.isEmpty()) {
            String fn = filePath.getFileName().toString().replace(".md", "").toLowerCase();
            skillId = fn.isEmpty() ? "unknown" : fn;
        }

        if (category.equals("未分类") || category.isEmpty()) {
            category = determineCategoryFromTags(tags, relPath);
        }

        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("skill_id", skillId);
        skill.put("title", title);
        skill.put("description", description);
        skill.put("category", category);
        skill.put("tags", tags);
        skill.put("file_path", relPath);
        skill.put("skill_dir", filePath.getParent() != null ? filePath.getParent().toString() : "");
        skill.put("content", content);
        return skill;
    }

    static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    // ============================================================
    // 扫描技能
    // ============================================================

    static Map<String, Object> scanSkills() {
        try { Files.createDirectories(SKILLS_DIR); } catch (IOException ignored) {}

        List<Path> mdFiles = new ArrayList<>();
        walkSkills(SKILLS_DIR, mdFiles);

        List<Map<String, Object>> skills = new ArrayList<>();
        Set<String> categoriesSet = new TreeSet<>();
        List<String> blockedTitles = new ArrayList<>();

        for (Path f : mdFiles) {
            Map<String, Object> skill = parseSkillMd(f);
            if (skill == null) continue;
            if (isBlockedSkill(skill)) {
                blockedTitles.add(str(skill.getOrDefault("title", f.toString())));
                continue;
            }
            skills.add(skill);
            categoriesSet.add(str(skill.get("category")));
        }

        List<String> categories = new ArrayList<>(categoriesSet);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skills", skills);
        data.put("categories", categories);
        data.put("last_scan", java.time.LocalDateTime.now().toString().replace("T", " ").substring(0, 19));
        data.put("skipped", blockedTitles);
        saveIndex(data);
        return data;
    }

    static void walkSkills(Path dir, List<Path> out) {
        if (!Files.exists(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) walkSkills(p, out);
                else if (p.getFileName().toString().equals("SKILL.md")) out.add(p);
            }
        } catch (IOException ignored) {}
    }

    // ============================================================
    // 模糊匹配 + 搜索
    // ============================================================

    static double fuzzyMatch(String query, String text, double threshold) {
        String q = query.toLowerCase();
        String t = text.toLowerCase();
        if (q.isEmpty() || t.isEmpty()) return 0;
        if (t.contains(q)) return 1.0;

        int maxLen = 0;
        for (int i = 0; i < q.length(); i++) {
            for (int j = i + 1; j <= q.length(); j++) {
                String sub = q.substring(i, j);
                if (t.contains(sub)) maxLen = Math.max(maxLen, sub.length());
            }
        }
        double ratio = (2.0 * maxLen) / (q.length() + t.length());
        return ratio >= threshold ? ratio : 0;
    }

    static double fuzzyMatch(String query, String text) {
        return fuzzyMatch(query, text, 0.3);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> searchSkills(Map<String, Object> index,
                                                   List<String> keywords,
                                                   String category, int limit) {
        if (keywords == null || keywords.isEmpty()) return new ArrayList<>();
        String query = String.join(" ", keywords).trim();
        if (query.isEmpty()) return new ArrayList<>();

        List<Map<String, Object>> results = new ArrayList<>();
        Object skillsObj = index.get("skills");
        if (!(skillsObj instanceof List<?> skillsList)) return results;

        for (Object sObj : skillsList) {
            if (!(sObj instanceof Map<?, ?> raw)) continue;
            Map<String, Object> skill = castMap(raw);
            if (category != null && !category.equals(str(skill.get("category")))) continue;

            double titleScore = fuzzyMatch(query, str(skill.get("title")));
            double descScore = fuzzyMatch(query, str(skill.get("description"))) * 0.8;

            double tagMax = 0;
            Object tagsObj = skill.get("tags");
            if (tagsObj instanceof List<?> tags) {
                for (Object tag : tags) {
                    tagMax = Math.max(tagMax, fuzzyMatch(query, str(tag)) * 0.7);
                }
            }

            String content = str(skill.get("content"));
            double contentScore = fuzzyMatch(query,
                content.substring(0, Math.min(2000, content.length()))) * 0.5;

            double score = Math.max(Math.max(titleScore, descScore), Math.max(tagMax, contentScore));
            if (score <= 0) continue;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("skill_id", skill.get("skill_id"));
            r.put("title", skill.get("title"));
            r.put("description", skill.get("description"));
            r.put("category", skill.get("category"));
            r.put("tags", skill.get("tags"));
            r.put("file_path", skill.get("file_path"));
            r.put("skill_dir", skill.getOrDefault("skill_dir", ""));
            r.put("score", Math.round(score * 1000) / 1000.0);
            results.add(r);
        }

        results.sort((a, b) -> Double.compare(
            ((Number) b.get("score")).doubleValue(),
            ((Number) a.get("score")).doubleValue()
        ));
        return results.subList(0, Math.min(limit, results.size()));
    }

    // ============================================================
    // LLM 精排
    // ============================================================

    @SuppressWarnings("unchecked")
    static Map<String, Object> llmRank(Agent agent, String query, List<String> keywords,
                                        List<Map<String, Object>> candidates, int topK) {
        List<Map<String, Object>> briefs = new ArrayList<>();
        for (Map<String, Object> s : candidates) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("skill_id", s.get("skill_id"));
            b.put("title", s.get("title"));
            String desc = str(s.get("description"));
            b.put("description", desc.substring(0, Math.min(200, desc.length())));
            Object tagsObj = s.get("tags");
            if (tagsObj instanceof List<?> tags) {
                b.put("tags", tags.stream().limit(5).collect(Collectors.toList()));
            } else {
                b.put("tags", new ArrayList<>());
            }
            briefs.add(b);
        }

        String systemPrompt =
            "你是一个技能推荐专家。用户提出问题并给出关键词，你需要从候选技能中选出最匹配的。\n" +
            "候选技能已经经过初步关键词筛选，你只需要做语义精排。\n" +
            "返回JSON格式：{\"skills\": [{\"skill_id\": \"xxx\", \"reason\": \"推荐理由\"}]}\n" +
            "只返回最相关的 top_k 个，按相关度从高到低排序。";

        String userPrompt =
            "用户问题：" + query + "\n" +
            "关键词：" + String.join(", ", keywords) + "\n" +
            "返回数量：" + topK + "\n" +
            "候选技能（共" + briefs.size() + "个）：\n" +
            toJson(briefs);

        try {
            Object llmObj = agent.get("llm");
            if (!(llmObj instanceof LLM llm)) throw new RuntimeException("LLM 未配置");

            String raw = llm.chat(List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ), null, null);

            Map<String, Object> parsed = extractJsonObject(raw);
            List<Map<String, Object>> ranked = new ArrayList<>();
            if (parsed != null && parsed.get("skills") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) ranked.add(castMap(m));
                }
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, ranked.size()); i++) {
                Map<String, Object> item = ranked.get(i);
                String sid = str(item.get("skill_id"));
                for (Map<String, Object> c : candidates) {
                    if (sid.equals(str(c.get("skill_id")))) {
                        Map<String, Object> enriched = new LinkedHashMap<>(c);
                        enriched.put("reason", item.get("reason"));
                        out.add(enriched);
                        break;
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("skills", out);
            result.put("total", out.size());
            return result;
        } catch (Exception e) {
            List<Map<String, Object>> fallback = candidates.subList(0, Math.min(topK, candidates.size()));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> s : fallback) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("skill_id", s.get("skill_id"));
                item.put("reason", "关键词匹配（LLM降级）");
                out.add(item);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("skills", out);
            result.put("total", out.size());
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 从 LLM 回复里抠出第一个完整 JSON 对象。
     * 复用 main_agent 里 LLMOutputParser 的同款括号配对思路。
     */
    static Map<String, Object> extractJsonObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        if (start == -1) return null;
        int depth = 0;
        boolean inString = false, escape = false;
        int end = -1;
        for (int i = start; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (escape) { escape = false; continue; }
            if (ch == '\\') { escape = true; continue; }
            if (ch == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (ch == '{') depth++;
            else if (ch == '}') { depth--; if (depth == 0) { end = i; break; } }
        }
        if (end == -1) return null;
        try {
            return MAPPER.readValue(raw.substring(start, end + 1), LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // 活跃技能读写
    // ============================================================

    static Path activeSkillFile(String sessionId) {
        return ACTIVE_SKILL_DIR.resolve(sessionId + "_skill.txt");
    }

    static String loadActiveSkill(String sessionId) {
        Path f = activeSkillFile(sessionId);
        if (!Files.exists(f)) return "";
        try { return Files.readString(f, StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { return ""; }
    }

    static void saveActiveSkill(String sessionId, String content) {
        try {
            Files.createDirectories(ACTIVE_SKILL_DIR);
            Files.writeString(activeSkillFile(sessionId), content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    static void clearActiveSkill(String sessionId) {
        try { Files.deleteIfExists(activeSkillFile(sessionId)); }
        catch (IOException ignored) {}
    }

    // ============================================================
    // 主入口
    // ============================================================

    @Override
    @SuppressWarnings("unchecked")
    public Envelop execute(Envelop envelop, Agent agent) {
        Map<String, Object> payload = envelop.payload != null ? envelop.payload : new LinkedHashMap<>();
        Map<String, Object> meta = envelop.meta != null ? envelop.meta : new LinkedHashMap<>();

        Map<String, Object> params = payload.get("params") instanceof Map
            ? (Map<String, Object>) payload.get("params") : new LinkedHashMap<>();
        if (params.isEmpty()) params = payload;

        String action = str(payload.getOrDefault("action", "list"));
        String sessionId = str(meta.get("session_id"));
        if (sessionId.isEmpty()) sessionId = str(params.get("session_id"));
        if (sessionId.isEmpty()) sessionId = "default";

        // ============ 搜索类 action ============

        if (action.equals("scan")) {
            Map<String, Object> data = scanSkills();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", ((List<?>) data.get("skills")).size());
            out.put("categories", data.get("categories"));
            out.put("last_scan", data.get("last_scan"));
            out.put("skipped", data.getOrDefault("skipped", new ArrayList<>()));
            envelop.payload = Map.of("ok", true, "data", out);
            return envelop;
        }

        if (action.equals("list")) {
            scanSkills();
            Map<String, Object> freshIndex = loadIndex();

            String category = str(params.get("category"));
            int limit = params.get("limit") instanceof Number n ? n.intValue() : 100;

            List<Map<String, Object>> skills = new ArrayList<>();
            Object sObj = freshIndex.get("skills");
            if (sObj instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> raw)) continue;
                    Map<String, Object> s = castMap(raw);
                    if (!category.isEmpty() && !category.equals(str(s.get("category")))) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("skill_id", s.get("skill_id"));
                    item.put("title", s.get("title"));
                    item.put("description", s.get("description"));
                    item.put("category", s.get("category"));
                    item.put("tags", s.get("tags"));
                    item.put("file_path", s.get("file_path"));
                    item.put("skill_dir", s.getOrDefault("skill_dir", ""));
                    skills.add(item);
                    if (skills.size() >= limit) break;
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("skills", skills);
            data.put("total", skills.size());
            data.put("categories", freshIndex.get("categories"));
            data.put("last_scan", freshIndex.get("last_scan"));
            envelop.payload = Map.of("ok", true, "data", data);
            return envelop;
        }

        if (action.equals("search")) {
            scanSkills();
            Map<String, Object> freshIndex = loadIndex();

            String query = str(params.get("query")).trim();
            Object kwObj = params.get("keywords");
            List<String> keywords = new ArrayList<>();
            if (kwObj instanceof List<?> list) {
                for (Object o : list) keywords.add(str(o));
            } else if (kwObj instanceof String s && !s.trim().isEmpty()) {
                keywords.add(s.trim());
            }

            if (query.isEmpty()) {
                envelop.payload = Map.of("ok", false, "error", "query 不能为空");
                return envelop;
            }

            // ★ 兜底：keywords 为空时用 query 代替
            if (keywords.isEmpty() && !query.isEmpty()) {
                for (String w : query.split("\\s+")) {
                    if (!w.isEmpty()) keywords.add(w);
                }
                if (keywords.isEmpty()) keywords.add(query);
            }

            if (keywords.isEmpty()) {
                envelop.payload = Map.of("ok", false, "error", "keywords 不能为空");
                return envelop;
            }

            String category = params.get("category") != null ? str(params.get("category")) : null;
            int limit = params.get("limit") instanceof Number n ? n.intValue() : 10;

            List<Map<String, Object>> candidates = searchSkills(freshIndex, keywords, category, 30);

            if (candidates.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("skills", new ArrayList<>());
                data.put("total", 0);
                data.put("query", query);
                data.put("keywords", keywords);
                envelop.payload = Map.of("ok", true, "data", data);
                return envelop;
            }

            Map<String, Object> rankResult = llmRank(agent, query, keywords, candidates, limit);
            List<Map<String, Object>> finalSkills = (List<Map<String, Object>>) rankResult.get("skills");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("skills", finalSkills);
            data.put("total", finalSkills.size());
            data.put("query", query);
            data.put("keywords", keywords);
            if (rankResult.containsKey("error")) {
                data.put("fallback", true);
                data.put("error", rankResult.get("error"));
            }
            envelop.payload = Map.of("ok", true, "data", data);
            return envelop;
        }

        // ============ 其余 action 读 index，不主动 scan ============

        Map<String, Object> index = loadIndex();

        if (action.equals("detail")) {
            String skillId = str(params.get("skill_id"));
            Object sObj = index.get("skills");
            if (sObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> raw) {
                        Map<String, Object> s = castMap(raw);
                        if (skillId.equals(str(s.get("skill_id")))) {
                            envelop.payload = Map.of("ok", true, "data", s);
                            return envelop;
                        }
                    }
                }
            }
            envelop.payload = Map.of("ok", false, "error", "技能不存在");
            return envelop;
        }

        if (action.equals("categories")) {
            Object cats = index.getOrDefault("categories", new ArrayList<>());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("categories", cats);
            data.put("total", cats instanceof List<?> l ? l.size() : 0);
            envelop.payload = Map.of("ok", true, "data", data);
            return envelop;
        }

        if (action.equals("stats")) {
            Map<String, Integer> categoryCount = new LinkedHashMap<>();
            Object sObj = index.get("skills");
            if (sObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> raw) {
                        String cat = str(castMap(raw).get("category"));
                        categoryCount.merge(cat, 1, Integer::sum);
                    }
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total_skills", sObj instanceof List<?> l ? l.size() : 0);
            Object cats = index.get("categories");
            data.put("total_categories", cats instanceof List<?> l2 ? l2.size() : 0);
            data.put("last_scan", index.get("last_scan"));
            data.put("skipped", index.getOrDefault("skipped", new ArrayList<>()));
            data.put("category_count", categoryCount);
            envelop.payload = Map.of("ok", true, "data", data);
            return envelop;
        }

        // ============ 加载类 action ============

        if (action.equals("get_active")) {
            String content = loadActiveSkill(sessionId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("session_id", sessionId);
            out.put("content", content);
            out.put("size", content.length());
            out.put("has_skill", !content.isEmpty());
            envelop.payload = out;
            return envelop;
        }

        if (action.equals("save_active")) {
            Object contentObj = payload.get("content");
            if (contentObj == null) contentObj = params.get("content");
            String newContent = str(contentObj);
            saveActiveSkill(sessionId, newContent);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("message", "✅ 已保存，" + newContent.length() + " 字");
            out.put("size", newContent.length());
            envelop.payload = out;
            return envelop;
        }

        if (action.equals("clear")) {
            clearActiveSkill(sessionId);
            envelop.payload = Map.of("ok", true, "message", "✅ 技能已卸载", "mode", "clear");
            return envelop;
        }

        if (action.equals("load")) {
            String skillId = str(params.get("skill_id"));
            String mode = str(params.getOrDefault("mode", "load"));

            if (mode.equals("clear")) {
                clearActiveSkill(sessionId);
                envelop.payload = Map.of("ok", true, "message", "✅ 技能已卸载", "mode", "clear");
                return envelop;
            }

            if (skillId.isEmpty()) {
                envelop.payload = Map.of("ok", false, "error", "缺少 skill_id 参数");
                return envelop;
            }

            Map<String, Object> skill = null;

            // 精确匹配
            Object sObj = index.get("skills");
            if (sObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> raw) {
                        Map<String, Object> s = castMap(raw);
                        if (skillId.equals(str(s.get("skill_id")))) { skill = s; break; }
                    }
                }
            }

            // 模糊匹配
            if (skill == null && sObj instanceof List<?> list) {
                String lower = skillId.toLowerCase();
                List<Map<String, Object>> candidates = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> raw) {
                        Map<String, Object> s = castMap(raw);
                        String sid = str(s.get("skill_id")).toLowerCase();
                        String sdir = str(s.get("skill_dir")).toLowerCase();
                        String fpath = str(s.get("file_path")).toLowerCase();
                        if (sid.contains(lower) || sdir.contains(lower) || fpath.contains(lower)) {
                            candidates.add(s);
                        }
                    }
                }
                if (candidates.size() == 1) {
                    skill = candidates.get(0);
                } else if (candidates.size() > 1) {
                    List<String> ids = candidates.stream()
                        .map(s -> str(s.get("skill_id")))
                        .collect(Collectors.toList());
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", false);
                    out.put("error", "skill_id \"" + skillId + "\" 匹配到多个技能，请用精确 ID");
                    out.put("candidates", ids);
                    envelop.payload = out;
                    return envelop;
                }
            }

            if (skill == null) {
                envelop.payload = Map.of("ok", false, "error", "技能不存在: " + skillId);
                return envelop;
            }

            String content = str(skill.get("content"));
            String title = str(skill.getOrDefault("title", skillId));
            String skillDir = str(skill.getOrDefault("skill_dir", ""));
            String filePath = str(skill.getOrDefault("file_path", ""));

            if (content.isEmpty()) {
                envelop.payload = Map.of("ok", false, "error", "技能 " + skillId + " 内容为空");
                return envelop;
            }

            String finalContent = content;
            if (!skillDir.isEmpty()) {
                String runtimeHeader =
                    "<!-- ============================================ -->\n" +
                    "<!-- 使用工具调用技能运行时信息 -->\n" +
                    "<!-- 技能目录: " + skillDir + " -->\n" +
                    "<!-- 技能文件: " + filePath + " -->\n" +
                    "<!-- 本技能中所有相对路径（如 scripts/xxx.py、editing.md） -->\n" +
                    "<!-- 均相对于上述技能目录。 -->\n" +
                    "<!-- 使用绝对路径拼接。 -->\n" +
                    "<!-- ============================================ -->\n\n";
                finalContent = runtimeHeader + content;
            }

            saveActiveSkill(sessionId, finalContent);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("message", "✅ 技能已加载：" + title + "，" + finalContent.length() + " 字");
            out.put("skill_id", skill.get("skill_id"));
            out.put("title", title);
            out.put("skill_dir", skillDir);
            out.put("size", finalContent.length());
            envelop.payload = out;
            return envelop;
        }

        envelop.payload = Map.of("ok", false, "error", "未知 action: " + action);
        return envelop;
    }

    // ============================================================
    // 工具
    // ============================================================

    static String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { return String.valueOf(o); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
        return result;
    }

    static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}
